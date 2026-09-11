package com.vasiliastyper.engine

import android.util.Log

class MemoryManager {
    private val runtime = Runtime.getRuntime()
    private val maxMemory = runtime.maxMemory()
    private val tag = "MemoryManager"

    fun checkAndFree(): Boolean {
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val usageRatio = usedMemory.toFloat() / maxMemory
        Log.d(tag, "Memory: ${(usageRatio * 100).toInt()}% (${usedMemory / 1024 / 1024}MB / ${maxMemory / 1024 / 1024}MB)")
        if (usageRatio > ProcessingConfig.MEMORY_THRESHOLD) {
            Log.w(tag, "Memory critical, forcing GC…")
            runtime.gc()
            Thread.sleep(100)
            return false
        }
        return true
    }

    fun forceGC() {
        runtime.gc()
        Thread.sleep(50)
    }

    fun availableMB(): Long =
        (runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()) / 1024 / 1024
}
