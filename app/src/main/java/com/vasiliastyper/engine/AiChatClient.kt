package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

object AiChatSettings {
    private const val PREFS = "ai_chat_settings"
    private const val OPENAI_KEY = "openai_key"
    private const val CLAUDE_KEY = "claude_key"
    private const val AGNES_KEY = "agnes_key"

    data class Keys(val openAi: String, val claude: String, val agnes: String)

    fun load(context: Context): Keys = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
        Keys(
            openAi = it.getString(OPENAI_KEY, "").orEmpty().trim(),
            claude = it.getString(CLAUDE_KEY, "").orEmpty().trim(),
            agnes = it.getString(AGNES_KEY, "").orEmpty().trim()
                .ifBlank { AgnesAiSettings.load(context).apiKey }
        )
    }

    fun save(context: Context, openAi: String, claude: String, agnes: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(OPENAI_KEY, openAi.trim())
            .putString(CLAUDE_KEY, claude.trim())
            .putString(AGNES_KEY, agnes.trim())
            .apply()
    }
}

data class AiConversationMessage(val role: String, val content: String)

enum class AiChatProvider(val displayName: String) {
    GPT_35("ChatGPT 3.5 Turbo"),
    CLAUDE("Claude")
}

/** AI client. Text chat uses the keyless Nexray GET endpoints; Agnes remains key-based. */
object AiChatClient {
    private const val NEXRAY_HOST = "api.nexray.eu.cc"
    private const val AGNES_ENDPOINT = "https://apihub.agnes-ai.com/v1/chat/completions"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    fun chat(context: Context, provider: AiChatProvider, messages: List<AiConversationMessage>): String {
        // Keep Context in the API for binary compatibility with existing callers.
        context.applicationContext
        val prompt = buildConversationPrompt(messages)
        val path = when (provider) {
            AiChatProvider.GPT_35 -> "gpt-3.5-turbo"
            AiChatProvider.CLAUDE -> "claude"
        }
        return nexrayGet(path, prompt)
    }

    fun understandImage(context: Context, prompt: String, bitmap: Bitmap): String {
        val key = AiChatSettings.load(context).agnes
        require(key.isNotBlank()) { "Agnes API key is not configured." }
        val imageData = bitmap.toDataUrl()
        val content = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", prompt.ifBlank { "Describe and understand this canvas image in detail." })
            })
            add(JsonObject().apply {
                addProperty("type", "image_url")
                add("image_url", JsonObject().apply { addProperty("url", imageData) })
            })
        }
        val payload = JsonObject().apply {
            addProperty("model", "agnes-2.5-flash")
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", content)
                })
            })
            addProperty("temperature", 0.2)
            addProperty("max_tokens", 2048)
        }
        return executeJson(
            Request.Builder().url(AGNES_ENDPOINT)
                .header("Authorization", "Bearer $key")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()
        ) { root -> root["choices"].asJsonArray[0].asJsonObject["message"].asJsonObject["content"].asString }
    }

    private fun buildConversationPrompt(messages: List<AiConversationMessage>): String {
        val recent = messages.takeLast(20)
        return recent.joinToString("\n\n") { message ->
            val role = when (message.role.lowercase()) {
                "system" -> "INSTRUKSI"
                "assistant" -> "ASISTEN"
                else -> "PENGGUNA"
            }
            "$role: ${message.content.trim()}"
        }.trim().take(12_000)
    }

    private fun nexrayGet(path: String, prompt: String): String {
        require(prompt.isNotBlank()) { "Prompt tidak boleh kosong." }
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(NEXRAY_HOST)
            .addPathSegment("ai")
            .addPathSegment(path)
            .addQueryParameter("text", prompt)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/plain;q=0.9")
            .header("User-Agent", "VasiliasTyper-Android/1.0")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Nexray HTTP ${response.code}: ${body.take(500)}")
            }
            if (body.isBlank()) throw IllegalStateException("Layanan AI mengembalikan respons kosong.")
            return extractNexrayText(body)
                .ifBlank { throw IllegalStateException("Respons AI tidak berisi teks.") }
        }
    }

    private fun extractNexrayText(body: String): String {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return body.trim()
        fun textOf(element: com.google.gson.JsonElement?): String {
            if (element == null || element.isJsonNull) return ""
            if (element.isJsonPrimitive) return element.asString.trim()
            if (element.isJsonArray) return element.asJsonArray.joinToString("\n") { textOf(it) }.trim()
            val obj = element.asJsonObject
            return textOf(obj.get("text"))
                .ifBlank { textOf(obj.get("content")) }
                .ifBlank { textOf(obj.get("result")) }
                .ifBlank { textOf(obj.get("response")) }
                .ifBlank { textOf(obj.get("message")) }
                .ifBlank { textOf(obj.get("answer")) }
                .ifBlank { textOf(obj.get("data")) }
        }
        return textOf(root)
    }

    private fun executeJson(request: Request, parse: (JsonObject) -> String): String {
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    val root = JsonParser.parseString(body).asJsonObject
                    root["error"]?.let { error ->
                        if (error.isJsonObject) error.asJsonObject["message"]?.asString else error.asString
                    }
                }.getOrNull() ?: body.take(500)
                throw IllegalStateException("HTTP ${response.code}: $detail")
            }
            if (body.isBlank()) throw IllegalStateException("The AI service returned an empty response.")
            return parse(JsonParser.parseString(body).asJsonObject).trim()
                .ifBlank { throw IllegalStateException("The AI response contained no text.") }
        }
    }

    private fun Bitmap.toDataUrl(): String = ByteArrayOutputStream().use { stream ->
        val maxSide = maxOf(width, height)
        val upload = if (maxSide > 1600) {
            val scale = 1600f / maxSide
            Bitmap.createScaledBitmap(this, (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), true)
        } else this
        try {
            check(upload.compress(Bitmap.CompressFormat.JPEG, 88, stream)) { "Could not encode canvas image." }
            "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (upload !== this && !upload.isRecycled) upload.recycle()
        }
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
