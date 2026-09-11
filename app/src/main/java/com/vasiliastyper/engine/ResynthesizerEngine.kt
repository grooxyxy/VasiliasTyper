package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * ResynthesizerEngine — faithful Kotlin/Android port of PhotoDemon's `pdInpaint`
 * class (Classes/pdInpaint.cls, BSD-licensed, Copyright 2022-2026 Tanner Helland),
 * which is itself a clean-room implementation of Paul Harrison's "Resynthesizer"
 * algorithm from his PhD thesis ("Image Texture Tools", logarithmic.net).
 *
 * This is the same algorithm family that powers GIMP's "Heal Selection" plugin
 * and the 61315/resynthesizer C library wrapped by
 * light-and-ray/resynthesizer-python-lib — widely regarded as the closest
 * open-source equivalent to Adobe Photoshop's Content-Aware Fill.
 *
 * Algorithm outline (per the paper + PhotoDemon):
 *  1. Crop to the mask bounding box expanded by a sampling radius (memory-safe).
 *  2. Build a Cauchy-distribution lookup table for robust colour differences.
 *  3. Pre-sort a list of neighbour offsets by Euclidean distance.
 *  4. Synthesize target pixels in random order (Fisher-Yates shuffle).
 *     Each pixel searches its [maxNeighbors] nearest already-known neighbours,
 *     evaluates their original source coordinates plus [maxRandomCandidates]
 *     random source pixels, and copies the best-matching source pixel.
 *  5. Refinement: the point list is re-appended (2 full passes + p·n extra
 *     passes) so early pixels get re-evaluated once more neighbours exist.
 *  6. Outside the ROI the bitmap is never touched; on any error the caller's
 *     bitmap remains unchanged (transactional behaviour).
 *
 * Threading: call from a background thread. Not re-entrant — one fill at a time.
 */
object ResynthesizerEngine {

    /** How target pixels are visited. Random is the paper default. */
    enum class FillOrder { RANDOM, OUTSIDE_IN, INSIDE_OUT }

    data class Params(
        /** Tolerance for outlier pixels, range (0.01, 1.0). PD default 0.15. */
        val allowOutliers: Double = 0.15,
        /** Nearest-neighbour patch size, range [4, 100]. PD default 20. */
        val maxNeighbors: Int = 20,
        /** Random source candidates per pixel, range [5, 200]. PD default 60. */
        val maxRandomCandidates: Int = 60,
        /** Fraction of points revisited after 2 full passes, [0.0, 0.99]. PD default 0.5. */
        val refinement: Double = 0.5,
        /** Source sampling radius around the mask bbox, [5, 500]. PD default 200. */
        val searchRadius: Int = 200,
        /** Pixel visitation order. */
        val fillOrder: FillOrder = FillOrder.RANDOM,
        /** Deterministic seed so Undo/Redo and repeated runs are reproducible. */
        val randomSeed: Int = 0x5EED1234
    )

    data class Result(
        val success: Boolean,
        val message: String,
        val processedPixels: Int = 0
    )

    /** Hard safety cap for the cropped working set (width × height). */
    private const val MAX_ROI_PIXELS = 4_200_000
    private const val CAUCHY_SCALE = 1 shl 16
    private const val LONG_MAX_FLAG = Int.MAX_VALUE

    // ── Public entry points ──────────────────────────────────────────────────

    /** Classic Resynthesizer inpainting (PhotoDemon Edit ▸ Content-Aware Fill). */
    fun inpaint(
        bitmap: Bitmap,
        region: Region,
        params: Params = Params(),
        onProgress: ((Float) -> Unit)? = null
    ): Result = run(bitmap, region, params, healTransparency = false, onProgress)

