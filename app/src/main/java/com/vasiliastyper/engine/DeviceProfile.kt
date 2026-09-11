package com.vasiliastyper.engine

import android.app.ActivityManager
import android.content.Context

/**
 * Runtime device profile helpers for memory-aware image processing.
 */
object DeviceProfile {
    private const val LOW_END_RAM_GB = 3f

    @Volatile private var cachedTotalMemBytes: Long = -1L
    @Volatile private var cachedIsLowEnd: Boolean? = null

    fun ramGB(context: Context): Float = totalMemBytes(context) / (1024f * 1024f * 1024f)

    fun isLowEnd(context: Context): Boolean {
        cachedIsLowEnd?.let { return it }
        val lowEnd = ramGB(context) < LOW_END_RAM_GB
        cachedIsLowEnd = lowEnd
        return lowEnd
    }

    // Legacy local-patch recommendations retained for non-ONNX fallback engines.
    // LaMa Manga selects a dynamic inference patch independently.
    fun recommendedInpaintTileSize(context: Context): Int = if (isLowEnd(context)) 128 else 256
    fun inpaintOverlap(context: Context): Int = if (isLowEnd(context)) 16 else 32
    fun maxInpaintThreads(context: Context): Int = if (isLowEnd(context)) 2 else 4
    fun preferredStripeHeight(context: Context): Int = if (isLowEnd(context)) 1200 else 2000
    fun shouldPreferRgb565(context: Context): Boolean = isLowEnd(context)

    fun clearCache() {
        cachedTotalMemBytes = -1L
        cachedIsLowEnd = null
    }

    private fun totalMemBytes(context: Context): Long {
        val cached = cachedTotalMemBytes
        if (cached > 0) return cached
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        cachedTotalMemBytes = mi.totalMem
        return mi.totalMem
    }
}
