package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import com.vasiliastyper.adapter.FontItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.max

/**
 * Compatibility replacement for the old Gemini Nano font matcher.
 *
 * The previous implementation depended on unavailable `genai` symbols on some
 * builds. This version keeps the public surface small, self-contained, and
 * offline-friendly by delegating to the deterministic local matcher.
 *
 * The object is intentionally conservative: it never uploads images, never
 * downloads models, and never requires any external SDK beyond the project
 * dependencies that already ship with this app.
 */
object GeminiNanoFontMatcher {

    enum class Generation {
        AUTO,
        LOCAL_ONLY,
        REMOTE_ONLY
    }

    enum class FeatureStatus {
        AVAILABLE,
        UNAVAILABLE,
        DOWNLOADING,
        ERROR
    }

    enum class DownloadStatus {
        NOT_REQUIRED,
        READY,
        DOWNLOADING,
        FAILED
    }

    data class TextPart(val text: String)
    data class ImagePart(val bytes: ByteArray, val mimeType: String = "image/jpeg")

    data class Analysis(
        val rankedFonts: List<FontItem>,
        val fillMode: String,
        val fillStartColor: Int,
        val fillEndColor: Int,
        val outlineEnabled: Boolean,
        val outlineColor: Int,
        val outlineWidth: Float,
        val shadowEnabled: Boolean,
        val shadowColor: Int,
        val shadowDx: Float,
        val shadowDy: Float,
        val shadowRadius: Float,
        val textShape: String,
        val source: String,
        val note: String
    )

    /**
     * Small DSL that mirrors the old builder-style calls.
     */
    class GenerationRequestBuilder {
        var temperature: Double = 0.0
        var candidateCount: Int = 1
        var maxOutputTokens: Int = 1024
        private val _parts = mutableListOf<Any>()
        val parts: List<Any> get() = _parts

        fun text(value: String) {
            _parts += TextPart(value)
        }

        fun image(bytes: ByteArray, mimeType: String = "image/jpeg") {
            _parts += ImagePart(bytes = bytes, mimeType = mimeType)
        }

        fun part(value: Any) {
            _parts += value
        }
    }

    data class GenerationRequest(
        val parts: List<Any>,
        val temperature: Double = 0.0,
        val candidateCount: Int = 1,
        val maxOutputTokens: Int = 1024
    )

    fun generateContentRequest(
        parts: List<Any>,
        temperature: Double = 0.0,
        candidateCount: Int = 1,
        maxOutputTokens: Int = 1024
    ): GenerationRequest = GenerationRequest(
        parts = parts,
        temperature = temperature,
        candidateCount = candidateCount,
        maxOutputTokens = maxOutputTokens
    )

    fun generateContentRequest(
        block: GenerationRequestBuilder.() -> Unit
    ): GenerationRequest {
        val builder = GenerationRequestBuilder().apply(block)
        return GenerationRequest(
            parts = builder.parts,
            temperature = builder.temperature,
            candidateCount = builder.candidateCount,
            maxOutputTokens = builder.maxOutputTokens
        )
    }

    fun featureStatus(context: Context): FeatureStatus =
        if (GeminiSettings.getApiKey(context).isNullOrBlank()) {
            FeatureStatus.UNAVAILABLE
        } else {
            FeatureStatus.AVAILABLE
        }

    fun downloadStatus(context: Context): DownloadStatus {
        // No local model is required for this compatibility matcher.
        return if (GeminiSettings.getApiKey(context).isNullOrBlank()) {
            DownloadStatus.NOT_REQUIRED
        } else {
            DownloadStatus.READY
        }
    }

    /**
     * Returns the deterministic offline analysis used by the current app.
     */
    suspend fun analyze(reference: Bitmap, bank: List<FontItem>): Analysis =
        withContext(Dispatchers.Default) {
            val local = LocalFontMatcher.analyze(reference, bank)
            local.toAnalysis()
        }

    /**
     * Callback-oriented helper that behaves like the older async API.
     */
    fun analyzeAsync(
        reference: Bitmap,
        bank: List<FontItem>,
        onSuccess: (Analysis) -> Unit,
        onFailure: (Throwable) -> Unit = {}
    ) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            try {
                val local = runBlockingLike(reference, bank)
                main.post { onSuccess(local) }
            } catch (t: Throwable) {
                main.post { onFailure(t) }
            }
        }.start()
    }

    private fun runBlockingLike(reference: Bitmap, bank: List<FontItem>): Analysis {
        // Keep this strictly synchronous so it can be called from a plain thread.
        val local = kotlinx.coroutines.runBlocking(Dispatchers.Default) {
            LocalFontMatcher.analyze(reference, bank)
        }
        return local.toAnalysis()
    }

    private fun LocalFontMatcher.Analysis.toAnalysis(): Analysis = Analysis(
        rankedFonts = rankedFonts,
        fillMode = fillMode,
        fillStartColor = fillStartColor,
        fillEndColor = fillEndColor,
        outlineEnabled = outlineEnabled,
        outlineColor = outlineColor,
        outlineWidth = outlineWidth,
        shadowEnabled = shadowEnabled,
        shadowColor = shadowColor,
        shadowDx = shadowDx,
        shadowDy = shadowDy,
        shadowRadius = shadowRadius,
        textShape = textShape,
        source = source.ifBlank { "Gemini Nano compatibility layer" },
        note = note.ifBlank { "Fallback offline matcher." }
    )
}

typealias GeminiNanoAnalysis = GeminiNanoFontMatcher.Analysis
typealias GeminiNanoGenerationRequest = GeminiNanoFontMatcher.GenerationRequest
