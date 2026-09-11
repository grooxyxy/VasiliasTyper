package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * CriminisiEngine — exemplar-based object removal after
 *
 *   A. Criminisi, P. Perez, K. Toyama,
 *   "Region Filling and Object Removal by Exemplar-Based Image Inpainting",
 *   IEEE Transactions on Image Processing, Vol. 13, No. 9, September 2004.
 *   https://www.irisa.fr/vista/Papers/2004_ip_criminisi.pdf
 *
 * This is the algorithm also exposed by the Python reference implementation
 * adl1995/image-eraser (github.com/adl1995/image-eraser), which implements
 * the same paper: isophote-driven patch filling where the fill order is
 * governed by a priority term
 *
 *      P(p) = C(p) · D(p)
 *
 *  • C(p) — confidence: how much reliable (known) information surrounds p.
 *  • D(p) — data term: strength of the isophote (structure) hitting the
 *    front at p, so linear structures are continued first.
 *
 * Algorithm outline (per the paper, section 3):
 *  1. Extract the fill front δΩ (boundary of the target region Ω).
 *  2. Compute priorities P(p) for every front pixel.
 *  3. Find the patch Ψp̂ centred on the highest-priority front pixel.
 *  4. Search the source region Φ for the best-matching exemplar patch
 *     Ψq̂ minimising the sum of squared differences over the KNOWN part
 *     of Ψp̂.
 *  5. Copy the UNKNOWN part of Ψq̂ into Ψp̂.
 *  6. Update confidences, recompute the front, repeat until Ω is empty.
 *
 * Manga/webtoon specific tuning:
 *  • The patch distance is measured in a luma-weighted colour space so
 *    screen-tone dots and ink lines dominate over subtle paper tint shifts.
 *  • A boundary feather pass at the end hides the 1px seam that pure
 *    patch copying can leave on anti-aliased manga strokes.
 *  • Large ROIs are adaptively down-scaled before synthesis, then the
 *    result is up-scaled and alpha-blended only inside the mask so tall
 *    800 × 20 000 px webtoon pages stay inside the mobile RAM budget.
 *
 * Threading: call from a background thread. Not re-entrant.
 */
object CriminisiEngine {

    data class Params(
        /** Patch half-size (paper used 9×9 default → half-size 4). */
        val patchRadius: Int = 4,
        /** Extra source context sampled around the mask bbox. */
        val contextPadding: Int = 160,
        /** Weight of the luminance channel in patch distance (0..1). */
        val lumaWeight: Float = 0.55f,
        /** Boundary feather strength (0 = off, 1 = full blend). */
        val feather: Float = 0.6f
    )

    data class Result(
        val success: Boolean,
        val message: String,
        val processedPixels: Int = 0
    )

    /** Hard cap for the cropped working set (width × height). */
    private const val MAX_ROI_PIXELS = 3_600_000
    private const val KNOWN: Byte = 0
    private const val TARGET: Byte = 1
    private const val FRONT: Byte = 2
    private const val INSIDE: Byte = 3
    private const val ALPHA_NORM = 1f / 255f

    // ── Public entry point ───────────────────────────────────────────────────

