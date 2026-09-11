package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Bounding-box mask + solid fill ported from the Telegram bot cleaner.
 *
 * The bot builds one binary mask from detector boxes, adds four pixels of
 * padding, dilates it with an elliptical radius of three pixels, then writes
 * white to every enabled mask pixel. This implementation keeps that behaviour
 * deterministic and local to Fill White/Black; no inpainting backend uses it.
 */
object BoxMaskWhiteFiller {
    private const val BOX_PADDING_PX = 4
    private const val MASK_DILATE_PX = 3
    private const val MAX_BATCH_PIXELS = 3_000_000L

    data class Result(
        val requestedRegions: Int,
        val validRegions: Int,
        val changedPixels: Int
    ) {
        val success: Boolean get() = validRegions > 0 && changedPixels > 0
    }

    fun fill(
        targetBitmap: Bitmap,
        regions: List<RectF>,
        fillColor: Int = Color.WHITE,
        paddingPx: Int = BOX_PADDING_PX,
        dilationPx: Int = MASK_DILATE_PX
    ): Result {
        if (!targetBitmap.isMutable || targetBitmap.isRecycled || regions.isEmpty()) {
            return Result(regions.size, 0, 0)
        }

        val width = targetBitmap.width
        val height = targetBitmap.height
        if (width <= 0 || height <= 0) return Result(regions.size, 0, 0)

        if (regions.size > 1 && estimatedUnionPixels(regions, width, height) > MAX_BATCH_PIXELS) {
            var valid = 0
            var changed = 0
            for (batch in splitIntoVerticalBatches(regions, width, height)) {
                val result = fill(targetBitmap, batch, fillColor, paddingPx, dilationPx)
                valid += result.validRegions
                changed += result.changedPixels
            }
            return Result(regions.size, valid, changed)
        }

        val clampedPadding = paddingPx.coerceIn(0, 64)
        val clampedDilation = dilationPx.coerceIn(0, 32)
        val expanded = regions.mapNotNull { rect ->
            val left = floor(rect.left).toInt().minus(clampedPadding).coerceIn(0, width)
            val top = floor(rect.top).toInt().minus(clampedPadding).coerceIn(0, height)
            val right = ceil(rect.right).toInt().plus(clampedPadding).coerceIn(0, width)
            val bottom = ceil(rect.bottom).toInt().plus(clampedPadding).coerceIn(0, height)
            if (right <= left || bottom <= top) null else IntBounds(left, top, right, bottom)
        }
        if (expanded.isEmpty()) return Result(regions.size, 0, 0)

        val unionLeft = (expanded.minOf { it.left } - clampedDilation).coerceAtLeast(0)
        val unionTop = (expanded.minOf { it.top } - clampedDilation).coerceAtLeast(0)
        val unionRight = (expanded.maxOf { it.right } + clampedDilation).coerceAtMost(width)
        val unionBottom = (expanded.maxOf { it.bottom } + clampedDilation).coerceAtMost(height)
        val cropWidth = unionRight - unionLeft
        val cropHeight = unionBottom - unionTop
        if (cropWidth <= 0 || cropHeight <= 0) return Result(regions.size, 0, 0)

        // Raster langsung dari bounding box yang sudah dipadding. Versi lama
        // memindai seluruh union mask lalu mengunjungi semua offset kernel untuk
        // setiap piksel aktif (O(area × kernel)); pada halaman webtoon panjang ini
        // menjadi penyebab utama Fill White terasa sangat lama.
        val finalMask = BooleanArray(cropWidth * cropHeight)
        expanded.forEach { rect ->
            rasterizeDilatedBox(
                mask = finalMask,
                maskWidth = cropWidth,
                maskHeight = cropHeight,
                box = IntBounds(
                    rect.left - unionLeft,
                    rect.top - unionTop,
                    rect.right - unionLeft,
                    rect.bottom - unionTop
                ),
                radius = clampedDilation
            )
        }

        val pixels = IntArray(cropWidth * cropHeight)
        targetBitmap.getPixels(pixels, 0, cropWidth, unionLeft, unionTop, cropWidth, cropHeight)
        var changed = 0
        for (i in pixels.indices) {
            if (finalMask[i] && pixels[i] != fillColor) {
                pixels[i] = fillColor
                changed++
            }
        }
        if (changed > 0) {
            targetBitmap.setPixels(pixels, 0, cropWidth, unionLeft, unionTop, cropWidth, cropHeight)
        }
        return Result(regions.size, expanded.size, changed)
    }

    private fun estimatedUnionPixels(regions: List<RectF>, width: Int, height: Int): Long {
        val left = floor(regions.minOf { it.left }).toInt().coerceIn(0, width)
        val top = floor(regions.minOf { it.top }).toInt().coerceIn(0, height)
        val right = ceil(regions.maxOf { it.right }).toInt().coerceIn(0, width)
        val bottom = ceil(regions.maxOf { it.bottom }).toInt().coerceIn(0, height)
        return (right - left).coerceAtLeast(0).toLong() * (bottom - top).coerceAtLeast(0).toLong()
    }

    private fun splitIntoVerticalBatches(
        regions: List<RectF>,
        width: Int,
        height: Int
    ): List<List<RectF>> {
        val sorted = regions.sortedBy { it.top }
        val batches = mutableListOf<MutableList<RectF>>()
        var current = mutableListOf<RectF>()
        for (region in sorted) {
            val candidate = current + region
            if (current.isNotEmpty() && estimatedUnionPixels(candidate, width, height) > MAX_BATCH_PIXELS) {
                batches += current
                current = mutableListOf()
            }
            current += region
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }

    /** Raster kotak + dilasi elips tanpa scan per-piksel kernel. */
    private fun rasterizeDilatedBox(
        mask: BooleanArray,
        maskWidth: Int,
        maskHeight: Int,
        box: IntBounds,
        radius: Int
    ) {
        val yStart = (box.top - radius).coerceAtLeast(0)
        val yEnd = (box.bottom + radius).coerceAtMost(maskHeight)
        val radiusSquared = radius * radius
        for (y in yStart until yEnd) {
            val distanceY = when {
                y < box.top -> box.top - y
                y >= box.bottom -> y - box.bottom + 1
                else -> 0
            }
            if (distanceY > radius) continue
            val extensionX = if (radius == 0) 0 else {
                sqrt((radiusSquared - distanceY * distanceY).coerceAtLeast(0).toDouble()).toInt()
            }
            val xStart = (box.left - extensionX).coerceAtLeast(0)
            val xEnd = (box.right + extensionX).coerceAtMost(maskWidth)
            if (xEnd > xStart) {
                mask.fill(true, y * maskWidth + xStart, y * maskWidth + xEnd)
            }
        }
    }

    private data class IntBounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )
}
