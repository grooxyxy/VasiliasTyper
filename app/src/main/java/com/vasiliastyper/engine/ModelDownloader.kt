package com.vasiliastyper.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Downloads the Apache-2.0 LaMa Manga dynamic ONNX model from Hugging Face. */
object ModelDownloader {
    private const val TAG = "ModelDownloader"
    private const val MODEL_KEY = "lama_manga"
    private const val MODEL_VERSION = 1
    const val LAMA_MANGA_FILENAME = "lama-manga-dynamic.onnx"
    private const val LAMA_MANGA_URL =
        "https://huggingface.co/ogkalu/lama-manga-onnx-dynamic/resolve/main/lama-manga-dynamic.onnx"

    // Reject HTML/error payloads and interrupted legacy downloads. The upstream
    // file is 206,291,843 bytes; a lower bound tolerates a future valid revision.
    private const val MIN_MODEL_BYTES = 190_000_000L

    private val models = listOf(
        ModelInfo(
            filename = LAMA_MANGA_FILENAME,
            url = LAMA_MANGA_URL,
            displayName = "LaMa Manga ONNX Dynamic",
            sizeDesc = "~197 MiB"
        )
    )

    data class ModelInfo(
        val filename: String,
        val url: String,
        val displayName: String,
        val sizeDesc: String
    )

    data class DownloadStatus(val lamaMangaReady: Boolean) {
        val allReady get() = lamaMangaReady
        val anyReady get() = lamaMangaReady
    }

    fun modelsDir(context: Context): File =
        File(context.filesDir, "models/$MODEL_KEY/v$MODEL_VERSION").also { it.mkdirs() }

    fun lamaMangaFile(context: Context): File = File(modelsDir(context), LAMA_MANGA_FILENAME)

    fun isValidLamaMangaFile(file: File): Boolean =
        file.isFile && file.length() >= MIN_MODEL_BYTES

    fun isLamaMangaReady(context: Context): Boolean {
        if (isValidLamaMangaFile(lamaMangaFile(context))) return true
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
            downloadFile(LAMA_MANGA_URL, destination) { downloaded, total ->
                onProgress?.invoke(downloaded, total)
            }
            require(isValidLamaMangaFile(destination)) {
                "Unduhan LaMa Manga tidak lengkap (${destination.length()} bytes)"
            }
            LamaMangaModelManager.release()
        }
    }

    private fun assetCandidates() = listOf(
        "models/$MODEL_KEY/v$MODEL_VERSION/$LAMA_MANGA_FILENAME",
        "models/$MODEL_KEY/$LAMA_MANGA_FILENAME",
        LAMA_MANGA_FILENAME
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
