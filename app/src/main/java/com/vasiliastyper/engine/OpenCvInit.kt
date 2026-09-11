package com.vasiliastyper.engine

import org.opencv.android.OpenCVLoader

/**
 * OpenCvInit — Lazy singleton that initialises the OpenCV native library.
 *
 * Call [ensureInit] before any OpenCV operation. It is safe to call from
 * any thread; the first call loads the .so files bundled in the APK,
 * subsequent calls return immediately.
 *
 * No Application context is required — [OpenCVLoader.initLocal] loads the
 * native libs packaged in the APK without needing the OpenCV Manager app.
 */
object OpenCvInit {

    @Volatile
    var isReady: Boolean = false
        private set

    @Synchronized
    fun ensureInit(): Boolean {
        if (isReady) return true
        isReady = try {
            OpenCVLoader.initDebug()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
        return isReady
    }
}