    fun inpaint(
        bitmap: Bitmap,
        region: Region,
        params: Params = Params(),
        onProgress: ((Float) -> Unit)? = null
    ): Result {
        if (!bitmap.isMutable || bitmap.isRecycled) {
            return Result(false, "Layer harus berupa bitmap mutable")
        }
        val bounds = Rect(0, 0, bitmap.width, bitmap.height)
        val clipped = Region(region).apply { op(Region(bounds), Region.Op.INTERSECT) }
        if (clipped.isEmpty) return Result(false, "Mask kosong")

        val maskBounds = Rect(clipped.bounds)
        if (maskBounds.isEmpty) return Result(false, "Mask berada di luar kanvas")

        val pad = params.contextPadding.coerceIn(24, 400)
        val rx = max(0, maskBounds.left - pad)
        val ry = max(0, maskBounds.top - pad)
        val rr = min(bitmap.width, maskBounds.right + pad)
        val rb = min(bitmap.height, maskBounds.bottom + pad)
        val rw = rr - rx
        val rh = rb - ry
        if (rw <= 0 || rh <= 0) return Result(false, "Area sampel tidak valid")

        var scale = 1f
        while (rw * rh * scale * scale > MAX_ROI_PIXELS && scale > 0.25f) {
            scale *= 0.7071f
        }
        val workW = max(16, (rw * scale).toInt())
        val workH = max(16, (rh * scale).toInt())

        return try {
            val workPixels: IntArray
            val workMask: ByteArray
            val scaled = scale != 1f

            if (scaled) {
                val tmp = Bitmap.createBitmap(bitmap, rx, ry, rw, rh)
                val small = Bitmap.createScaledBitmap(tmp, workW, workH, true)
                if (tmp != small && !tmp.isRecycled) tmp.recycle()
                workPixels = IntArray(workW * workH)
                small.getPixels(workPixels, 0, workW, 0, 0, workW, workH)
                workMask = buildMaskScaled(shiftRegion(clipped, -rx, -ry), rw, rh, workW, workH)
                if (!small.isRecycled) small.recycle()
            } else {
                workPixels = IntArray(rw * rh)
                bitmap.getPixels(workPixels, 0, rw, rx, ry, rw, rh)
                workMask = buildMask(shiftRegion(clipped, -rx, -ry), workW, workH)
            }

            val targetCount = workMask.count { it != KNOWN }
            if (targetCount == 0) return Result(false, "Tidak ada piksel yang perlu diisi")

            onProgress?.invoke(0.03f)

            val synthOk = synthesize(workPixels, workMask, workW, workH, params) { p ->
                onProgress?.invoke(0.03f + p * 0.92f)
            }
            if (!synthOk) return Result(false, "Tekstur sumber tidak mencukupi")

            writeBack(
                bitmap, workPixels, clipped,
                rx, ry, rw, rh, workW, workH, scaled,
                feather = params.feather
            )
            onProgress?.invoke(1f)
            Result(true, "Criminisi selesai", targetCount)
        } catch (oom: OutOfMemoryError) {
            oom.printStackTrace()
            Result(false, "Memori tidak cukup — perkecil area seleksi")
        } catch (t: Throwable) {
            t.printStackTrace()
            Result(false, "Criminisi dibatalkan: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ── Core exemplar-based synthesis ────────────────────────────────────────

    /**
     * Criminisi et al. synthesis loop. Mutates [pixels] and [state] in place.
     * [state]: KNOWN(0) / TARGET(1) / FRONT(2) / INSIDE(3).
     */
    private fun synthesize(
        pixels: IntArray,
        state: ByteArray,
        w: Int,
        h: Int,
        params: Params,
        onProgress: ((Float) -> Unit)?
    ): Boolean {
        val size = w * h
        val patchR = params.patchRadius.coerceIn(2, 12)

        // Split RGB into planar luma/chroma for cheap weighted distance.
        val lum = FloatArray(size)
        val chA = FloatArray(size)
        val chB = FloatArray(size)
        for (i in 0 until size) {
            val c = pixels[i]
            val r = Color.red(c).toFloat()
            val g = Color.green(c).toFloat()
            val b = Color.blue(c).toFloat()
            lum[i] = 0.299f * r + 0.587f * g + 0.114f * b
            chA[i] = r - 0.5f * g - 0.5f * b
            chB[i] = 0.5f * g - 0.5f * b
        }

        // Confidence C(p) — 1 for known pixels, 0 for targets (paper §3.1).
        val confidence = FloatArray(size)
        // Working copy of the target count so progress is monotonic.
        var remaining = 0
        for (i in 0 until size) {
            if (state[i] == TARGET) {
                confidence[i] = 0f
                state[i] = INSIDE
                remaining++
            } else {
                confidence[i] = 1f
            }
        }
        if (remaining == 0) return false

        // Collect known source pixel indices once (paper: Ψq̂ ∈ Φ).
        val sourceIndices = ArrayList<Int>(size - remaining)
        for (i in 0 until size) if (state[i] == KNOWN) sourceIndices.add(i)
        if (sourceIndices.isEmpty()) return false

        // Gradient of the image (Sobel) — needed for the data term.
        // Paper uses the isophote direction = perpendicular to the max gradient.
        val gradX = FloatArray(size)
        val gradY = FloatArray(size)
        computeGradients(lum, state, w, h, gradX, gradY)

        val initialTotal = remaining
        var pass = 0

        while (remaining > 0) {
            pass++
            // 1) Identify the fill front: inside pixels with ≥1 known neighbour.
            val front = ArrayList<Int>(min(remaining, 4096))
            for (i in 0 until size) {
                if (state[i] != INSIDE) continue
                val x = i % w
                val y = i / w
                if ((x > 0 && state[i - 1] == KNOWN) ||
                    (x + 1 < w && state[i + 1] == KNOWN) ||
                    (y > 0 && state[i - w] == KNOWN) ||
                    (y + 1 < h && state[i + w] == KNOWN)
                ) {
                    front.add(i)
                }
            }
            if (front.isEmpty()) {
                // Disconnected leftovers — fill with nearest known colour.
                for (i in 0 until size) {
                    if (state[i] == INSIDE) {
                        val n = nearestKnown(lum, chA, chB, state, w, h, i)
                        lum[i] = lum[n]; chA[i] = chA[n]; chB[i] = chB[n]
                        state[i] = KNOWN
                        confidence[i] = 1f
                        remaining--
                    }
                }
                break
            }

            // 2) Priority P(p) = C(p)·D(p) for every front pixel.
            var bestFront = -1
            var bestPriority = -1f
            var bestFrontConf = 0f
            val patchArea = (2 * patchR + 1) * (2 * patchR + 1)

            for (fi in front.indices) {
                val p = front[fi]
                // C(p): average confidence of known pixels in the patch.
                var sumC = 0f
                var cntC = 0
                val px = p % w
                val py = p / w
                val x0 = max(0, px - patchR)
                val x1 = min(w - 1, px + patchR)
                val y0 = max(0, py - patchR)
                val y1 = min(h - 1, py + patchR)
                for (yy in y0..y1) {
                    val row = yy * w
                    for (xx in x0..x1) {
                        sumC += confidence[row + xx]
                        cntC++
                    }
                }
                val c = sumC / patchArea

                // D(p): |∇I⊥ · np| / α  (paper eq. 3, α = 255).
                // Normal np is the unit vector pointing into the target region.
                val nrm = computeFrontNormal(state, w, h, px, py)
                val gx = gradX[p]
                val gy = gradY[p]
                val gmag = sqrt(gx * gx + gy * gy)
                val data = if (gmag > 1e-6f) {
                    // Isophote direction = perpendicular to gradient.
                    val ix = -gy / gmag
                    val iy = gx / gmag
                    abs(ix * nrm[0] + iy * nrm[1]) * (gmag * ALPHA_NORM)
                } else 0f

                val priority = c * (0.001f + data) // small epsilon avoids 0-priority deadlock
                if (priority > bestPriority) {
                    bestPriority = priority
                    bestFront = p
                    bestFrontConf = c
                }
            }
            if (bestFront < 0) break

            // 3) Best exemplar patch Ψq̂ for Ψp̂ centred at bestFront.
            val bestSource = findBestExemplar(
                lum, chA, chB, state, confidence,
                w, h, bestFront, patchR, sourceIndices, params.lumaWeight
            )

            // 4) Copy unknown part of Ψq̂ into Ψp̂ (paper §3.3).
            val px = bestFront % w
            val py = bestFront / w
            val sx = bestSource % w
            val sy = bestSource / w
            val offX = sx - px
            val offY = sy - py

            val x0 = max(0, px - patchR)
            val x1 = min(w - 1, px + patchR)
            val y0 = max(0, py - patchR)
            val y1 = min(h - 1, py + patchR)
            for (yy in y0..y1) {
                val srcY = yy + offY
                if (srcY !in 0 until h) continue
                val row = yy * w
                val srcRow = srcY * w
                for (xx in x0..x1) {
                    val srcX = xx + offX
                    if (srcX !in 0 until w) continue
                    val t = row + xx
                    if (state[t] == KNOWN) continue
                    val s = srcRow + srcX
                    if (state[s] != KNOWN) continue
                    lum[t] = lum[s]
                    chA[t] = chA[s]
                    chB[t] = chB[s]
                    state[t] = KNOWN
                    // Update confidence with the front pixel's confidence
                    // (paper eq. 4: C(q) = C(p̂) ∀ q ∈ Ψp̂ ∩ Ω).
                    confidence[t] = bestFrontConf
                    remaining--
                }
            }

            if (onProgress != null && (pass and 0xF) == 0) {
                onProgress(1f - remaining.toFloat() / initialTotal)
            }
        }

        // Recompose planar luma/chroma back to ARGB pixels.
        for (i in 0 until size) {
            if (state[i] != KNOWN) continue
            val l = lum[i]
            val a = chA[i]
            val b = chB[i]
            // Inverse of the linear transform used above.
            val r = l + 0.667f * a
            val g = l - 0.333f * a + b
            val bb = l - 0.333f * a - b
            val alpha = Color.alpha(pixels[i])
            pixels[i] = Color.argb(
                alpha,
                r.toInt().coerceIn(0, 255),
                g.toInt().coerceIn(0, 255),
                bb.toInt().coerceIn(0, 255)
            )
        }
        return true
    }

    // ── Criminisi helpers ────────────────────────────────────────────────────

    /** Unit normal of the fill front at (x,y), pointing into the target region. */
    private fun computeFrontNormal(state: ByteArray, w: Int, h: Int, x: Int, y: Int): FloatArray {
        var nx = 0f
        var ny = 0f
        // Sample a small neighbourhood; the normal is the average direction
        // from known pixels toward unknown pixels.
        for (dy in -2..2) {
            for (dx in -2..2) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx
                val yy = y + dy
                if (xx !in 0 until w || yy !in 0 until h) continue
                val i = yy * w + xx
                if (state[i] == KNOWN) {
                    nx -= dx
                    ny -= dy
                } else {
                    nx += dx
                    ny += dy
                }
            }
        }
        val len = sqrt(nx * nx + ny * ny)
        return if (len > 1e-6f) floatArrayOf(nx / len, ny / len) else floatArrayOf(0f, 1f)
    }

    /** Sobel gradients of the luminance plane, restricted to known pixels. */
    private fun computeGradients(
        lum: FloatArray,
        state: ByteArray,
        w: Int,
        h: Int,
        gradX: FloatArray,
        gradY: FloatArray
    ) {
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                if (state[i] != KNOWN) continue
                fun l(xx: Int, yy: Int): Float {
                    val j = yy * w + xx
                    return if (state[j] == KNOWN) lum[j] else lum[i]
                }
                // Sobel kernels
                gradX[i] = (l(x + 1, y - 1) + 2f * l(x + 1, y) + l(x + 1, y + 1)) -
                    (l(x - 1, y - 1) + 2f * l(x - 1, y) + l(x - 1, y + 1))
                gradY[i] = (l(x - 1, y + 1) + 2f * l(x, y + 1) + l(x + 1, y + 1)) -
                    (l(x - 1, y - 1) + 2f * l(x, y - 1) + l(x + 1, y - 1))
            }
        }
    }

