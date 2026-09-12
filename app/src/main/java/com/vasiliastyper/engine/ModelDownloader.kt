package com.vasiliastyper.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the Apache-2.0 LaMa manga ONNX model from Hugging Face (bundled via CI). */
object ModelDownloader {
    private const val TAG = "ModelDownloader"
    const val MODEL_KEY = "lama_manga"
    const val MODEL_VERSION = 1
    const val LAMA_FILENAME = "lama-manga-dynamic.onnx"
    // LaMa manga (ogkalu/lama-manga-onnx-dynamic, Apache-2.0), ONNX dinamis.
    // Kontrak: image [1,3,H,W] RGB /255, mask [1,1,H,W] 1=erase,
    // output inpainted [1,3,H,W] RGB [0,1] (otomatis dinormalisasi bila [0,255]).
    const val LAMA_URL =
        "https://huggingface.co/ogkalu/lama-manga-onnx-dynamic/resolve/main/lama-manga-dynamic.onnx"

    // Legacy original LaMa — tetap didukung baca agar file lama tidak crash,
    // tapi unduhan/bundle baru selalu memakai model manga di atas.
    @Deprecated("Gunakan LAMA_FILENAME", ReplaceWith("LAMA_FILENAME"))
    const val LAMA_MANGA_FILENAME = "lama-manga-dynamic.onnx"
    private const val LEGACY_MODEL_KEY = "lama"
    private const val LEGACY_FILENAME = "lama-fp32.onnx"
    private const val LEGACY_URL =
        "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"

    // Model manga ~30-60MB single-file. Tolak HTML/error-page (<10MB).
    const val MIN_MODEL_BYTES = 10_000_000L

    data class ModelInfo(
        val filename: String,
        val url: String,
        val displayName: String,
        val sizeDesc: String
    )

    private val models = listOf(
        ModelInfo(
            filename = LAMA_FILENAME,
            url = LAMA_URL,
            displayName = "LaMa Manga ONNX",
            sizeDesc = "~40 MiB"
        )
    )

    data class DownloadStatus(val lamaMangaReady: Boolean) {
        val allReady get() = lamaMangaReady
        val anyReady get() = lamaMangaReady
    }

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models/$MODEL_KEY/v$MODEL_VERSION").also { it.mkdirs() }

    fun lamaMangaFile(context: Context): File = File(modelsDir(context), LAMA_FILENAME)

    fun lamaFile(context: Context): File = lamaMangaFile(context)

    fun isValidLamaMangaFile(file: File): Boolean =
        file.isFile && file.length() >= MIN_MODEL_BYTES

    fun isValidLamaFile(file: File): Boolean = isValidLamaMangaFile(file)

    fun isLamaMangaReady(context: Context): Boolean {
        if (isValidLamaMangaFile(lamaMangaFile(context))) return true
        // Legacy file lama original tetap dianggap ready agar tidak crash pasca-migrasi.
        try {
            val legacy = File(context.filesDir, "models/$LEGACY_MODEL_KEY/v$MODEL_VERSION/$LEGACY_FILENAME")
            if (isValidLamaMangaFile(legacy)) return true
        } catch (_: Exception) { }
        return assetCandidates().any { assetPath ->
            try {
                context.assets.openFd(assetPath).use { it.length >= MIN_MODEL_BYTES }
            } catch (_: Exception) {
                false
            }
        }
    }

    fun status(context: Context) = DownloadStatus(isLamaMangaReady(context))

    fun downloadAll(
        context: Context,
        onProgress: ((model: String, downloaded: Long, total: Long) -> Unit)? = null
    ): DownloadStatus {
        for (model in models) {
            val destination = File(modelsDir(context), model.filename)
            if (isValidLamaMangaFile(destination)) continue
            downloadFile(model.url, destination) { downloaded, total ->
                onProgress?.invoke(model.displayName, downloaded, total)
            }
        }
        LamaMangaModelManager.release()
        return status(context)
    }

    fun downloadLamaManga(
        context: Context,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ) {
        val destination = lamaMangaFile(context)
        if (!isValidLamaMangaFile(destination)) {
            downloadFile(LAMA_URL, destination) { downloaded, total ->
                onProgress?.invoke(downloaded, total)
            }
            require(isValidLamaMangaFile(destination)) {
                "Unduhan LaMa tidak lengkap (${destination.length()} bytes)"
            }
            LamaMangaModelManager.release()
        }
    }

    fun downloadLama(
        context: Context,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null
    ) = downloadLamaManga(context, onProgress)

    private fun assetCandidates() = listOf(
        "models/$MODEL_KEY/v$MODEL_VERSION/$LAMA_FILENAME",
        "models/$MODEL_KEY/$LAMA_FILENAME",
        LAMA_FILENAME,
        // Legacy original paths (baca saja, bundle baru memakai path lama_manga/* di atas).
        "models/$LEGACY_MODEL_KEY/v$MODEL_VERSION/$LEGACY_FILENAME",
        "models/$LEGACY_MODEL_KEY/$LEGACY_FILENAME",
        LEGACY_FILENAME
    )

    private fun downloadFile(
        urlString: String,
        destination: File,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.tmp")
        val existingBytes = temporary.takeIf { it.exists() }?.length() ?: 0L
        var connection = openConnection(urlString, existingBytes)

        try {
            var redirects = 0
            while (connection.responseCode in 300..399 && redirects < 8) {
                val location = connection.getHeaderField("Location")
                    ?: error("Redirect tanpa Location")
                // Tolak downgrade ke http polos agar model tidak disadap/diganti.
                require(!location.startsWith("http://")) {
                    "Redirect tidak aman (http) saat mengunduh model"
                }
                connection.disconnect()
                connection = openConnection(location, existingBytes)
                redirects++
            }
            require(connection.responseCode == HttpURLConnection.HTTP_OK ||
                connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                "HTTP ${connection.responseCode} saat mengunduh model"
            }

            val resuming = connection.responseCode == HttpURLConnection.HTTP_PARTIAL
            val contentLength = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            val total = if (resuming && contentLength > 0) existingBytes + contentLength else contentLength
            val output = java.io.FileOutputStream(temporary, resuming)

            connection.inputStream.use { input ->
                output.use { stream ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = if (resuming) existingBytes else 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        stream.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    stream.flush()
                }
            }
            require(temporary.length() >= MIN_MODEL_BYTES) {
                "File model terlalu kecil: ${temporary.length()} bytes"
            }
            if (destination.exists()) require(destination.delete()) {
                "Tidak dapat mengganti ${destination.name}"
            }
            require(temporary.renameTo(destination)) {
                "Tidak dapat menyelesaikan ${destination.name}"
            }
            Log.i(TAG, "${destination.name} ready (${destination.length()} bytes)")
        } catch (error: Throwable) {
            Log.e(TAG, "Model download failed: ${error.message}", error)
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(urlString: String, resumeFrom: Long): HttpURLConnection =
        (URL(urlString).openConnection() as HttpURLConnection).apply {
            // Model hanya dari https (Hugging Face / Drive) — cegah MITM.
            require(urlString.startsWith("https://")) {
                "URL model harus https: $urlString"
            }
            instanceFollowRedirects = false
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("User-Agent", "VasiliasTyper/1.0 Android")
            if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            connect()
        }
}
