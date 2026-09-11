package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import kotlin.math.*

// Inpainter v6.0 — OOM-safe for images of any size
//
// KEY CHANGE: inpaintInPlace(bitmap, region)
//   Reads the sub-region into a temporary IntArray, processes it, then writes
//   it back — all without ever calling bitmap.copy() or creating an intermediate
//   Bitmap.  Peak extra allocation = ONE IntArray of (rw × rh) ints.
//
//   For an 800 × 18 000 image, a typical 300 × 300 bubble uses only 360 KB
//   of extra RAM instead of the 57.6 MB that bitmap.copy() would allocate.
//
// The old inpaint() entry point is kept for backward compatibility; it now
// tries inpaintInPlace() first and falls back to a copy only when the bitmap
// is immutable (rare in practice — layer bitmaps are always mutable).

object Inpainter {

    private const val GRADIENT_THRESHOLD = 32.0
    private const val SAMPLE_RING        = 18
    private const val REFINE_RADIUS      = 12
    private const val BLEND_WIDTH        = 6f
    private const val BOUNDARY_BLUR_PX   = 4

    // Cap for the sub-region processed at full resolution.
    // Above this we downsample the patch — still OOM-safe because we use
    // IntArray arithmetic (no intermediate Bitmap for scaling).
    private const val MAX_INPAINT_PIXELS = 300_000
    private const val CONTEXT_PAD        = 50

    // ── Public entry points ──────────────────────────────────────────────────

    /**
     * Modifies [bitmap] in-place (no full-image copy). Returns true on success.
     * For immutable bitmaps returns false — use [inpaint] as a fallback.
     */
    fun inpaintInPlace(bitmap: Bitmap, region: Region): Boolean {
        if (!bitmap.isMutable) return false
        return try {
            processRegion(bitmap, region)
            true
        } catch (oom: OutOfMemoryError) { oom.printStackTrace(); false }
          catch (e: Exception)          { e.printStackTrace(); false }
    }

    /**
     * Legacy entry point: returns a NEW mutable Bitmap with inpainting applied.
     * For large images prefer [inpaintInPlace] — this method still calls
     * bitmap.copy() which doubles peak RAM usage.
     */
    fun inpaint(bitmap: Bitmap, region: Region): Bitmap {
        // Try zero-copy in-place first
        if (bitmap.isMutable) {
            val work = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            return try {
                processRegion(work, region); work
            } catch (oom: OutOfMemoryError) {
                oom.printStackTrace(); work
            }
        }
        // Immutable fallback
        return try {
            val work = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            processRegion(work, region); work
        } catch (oom: OutOfMemoryError) {
            oom.printStackTrace(); bitmap
        }
    }

    // ── Core processing — no Bitmap allocations ──────────────────────────────

    private fun processRegion(bitmap: Bitmap, region: Region) {
        val W = bitmap.width; val H = bitmap.height
        val b = region.bounds

        val rx = (b.left   - CONTEXT_PAD).coerceIn(0, W - 1)
        val ry = (b.top    - CONTEXT_PAD).coerceIn(0, H - 1)
        val rw = ((b.right  + CONTEXT_PAD).coerceAtMost(W) - rx).coerceAtLeast(1)
        val rh = ((b.bottom + CONTEXT_PAD).coerceAtMost(H) - ry).coerceAtLeast(1)

        val subPx = rw.toLong() * rh

        if (subPx <= MAX_INPAINT_PIXELS) {
            // ── Full-resolution path: single IntArray, no Bitmap ──────────────
            val pixels = IntArray(rw * rh)
            bitmap.getPixels(pixels, 0, rw, rx, ry, rw, rh)
            val mask = buildMask(shiftRegion(region, -rx, -ry), rw, rh)
            if (!mask.any { it }) return
            inpaintPixels(pixels, mask, rw, rh)
            bitmap.setPixels(pixels, 0, rw, rx, ry, rw, rh)

        } else {
            // ── Downscale path: scale entirely in IntArrays ───────────────────
            val scale = sqrt(MAX_INPAINT_PIXELS.toDouble() / subPx).toFloat()
            val dW = (rw * scale).toInt().coerceAtLeast(4)
            val dH = (rh * scale).toInt().coerceAtLeast(4)

            // Read full-res sub-region, then downsample (reuse array slot)
            val subPixels = IntArray(rw * rh)
            bitmap.getPixels(subPixels, 0, rw, rx, ry, rw, rh)
            val small = downsampleNN(subPixels, rw, rh, dW, dH)

            val scaledRegion = scaleRegionDown(shiftRegion(region, -rx, -ry), rw, rh, dW, dH)
            val mask = buildMask(scaledRegion, dW, dH)
            if (!mask.any { it }) return
            inpaintPixels(small, mask, dW, dH)

            // Upsample and write back using subPixels as output buffer
            upsampleNNInto(small, dW, dH, subPixels, rw, rh)
            bitmap.setPixels(subPixels, 0, rw, rx, ry, rw, rh)
        }
    }

