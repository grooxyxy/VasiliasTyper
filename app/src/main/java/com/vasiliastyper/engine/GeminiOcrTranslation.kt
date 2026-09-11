package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * GeminiOcrTranslation — Gemini 3.1 Flash-Lite OCR and translation client.
 *
 * Flash-Lite is the stable Gemini 3.1 multimodal text-output model and is a good
 * fit for image text extraction and high-volume translation. Callers retain an
 * offline ML Kit/TranslationManager fallback for missing keys or API failures.
 */
object GeminiOcrTranslation {

    // Digunakan OcrActivity untuk menerima hasil (nama OcrCallback agar tidak konflik dengan okhttp3.Callback)
    interface OcrCallback {
        fun onSuccess(originalText: String, translatedText: String, detectedLang: String)
        fun onFailure(error: String)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())

    const val MODEL_NAME = "gemini-3.1-flash-lite"

    private const val API_BASE =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL_NAME:generateContent"

    interface TranslationCallback {
        fun onSuccess(translatedText: String)
        fun onFailure(error: String)
    }

    fun recognizeAndTranslate(
        bitmap: Bitmap,
        srcLang: String,
        tgtLang: String,
        apiKey: String,
        callback: OcrCallback
    ) {
        if (apiKey.isBlank()) {
            main.post { callback.onFailure("API key kosong") }
            return
        }

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val imageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val promptText = buildPrompt(srcLang, tgtLang)
        val escapedPrompt = escapeForJson(promptText)

        val jsonBody = "{" +
            "\"contents\":[{\"parts\":[" +
            "{\"inline_data\":{\"mime_type\":\"image/jpeg\",\"data\":\"" + imageBase64 + "\"}}," +
            "{\"text\":\"" + escapedPrompt + "\"}" +
            "]}]," +
            "\"generationConfig\":{\"temperature\":0.1,\"maxOutputTokens\":8192}" +
            "}"

        val request = Request.Builder()
            .url(API_BASE)
            .header("x-goog-api-key", apiKey)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        // Gunakan okhttp3.Callback (nama fully qualified) agar tidak konflik dengan OcrCallback
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                main.post { callback.onFailure(e.message ?: "Network error") }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                try {
                    val body = response.body?.string()
                    if (body == null) {
                        main.post { callback.onFailure("Empty response") }
                        return
                    }
                    val json = JsonParser.parseString(body).asJsonObject
                    if (json.has("error")) {
                        val msg = json.getAsJsonObject("error")
                            .get("message")?.asString ?: "Unknown error"
                        main.post { callback.onFailure("Gemini: " + msg) }
                        return
                    }
                    val text = json
                        .getAsJsonArray("candidates")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")?.get(0)?.asJsonObject
                        ?.get("text")?.asString
                    if (text == null) {
                        main.post { callback.onFailure("Respons tidak valid") }
                        return
                    }
                    val (original, translated) = parseResponse(text)
                    main.post { callback.onSuccess(original, translated, srcLang) }
                } catch (e: Exception) {
                    main.post { callback.onFailure(e.message ?: "Parse error") }
                }
            }
        })
    }

    fun translateText(
        text: String,
        srcLang: String,
        tgtLang: String,
        apiKey: String,
        callback: TranslationCallback
    ) {
        if (apiKey.isBlank()) {
            main.post { callback.onFailure("API key kosong") }
            return
        }
        if (text.isBlank()) {
            main.post { callback.onSuccess("") }
            return
        }

        val prompt = "Translate the following webtoon text from ${languageName(srcLang, true)} " +
            "to ${languageName(tgtLang, false)}. Preserve meaning, tone, names, sound effects, " +
            "punctuation, and line breaks. Return only the translated text, without labels or markdown.\n\n" +
            text
        val jsonBody = "{" +
            "\"contents\":[{\"parts\":[{\"text\":\"${escapeForJson(prompt)}\"}]}]," +
            "\"generationConfig\":{\"temperature\":0.1,\"maxOutputTokens\":8192}" +
            "}"
        val request = Request.Builder()
            .url(API_BASE)
            .header("x-goog-api-key", apiKey)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                main.post { callback.onFailure(e.message ?: "Network error") }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    try {
                        val body = response.body?.string().orEmpty()
                        val json = JsonParser.parseString(body).asJsonObject
                        if (!response.isSuccessful || json.has("error")) {
                            val message = json.getAsJsonObject("error")
                                ?.get("message")?.asString ?: "Gemini HTTP ${response.code}"
                            main.post { callback.onFailure(message) }
                            return
                        }
                        val translated = extractText(json).trim()
                        if (translated.isBlank()) {
                            main.post { callback.onFailure("Respons terjemahan kosong") }
                        } else {
                            main.post { callback.onSuccess(translated) }
                        }
                    } catch (e: Exception) {
                        main.post { callback.onFailure(e.message ?: "Parse error") }
                    }
                }
            }
        })
    }

    private fun buildPrompt(srcLang: String, tgtLang: String): String {
        val srcDesc = languageName(srcLang, true)
        val tgtDesc = languageName(tgtLang, false)
        return "You are an expert OCR and translation assistant for Korean-style vertical webtoon images. " +
            "Extract ALL text visible in this image exactly as written in " + srcDesc + ". " +
            "Then translate all extracted text to " + tgtDesc + ". " +
            "Preserve line breaks, reading order, punctuation, names, and onomatopoeia. " +
            "Do not describe the image or invent hidden text. " +
            "Respond ONLY in this exact format:\n" +
            "ORIGINAL:\n[extracted text here]\n" +
            "TRANSLATED:\n[translated text here]"
    }

    private fun languageName(code: String, allowAuto: Boolean): String = when (code.lowercase()) {
        "id" -> "Indonesian (Bahasa Indonesia)"
        "en" -> "English"
        "zh" -> "Chinese (Simplified or Traditional)"
        "ko" -> "Korean"
        "ja" -> "Japanese"
        "auto" -> if (allowAuto) "the automatically detected source language" else "English"
        else -> code
    }

    private fun extractText(json: com.google.gson.JsonObject): String {
        val candidates = json.getAsJsonArray("candidates") ?: return ""
        if (candidates.size() == 0) return ""
        val parts = candidates[0].asJsonObject
            .getAsJsonObject("content")
            ?.getAsJsonArray("parts") ?: return ""
        return buildString {
            for (part in parts) {
                val value = part.asJsonObject.get("text")?.asString.orEmpty()
                if (value.isNotBlank()) {
                    if (isNotEmpty()) append('\n')
                    append(value)
                }
            }
        }
    }

    private fun escapeForJson(text: String): String {
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun parseResponse(text: String): Pair<String, String> {
        val origMarker = "ORIGINAL:"
        val transMarker = "TRANSLATED:"
        val origIdx = text.indexOf(origMarker)
        val transIdx = text.indexOf(transMarker)
        return if (origIdx >= 0 && transIdx > origIdx) {
            val original = text.substring(origIdx + origMarker.length, transIdx).trim()
            val translated = text.substring(transIdx + transMarker.length).trim()
            Pair(original.ifBlank { text.trim() }, translated.ifBlank { text.trim() })
        } else {
            Pair(text.trim(), text.trim())
        }
    }
}
