package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import com.vasiliastyper.model.Layer

/** Shared renderer used by the editor canvas, exports, merge and PSD preview. */
object LayerCompositor {

    fun composite(layers: List<Layer>, width: Int, height: Int): Bitmap {
        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        layers.forEachIndexed { index, layer ->
            if (!layer.isEffectivelyVisible || layer.bitmap.isRecycled) return@forEachIndexed
            drawLayer(canvas, layer, clippingBaseFor(layers, index), width, height)
        }
        return result
    }

    /**
     * Draws one layer with optional alpha and clipping masks without mutating its
     * bitmap. saveLayer keeps PorterDuff operations isolated from content below.
     */
    fun drawLayer(
        canvas: Canvas,
        layer: Layer,
        clippingBase: Layer?,
        width: Int = layer.bitmap.width,
        height: Int = layer.bitmap.height
    ) {
        if (!layer.isEffectivelyVisible || layer.bitmap.isRecycled) return
        val restorePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = (layer.opacity / 100f * 255).toInt().coerceIn(0, 255)
            xfermode = PorterDuffXfermode(layer.blendMode)
        }
        val needsIsolatedMask = layer.isAlphaMaskEnabled && layer.hasAlphaMask ||
            layer.isClippingMask && clippingBase != null
        if (!needsIsolatedMask) {
            canvas.drawBitmap(layer.bitmap, 0f, 0f, restorePaint)
            return
        }

        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val checkpoint = canvas.saveLayer(bounds, restorePaint)
        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
        val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        if (layer.isAlphaMaskEnabled) {
            layer.alphaMask?.takeUnless { it.isRecycled }?.let { mask ->
                canvas.drawBitmap(mask, null, bounds, maskPaint)
            }
        }
        if (layer.isClippingMask && clippingBase != null &&
            clippingBase.isEffectivelyVisible && !clippingBase.bitmap.isRecycled
        ) {
            maskPaint.alpha = (clippingBase.opacity / 100f * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(clippingBase.bitmap, 0f, 0f, maskPaint)
            if (clippingBase.isAlphaMaskEnabled) {
                clippingBase.alphaMask?.takeUnless { it.isRecycled }?.let { baseMask ->
                    maskPaint.alpha = 255
                    canvas.drawBitmap(baseMask, null, bounds, maskPaint)
                }
            }
        }
        maskPaint.xfermode = null
        canvas.restoreToCount(checkpoint)
    }

    /** Nearest non-clipping pixel layer below forms the clipping group base. */
    fun clippingBaseFor(layers: List<Layer>, index: Int): Layer? {
        if (index !in layers.indices || !layers[index].isClippingMask) return null
        for (candidateIndex in index - 1 downTo 0) {
            val candidate = layers[candidateIndex]
            if (!candidate.isClippingMask) return candidate
        }
        return null
    }

    fun blendModeFromIndex(index: Int): PorterDuff.Mode = when (index) {
        0 -> PorterDuff.Mode.SRC_OVER
        1 -> PorterDuff.Mode.MULTIPLY
        2 -> PorterDuff.Mode.SCREEN
        3 -> PorterDuff.Mode.OVERLAY
        4 -> PorterDuff.Mode.DARKEN
        5 -> PorterDuff.Mode.LIGHTEN
        6 -> PorterDuff.Mode.ADD
        7 -> PorterDuff.Mode.XOR
        8 -> PorterDuff.Mode.SRC_IN
        9 -> PorterDuff.Mode.DST_IN
        10 -> PorterDuff.Mode.SRC_OUT
        11 -> PorterDuff.Mode.DST_OVER
        else -> PorterDuff.Mode.SRC_OVER
    }

    val blendModeNames = listOf(
        "Normal", "Multiply", "Screen", "Overlay",
        "Darken", "Lighten", "Add", "XOR",
        "Src In", "Dst In", "Src Out", "Dst Over"
    )
}