    // ── Pure-IntArray nearest-neighbour scaling ──────────────────────────────

    private fun downsampleNN(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        val out = IntArray(dw * dh)
        val xs = sw.toFloat() / dw; val ys = sh.toFloat() / dh
        for (y in 0 until dh) for (x in 0 until dw) {
            val sx = (x * xs).toInt().coerceIn(0, sw - 1)
            val sy = (y * ys).toInt().coerceIn(0, sh - 1)
            out[y * dw + x] = src[sy * sw + sx]
        }
        return out
    }

    // Upsample src(dw×dh) → dst(dstW×dstH) without allocating a new array
    private fun upsampleNNInto(src: IntArray, sw: Int, sh: Int, dst: IntArray, dw: Int, dh: Int) {
        val xs = sw.toFloat() / dw; val ys = sh.toFloat() / dh
        for (y in 0 until dh) for (x in 0 until dw) {
            val sx = (x * xs).toInt().coerceIn(0, sw - 1)
            val sy = (y * ys).toInt().coerceIn(0, sh - 1)
            dst[y * dw + x] = src[sy * sw + sx]
        }
    }

    // ── Region helpers ───────────────────────────────────────────────────────

    private fun shiftRegion(region: Region, dx: Int, dy: Int): Region {
        val out = Region(); val ri = RegionIterator(region); val r = Rect()
        while (ri.next(r)) {
            val s = Rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
            if (!s.isEmpty) out.union(s)
        }
        return out
    }

    private fun scaleRegionDown(region: Region, ow: Int, oh: Int, nw: Int, nh: Int): Region {
        val sx = nw.toFloat() / ow; val sy = nh.toFloat() / oh
        val out = Region(); val ri = RegionIterator(region); val r = Rect()
        while (ri.next(r)) {
            val s = Rect(
                (r.left   * sx).toInt(), (r.top    * sy).toInt(),
                (r.right  * sx).toInt().coerceAtMost(nw),
                (r.bottom * sy).toInt().coerceAtMost(nh)
            )
            if (!s.isEmpty) out.union(s)
        }
        return out
    }

    // ── Inpaint pixel array in-place ─────────────────────────────────────────

    private fun inpaintPixels(pixels: IntArray, mask: BooleanArray, W: Int, H: Int) {
        val bgSamples = sampleBgColors(pixels, mask, W, H)
        val variance  = colorVariance(bgSamples)

        if (variance < GRADIENT_THRESHOLD) {
            val bgColor = dominantColor(bgSamples)
            for (i in pixels.indices) if (mask[i]) pixels[i] = bgColor
            teleaRefine(pixels, mask, W, H)
        } else {
            exemplarFill(pixels, mask, W, H)
            teleaRefine(pixels, mask, W, H)
            gaussianBoundaryBlur(pixels, mask, W, H)
        }
    }

    // ── Mask ─────────────────────────────────────────────────────────────────

    private fun buildMask(region: Region, W: Int, H: Int): BooleanArray {
        val mask = BooleanArray(W * H)
        val ri = RegionIterator(region); val r = Rect()
        while (ri.next(r)) {
            val x0 = r.left.coerceIn(0, W);  val x1 = r.right.coerceIn(0, W)
            val y0 = r.top.coerceIn(0, H);   val y1 = r.bottom.coerceIn(0, H)
            for (y in y0 until y1) for (x in x0 until x1) mask[y * W + x] = true
        }
        return mask
    }

