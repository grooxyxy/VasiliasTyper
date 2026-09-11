package com.vasiliastyper.model

import android.graphics.RectF

/**
 * Immutable output produced by the local speech-bubble detector.
 *
 * [confidence] is normalized to 0..1 and combines shape, brightness, edge,
 * and interior ink evidence. [textPreview] is optional and lets the UI show a
 * short OCR snippet without requiring another OCR pass.
 */
data class BubbleDetection(
    val bounds: RectF,
    val confidence: Float,
    val source: Source,
    val textPreview: String = ""
) {
    enum class Source {
        BRIGHT_REGION,
        DARK_REGION,
        CLOSED_OUTLINE,
        ML_KIT,
        RT_DETR,
        HYBRID
    }
}

/** Tunable, device-independent parameters for bubble detection. */
data class BubbleDetectionConfig(
    val minimumConfidence: Float = 0.60f,
    val minimumWidthPx: Int = 32,
    val minimumHeightPx: Int = 20,
    val maximumAspectRatio: Float = 5.5f,
    val stripeHeightPx: Int = 1800,
    val stripeOverlapPx: Int = 192,
    val maximumProcessingWidthPx: Int = 1200,
    val mlKitBubblePaddingRatio: Float = 0.38f,
    val maximumDetections: Int = 120
) {
    init {
        require(minimumConfidence in 0f..1f)
        require(minimumWidthPx > 0 && minimumHeightPx > 0)
        require(maximumAspectRatio >= 1f)
        require(stripeHeightPx > stripeOverlapPx)
        require(stripeOverlapPx >= 0)
        require(maximumProcessingWidthPx >= 320)
        require(mlKitBubblePaddingRatio in 0f..2f)
        require(maximumDetections > 0)
    }
}
