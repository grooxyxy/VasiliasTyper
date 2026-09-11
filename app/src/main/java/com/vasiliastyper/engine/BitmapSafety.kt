package com.vasiliastyper.engine

import android.graphics.Bitmap
import kotlin.math.sqrt

/**
 * Shared bitmap sizing guardrails for heavy render paths.
 *
 * The goal is to prevent large temporary allocations (mesh/perspective/blur
 * preview buffers, history snapshots, etc.) from spiking RAM on low-end
 * devices. The helper preserves aspect ratio while keeping the pixel budget
 * under a configurable limit.
 */
object BitmapSafety {
    const val MAX_EDIT_PIXELS: Int = 4_000_000
    const val MAX_CANVAS_PIXELS: Int = 12_000_000

    /**
     * Pixel budget for a full editor canvas. A canvas is never the only bitmap
     * in memory: undo, layers, previews, and export all need temporary copies.
     * Keeping one canvas below roughly 1/6 of the heap prevents startup OOMs.
     */
    fun canvasPixelBudget(maxMemoryBytes: Long = Runtime.getRuntime().maxMemory()): Int {
        val heapBasedPixels = (maxMemoryBytes / 24L)
            .coerceAtLeast(2_000_000L)
            .coerceAtMost(MAX_CANVAS_PIXELS.toLong())
        return heapBasedPixels.toInt()
    }

    fun fitCanvasToDevice(width: Int, height: Int): Pair<Int, Int> =
        fitWithinMaxPixels(width, height, canvasPixelBudget())

    /**
     * Returns a width/height pair that fits within [maxPixels] while keeping
     * the original aspect ratio. The returned dimensions are always at least 1.
     */
    fun fitWithinMaxPixels(width: Int, height: Int, maxPixels: Int = MAX_EDIT_PIXELS): Pair<Int, Int> {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val pixels = w.toLong() * h.toLong()
        if (pixels <= maxPixels.toLong()) return w to h

        val scale = sqrt(maxPixels.toDouble() / pixels.toDouble()).toFloat().coerceIn(0.01f, 1f)
        val outW = (w * scale).toInt().coerceAtLeast(1)
        val outH = (h * scale).toInt().coerceAtLeast(1)
        return outW to outH
    }

    fun createSafeBitmap(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val (w, h) = fitWithinMaxPixels(width, height)
        return Bitmap.createBitmap(w, h, config)
    }
}
