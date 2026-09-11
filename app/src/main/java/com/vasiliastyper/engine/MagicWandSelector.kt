package com.vasiliastyper.engine

import android.graphics.*
import kotlin.math.*

/**
 * VasiliasTyper v2.0 — Magic Wand Selector
 *
 * Algorithm: Scanline (row-segment) flood fill — 3-5× faster than pixel BFS.
 * Edge-aware: optional Sobel pre-pass stops selection at strong edges/ink lines.
 */
object MagicWandSelector {

    private const val OUTLINE_LUM_THRESHOLD = 128
    private const val OUTLINE_MAX_PASSES    = 2
    private const val MAX_SAFE_PIXELS       = 10_000_000
    private const val EDGE_STOP_THRESHOLD   = 50

    private const val UNVISITED: Byte = 0
    private const val SELECTED:  Byte = 1

    fun select(bitmap: Bitmap, startX: Int, startY: Int, tolerance: Int): Region {
        return try {
            selectInternal(bitmap, startX, startY, tolerance)
        } catch (e: OutOfMemoryError) { e.printStackTrace(); Region() }
          catch (e: Exception)        { e.printStackTrace(); Region() }
    }

    fun fillRegion(bitmap: Bitmap, region: Region, fillColor: Int,
                   @Suppress("UNUSED_PARAMETER") ignoreText: Boolean = false) {
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = fillColor
                style = Paint.Style.FILL
                xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            }
            val canvas = Canvas(bitmap)
            val iterator = RegionIterator(region)
            val rect = Rect()
            while (iterator.next(rect)) {
                if (!rect.isEmpty) canvas.drawRect(rect, paint)
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    /**
     * Inpaint the masked region using Inpainter's diffusion-based algorithm.
     *
     * FIX: The old iterative Jacobi approach only propagated color 1 pixel per
     * iteration, so with 12 iterations only the outer ~12 px ring was filled —
     * the interior remained unchanged (looked like "only the outline changed").
     *
     * Now delegates to Inpainter.inpaint() which uses BFS-ordered exemplar fill,
     * correctly filling the ENTIRE masked area regardless of its size.
     */
    fun inpaintTelea(bitmap: Bitmap, region: Region, iterations: Int = 12): Bitmap {
        // Delegate to the proper diffusion inpainter that fills the whole region.
        // The `iterations` parameter is kept for API compatibility but not used.
        return try {
            Inpainter.inpaint(bitmap, region)
        } catch (oom: OutOfMemoryError) {
            oom.printStackTrace()
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        } catch (e: Exception) {
            e.printStackTrace()
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
    }

    private fun selectInternal(
        bitmap: Bitmap, startX: Int, startY: Int, tolerance: Int
    ): Region {
        if (startX < 0 || startY < 0 || startX >= bitmap.width || startY >= bitmap.height)
            return Region()

        val totalPixels = bitmap.width.toLong() * bitmap.height.toLong()
        if (totalPixels > 4_000_000L) {
            return selectInternalCropped(bitmap, startX, startY, tolerance)
        }

        fun runPass(work: Bitmap, sx: Int, sy: Int, tol: Int, useEdges: Boolean): Pair<Region, Float> {
            val W = work.width
            val H = work.height
            val pixels = IntArray(W * H)
            work.getPixels(pixels, 0, W, 0, 0, W, H)
            val targetColor = pixels[sy * W + sx]
            val balloonMode = isBrightRegion(targetColor)
            val effectiveTol = if (balloonMode) minOf(maxOf(tol, 34), 58).toFloat() else tol.toFloat()
            val edgeMap: IntArray? = if (useEdges && EDGE_STOP_THRESHOLD > 0) computeSobel(pixels, W, H) else null
            val mask = ByteArray(W * H)
            val stack = ArrayDeque<Int>()
            stack.addLast((sy shl 16) or sx)

            while (stack.isNotEmpty()) {
                val seed = stack.removeLast()
                var x = seed and 0xFFFF
                val y = (seed ushr 16) and 0xFFFF
                if (y < 0 || y >= H) continue
                if (mask[y * W + x] == SELECTED) continue
                if (!canSelect(pixels, mask, edgeMap, W, x, y, targetColor, effectiveTol, balloonMode)) continue

                while (x > 0 && canSelect(pixels, mask, edgeMap, W, x - 1, y, targetColor, effectiveTol, balloonMode)) x--

                var spanAbove = false
                var spanBelow = false
                while (x < W && canSelect(pixels, mask, edgeMap, W, x, y, targetColor, effectiveTol, balloonMode)) {
                    mask[y * W + x] = SELECTED

                    if (y > 0) {
                        val canUp = canSelect(pixels, mask, edgeMap, W, x, y - 1, targetColor, effectiveTol, balloonMode)
                        if (!spanAbove && canUp) {
                            stack.addLast(((y - 1) shl 16) or x)
                            spanAbove = true
                        } else if (spanAbove && !canUp) {
                            spanAbove = false
                        }
                    }

                    if (y < H - 1) {
                        val canDn = canSelect(pixels, mask, edgeMap, W, x, y + 1, targetColor, effectiveTol, balloonMode)
                        if (!spanBelow && canDn) {
                            stack.addLast(((y + 1) shl 16) or x)
                            spanBelow = true
                        } else if (spanBelow && !canDn) {
                            spanBelow = false
                        }
                    }
                    x++
                }
            }

            if (balloonMode) absorbOutline(mask, pixels, W, H, OUTLINE_MAX_PASSES)
            val finalMask = if (balloonMode) mask else dilate1px(mask, W, H)
            val region = buildRegion(finalMask, W, H)
            return region to regionArea(region)
        }

        val scaledPixels = bitmap.width.toLong() * bitmap.height.toLong()
        val scaled = scaledPixels > MAX_SAFE_PIXELS
        val work = if (scaled) {
            val sw = (bitmap.width * 0.75f).toInt().coerceAtLeast(1)
            val sh = (bitmap.height * 0.75f).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, sw, sh, true)
        } else bitmap
        val scale = if (scaled) bitmap.width.toFloat() / work.width.toFloat() else 1f
        val wx = (startX / scale).toInt().coerceIn(0, work.width - 1)
        val wy = (startY / scale).toInt().coerceIn(0, work.height - 1)

        val primary = runPass(work, wx, wy, tolerance, useEdges = true)
        var region = primary.first
        if (region.isEmpty) {
            val fallback = runPass(work, wx, wy, (tolerance + 10).coerceAtMost(70), useEdges = false)
            if (fallback.second > primary.second) region = fallback.first
        }

        if (work !== bitmap) work.recycle()
        return if (scale != 1f) scaleRegionUp(region, bitmap.width, bitmap.height, scale) else region
    }


    private fun selectInternalCropped(
        bitmap: Bitmap, startX: Int, startY: Int, tolerance: Int
    ): Region {
        val W = bitmap.width
        val H = bitmap.height
        val cropSize = 1800
        val cW = minOf(W, cropSize)
        val cH = minOf(H, cropSize)
        val cropLeft = (startX - cW / 2).coerceIn(0, (W - cW).coerceAtLeast(0))
        val cropTop  = (startY - cH / 2).coerceIn(0, (H - cH).coerceAtLeast(0))
        val crop = try {
            Bitmap.createBitmap(bitmap, cropLeft, cropTop, cW, cH)
        } catch (e: Throwable) {
            // Do NOT recurse back into selectInternal() with the same oversized
            // bitmap — that caused an infinite loop / StackOverflow on huge pages.
            e.printStackTrace()
            return Region()
        }
        return try {
            // The crop is guaranteed ≤ 1800×1800 (< 4 MP) so this hits the direct
            // path, never the cropped branch again — no recursion risk.
            val region = selectInternal(crop, startX - cropLeft, startY - cropTop, tolerance)
            val shifted = Region()
            val it = RegionIterator(region)
            val r = Rect()
            while (it.next(r)) {
                shifted.union(Rect(r.left + cropLeft, r.top + cropTop, r.right + cropLeft, r.bottom + cropTop))
            }
            shifted
        } finally {
            if (!crop.isRecycled) crop.recycle()
        }
    }

    private fun canSelect(
        pixels: IntArray, mask: ByteArray, edgeMap: IntArray?,
        W: Int, x: Int, y: Int,
        targetColor: Int, tolerance: Float, balloonMode: Boolean
    ): Boolean {
        val idx = y * W + x
        if (mask[idx] == SELECTED) return false
        val c = pixels[idx]
        if (edgeMap != null && edgeMap[idx] > EDGE_STOP_THRESHOLD) return false
        if (balloonMode && luminance(c) < 80) return false
        return colorDist(c, targetColor) <= tolerance
    }

    private fun computeSobel(pixels: IntArray, W: Int, H: Int): IntArray {
        val edge = IntArray(W * H)
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            val tl = luminance(pixels[(y-1)*W+(x-1)]); val tc = luminance(pixels[(y-1)*W+x]); val tr = luminance(pixels[(y-1)*W+(x+1)])
            val ml = luminance(pixels[y*W+(x-1)]);                                              val mr = luminance(pixels[y*W+(x+1)])
            val bl = luminance(pixels[(y+1)*W+(x-1)]); val bc = luminance(pixels[(y+1)*W+x]); val br = luminance(pixels[(y+1)*W+(x+1)])
            val gx = -tl - 2*ml - bl + tr + 2*mr + br
            val gy = -tl - 2*tc - tr + bl + 2*bc + br
            edge[y * W + x] = sqrt((gx*gx + gy*gy).toDouble()).toInt().coerceAtMost(255)
        }
        return edge
    }