    /**
     * RemovR mode — mirrors `resynthesize(source, mask)` from
     * light-and-ray/resynthesizer-python-lib: transparent AND semi-transparent
     * pixels inside the mask are treated as unknown and are re-synthesized from
     * surrounding opaque texture.
     *
     * BLACK-OUTLINE FIX (v9):
     *  • The old implementation only treated alpha < 16 as unknown. The soft
     *    brush (DST_OUT + BlurMaskFilter) leaves a gradient of SEMI-transparent
     *    pixels (alpha 16..254) around every stroke; those pixels were kept as
     *    valid texture sources, so the dark fringe was copied back into the
     *    fill → the visible black outline.
     *  • The mask is now built from the **effective alpha** (alpha < 128) and
     *    is additionally **dilated** by 2 px, swallowing the whole feathered
     *    fringe instead of just its fully-transparent core.
     *  • writeBack blends the synthesized colour with the original semi-
     *    transparent pixel proportional to its alpha, and forces alpha back
     *    to fully opaque wherever the original was even partially erased, so
     *    no premultiplied dark residue can survive.
     */
    fun healSelection(
        bitmap: Bitmap,
        region: Region,
        params: Params = Params(),
        onProgress: ((Float) -> Unit)? = null
    ): Result = run(bitmap, region, params, healTransparency = true, onProgress)

    // ── Core implementation ──────────────────────────────────────────────────

