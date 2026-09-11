package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator

/**
 * TilingEngine — splits a large bitmap into overlapping tiles, processes each
 * via [tileFn], then writes only the CENTER of each processed tile back to
 * the bitmap.
 *
 * WHY CENTER-WRITE?
 *   Each pixel lives in exactly one tile's center zone, so there are no
 *   seams and no per-pixel blending accumulators (which would require
 *   float arrays the size of the full image — too large for 800 × 19 000).
 *
 * MEMORY BUDGET (800 × 19 000 example):
 *   Full-image accumulators: 4 × (800 × 19 000 × 4 B) ≈ 230 MB  ← avoided
 *   Per-tile IntArray:       512 × 512 × 4 B             ≈   1 MB  ← actual peak
 *
 * TILING PARAMETERS (defaults match LaMa expectations):
 *   tileSize = 512  — matches LaMa's native input resolution
 *   overlap  = 64   — border fed as context; never written back as output
 */
object TilingEngine {

    /**
     * Process every tile of [bitmap] that intersects [region].
     *
     * @param bitmap      mutable bitmap to update in-place
     * @param region      mask region (pixels to inpaint)
     * @param tileSize    tile side length in pixels (default 512)
     * @param overlap     overlap border fed to the model for context (default 64)
     * @param onProgress  callback with value 0 → 1 after each tile
     * @param tileFn      called with (pixels, mask, w, h); must modify [pixels]
     *                    in-place wherever [mask] is true
     */
    fun process(
        bitmap:     Bitmap,
        region:     Region,
        tileSize:   Int = 512,
        overlap:    Int = 64,
        onProgress: ((Float) -> Unit)? = null,
        tileFn:     (pixels: IntArray, mask: BooleanArray, w: Int, h: Int) -> Unit
    ) {
        val W = bitmap.width
        val H = bitmap.height
        val b = region.bounds

        // Step between tile origins (non-overlapping centre zones)
        val step = (tileSize - overlap * 2).coerceAtLeast(64)

        // Cover the region plus one full overlap border on every side
        val startX = (b.left  - overlap).coerceAtLeast(0)
        val startY = (b.top   - overlap).coerceAtLeast(0)
        val endX   = (b.right  + overlap).coerceAtMost(W)
        val endY   = (b.bottom + overlap).coerceAtMost(H)

        // Collect tile origins
        val origins = mutableListOf<Pair<Int, Int>>()
        var ty = startY
        while (ty < endY) {
            var tx = startX
            while (tx < endX) { origins.add(tx to ty); tx += step }
            ty += step
        }

        val total = origins.size.coerceAtLeast(1)

        origins.forEachIndexed { idx, (tileLeft, tileTop) ->
            val tileRight  = (tileLeft + tileSize).coerceAtMost(W)
            val tileBottom = (tileTop  + tileSize).coerceAtMost(H)
            val tw = tileRight  - tileLeft
            val th = tileBottom - tileTop
            if (tw <= 0 || th <= 0) { onProgress?.invoke((idx + 1f) / total); return@forEachIndexed }

            // Read tile pixels
            val tilePixels = IntArray(tw * th)
            bitmap.getPixels(tilePixels, 0, tw, tileLeft, tileTop, tw, th)

            // Build mask local to this tile
            val localRegion = shiftRegion(region, -tileLeft, -tileTop)
            val tileMask    = buildMask(localRegion, tw, th)

            if (tileMask.any { it }) {
                // Run the model / algorithm on the full tile (with overlap context)
                tileFn(tilePixels, tileMask, tw, th)

                // Determine the CENTER write-back zone
                // (skip the overlap border except at image edges)
                val writeX0 = if (tileLeft  == 0) 0  else overlap
                val writeY0 = if (tileTop   == 0) 0  else overlap
                val writeX1 = if (tileRight  == W) tw else (tw - overlap).coerceAtLeast(writeX0 + 1)
                val writeY1 = if (tileBottom == H) th else (th - overlap).coerceAtLeast(writeY0 + 1)

                val ww = writeX1 - writeX0
                val wh = writeY1 - writeY0
                val absLeft = tileLeft + writeX0
                val absTop  = tileTop  + writeY0

                // Read the current pixels in that write zone, overwrite masked ones
                val zonePixels = IntArray(ww * wh)
                bitmap.getPixels(zonePixels, 0, ww, absLeft, absTop, ww, wh)

                val writeRegion = shiftRegion(region, -absLeft, -absTop)
                val writeMask   = buildMask(writeRegion, ww, wh)

                for (wy in 0 until wh) {
                    for (wx in 0 until ww) {
                        val zoneIdx = wy * ww + wx
                        if (!writeMask[zoneIdx]) continue
                        val tileIdx = (writeY0 + wy) * tw + (writeX0 + wx)
                        zonePixels[zoneIdx] = tilePixels[tileIdx]
                    }
                }

                bitmap.setPixels(zonePixels, 0, ww, absLeft, absTop, ww, wh)
            }

            onProgress?.invoke((idx + 1f) / total)
            // Let ART manage short-lived tile arrays. Explicit System.gc() here
            // caused visible multi-second pauses on long pages.
        }
    }

    // ── Region helpers ───────────────────────────────────────────────────────

    internal fun shiftRegion(region: Region, dx: Int, dy: Int): Region {
        val out = Region()
        val ri  = RegionIterator(region)
        val r   = Rect()
        while (ri.next(r)) {
            val s = Rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
            if (!s.isEmpty) out.union(s)
        }
        return out
    }

    internal fun buildMask(region: Region, W: Int, H: Int): BooleanArray {
        val mask = BooleanArray(W * H)
        val ri   = RegionIterator(region)
        val r    = Rect()
        while (ri.next(r)) {
            for (y in r.top.coerceIn(0, H) until r.bottom.coerceIn(0, H))
                for (x in r.left.coerceIn(0, W) until r.right.coerceIn(0, W))
                    mask[y * W + x] = true
        }
        return mask
    }
}
