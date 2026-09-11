package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object GeminiMaskDetector {
    interface DetectCallback {
        fun onSuccess(regions: List<PaddleDbNetDetector.DetectedRegion>)
        fun onFailure(error: String)
    }

    private const val MODEL = "gemini-2.5-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"
    private const val MAX_SEND_SIDE = 1536

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun lastError(): String? = lastErrorMessage
    @Volatile private var lastErrorMessage: String? = null

    fun detect(context: Context, bitmap: Bitmap, lang: String, cb: DetectCallback) {
        val apiKey = GeminiSettings.getApiKey(context)
        if (apiKey.isNullOrBlank()) {
            lastErrorMessage = "No Gemini key"
            main.post { cb.onFailure("No Gemini key") }
            return
        }

        val upload = downscale(bitmap)
        val jpeg = upload.toJpegBytes()
        if (upload !== bitmap) upload.recycle()
        if (jpeg == null) {
            lastErrorMessage = "Failed to encode image"
            main.post { cb.onFailure("Failed to encode image") }
            return
        }

        Thread {
            try {
                val regions = executeDetection(apiKey, bitmap.width, bitmap.height, buildPrompt(lang), jpeg)
                lastErrorMessage = null
                main.post { cb.onSuccess(regions) }
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                lastErrorMessage = msg
                main.post { cb.onFailure(msg) }
            }
        }.start()
    }

    suspend fun detectSuspend(context: Context, bitmap: Bitmap, lang: String): List<PaddleDbNetDetector.DetectedRegion> =
        withContext(Dispatchers.IO) {
            val apiKey = GeminiSettings.getApiKey(context)
            if (apiKey.isNullOrBlank()) {
                lastErrorMessage = "No Gemini key"
                throw IllegalStateException("No Gemini key")
            }

            val upload = downscale(bitmap)
            val jpeg = upload.toJpegBytes()
            if (upload !== bitmap) upload.recycle()
            if (jpeg == null) {
                lastErrorMessage = "Failed to encode image"
                throw IllegalStateException("Failed to encode image")
            }

            executeDetection(apiKey, bitmap.width, bitmap.height, buildPrompt(lang), jpeg)
        }


    private fun executeDetection(
        apiKey: String,
        canvasW: Int,
        canvasH: Int,
        prompt: String,
        jpeg: ByteArray,
    ): List<PaddleDbNetDetector.DetectedRegion> {
        val request = Request.Builder()
            .url(ENDPOINT)
            .header("x-goog-api-key", apiKey)
            .post(buildBody(prompt, jpeg))
            .build()

        var attempt = 0
        var lastError: Throwable? = null
        while (attempt < 3) {
            var response: okhttp3.Response? = null
            try {
                response = client.newCall(request).execute()
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val msg = extractError(raw) ?: "Gemini HTTP ${response.code}"
                    val ex = IllegalStateException(msg)
                    lastErrorMessage = msg
                    if (shouldRetry(response.code) && attempt < 2) {
                        lastError = ex
                        response.close()
                        backoffDelay(attempt)
                        attempt++
                        continue
                    }
                    throw ex
                }
                val regions = parseRegions(extractGeminiText(raw), canvasW, canvasH)
                lastErrorMessage = null
                response.close()
                return regions
            } catch (t: Throwable) {
                response?.close()
                val msg = t.message ?: t.javaClass.simpleName
                lastErrorMessage = msg
                lastError = t
                if (!shouldRetry(t) || attempt >= 2) break
                backoffDelay(attempt)
                attempt++
            }
        }
        throw (lastError ?: IllegalStateException(lastErrorMessage ?: "Gemini error"))
    }

    private fun backoffDelay(attempt: Int) {
        val delayMs = when (attempt) {
            0 -> 400L
            1 -> 1200L
            else -> 2400L
        }
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun shouldRetry(code: Int): Boolean = code == 429 || code == 500 || code == 503 || code == 504

    private fun shouldRetry(t: Throwable): Boolean {
        val msg = (t.message ?: "").lowercase()
        return msg.contains("timeout") || msg.contains("timed out") || msg.contains("connection") || msg.contains("socket") || msg.contains("temporarily")
    }

    private fun buildBody(prompt: String, jpeg: ByteArray): okhttp3.RequestBody {
        val body = JsonObject().apply {
            add("contents", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("parts", JsonArray().apply {
                        add(JsonObject().apply { addProperty("text", prompt) })
                        add(JsonObject().apply {
                            add("inlineData", JsonObject().apply {
                                addProperty("mimeType", "image/jpeg")
                                addProperty("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
                            })
                        })
                    })
                })
            })
            add("generationConfig", generationConfig())
        }
        return body.toString().toRequestBody(jsonType)
    }

    private fun generationConfig(): JsonObject = JsonObject().apply {
        addProperty("temperature", 0.0)
        addProperty("topP", 0.9)
        addProperty("maxOutputTokens", 4096)
        addProperty("responseMimeType", "application/json")
        add("responseSchema", JsonObject().apply {
            addProperty("type", "OBJECT")
            add("properties", JsonObject().apply {
                add("regions", JsonObject().apply {
                    addProperty("type", "ARRAY")
                    add("items", JsonObject().apply {
                        addProperty("type", "OBJECT")
                        add("properties", JsonObject().apply {
                            for (name in listOf("x1", "y1", "x2", "y2")) {
                                add(name, JsonObject().apply { addProperty("type", "NUMBER") })
                            }
                            add("text", JsonObject().apply { addProperty("type", "STRING") })
                        })
                        add("required", JsonArray().apply {
                            add("x1"); add("y1"); add("x2"); add("y2"); add("text")
                        })
                    })
                })
            })
            add("required", JsonArray().apply { add("regions") })
        })
    }

    private fun buildPrompt(lang: String): String = buildString {
        append("You are a text region detector for manga/comic pages. ")
        append("Identify all visible text regions in the image. ")
        append("Return STRICT JSON only, no markdown, no explanation. ")
        append("Use this schema: {\"regions\":[{\"x1\":0.0,\"y1\":0.0,\"x2\":0.0,\"y2\":0.0,\"text\":\"exact visible text\"}]}. ")
        append("Coordinates may be normalized floats from 0 to 1, or 0 to 1000 if the model uses that convention. ")
        append("Transcribe each region exactly; do not translate or explain it. ")
        append("Merge characters into line-level boxes, not one box per character. ")
        append("Prefer tight bounding boxes around text, not speech bubble borders. ")
        append("Source language hint: ")
        append(langName(lang))
        append('.')
    }

    private fun downscale(src: Bitmap): Bitmap {
        val maxSide = max(src.width, src.height)
        if (maxSide <= MAX_SEND_SIDE) return src
        val scale = MAX_SEND_SIDE.toFloat() / maxSide.toFloat()
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun Bitmap.toJpegBytes(): ByteArray? = try {
        val bos = ByteArrayOutputStream()
        compress(Bitmap.CompressFormat.JPEG, 88, bos)
        bos.toByteArray()
    } catch (_: Exception) {
        null
    }

    private fun parseRegions(text: String, canvasW: Int, canvasH: Int): List<PaddleDbNetDetector.DetectedRegion> {
        val jsonText = extractJson(text)
        if (jsonText.isBlank()) return emptyList()

        return try {
            val root = JsonParser.parseString(jsonText)
            val regions = mutableListOf<PaddleDbNetDetector.DetectedRegion>()
            val arr = when {
                root.isJsonObject -> root.asJsonObject["regions"]?.asJsonArray
                root.isJsonArray -> root.asJsonArray
                else -> null
            } ?: return emptyList()

            for (el in arr) {
                val rect = readRect(el, canvasW, canvasH) ?: continue
                if (rect.width() < 2f || rect.height() < 2f) continue
                val value = runCatching { el.asJsonObject["text"]?.asString?.trim().orEmpty() }
                    .getOrDefault("")
                regions.add(PaddleDbNetDetector.DetectedRegion(rect, "text", value))
            }
            regions
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readRect(el: JsonElement, canvasW: Int, canvasH: Int): RectF? {
        val obj = el.asJsonObject
        val x1 = readFloat(obj, "x1", "left", "xMin", "xmin")
        val y1 = readFloat(obj, "y1", "top", "yMin", "ymin")
        val x2 = readFloat(obj, "x2", "right", "xMax", "xmax")
        val y2 = readFloat(obj, "y2", "bottom", "yMax", "ymax")
        if (x1 != null && y1 != null && x2 != null && y2 != null) {
            return normalizeRect(x1, y1, x2, y2, canvasW, canvasH)
        }

        val poly = obj["polygon"]?.asJsonArray ?: obj["points"]?.asJsonArray ?: return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (pt in poly) {
            val p = pt.asJsonObject
            val px = readFloat(p, "x") ?: continue
            val py = readFloat(p, "y") ?: continue
            minX = min(minX, px)
            minY = min(minY, py)
            maxX = max(maxX, px)
            maxY = max(maxY, py)
        }
        if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) return null
        return normalizeRect(minX, minY, maxX, maxY, canvasW, canvasH)
    }

    private fun normalizeRect(x1: Float, y1: Float, x2: Float, y2: Float, canvasW: Int, canvasH: Int): RectF {
        val leftRaw = min(x1, x2)
        val topRaw = min(y1, y2)
        val rightRaw = max(x1, x2)
        val bottomRaw = max(y1, y2)
        val maxCoord = max(max(abs(leftRaw), abs(topRaw)), max(abs(rightRaw), abs(bottomRaw)))

        return when {
            maxCoord <= 1.5f -> RectF(
                (leftRaw.coerceIn(0f, 1f) * canvasW).coerceIn(0f, canvasW.toFloat()),
                (topRaw.coerceIn(0f, 1f) * canvasH).coerceIn(0f, canvasH.toFloat()),
                (rightRaw.coerceIn(0f, 1f) * canvasW).coerceIn(0f, canvasW.toFloat()),
                (bottomRaw.coerceIn(0f, 1f) * canvasH).coerceIn(0f, canvasH.toFloat())
            )
            maxCoord <= 1000f -> RectF(
                (leftRaw.coerceIn(0f, 1000f) / 1000f * canvasW).coerceIn(0f, canvasW.toFloat()),
                (topRaw.coerceIn(0f, 1000f) / 1000f * canvasH).coerceIn(0f, canvasH.toFloat()),
                (rightRaw.coerceIn(0f, 1000f) / 1000f * canvasW).coerceIn(0f, canvasW.toFloat()),
                (bottomRaw.coerceIn(0f, 1000f) / 1000f * canvasH).coerceIn(0f, canvasH.toFloat())
            )
            else -> RectF(
                leftRaw.coerceIn(0f, canvasW.toFloat()),
                topRaw.coerceIn(0f, canvasH.toFloat()),
                rightRaw.coerceIn(0f, canvasW.toFloat()),
                bottomRaw.coerceIn(0f, canvasH.toFloat())
            )
        }
    }

    private fun readFloat(obj: JsonObject, vararg keys: String): Float? {
        for (key in keys) {
            val el = obj[key] ?: continue
            try {
                return el.asFloat
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun extractJson(raw: String): String {
        val cleaned = raw
            .replace(Regex("```json", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
        val objectStart = cleaned.indexOf('{')
        val arrayStart = cleaned.indexOf('[')
        val start = listOf(objectStart, arrayStart).filter { it >= 0 }.minOrNull() ?: return ""
        val closing = if (cleaned[start] == '{') '}' else ']'
        val end = cleaned.lastIndexOf(closing)
        if (end <= start) return ""
        return cleaned.substring(start, end + 1)
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

    private fun langName(code: String) = when (code) {
        MlKitOcrEngine.LANG_ZH -> "chinese"
        MlKitOcrEngine.LANG_KO -> "korean"
        MlKitOcrEngine.LANG_EN -> "english"
        else -> "auto"
    }
}
