package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** User-owned Agnes AI configuration. No API key is bundled in the application. */
object AgnesAiSettings {
    private const val PREFS = "agnes_image_ai"
    private const val API_KEY = "api_key"
    private const val PROMPT = "prompt"

    const val DEFAULT_PROMPT =
        "Remove only the object or text indicated by the magenta selection in the second reference image. Reconstruct that area naturally from the surrounding pixels. Preserve the first reference image's composition, dimensions, colors, gradients, texture, comic line art, and speech-bubble shape. Do not add text, symbols, logos, or new objects. Return a clean seamless version of the first image without the magenta guide."

    data class Credentials(
        val apiKey: String,
        val prompt: String
    )

    fun load(context: Context): Credentials {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Credentials(
            apiKey = prefs.getString(API_KEY, "").orEmpty().trim(),
            prompt = prefs.getString(PROMPT, DEFAULT_PROMPT).orEmpty().trim()
                .ifBlank { DEFAULT_PROMPT }
        )
    }

    fun save(context: Context, apiKey: String, prompt: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(API_KEY, apiKey.trim())
            .putString(PROMPT, prompt.trim().ifBlank { DEFAULT_PROMPT })
            .apply()
    }

    fun isConfigured(context: Context): Boolean = load(context).apiKey.isNotBlank()
}

/**
 * Agnes Image 2.1 Flash image-to-image backend.
 *
 * Agnes does not expose a dedicated binary-mask parameter. The request therefore
 * supplies the clean source patch followed by a visual guide patch whose selected
 * pixels are magenta. The prompt identifies the second image as guidance. Only the
 * original Android [region] is composited back, so pixels outside the user's mask
 * can never be changed by the remote response.
 *
 * This method is synchronous and must be called from a background thread.
 */