    /**
     * Exhaustive exemplar search (paper §3.2): minimise the SSD between the
     * KNOWN part of the target patch and the source patch, luma-weighted.
     * A confidence-weighted term prefers sources whose own neighbourhood is
     * fully known (avoids copying from the still-unfilled frontier).
     */
    private fun findBestExemplar(
        lum: FloatArray,
        chA: FloatArray,
        chB: FloatArray,
        state: ByteArray,
        confidence: FloatArray,
        w: Int,
        h: Int,
        targetCenter: Int,
        patchR: Int,
        sourceIndices: List<Int>,
        lumaWeight: Float
    ): Int {
        val tx = targetCenter % w
        val ty = targetCenter / w
        val x0 = max(0, tx - patchR)
        val x1 = min(w - 1, tx + patchR)
        val y0 = max(0, ty - patchR)
        val y1 = min(h - 1, ty + patchR)

        var best = -1
        var bestDist = Float.MAX_VALUE

        // Coarse stride first, refine around the winner — keeps the exhaustive
        // search O(n) but with a practical constant on mobile CPUs.
        val stride = max(1, patchR / 2)

        // Proper scoring loop (stride scan).
        var bestStride = -1
        var bestStrideDist = Float.MAX_VALUE
        var si = 0
        val nSrc = sourceIndices.size
        while (si < nSrc) {
            val srcCenter = sourceIndices[si]
            si += stride
            val sx = srcCenter % w
            val sy = srcCenter / w
            // Source patch must be fully inside the known region.
            if (sx - patchR < 0 || sx + patchR >= w || sy - patchR < 0 || sy + patchR >= h) continue
            var fullyKnown = true
            outer@ for (yy in sy - patchR..sy + patchR) {
                val row = yy * w
                for (xx in sx - patchR..sx + patchR) {
                    if (state[row + xx] != KNOWN) { fullyKnown = false; break@outer }
                }
            }
            if (!fullyKnown) continue

            var dist = 0f
            var confSum = 0f
            var cnt = 0
            for (dy in -patchR..patchR) {
                val tyy = ty + dy
                val syy = sy + dy
                if (tyy < y0 || tyy > y1 || syy !in 0 until h) continue
                val tRow = tyy * w
                val sRow = syy * w
                for (dx in -patchR..patchR) {
                    val txx = tx + dx
                    val sxx = sx + dx
                    if (txx < x0 || txx > x1 || sxx !in 0 until w) continue
                    val t = tRow + txx
                    if (state[t] != KNOWN) continue
                    val s = sRow + sxx
                    val dl = lum[t] - lum[s]
                    val da = chA[t] - chA[s]
                    val db = chB[t] - chB[s]
                    dist += lumaWeight * dl * dl + (1f - lumaWeight) * 0.5f * (da * da + db * db)
                    confSum += confidence[s]
                    cnt++
                }
            }
            if (cnt == 0) continue
            // Normalise + small penalty for low-confidence sources.
            dist = dist / cnt - 0.0001f * (confSum / cnt)
            if (dist < bestStrideDist) {
                bestStrideDist = dist
                bestStride = srcCenter
            }
        }

        // Refine in a small window around the stride winner.
        if (bestStride >= 0) {
            val cx = bestStride % w
            val cy = bestStride / w
            val ref = max(1, stride * 2)
            for (yy in max(patchR, cy - ref)..min(h - 1 - patchR, cy + ref)) {
                val row = yy * w
                for (xx in max(patchR, cx - ref)..min(w - 1 - patchR, cx + ref)) {
                    val s = row + xx
                    if (state[s] != KNOWN) continue
                    var fullyKnown = true
                    outer@ for (dy in -patchR..patchR) {
                        for (dx in -patchR..patchR) {
                            if (state[(yy + dy) * w + (xx + dx)] != KNOWN) { fullyKnown = false; break@outer }
                        }
                    }
                    if (!fullyKnown) continue
                    var dist = 0f
                    var confSum = 0f
                    var cnt = 0
                    for (dy in -patchR..patchR) {
                        val tyy = ty + dy
                        val syy = yy + dy
                        if (tyy < y0 || tyy > y1) continue
                        val tRow = tyy * w
                        val sRow = syy * w
                        for (dx in -patchR..patchR) {
                            val txx = tx + dx
                            val sxx = xx + dx
                            if (txx < x0 || txx > x1) continue
                            val t = tRow + txx
                            if (state[t] != KNOWN) continue
                            val ss = sRow + sxx
                            val dl = lum[t] - lum[ss]
                            val da = chA[t] - chA[ss]
                            val db = chB[t] - chB[ss]
                            dist += lumaWeight * dl * dl + (1f - lumaWeight) * 0.5f * (da * da + db * db)
                            confSum += confidence[ss]
                            cnt++
                        }
                    }
                    if (cnt == 0) continue
                    dist = dist / cnt - 0.0001f * (confSum / cnt)
                    if (dist < bestDist) {
                        bestDist = dist
                        best = s
                    }
                }
            }
        }
        return if (best >= 0) best else bestStride
    }