    private fun absorbOutline(mask: ByteArray, pixels: IntArray, W: Int, H: Int, passes: Int) {
        repeat(passes) {
            val add = ByteArray(W * H)
            for (y in 1 until H - 1) for (x in 1 until W - 1) {
                if (mask[y * W + x] != SELECTED) continue
                for (ni in intArrayOf((y-1)*W+x, (y+1)*W+x, y*W+(x-1), y*W+(x+1))) {
                    if (mask[ni] == SELECTED) continue
                    if (luminance(pixels[ni]) < OUTLINE_LUM_THRESHOLD) add[ni] = 1
                }
            }
            for (i in mask.indices) if (add[i] == 1.toByte()) mask[i] = SELECTED
        }
    }

    private fun dilate1px(src: ByteArray, W: Int, H: Int): ByteArray {
        val out = src.copyOf()
        for (y in 1 until H - 1) for (x in 1 until W - 1) {
            if (src[y * W + x] != SELECTED) continue
            for (dy in -1..1) for (dx in -1..1) out[(y+dy)*W+(x+dx)] = SELECTED
        }
        return out
    }

    private fun buildRegion(mask: ByteArray, W: Int, H: Int): Region {
        val region = Region()
        for (y in 0 until H) {
            var xStart = -1
            for (x in 0 until W) {
                val sel = mask[y * W + x] == SELECTED
                if (sel && xStart == -1) xStart = x
                if (!sel && xStart != -1) { region.union(Rect(xStart, y, x, y+1)); xStart = -1 }
            }
            if (xStart != -1) region.union(Rect(xStart, y, W, y+1))
        }
        return region
    }

