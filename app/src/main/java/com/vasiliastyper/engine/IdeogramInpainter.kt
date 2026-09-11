package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Prompt settings for the public Nexray Ideogram endpoint. */
object IdeogramSettings {
    private const val PREFS = "nexray_ideogram"
    private const val PROMPT = "prompt"

    const val DEFAULT_PROMPT =
        "clean seamless comic background texture without text, letters, logos, people, or objects"

    fun getPrompt(context: Context): String = context
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString(PROMPT, DEFAULT_PROMPT)
        .orEmpty()
        .trim()
        .ifBlank { DEFAULT_PROMPT }

    fun setPrompt(context: Context, prompt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(PROMPT, prompt.trim().ifBlank { DEFAULT_PROMPT })
            .apply()
    }
}

/**
 * Prompt-based fill using GET /ai/ideogram?prompt=....
 *
 * The endpoint does not accept a source image or binary mask. The generated image is
 * therefore center-cropped and composited strictly inside the selected Android Region;
 * pixels outside the user's selection are never changed.
 */
object IdeogramInpainter {
    private const val ENDPOINT_HOST = "api.nexray.eu.cc"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(210, TimeUnit.SECONDS)
        .build()

    fun inpaint(
        context: Context,
        bitmap: Bitmap,
        region: Region,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (bitmap.isRecycled || !bitmap.isMutable || region.isEmpty) return false
        val prompt = IdeogramSettings.getPrompt(context)
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(ENDPOINT_HOST)
            .addPathSegment("ai")
            .addPathSegment("ideogram")
            .addQueryParameter("prompt", prompt)
            .build()
        onProgress?.invoke(0.08f)

        val request = Request.Builder()
            .url(url)
            .header("Accept", "image/png,image/*,application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()
                ?: throw IllegalStateException("Ideogram mengembalikan respons kosong")
            if (!response.isSuccessful) {
                throw IllegalStateException("Ideogram HTTP ${response.code}: ${bytes.toString(Charsets.UTF_8).take(240)}")
            }
            onProgress?.invoke(0.82f)
            val generated = decodeImage(bytes, response.header("Content-Type"))
                ?: throw IllegalStateException("Gambar Ideogram tidak dapat dibaca")
            try {
                val destination = region.bounds
                if (destination.isEmpty) return false
                val source = centerCropRect(generated.width, generated.height, destination.width(), destination.height())
                val canvas = Canvas(bitmap)
                val save = canvas.save()
                val clipPath = android.graphics.Path()
                region.getBoundaryPath(clipPath)
                canvas.clipPath(clipPath)
                canvas.drawBitmap(
                    generated,
                    source,
                    destination,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
                canvas.restoreToCount(save)
            } finally {
                if (!generated.isRecycled) generated.recycle()
            }
            onProgress?.invoke(1f)
            return true
        }
    }

    private fun decodeImage(bytes: ByteArray, contentType: String?): Bitmap? {
        if (contentType?.startsWith("image/") == true) {
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        val body = bytes.toString(Charsets.UTF_8)
        val imageUrl = runCatching {
            findImageUrl(JsonParser.parseString(body))
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val request = Request.Builder().url(imageUrl).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val imageBytes = response.body?.bytes() ?: return@use null
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        }
    }

    private fun findImageUrl(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) {
            val value = element.asString
            return if (value.startsWith("http://") || value.startsWith("https://")) value else ""
        }
        if (element.isJsonArray) {
            for (item in element.asJsonArray) {
                val found = findImageUrl(item)
                if (found.isNotBlank()) return found
            }
            return ""
        }
        val obj = element.asJsonObject
        for (key in listOf("url", "image", "image_url", "result", "data")) {
            val found = findImageUrl(obj.get(key))
            if (found.isNotBlank()) return found
        }
        return ""
    }

    private fun centerCropRect(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Rect {
        val sourceRatio = sourceWidth.toDouble() / sourceHeight.coerceAtLeast(1)
        val targetRatio = targetWidth.toDouble() / targetHeight.coerceAtLeast(1)
        return if (sourceRatio > targetRatio) {
            val width = (sourceHeight * targetRatio).toInt().coerceIn(1, sourceWidth)
            val left = (sourceWidth - width) / 2
            Rect(left, 0, left + width, sourceHeight)
        } else {
            val height = (sourceWidth / targetRatio).toInt().coerceIn(1, sourceHeight)
            val top = (sourceHeight - height) / 2
            Rect(0, top, sourceWidth, top + height)
        }
    }
}