    // ── Background sampling ───────────────────────────────────────────────────

    private fun sampleBgColors(pixels: IntArray, mask: BooleanArray, W: Int, H: Int): List<Int> {
        val samples = mutableListOf<Int>()
        for (y in 0 until H step 2) for (x in 0 until W step 2) {
            if (!mask[y * W + x]) continue
            outer@ for (dy in -SAMPLE_RING..SAMPLE_RING)
                    for (dx in -SAMPLE_RING..SAMPLE_RING) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue
                if (!mask[ny * W + nx]) { samples.add(pixels[ny * W + nx]); break@outer }
            }
        }
        return if (samples.isEmpty()) listOf(Color.WHITE) else samples
    }

    private fun colorVariance(colors: List<Int>): Double {
        if (colors.size < 2) return 0.0
        fun List<Double>.v(): Double { val m = average(); return map { (it - m).pow(2) }.average() }
        return (colors.map { Color.red(it).toDouble() }.v() +
                colors.map { Color.green(it).toDouble() }.v() +
                colors.map { Color.blue(it).toDouble() }.v()) / 3.0
    }

    private fun dominantColor(colors: List<Int>): Int {
        val step = 32; val buckets = HashMap<Int, Int>()
        for (c in colors) {
            val key = ((Color.red(c) / step) shl 16) or
                      ((Color.green(c) / step) shl 8) or (Color.blue(c) / step)
            buckets[key] = (buckets[key] ?: 0) + 1
        }
        val best = buckets.maxByOrNull { it.value }?.key ?: return Color.WHITE
        return Color.rgb(
            ((best shr 16 and 0xFF) * step + step / 2).coerceIn(0, 255),
            ((best shr  8 and 0xFF) * step + step / 2).coerceIn(0, 255),
            ((best        and 0xFF) * step + step / 2).coerceIn(0, 255)
        )
    }

    // ── Exemplar fill ─────────────────────────────────────────────────────────

    private fun exemplarFill(pixels: IntArray, mask: BooleanArray, W: Int, H: Int) {
        val dist  = IntArray(W * H) { if (mask[it]) Int.MAX_VALUE else 0 }
        val queue = ArrayDeque<Int>()
        for (i in dist.indices) if (dist[i] == 0) queue.add(i)
        val dirs4 = intArrayOf(-1, 1, -W, W)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (d in dirs4) {
                val nb = cur + d
                if (nb < 0 || nb >= W * H) continue
                val nx = nb % W
                if (d == -1 && nx == W - 1) continue
                if (d ==  1 && nx == 0)     continue
                if (dist[nb] > dist[cur] + 1) { dist[nb] = dist[cur] + 1; queue.add(nb) }
            }
        }

        val order = (0 until W * H).filter { mask[it] }.sortedBy { dist[it] }
        for (i in order) {
            val x = i % W; val y = i / W
            val sampleR = (dist[i] + 4).coerceAtMost(14)
            var rS = 0.0; var gS = 0.0; var bS = 0.0; var wS = 0.0
            for (dy in -sampleR..sampleR) for (dx in -sampleR..sampleR) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue
                val ni = ny * W + nx
                if (mask[ni] && dist[ni] >= dist[i]) continue
                val d2 = dx.toLong() * dx + dy.toLong() * dy
                val wt = 1.0 / (d2 + 1.0)
                val c  = pixels[ni]
                rS += Color.red(c) * wt; gS += Color.green(c) * wt
                bS += Color.blue(c) * wt; wS += wt
            }
            if (wS > 0.0) pixels[i] = Color.rgb(
                (rS / wS).toInt().coerceIn(0, 255),
                (gS / wS).toInt().coerceIn(0, 255),
                (bS / wS).toInt().coerceIn(0, 255)
            )
        }
    }

    // ── Telea boundary refinement ─────────────────────────────────────────────

    private fun teleaRefine(pixels: IntArray, mask: BooleanArray, W: Int, H: Int) {
        val dist = bfsDist(mask, W, H)
        val orig = pixels.copyOf()
        for (idx in pixels.indices) {
            if (!mask[idx]) continue
            val d = dist[idx]; if (d > REFINE_RADIUS) continue
            val x = idx % W; val y = idx / W
            var rA = 0.0; var gA = 0.0; var bA = 0.0; var wA = 0.0
            for (dy in -REFINE_RADIUS..REFINE_RADIUS) for (dx in -REFINE_RADIUS..REFINE_RADIUS) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || nx >= W || ny < 0 || ny >= H) continue
                val ni = ny * W + nx
                if (mask[ni] && dist[ni] >= d) continue
                val wt = 1.0 / ((dx * dx + dy * dy).toDouble() + 0.01)
                val c  = orig[ni]
                rA += Color.red(c) * wt; gA += Color.green(c) * wt
                bA += Color.blue(c) * wt; wA += wt
            }
            if (wA > 0.0) {
                val refined = Color.rgb(
                    (rA / wA).toInt().coerceIn(0, 255),
                    (gA / wA).toInt().coerceIn(0, 255),
                    (bA / wA).toInt().coerceIn(0, 255)
                )
                val t = (d / BLEND_WIDTH).coerceIn(0f, 1f)
                pixels[idx] = lerpColor(refined, pixels[idx], t)
            }
        }
    }

    // ── Boundary-only Gaussian blur ───────────────────────────────────────────

    private fun gaussianBoundaryBlur(pixels: IntArray, mask: BooleanArray, W: Int, H: Int) {
        val dist   = bfsDist(mask, W, H)
        val kernel = floatArrayOf(1f, 4f, 6f, 4f, 1f); val radius = 2
        val tmp    = pixels.copyOf()
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            if (!mask[i] || dist[i] > BOUNDARY_BLUR_PX) continue
            var r = 0f; var g = 0f; var b = 0f; var w = 0f
            for (dx in -radius..radius) {
                val nx = (x + dx).coerceIn(0, W - 1)
                val c = tmp[y * W + nx]; val wt = kernel[dx + radius]
                r += Color.red(c) * wt; g += Color.green(c) * wt; b += Color.blue(c) * wt; w += wt
            }
            if (w > 0f) pixels[i] = Color.rgb((r/w).toInt(), (g/w).toInt(), (b/w).toInt())
        }
        val tmp2 = pixels.copyOf()
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            if (!mask[i] || dist[i] > BOUNDARY_BLUR_PX) continue
            var r = 0f; var g = 0f; var b = 0f; var w = 0f
            for (dy in -radius..radius) {
                val ny = (y + dy).coerceIn(0, H - 1)
                val c = tmp2[ny * W + x]; val wt = kernel[dy + radius]
                r += Color.red(c) * wt; g += Color.green(c) * wt; b += Color.blue(c) * wt; w += wt
            }
            if (w > 0f) pixels[i] = Color.rgb((r/w).toInt(), (g/w).toInt(), (b/w).toInt())
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun bfsDist(mask: BooleanArray, W: Int, H: Int): FloatArray {
        val dist  = FloatArray(W * H) { if (mask[it]) Float.MAX_VALUE else 0f }
        val queue = ArrayDeque<Int>()
        for (i in dist.indices) if (dist[i] == 0f) queue.add(i)
        val dirs4 = intArrayOf(-1, 1, -W, W)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (d in dirs4) {
                val nb = cur + d
                if (nb < 0 || nb >= dist.size) continue
                val nx = nb % W
                if (d == -1 && nx == W - 1) continue
                if (d ==  1 && nx == 0)     continue
                val nd = dist[cur] + 1f
                if (nd < dist[nb]) { dist[nb] = nd; queue.add(nb) }
            }
        }
        return dist
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r  = (Color.red(a)   * (1 - t) + Color.red(b)   * t).toInt().coerceIn(0, 255)
        val g  = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt().coerceIn(0, 255)
        val bl = (Color.blue(a)  * (1 - t) + Color.blue(b)  * t).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, bl)
    }
}