    private fun run(
        bitmap: Bitmap,
        requestedRegion: Region,
        params: Params,
        healTransparency: Boolean,
        onProgress: ((Float) -> Unit)?
    ): Result {
        if (!bitmap.isMutable || bitmap.isRecycled) {
            return Result(false, "Layer harus berupa bitmap mutable")
        }
        val bounds = Rect(0, 0, bitmap.width, bitmap.height)
        val region = Region(requestedRegion).apply {
            op(Region(bounds), Region.Op.INTERSECT)
        }
        if (region.isEmpty) return Result(false, "Mask kosong")

        val maskBounds = Rect(region.bounds)
        if (maskBounds.isEmpty) return Result(false, "Mask berada di luar kanvas")

        // 1) Expand the working rect by the sampling radius, clamped to the
        //    bitmap — identical to PhotoDemon's expandedFillRect logic.
        val radius = params.searchRadius.coerceIn(5, 500)
        val rx = max(0, maskBounds.left - radius)
        val ry = max(0, maskBounds.top - radius)
        val rr = min(bitmap.width, maskBounds.right + radius)
        val rb = min(bitmap.height, maskBounds.bottom + radius)
        val rw = rr - rx
        val rh = rb - ry
        if (rw <= 0 || rh <= 0) return Result(false, "Area sampel tidak valid")

        // 2) Adaptive downscale for very large ROIs so mobile RAM stays safe.
        var scale = 1f
        while (rw * rh * scale * scale > MAX_ROI_PIXELS && scale > 0.25f) {
            scale *= 0.7071f
        }
        val workW = max(8, (rw * scale).toInt())
        val workH = max(8, (rh * scale).toInt())

        return try {
            val scaled = scale != 1f
            val workPixels: IntArray
            val workMask: ByteArray // 0 = known/source, 1 = must synthesize
            if (scaled) {
                val tmp = Bitmap.createBitmap(bitmap, rx, ry, rw, rh)
                val small = Bitmap.createScaledBitmap(tmp, workW, workH, true)
                if (tmp != small && !tmp.isRecycled) tmp.recycle()
                workPixels = IntArray(workW * workH)
                small.getPixels(workPixels, 0, workW, 0, 0, workW, workH)
                val localRegion = shiftRegion(region, -rx, -ry)
                workMask = buildMaskScaled(localRegion, rw, rh, workW, workH)
                if (!small.isRecycled) small.recycle()
            } else {
                workPixels = IntArray(rw * rh)
                bitmap.getPixels(workPixels, 0, rw, rx, ry, rw, rh)
                val localRegion = shiftRegion(region, -rx, -ry)
                workMask = buildMask(localRegion, workW, workH)
            }

            var targetCount = 0
            for (m in workMask) if (m != 0.toByte()) targetCount++
            if (targetCount == 0) return Result(false, "Tidak ada piksel yang perlu diisi")

            onProgress?.invoke(0.03f)

            // 3) RemovR: transparent + SEMI-transparent pixels inside the mask
            //    count as unknown (v9 black-outline fix).
            if (healTransparency) {
                // a) Any pixel whose alpha dropped below 128 was touched by the
                //    soft eraser → it is not a reliable texture source.
                for (i in workPixels.indices) {
                    if (Color.alpha(workPixels[i]) < 128) workMask[i] = 1
                }
                // b) Dilate the mask by 2 px so the feathered fringe of the
                //    soft brush (the actual black outline) is fully inside the
                //    unknown region and cannot leak into the synthesized fill.
                dilateMask(workMask, workW, workH, iterations = 2)
                // c) Re-scan: after dilation some fringe pixels may still have
                //    alpha ≥ 128 but are geometrically inside the stroke — they
                //    are now masked by (b). Nothing more to do here.
            }

            // 4) Run the synthesis (destructive on workPixels/workMask).
            val synthOk = synthesize(workPixels, workMask, workW, workH, params) { p ->
                onProgress?.invoke(0.03f + p * 0.90f)
            }
            if (!synthOk) return Result(false, "Tekstur sumber tidak mencukupi")

            // 5) Write the result back, preserving the original alpha channel
            //    (the synthesizer works on colour; alpha of known pixels and
            //    fully-opaque filled pixels is restored/opaque).
            val srcPixels = if (scaled) {
                val tmp = IntArray(rw * rh)
                bitmap.getPixels(tmp, 0, rw, rx, ry, rw, rh)
                tmp
            } else null

            if (scaled) {
                val workBmp = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
                workBmp.setPixels(workPixels, 0, workW, 0, 0, workW, workH)
                val up = Bitmap.createScaledBitmap(workBmp, rw, rh, true)
                if (!workBmp.isRecycled) workBmp.recycle()
                val upPixels = IntArray(rw * rh)
                up.getPixels(upPixels, 0, rw, 0, 0, rw, rh)
                if (!up.isRecycled) up.recycle()
                writeBack(bitmap, upPixels, srcPixels!!, region, rx, ry, rw, rh, healTransparency)
            } else {
                writeBack(bitmap, workPixels, null, region, rx, ry, rw, rh, healTransparency)
            }

            onProgress?.invoke(1f)
            Result(true, "Resynthesizer selesai", targetCount)
        } catch (oom: OutOfMemoryError) {
            oom.printStackTrace()
            Result(false, "Memori tidak cukup — perkecil area seleksi")
        } catch (t: Throwable) {
            t.printStackTrace()
            Result(false, "Resynthesizer dibatalkan: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * The actual texture synthesis loop. Mutates [pixels] in place.
     * [mask]: 0 = known, 1 = unknown. [pixels] are ARGB ints.
     */
    private fun synthesize(
        pixels: IntArray,
        mask: ByteArray,
        w: Int,
        h: Int,
        params: Params,
        onProgress: ((Float) -> Unit)?
    ): Boolean {
        val rng = Random(params.randomSeed xor (w * 31 + h))

        // ── Cauchy lookup (PhotoDemon InitializeInpainter) ──────────────────
        val cauchy = IntArray(511) // index = diff + 255
        val invAllowOutliers = 1.0 / params.allowOutliers.coerceIn(0.01, 1.0)
        fun cauchyOf(x: Double) = ln(x * x + 1.0)
        for (i in -255..255) {
            val v = cauchyOf(i / 256.0 * invAllowOutliers) / cauchyOf(invAllowOutliers)
            cauchy[i + 255] = (v * CAUCHY_SCALE + 0.5).toInt()
        }
        val cauchyMax = (cauchyOf(256.0 / 256.0 * invAllowOutliers) /
            cauchyOf(invAllowOutliers) * CAUCHY_SCALE + 0.5).toInt() * 3

        // ── Sorted neighbour offsets (InitializeOffsetList) ─────────────────
        val radius = min(params.searchRadius.coerceIn(5, 500), max(w, h))
        val offsetCount = (2 * radius + 1) * (2 * radius + 1) - 1
        val offsetX = IntArray(offsetCount)
        val offsetY = IntArray(offsetCount)
        val offsetDist = IntArray(offsetCount)
        var oc = 0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx == 0 && dy == 0) continue
                offsetX[oc] = dx
                offsetY[oc] = dy
                offsetDist[oc] = dx * dx + dy * dy
                oc++
            }
        }
        // Insertion into ascending order via IntArray sort of packed keys is
        // overkill here; use a simple index sort on distance (stable enough).
        val order = (0 until oc).sortedBy { offsetDist[it] }
        val sortedOffX = IntArray(oc) { offsetX[order[it]] }
        val sortedOffY = IntArray(oc) { offsetY[order[it]] }

        // ── Point lists (synthesis state per pixel) ─────────────────────────
        val size = w * h
        val stateX = IntArray(size)
        val stateY = IntArray(size)
        val stateIter = IntArray(size) { -1 } // "already evaluated this pass" flag

        var numSources = 0
        var numTargets = 0
        val sourcePoints = IntArray(size) // packed y * w + x
        val targetPoints = IntArray(size)

        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val i = base + x
                if (mask[i] == 0.toByte()) {
                    sourcePoints[numSources++] = i
                    stateX[i] = x
                    stateY[i] = y
                } else {
                    targetPoints[numTargets++] = i
                    stateX[i] = LONG_MAX_FLAG
                    stateY[i] = 0
                }
            }
        }
        if (numSources == 0) return false
        if (numTargets == 0) return false

        // ── Order the target list ────────────────────────────────────────────
        when (params.fillOrder) {
            FillOrder.RANDOM -> {
                for (i in numTargets - 1 downTo 1) {
                    val j = rng.nextInt(i + 1)
                    val tmp = targetPoints[i]
                    targetPoints[i] = targetPoints[j]
                    targetPoints[j] = tmp
                }
            }
            else -> {
                // Sort by distance to the sampling centre (bbox centre).
                var cx = 0
                var cy = 0
                for (i in 0 until numTargets) {
                    cx += targetPoints[i] % w
                    cy += targetPoints[i] / w
                }
                cx /= numTargets
                cy /= numTargets
                val centreDist = IntArray(numTargets) {
                    val p = targetPoints[it]
                    val dx = p % w - cx
                    val dy = p / w - cy
                    dx * dx + dy * dy
                }
                val idx = (0 until numTargets).sortedBy { centreDist[it] }
                val sortedTargets = IntArray(numTargets) { targetPoints[idx[it]] }
                if (params.fillOrder == FillOrder.OUTSIDE_IN) {
                    for (i in 0 until numTargets) targetPoints[i] = sortedTargets[numTargets - 1 - i]
                } else {
                    sortedTargets.copyInto(targetPoints)
                }
            }
        }

        // ── Refinement queue (PhotoDemon InitializeInpainter) ───────────────
        // PD always performs two FULL passes; the refinement parameter then
        // re-appends p·n points for a 3rd pass, p²·n for a 4th, and so on,
        // capped at 5× the original point count.
        val refine = params.refinement.coerceIn(0.0, 0.99)
        val maxPoints = numTargets * 5
        val queue = IntArray(maxPoints.coerceAtLeast(numTargets * 2))
        var queueSize = 0
        fun append(count: Int) {
            if (count <= 0 || queueSize + count > queue.size) return
            for (i in 0 until count) queue[queueSize + i] = targetPoints[i]
            queueSize += count
        }
        append(numTargets) // pass 1 (full)
        var toRefine = numTargets
        while (toRefine > 1 && queueSize < maxPoints) {
            append(toRefine) // pass 2 (full), then geometric decay
            toRefine = (toRefine * refine).toInt()
            if (queueSize + toRefine > maxPoints) toRefine = maxPoints - queueSize
        }

        // ── Comparator scratch buffers ───────────────────────────────────────
        val maxNeighbors = params.maxNeighbors.coerceIn(4, 100)
        val compOffX = IntArray(maxNeighbors)
        val compOffY = IntArray(maxNeighbors)
        val compCoordX = IntArray(maxNeighbors)
        val compCoordY = IntArray(maxNeighbors)
        val compColor = IntArray(maxNeighbors)

        var bestX = 0
        var bestY = 0
        var bestDiff = Long.MAX_VALUE

        fun evaluate(srcPoint: Int) {
            val sx = srcPoint % w
            val sy = srcPoint / w
            var totalDiff = 0L
            var compared = 0
            for (n in 0 until maxNeighbors) {
                if (compColor[n] == Int.MIN_VALUE) break // fewer neighbours than max
                val x = sx + compOffX[n]
                val y = sy + compOffY[n]
                var curDiff: Long
                if (x < 0 || y < 0 || x >= w || y >= h) {
                    curDiff = cauchyMax.toLong()
                } else {
                    val idx = y * w + x
                    if (mask[idx] == 0.toByte()) {
                        val a = pixels[idx]
                        val b = compColor[n]
                        val db = (b and 0xFF) - (a and 0xFF)
                        val dg = ((b ushr 8) and 0xFF) - ((a ushr 8) and 0xFF)
                        val dr = ((b ushr 16) and 0xFF) - ((a ushr 16) and 0xFF)
                        curDiff = (cauchy[db + 255] + cauchy[dg + 255] + cauchy[dr + 255]).toLong()
                    } else {
                        curDiff = if (compared != 0) totalDiff / compared else cauchyMax.toLong()
                    }
                }
                totalDiff += curDiff
                if (totalDiff >= bestDiff) return
                compared++
            }
            bestDiff = totalDiff
            bestX = sx
            bestY = sy
        }

        // ── Main synthesis loop ──────────────────────────────────────────────
        for (pass in 0 until queueSize) {
            val curPoint = queue[pass]
            val curX = curPoint % w
            val curY = curPoint / w

            // Assemble the nearest already-known neighbours as comparators.
            var numComp = 0
            var j = 0
            while (j < oc && numComp < maxNeighbors) {
                val tx = curX + sortedOffX[j]
                val ty = curY + sortedOffY[j]
                j++
                if (tx < 0 || tx >= w || ty < 0 || ty >= h) continue
                val tIdx = ty * w + tx
                if (stateX[tIdx] == LONG_MAX_FLAG) continue // not synthesized yet
                compOffX[numComp] = tx - curX
                compOffY[numComp] = ty - curY
                compCoordX[numComp] = stateX[tIdx]
                compCoordY[numComp] = stateY[tIdx]
                compColor[numComp] = pixels[tIdx]
                numComp++
            }
            // Sentinel so evaluate() knows how many comparators are valid.
            for (n in numComp until maxNeighbors) compColor[n] = Int.MIN_VALUE

            bestDiff = Long.MAX_VALUE

            if (numComp > 0) {
                // Evaluate each neighbour's original source coordinate.
                for (n in 0 until numComp) {
                    val testX = compCoordX[n] - compOffX[n]
                    val testY = compCoordY[n] - compOffY[n]
                    if (testX < 0 || testX >= w || testY < 0 || testY >= h) continue
                    val testIdx = testY * w + testX
                    if (mask[testIdx] != 0.toByte()) continue
                    if (stateIter[testIdx] == pass) continue
                    evaluate(testIdx)
                    if (bestDiff == 0L) break
                    stateIter[testIdx] = pass
                }
            }

            if (bestDiff > 0L) {
                // Scale random candidates down as the pass progresses (PD trick).
                val numRandom = (params.maxRandomCandidates.coerceIn(5, 200) *
                    (0.25 + 0.75 * ((queueSize - pass).toDouble() / queueSize))).toInt()
                for (rCand in 0 until numRandom) {
                    evaluate(sourcePoints[rng.nextInt(numSources)])
                    if (bestDiff == 0L) break
                }
            }

            // Assign the best-matching source pixel to this target pixel.
            pixels[curPoint] = pixels[bestY * w + bestX]
            stateX[curPoint] = bestX
            stateY[curPoint] = bestY

            if (onProgress != null && (pass and 0x3FF) == 0) {
                onProgress(pass.toFloat() / queueSize)
            }
        }
        return true
    }

    /**
     * Writes synthesized pixels back into the bitmap.
     *
     * v9 RemovR alpha handling — eliminates the black outline completely:
     *  • Pixels that were fully transparent (alpha < 16) → fully opaque fill.
     *  • Pixels that were SEMI-transparent (16 ≤ alpha < 255, i.e. the soft-
     *    brush feather) → the synthesized colour is blended with the original
     *    colour proportional to the REMAINING alpha, then the result is made
     *    fully opaque. The premultiplied dark fringe is therefore washed out
     *    by the new texture instead of surviving as a dark halo.
     *  • Fully opaque pixels keep their alpha and take the new colour.
     */
    private fun writeBack(
        bitmap: Bitmap,
        filled: IntArray,
        original: IntArray?,
        region: Region,
        rx: Int,
        ry: Int,
        rw: Int,
        rh: Int,
        healTransparency: Boolean
    ) {
        val base = original ?: run {
            val tmp = IntArray(rw * rh)
            bitmap.getPixels(tmp, 0, rw, rx, ry, rw, rh)
            tmp
        }
        val out = base.copyOf()
        val local = shiftRegion(region, -rx, -ry)
        // v9: for heal mode also cover the soft-brush blur fringe that can spill
        // a few pixels OUTSIDE the painted region (BlurMaskFilter.NORMAL blurs
        // outward as well as inward). We expand the write region by 3 px but
        // only touch pixels there whose alpha was actually reduced by the brush.
        val writeRegion = if (healTransparency) expandRegion(local, 3, rw, rh) else local
        val it = RegionIterator(writeRegion)
        val r = Rect()
        while (it.next(r)) {
            for (y in r.top until r.bottom) {
                if (y < 0 || y >= rh) continue
                val rowBase = y * rw
                for (x in r.left until r.right) {
                    if (x < 0 || x >= rw) continue
                    val i = rowBase + x
                    val origAlpha = Color.alpha(base[i])
                    val fillColor = filled[i]
                    val insideOriginal = local.contains(x, y)
                    out[i] = when {
                        // RemovR fully-erased hole → opaque synthesized texture.
                        healTransparency && origAlpha < 16 ->
                            fillColor or (0xFF shl 24)
                        // RemovR soft-brush feather (inside OR just outside the
                        // painted region) → blend toward the new colour, force
                        // opaque so no dark premultiplied residue survives.
                        healTransparency && origAlpha < 255 -> {
                            val t = origAlpha / 255f // how much of the OLD pixel remains
                            val fr = (Color.red(fillColor) * (1f - t) + Color.red(base[i]) * t).toInt()
                            val fg = (Color.green(fillColor) * (1f - t) + Color.green(base[i]) * t).toInt()
                            val fb = (Color.blue(fillColor) * (1f - t) + Color.blue(base[i]) * t).toInt()
                            Color.argb(255, fr, fg, fb)
                        }
                        // Pixels in the expanded fringe that were never touched
                        // by the eraser must be left completely alone.
                        healTransparency && !insideOriginal -> base[i]
                        // Classic fill / fully opaque RemovR pixel → keep alpha.
                        else -> (fillColor and 0x00FFFFFF) or (origAlpha shl 24)
                    }
                }
            }
        }
        bitmap.setPixels(out, 0, rw, rx, ry, rw, rh)
    }

    /** Expand a local region by [radius] px, clamped to the working rect. */
    private fun expandRegion(region: Region, radius: Int, w: Int, h: Int): Region {
        val out = Region()
        val it = RegionIterator(region)
        val r = Rect()
        while (it.next(r)) {
            val e = Rect(
                (r.left - radius).coerceAtLeast(0),
                (r.top - radius).coerceAtLeast(0),
                (r.right + radius).coerceAtMost(w),
                (r.bottom + radius).coerceAtMost(h)
            )
            if (!e.isEmpty) out.op(e, Region.Op.UNION)
        }
        return out
    }

    /** 4-connected binary dilation of the mask — swallows the soft-brush fringe. */
    private fun dilateMask(mask: ByteArray, w: Int, h: Int, iterations: Int) {
        val tmp = ByteArray(mask.size)
        repeat(iterations) {
            System.arraycopy(mask, 0, tmp, 0, mask.size)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    if (tmp[i] != 0.toByte()) continue
                    if ((x > 0 && tmp[i - 1] != 0.toByte()) ||
                        (x + 1 < w && tmp[i + 1] != 0.toByte()) ||
                        (y > 0 && tmp[i - w] != 0.toByte()) ||
                        (y + 1 < h && tmp[i + w] != 0.toByte())
                    ) {
                        mask[i] = 1
                    }
                }
            }
        }
    }

    // ── Mask helpers ─────────────────────────────────────────────────────────

    private fun buildMask(region: Region, w: Int, h: Int): ByteArray {
        val mask = ByteArray(w * h)
        val it = RegionIterator(region)
        val r = Rect()
        while (it.next(r)) {
            val x0 = r.left.coerceIn(0, w)
            val x1 = r.right.coerceIn(0, w)
            val y0 = r.top.coerceIn(0, h)
            val y1 = r.bottom.coerceIn(0, h)
            for (yy in y0 until y1) {
                val base = yy * w
                for (xx in x0 until x1) mask[base + xx] = 1
            }
        }
        return mask
    }

    /** Conservative mask resample: a target pixel is masked if any covered source pixel is masked. */
    private fun buildMaskScaled(region: Region, srcW: Int, srcH: Int, dstW: Int, dstH: Int): ByteArray {
        val mask = ByteArray(dstW * dstH)
        val it = RegionIterator(region)
        val r = Rect()
        while (it.next(r)) {
            val x0 = ((r.left.toLong() * dstW) / srcW).toInt().coerceIn(0, dstW)
            val x1 = ((r.right.toLong() * dstW + srcW - 1) / srcW).toInt().coerceIn(0, dstW)
            val y0 = ((r.top.toLong() * dstH) / srcH).toInt().coerceIn(0, dstH)
            val y1 = ((r.bottom.toLong() * dstH + srcH - 1) / srcH).toInt().coerceIn(0, dstH)
            for (yy in y0 until y1) {
                val base = yy * dstW
                for (xx in x0 until x1) mask[base + xx] = 1
            }
        }
        return mask
    }

    private fun shiftRegion(region: Region, dx: Int, dy: Int): Region {
        val out = Region()
        val it = RegionIterator(region)
        val r = Rect()
        while (it.next(r)) {
            val rr = Rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
            if (!rr.isEmpty) out.op(rr, Region.Op.UNION)
        }
        return out
    }

    /** Exposed for quality scoring in ContentAwareFillEngine (boundary seam metric). */
    internal fun colorDistance(a: Int, b: Int): Double {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        return (abs(dr) + abs(dg) + abs(db)) / 3.0
    }
}
