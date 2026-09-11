
package com.vasiliastyper.engine

import android.util.Log
import kotlin.math.max

object MaskProfiling {
    private const val TAG = "MaskProfile"

    fun memoryMb(): Long {
        val rt = Runtime.getRuntime()
        return max(0L, (rt.totalMemory() - rt.freeMemory()) / 1024 / 1024)
    }

    fun log(engine: String, size: String, vararg pairs: Pair<String, Long>, regions: Int? = null) {
        val body = buildString {
            append("engine=").append(engine)
            append(' ').append("size=").append(size)
            for ((k, v) in pairs) {
                append(' ').append(k).append('=').append(v).append("ms")
            }
            if (regions != null) append(' ').append("regions=").append(regions)
            append(' ').append("memoryMb=").append(memoryMb())
        }
        Log.i(TAG, body)
    }

    fun logError(stage: String, t: Throwable, extra: String = "") {
        Log.e(TAG, if (extra.isBlank()) stage else "$stage $extra", t)
    }
}