    private fun regionArea(region: Region): Float {
        val iterator = RegionIterator(region)
        val rect = Rect()
        var area = 0f
        while (iterator.next(rect)) {
            area += max(0, rect.width()) * max(0, rect.height()).toFloat()
        }
        return area
    }

    private fun scaleRegionUp(region: Region, origW: Int, origH: Int, scale: Float): Region {
        val inv = 1f / scale
        val out = Region(); val rect = Rect(); val ri = RegionIterator(region)
        while (ri.next(rect)) {
            val r = Rect(
                (rect.left   * inv).toInt(),
                (rect.top    * inv).toInt(),
                (rect.right  * inv).toInt().coerceAtMost(origW),
                (rect.bottom * inv).toInt().coerceAtMost(origH)
            )
            if (!r.isEmpty) out.union(r)
        }
        return out
    }

    private fun isBrightRegion(color: Int) = luminance(color) > 200

    private fun luminance(c: Int): Int {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt()
    }

    private fun colorDist(c1: Int, c2: Int): Float {
        val dr = (Color.red(c1)   - Color.red(c2)).toFloat()
        val dg = (Color.green(c1) - Color.green(c2)).toFloat()
        val db = (Color.blue(c1)  - Color.blue(c2)).toFloat()
        if (maxOf(abs(dr), abs(dg), abs(db)) > 200f) return 300f
        return sqrt(0.299f*dr*dr + 0.587f*dg*dg + 0.114f*db*db)
    }
}
