package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

/**
 * HybridTextMaskFiller — Fast text-only pixel cleaner for large webtoon images.
 *
 * Design goals:
 *  • Support images up to 800 × 17 000 px without OOM on low-end devices.
 *  • Much faster than the per-pixel kernel scan in TextMaskEngine for the
 *    Mask panel "Fill White / Fill Black" action, which must *only* erase text
 *    glyphs (not background, balloon outlines, or shadows).
 *  • Falls back to a simple luminance-threshold scan when the detailed
 *    TextMaskEngine result is unavailable.
 *
 * Algorithm per region:
 *  1. Read a pixel buffer from [sourceBitmap] (immutable snapshot).
 *  2. Mark "dark" pixels (luminance < threshold) as text candidates.
 *  3. Dilate the candidate set by [bleedPx] to catch anti-aliased edges.
 *  4. Write the fill color to every candidate pixel in [targetBitmap].
 *
 * Tile processing: regions are split vertically into tiles of at most
 * [TILE_MAX_PIXELS] so that working buffers never exceed ~3 MB.
 */
object HybridTextMaskFiller {

    /** Maximum pixels per processing tile (approx. 3 MB of IntArray). */
    private const val TILE_MAX_PIXELS = 800_000

    /** Luminance threshold below which a pixel is considered dark text. */
    private const val DARK_THRESHOLD = 110

    data class Result(
        val processedRegions: Int,
        val filledRegions: Int,
        val totalPixelsFilled: Long
    )

    /**
     * Fill only text pixels in each of [regions] on [targetBitmap].
     *
     * @param sourceBitmap  Immutable read-only snapshot — never modified.
     * @param targetBitmap  Mutable bitmap that receives the fill.
     * @param regions       Detected text bounding boxes in canvas coordinates.
     * @param fillColor     Color to paint over glyph pixels (default WHITE).
     * @param bleedPx       Extra dilation radius to cover anti-aliased edges.
     * @return              Summary of what was processed and changed.
     */
    fun fillTextOnly(
        sourceBitmap: Bitmap,
        targetBitmap: Bitmap,
        regions: List<Rect>,
        fillColor: Int = Color.WHITE,
        bleedPx: Int = 1
    ): Result {
        if (regions.isEmpty() || sourceBitmap.isRecycled || targetBitmap.isRecycled) {
            return Result(0, 0, 0L)
        }
        if (!targetBitmap.isMutable) return Result(regions.size, 0, 0L)

        val bw = sourceBitmap.width
        val bh = sourceBitmap.height

        var totalFilled = 0L
        var filledCount = 0

        for (region in regions) {
            val left   = region.left.coerceIn(0, bw)
            val top    = region.top.coerceIn(0, bh)
            val right  = region.right.coerceIn(0, bw)
            val bottom = region.bottom.coerceIn(0, bh)
            val rw     = right - left
            val rh     = bottom - top
            if (rw <= 0 || rh <= 0) continue

            // Split tall regions into vertical tiles to stay within RAM budget
            val tileH  = if ((rw.toLong() * rh) > TILE_MAX_PIXELS) (TILE_MAX_PIXELS / rw).coerceAtLeast(1) else rh
            var regionFilled = 0L

            var tileTop = top
            while (tileTop < bottom) {
                val tileBottom = minOf(tileTop + tileH, bottom)
                val th = tileBottom - tileTop
                val pixCount = rw * th

                val src = IntArray(pixCount)
                val dst = IntArray(pixCount)
                sourceBitmap.getPixels(src, 0, rw, left, tileTop, rw, th)
                targetBitmap.getPixels(dst, 0, rw, left, tileTop, rw, th)

                val mask = buildFastTextMask(src, rw, th, bleedPx)

                var changed = false
                for (i in dst.indices) {
                    if (mask[i] && dst[i] != fillColor) {
                        dst[i] = fillColor
                        regionFilled++
                        changed = true
                    }
                }

                if (changed) {
                    targetBitmap.setPixels(dst, 0, rw, left, tileTop, rw, th)
                }
                tileTop = tileBottom
            }

            if (regionFilled > 0) {
                filledCount++
                totalFilled += regionFilled
            }
        }

        return Result(regions.size, filledCount, totalFilled)
    }

    /**
     * Build a binary text mask using luminance threshold + dilation.
     * Fast O(n) scan — no per-pixel kernel iteration.
     *
     * @param pixels  ARGB pixel buffer for the region tile.
     * @param width   Tile width.
     * @param height  Tile height.
     * @param bleed   Dilation radius (Manhattan distance).
     * @return        BooleanArray where true = "erase this pixel".
     */
    private fun buildFastTextMask(
        pixels: IntArray,
        width: Int,
        height: Int,
        bleed: Int
    ): BooleanArray {
        val raw = BooleanArray(pixels.size)

        // Step 1: mark dark / semi-transparent pixels as text candidates
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            if (a < 32) continue    // skip transparent
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b = p          and 0xFF
            val lum = ((r * 299 + g * 587 + b * 114) / 1000)
            if (lum < DARK_THRESHOLD) raw[i] = true
        }

        if (bleed <= 0) return raw

        // Step 2: row-sweep dilation — fast O(width × height × bleed) but with
        // a single contiguous scan, not a convolution kernel per pixel.
        val out = BooleanArray(pixels.size)

        // Horizontal pass
        val hTemp = BooleanArray(pixels.size)
        for (y in 0 until height) {
            val rowBase = y * width
            // Build prefix marks for this row
            for (x in 0 until width) {
                if (!raw[rowBase + x]) continue
                val xStart = maxOf(0, x - bleed)
                val xEnd   = minOf(width, x + bleed + 1)
                for (nx in xStart until xEnd) hTemp[rowBase + nx] = true
            }
        }

        // Vertical pass over the horizontal result
        for (x in 0 until width) {
            for (y in 0 until height) {
                if (!hTemp[y * width + x]) continue
                val yStart = maxOf(0, y - bleed)
                val yEnd   = minOf(height, y + bleed + 1)
                for (ny in yStart until yEnd) out[ny * width + x] = true
            }
        }

        return out
    }
}
