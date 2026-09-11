package com.vasiliastyper.model

import android.graphics.RectF

/**
 * One editable OCR region on the canvas.
 * [regionRect] remains in canvas coordinates so the result can be highlighted,
 * deleted from the canvas, and pasted into Script OCR as SOURCE text.
 */
data class OcrResult(
    var originalText: String,
    var sourceLang: String,
    var translatedText: String? = null,
    var targetLang: String = "id",
    val selectionIndex: Int = 0,
    val regionRect: RectF? = null
)