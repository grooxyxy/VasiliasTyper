package com.vasiliastyper.engine

import android.os.Handler
import android.os.Looper
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Text-only AI providers exposed by api.nexray.eu.cc. */
object NexrayAiClient {
    enum class Provider(val displayName: String, val path: String) {
        GPT_35_TURBO("GPT-3.5 Turbo", "gpt-3.5-turbo"),
        CLAUDE("Claude", "claude")
    }

    interface TranslationCallback {
        fun onSuccess(translatedText: String)
        fun onFailure(error: String)
    }

    private const val MAX_CHUNK_LENGTH = 1200
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    fun translate(
        text: String,
        sourceLanguage: String,
        targetLanguage: String,
        provider: Provider,
        callback: TranslationCallback
    ) {
        if (text.isBlank()) {
            main.post { callback.onSuccess("") }
            return
        }
        if (sourceLanguage == targetLanguage) {
            main.post { callback.onSuccess(text) }
            return
        }

        val chunks = splitText(text, MAX_CHUNK_LENGTH)
        val translated = ArrayList<String>(chunks.size)

        fun translateChunk(index: Int) {
            if (index >= chunks.size) {
                main.post { callback.onSuccess(translated.joinToString("\n").trim()) }
                return
            }
            val prompt = buildPrompt(chunks[index], sourceLanguage, targetLanguage)
            request(provider, prompt, object : TranslationCallback {
                override fun onSuccess(translatedText: String) {
                    translated += cleanAnswer(translatedText)
                    translateChunk(index + 1)
                }

                override fun onFailure(error: String) {
                    main.post { callback.onFailure(error) }
                }
            })
        }

        translateChunk(0)
    }

    private fun request(provider: Provider, prompt: String, callback: TranslationCallback) {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host("api.nexray.eu.cc")
            .addPathSegment("ai")
            .addPathSegment(provider.path)
            .addQueryParameter("text", prompt)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                main.post { callback.onFailure(e.message ?: "Network error") }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        main.post {
                            callback.onFailure("${provider.displayName} HTTP ${response.code}: ${body.take(180)}")
                        }
                        return
                    }
                    val result = runCatching { extractResult(body) }.getOrElse {
                        main.post { callback.onFailure("Respons ${provider.displayName} tidak valid") }
                        return
                    }
                    if (result.isBlank()) {
                        main.post { callback.onFailure("Respons ${provider.displayName} kosong") }
                    } else {
                        main.post { callback.onSuccess(result) }
                    }
                }
            }
        })
    }

    private fun extractResult(body: String): String {
        val root = JsonParser.parseString(body)
        if (!root.isJsonObject) return body.trim()
        val obj = root.asJsonObject
        if (obj.has("status") && !obj.get("status").asBoolean) {
            return obj.get("message")?.asString.orEmpty()
        }
        return jsonToText(obj.get("result"))
            .ifBlank { jsonToText(obj.get("response")) }
            .ifBlank { jsonToText(obj.get("message")) }
    }

    private fun jsonToText(element: JsonElement?): String {
        if (element == null || element.isJsonNull) return ""
        if (element.isJsonPrimitive) return element.asString.trim()
        if (element.isJsonArray) {
            return element.asJsonArray.joinToString("\n") { jsonToText(it) }.trim()
        }
        val obj = element.asJsonObject
        return jsonToText(obj.get("text"))
            .ifBlank { jsonToText(obj.get("content")) }
            .ifBlank { jsonToText(obj.get("result")) }
    }

    private fun buildPrompt(text: String, source: String, target: String): String =
        "Terjemahkan teks OCR webtoon berikut dari ${languageName(source, true)} ke " +
            "${languageName(target, false)}. Pertahankan nama, nada, tanda baca, onomatope, " +
            "dan pemisah baris. Jangan menjelaskan dan jangan gunakan label atau markdown. " +
            "Kembalikan hanya hasil terjemahan.\n\n$text"

    private fun languageName(code: String, allowAuto: Boolean): String = when (code.lowercase()) {
        "id" -> "Bahasa Indonesia"
        "en" -> "Bahasa Inggris"
        "zh" -> "Bahasa Mandarin"
        "ko" -> "Bahasa Korea"
        "ja" -> "Bahasa Jepang"
        "auto" -> if (allowAuto) "bahasa sumber yang terdeteksi" else "Bahasa Inggris"
        else -> code
    }

    private fun splitText(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)
        val result = mutableListOf<String>()
        var remaining = text.trim()
        while (remaining.length > maxLength) {
            val window = remaining.take(maxLength)
            val splitAt = maxOf(window.lastIndexOf('\n'), window.lastIndexOf(' '))
                .takeIf { it >= maxLength / 2 } ?: maxLength
            result += remaining.substring(0, splitAt).trim()
            remaining = remaining.substring(splitAt).trimStart()
        }
        if (remaining.isNotBlank()) result += remaining
        return result
    }

    private fun cleanAnswer(value: String): String = value.trim()
        .removePrefix("```")
        .removeSuffix("```")
        .removePrefix("TERJEMAHAN:")
        .removePrefix("Terjemahan:")
        .trim()
}
