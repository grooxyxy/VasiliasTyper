package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object GeminiOcrManager {
    interface Cb { fun onSuccess(text: String, lang: String); fun onFailure(message: String) }

    private const val GEMINI_MODEL = GeminiOcrTranslation.MODEL_NAME
    private const val GEMINI_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun recognize(context: Context, bitmap: Bitmap, srcLang: String, cb: Cb) {
        val apiKey = GeminiSettings.getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            main.post { cb.onFailure("No Gemini key") }
            return
        }

        val upload = bitmap.downscaleForGemini()
        val png = upload.toJpegBytes()
        if (upload !== bitmap) upload.recycle()
        if (png == null) {
            main.post { cb.onFailure("Failed to encode image") }
            return
        }

        val prompt = buildPrompt(srcLang)
        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", prompt) })
                        add(JsonObject().apply {
                            add("inlineData", JsonObject().apply {
                                addProperty("mimeType", "image/jpeg")
                                addProperty("data", android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP))
                            })
                        })
                    })
                })
            })
            add("generationConfig", JsonObject().apply {
                addProperty("temperature", 0.0)
                addProperty("topP", 0.9)
                addProperty("maxOutputTokens", 2048)
            })
        }.toString().toRequestBody(jsonType)

        val req = Request.Builder()
            .url(GEMINI_ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .post(body)
            .build()

        client.newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { cb.onFailure(e.message ?: "Network error") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    try {
                        val raw = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            main.post { cb.onFailure(extractError(raw) ?: "Gemini HTTP ${resp.code}") }
                            return
                        }
                        val text = normalize(extractGeminiText(raw))
                        if (text.isBlank()) {
                            main.post { cb.onFailure("Empty Gemini response") }
                            return
                        }
                        main.post { cb.onSuccess(text, guessLang(text, srcLang)) }
                    } catch (e: Exception) {
                        main.post { cb.onFailure(e.message ?: "Gemini parse error") }
                    }
                }
            }
        })
    }


    private fun Bitmap.downscaleForGemini(maxPixels: Long = 1_800_000L): Bitmap {
        val pixels = width.toLong() * height.toLong()
        if (pixels <= maxPixels) return this
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / pixels.toDouble()).coerceAtMost(1.0)
        val w = (width * scale).toInt().coerceAtLeast(1)
        val h = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, w, h, true)
    }

    private fun Bitmap.toJpegBytes(): ByteArray? = try {
        val out = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 84, out)
        out.toByteArray()
    } catch (_: Throwable) { null }

    private fun buildPrompt(srcLang: String): String = buildString {
        append("You are an OCR engine for manga/comic pages. ")
        append("Transcribe ONLY the visible text exactly as written. ")
        append("Do NOT translate, paraphrase, summarize, or explain. ")
        append("Preserve line breaks, punctuation, spacing, and casing as much as possible. ")
        append("Return plain text only, with no labels, no bullets, no markdown. ")
        append("Source language: ")
        append(langName(srcLang))
        append('.')
    }

    private fun Bitmap.toPngBytes(): ByteArray? = try {
        val bos = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.PNG, 100, bos)
        bos.toByteArray()
    } catch (_: Exception) {
        null
    }

    private fun extractGeminiText(raw: String): String {
        val json = JsonParser.parseString(raw).asJsonObject
        val candidates = json["candidates"]?.asJsonArray ?: return ""
        for (cand in candidates) {
            val content = cand.asJsonObject["content"]?.asJsonObject ?: continue
            val parts = content["parts"]?.asJsonArray ?: continue
            val sb = StringBuilder()
            for (part in parts) {
                val t = part.asJsonObject["text"]?.asString?.trim().orEmpty()
                if (t.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(t)
                }
            }
            val s = sb.toString().trim()
            if (s.isNotBlank()) return s
        }
        return json["text"]?.asString ?: ""
    }

    private fun extractError(raw: String): String? = try {
        val j = JsonParser.parseString(raw).asJsonObject
        j["error"]?.asJsonObject?.get("message")?.asString
    } catch (_: Exception) {
        null
    }

    private fun normalize(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("[\r\t]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun guessLang(text: String, fallback: String): String {
        val hasKo = text.any { it.code in 0xAC00..0xD7A3 }
        val hasZh = text.any { it.code in 0x4E00..0x9FFF }
        return when {
            hasKo -> MlKitOcrEngine.LANG_KO
            hasZh -> MlKitOcrEngine.LANG_ZH
            fallback == MlKitOcrEngine.LANG_EN -> MlKitOcrEngine.LANG_EN
            else -> fallback
        }
    }

    private fun langName(code: String) = when (code) {
        MlKitOcrEngine.LANG_ZH -> "chinese"
        MlKitOcrEngine.LANG_KO -> "korean"
        MlKitOcrEngine.LANG_EN -> "english"
        else -> "auto"
    }
}
