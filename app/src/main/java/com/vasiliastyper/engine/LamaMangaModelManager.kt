package com.vasiliastyper.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File

/**
 * Owns the ONNX Runtime session for ogkalu/lama-manga-onnx-dynamic.
 *
 * Resolution order:
 *  1. filesDir/models/lama_manga/v1/lama-manga-dynamic.onnx
 *  2. assets/models/lama_manga/v1/lama-manga-dynamic.onnx
 *  3. assets/models/lama_manga/lama-manga-dynamic.onnx
 *  4. assets/lama-manga-dynamic.onnx
 *
 * The model is opened by file path so ORT can map it without first copying the
 * 206 MB model into the managed heap. Bundled assets are copied once to cache.
 */
object LamaMangaModelManager {
    private const val TAG = "LamaMangaModelManager"
    private const val MODEL_ASSET = "lama-manga-dynamic.onnx"
    private const val MODEL_KEY = "lama_manga"
    private const val MODEL_VERSION = 1

    private val assetCandidates = listOf(
        "models/$MODEL_KEY/v$MODEL_VERSION/$MODEL_ASSET",
        "models/$MODEL_KEY/$MODEL_ASSET",
        MODEL_ASSET
    )

    @Volatile private var environment: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var initialized = false
    @Volatile private var initFailed = false

    fun isAvailable(context: Context): Boolean {
        if (initFailed) return false
        if (initialized) return session != null
        return initialize(context.applicationContext)
    }

    fun getSession(): OrtSession? = session
    fun getEnvironment(): OrtEnvironment? = environment

    @Synchronized
    fun release() {
        session?.close()
        session = null
        // OrtEnvironment.getEnvironment() is process-global. Closing it while
        // another detector owns a session can invalidate unrelated ONNX work.
        environment = null
        initialized = false
        initFailed = false
    }

    @Synchronized
    private fun initialize(context: Context): Boolean {
        if (initialized) return session != null
        return try {
            val env = OrtEnvironment.getEnvironment()
            environment = env
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(ProcessingConfig.NUM_THREADS)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                addConfigEntry("session.memory.enable_memory_arena_shrinkage", "cpu:0")
            }
            val loaded = loadDownloaded(context, env, options)
                ?: loadBundled(context, env, options)
            session = loaded
            initialized = true
            initFailed = loaded == null
            if (loaded == null) {
                Log.w(TAG, "$MODEL_ASSET is unavailable; local patch fallback remains active")
                false
            } else {
                Log.i(
                    TAG,
                    "LaMa Manga loaded; inputs=${loaded.inputNames}, outputs=${loaded.outputNames}"
                )
                true
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to initialize LaMa Manga: ${error.message}", error)
            session = null
            initialized = true
            initFailed = true
            false
        }
    }

    private fun loadDownloaded(
        context: Context,
        env: OrtEnvironment,
        options: OrtSession.SessionOptions
    ): OrtSession? {
        val model = ModelDownloader.lamaMangaFile(context)
        if (!ModelDownloader.isValidLamaMangaFile(model)) return null
        return runCatching { env.createSession(model.absolutePath, options) }
            .onFailure { Log.e(TAG, "Downloaded model load failed: ${it.message}") }
            .getOrNull()
    }

    private fun loadBundled(
        context: Context,
        env: OrtEnvironment,
        options: OrtSession.SessionOptions
    ): OrtSession? {
        for (assetPath in assetCandidates) {
            try {
                context.assets.openFd(assetPath).use { descriptor ->
                    if (descriptor.length <= 0L) return@use
                    val cached = File(
                        context.cacheDir,
                        "models/$MODEL_KEY/v$MODEL_VERSION/$MODEL_ASSET"
                    )
                    if (!cached.exists() || cached.length() != descriptor.length) {
                        cached.parentFile?.mkdirs()
                        context.assets.open(assetPath).use { input ->
                            cached.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                        }
                    }
                    if (cached.length() != descriptor.length) return@use
                    return env.createSession(cached.absolutePath, options)
                }
            } catch (_: java.io.FileNotFoundException) {
                // Continue with the next supported bundled path.
            } catch (error: Throwable) {
                Log.e(TAG, "Bundled model load failed at $assetPath: ${error.message}")
            }
        }
        return null
    }
}