    /** Fallback for disconnected leftovers: copy the nearest known pixel. */
    private fun nearestKnown(
        lum: FloatArray,
        chA: FloatArray,
        chB: FloatArray,
        state: ByteArray,
        w: Int,
        h: Int,
        idx: Int
    ): Int {
        val x = idx % w
        val y = idx / w
        for (radius in 1..(max(w, h) / 2 + 2)) {
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (state[ni] == KNOWN) return ni
                }
            }
        }
        return idx
    }

    // ── Write-back with feathered seam ───────────────────────────────────────

    private fun writeBack(
        bitmap: Bitmap,
        workPixels: IntArray,
        region: Region,
        rx: Int, ry: Int, rw: Int, rh: Int,
        workW: Int, workH: Int,
        scaled: Boolean,
        feather: Float
    ) {
        val upPixels: IntArray
        if (scaled) {
            val workBmp = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
            workBmp.setPixels(workPixels, 0, workW, 0, 0, workW, workH)
            val up = Bitmap.createScaledBitmap(workBmp, rw, rh, true)
            if (!workBmp.isRecycled) workBmp.recycle()
            upPixels = IntArray(rw * rh)
            up.getPixels(upPixels, 0, rw, 0, 0, rw, rh)
            if (!up.isRecycled) up.recycle()
        } else {
            upPixels = workPixels
        }

        val base = IntArray(rw * rh)
        bitmap.getPixels(base, 0, rw, rx, ry, rw, rh)
        val out = base.copyOf()
        val local = shiftRegion(region, -rx, -ry)
        val it = RegionIterator(local)
        val r = Rect()
        while (it.next(r)) {
            for (y in r.top until r.bottom) {
                if (y < 0 || y >= rh) continue
                val row = y * rw
                for (x in r.left until r.right) {
                    if (x < 0 || x >= rw) continue
                    val i = row + x
                    val fill = upPixels[i]
                    val orig = base[i]
                    // Feathered seam: blend 1px inside the mask boundary so the
                    // patch-copy edge disappears into anti-aliased manga strokes.
                    val onBoundary = isMaskBoundary(local, x, y, rw, rh)
                    val blended = if (onBoundary && feather > 0f) {
                        val f = feather.coerceIn(0f, 1f)
                        val fr = (Color.red(fill) * (1f - f) + Color.red(orig) * f).toInt()
                        val fg = (Color.green(fill) * (1f - f) + Color.green(orig) * f).toInt()
                        val fb = (Color.blue(fill) * (1f - f) + Color.blue(orig) * f).toInt()
                        Color.rgb(fr, fg, fb)
                    } else {
                        Color.rgb(Color.red(fill), Color.green(fill), Color.blue(fill))
                    }
                    val alpha = Color.alpha(orig)
                    out[i] = (alpha shl 24) or (blended and 0x00FFFFFF)
                }
            }
        }
        bitmap.setPixels(out, 0, rw, rx, ry, rw, rh)
    }

    private fun isMaskBoundary(region: Region, x: Int, y: Int, w: Int, h: Int): Boolean {
        // A masked pixel is on the boundary if any 4-neighbour is outside the mask.
        if (x > 0 && !region.contains(x - 1, y)) return true
        if (x + 1 < w && !region.contains(x + 1, y)) return true
        if (y > 0 && !region.contains(x, y - 1)) return true
        if (y + 1 < h && !region.contains(x, y + 1)) return true
        return false
    }

    // ── Mask helpers (mirrors ResynthesizerEngine conventions) ──────────────

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
                for (xx in x0 until x1) mask[base + xx] = TARGET
            }
        }
        return mask
    }

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
                for (xx in x0 until x1) mask[base + xx] = TARGET
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
}