object AgnesAiInpainter {
    private const val ENDPOINT = "https://apihub.agnes-ai.com/v1/images/generations"
    private const val MODEL = "agnes-image-2.1-flash"
    private const val CONTEXT_PAD = 96
    private const val TEXTURED_CONTEXT_STD_DEV = 44.0
    private const val MAX_CONTEXT_COLOR_DISTANCE = 138.0

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(360, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    fun inpaint(
        context: Context,
        bitmap: Bitmap,
        region: Region,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!bitmap.isMutable || bitmap.isRecycled || region.isEmpty) return false
        val credentials = AgnesAiSettings.load(context)
        if (credentials.apiKey.isBlank()) return false

        val bounds = region.bounds
        val patchBounds = Rect(
            (bounds.left - CONTEXT_PAD).coerceAtLeast(0),
            (bounds.top - CONTEXT_PAD).coerceAtLeast(0),
            (bounds.right + CONTEXT_PAD).coerceAtMost(bitmap.width),
            (bounds.bottom + CONTEXT_PAD).coerceAtMost(bitmap.height)
        )
        if (patchBounds.isEmpty) return false
        onProgress?.invoke(0.08f)

        val sourcePatch = Bitmap.createBitmap(
            bitmap,
            patchBounds.left,
            patchBounds.top,
            patchBounds.width(),
            patchBounds.height()
        )
        val sourceMask = buildPatchMask(region, patchBounds)
        val guidePatch = buildGuidePatch(sourcePatch, sourceMask)

        return try {
            val sourceDataUri = sourcePatch.toPngDataUri()
            val guideDataUri = guidePatch.toPngDataUri()
            onProgress?.invoke(0.20f)

            val extraBody = JsonObject().apply {
                add("image", JsonArray().apply {
                    add(sourceDataUri)
                    add(guideDataUri)
                })
                addProperty("response_format", "b64_json")
            }
            val payload = JsonObject().apply {
                addProperty("model", MODEL)
                addProperty("prompt", credentials.prompt)
                addProperty("size", "1K")
                addProperty("ratio", closestSupportedRatio(sourcePatch.width, sourcePatch.height))
                add("extra_body", extraBody)
            }
            val request = Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", "Bearer ${credentials.apiKey}")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBytes = response.body?.bytes()
                    ?: throw IllegalStateException("Agnes AI returned an empty response")
                if (!response.isSuccessful) {
                    val detail = responseBytes.toString(Charsets.UTF_8).take(500)
                    throw IllegalStateException("Agnes AI HTTP ${response.code}: $detail")
                }
                onProgress?.invoke(0.86f)
                val generated = decodeOutput(responseBytes, credentials.apiKey)
                    ?: throw IllegalStateException("Agnes AI returned no decodable image")
                try {
                    if (!isOutputCoherent(sourcePatch, sourceMask, generated)) {
                        onProgress?.invoke(0.92f)
                        return PatchMatchInpainter.inpaintInPlace(bitmap, region) { progress ->
                            onProgress?.invoke(0.92f + progress.coerceIn(0f, 1f) * 0.08f)
                        }
                    }

                    val canvas = Canvas(bitmap)
                    val save = canvas.save()
                    val clipPath = android.graphics.Path()
                    region.getBoundaryPath(clipPath)
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(
                        generated,
                        Rect(0, 0, generated.width, generated.height),
                        patchBounds,
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    )
                    canvas.restoreToCount(save)
                } finally {
                    if (!generated.isRecycled) generated.recycle()
                }
                onProgress?.invoke(1f)
                true
            }
        } finally {
            if (!sourcePatch.isRecycled) sourcePatch.recycle()
            if (!sourceMask.isRecycled) sourceMask.recycle()
            if (!guidePatch.isRecycled) guidePatch.recycle()
        }
    }

    private fun buildPatchMask(region: Region, patchBounds: Rect): Bitmap {
        val mask = Bitmap.createBitmap(
            patchBounds.width(),
            patchBounds.height(),
            Bitmap.Config.ARGB_8888
        )
        Canvas(mask).apply {
            drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
            val iterator = RegionIterator(region)
            val rect = Rect()
            while (iterator.next(rect)) {
                drawRect(
                    (rect.left - patchBounds.left).toFloat(),
                    (rect.top - patchBounds.top).toFloat(),
                    (rect.right - patchBounds.left).toFloat(),
                    (rect.bottom - patchBounds.top).toFloat(),
                    paint
                )
            }
        }
        return mask
    }

    private fun buildGuidePatch(source: Bitmap, mask: Bitmap): Bitmap {
        val guide = source.copy(Bitmap.Config.ARGB_8888, true)
        val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(210, 255, 0, 255) }
        val canvas = Canvas(guide)
        val iteratorRegion = Region()
        val pixels = IntArray(mask.width * mask.height)
        mask.getPixels(pixels, 0, mask.width, 0, 0, mask.width, mask.height)
        for (y in 0 until mask.height) {
            var runStart = -1
            for (x in 0..mask.width) {
                val selected = x < mask.width && Color.red(pixels[y * mask.width + x]) >= 128
                if (selected && runStart < 0) runStart = x
                if (!selected && runStart >= 0) {
                    iteratorRegion.op(Rect(runStart, y, x, y + 1), Region.Op.UNION)
                    runStart = -1
                }
            }
        }
        val iterator = RegionIterator(iteratorRegion)
        val rect = Rect()
        while (iterator.next(rect)) canvas.drawRect(rect, overlay)
        return guide
    }

    private fun closestSupportedRatio(width: Int, height: Int): String {
        val value = width.toDouble() / height.coerceAtLeast(1)
        val ratios = listOf(
            "1:1" to 1.0,
            "3:4" to 3.0 / 4.0,
            "4:3" to 4.0 / 3.0,
            "16:9" to 16.0 / 9.0,
            "9:16" to 9.0 / 16.0,
            "2:3" to 2.0 / 3.0,
            "3:2" to 3.0 / 2.0,
            "21:9" to 21.0 / 9.0
        )
        return ratios.minByOrNull { kotlin.math.abs(it.second - value) }?.first ?: "1:1"
    }

    private fun Bitmap.toPngDataUri(): String = ByteArrayOutputStream().use { stream ->
        if (!compress(Bitmap.CompressFormat.PNG, 100, stream)) {
            throw IllegalStateException("Unable to encode the Agnes AI input patch")
        }
        "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun decodeOutput(responseBytes: ByteArray, apiKey: String): Bitmap? {
        return runCatching {
            val root = JsonParser.parseString(responseBytes.toString(Charsets.UTF_8)).asJsonObject
            val item = root.getAsJsonArray("data")?.firstOrNull()?.asJsonObject
                ?: return@runCatching null
            item.get("b64_json")?.takeUnless { it.isJsonNull }?.asString?.let { encoded ->
                val decoded = Base64.decode(encoded.substringAfter("base64,"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(decoded, 0, decoded.size)?.let { return@runCatching it }
            }
            val url = item.get("url")?.takeUnless { it.isJsonNull }?.asString
                ?: return@runCatching null
            val download = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            client.newCall(download).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }

    private fun isOutputCoherent(source: Bitmap, mask: Bitmap, generated: Bitmap): Boolean {
        var ringR = 0.0
        var ringG = 0.0
        var ringB = 0.0
        var ringR2 = 0.0
        var ringG2 = 0.0
        var ringB2 = 0.0
        var ringCount = 0
        var outR = 0.0
        var outG = 0.0
        var outB = 0.0
        var outCount = 0
        val step = maxOf(1, min(source.width, source.height) / 160)

        fun isMasked(x: Int, y: Int): Boolean = Color.red(mask.getPixel(x, y)) >= 128

        for (y in 0 until source.height step step) {
            for (x in 0 until source.width step step) {
                if (isMasked(x, y)) {
                    val gx = (x.toLong() * generated.width / source.width).toInt()
                        .coerceIn(0, generated.width - 1)
                    val gy = (y.toLong() * generated.height / source.height).toInt()
                        .coerceIn(0, generated.height - 1)
                    val pixel = generated.getPixel(gx, gy)
                    outR += Color.red(pixel)
                    outG += Color.green(pixel)
                    outB += Color.blue(pixel)
                    outCount++
                } else {
                    var nearMask = false
                    val radius = maxOf(2, 6 / step)
                    loop@ for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val nx = (x + dx * step).coerceIn(0, mask.width - 1)
                            val ny = (y + dy * step).coerceIn(0, mask.height - 1)
                            if (isMasked(nx, ny)) {
                                nearMask = true
                                break@loop
                            }
                        }
                    }
                    if (nearMask) {
                        val pixel = source.getPixel(x, y)
                        val r = Color.red(pixel).toDouble()
                        val g = Color.green(pixel).toDouble()
                        val b = Color.blue(pixel).toDouble()
                        ringR += r
                        ringG += g
                        ringB += b
                        ringR2 += r * r
                        ringG2 += g * g
                        ringB2 += b * b
                        ringCount++
                    }
                }
            }
        }
        if (ringCount < 8 || outCount < 4) return true

        val meanR = ringR / ringCount
        val meanG = ringG / ringCount
        val meanB = ringB / ringCount
        val variance = (
            (ringR2 / ringCount - meanR * meanR).coerceAtLeast(0.0) +
                (ringG2 / ringCount - meanG * meanG).coerceAtLeast(0.0) +
                (ringB2 / ringCount - meanB * meanB).coerceAtLeast(0.0)
            ) / 3.0
        if (kotlin.math.sqrt(variance) >= TEXTURED_CONTEXT_STD_DEV) return true

        val generatedR = outR / outCount
        val generatedG = outG / outCount
        val generatedB = outB / outCount
        val distance = kotlin.math.sqrt(
            (generatedR - meanR) * (generatedR - meanR) +
                (generatedG - meanG) * (generatedG - meanG) +
                (generatedB - meanB) * (generatedB - meanB)
        )
        return distance <= MAX_CONTEXT_COLOR_DISTANCE
    }
}
