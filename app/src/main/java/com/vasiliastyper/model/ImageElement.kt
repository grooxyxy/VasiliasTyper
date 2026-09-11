package com.vasiliastyper.model

import android.graphics.Bitmap
import android.graphics.Paint
import kotlin.math.PI

/**
 * A free-floating image element on the canvas — similar to TextElement but for bitmaps.
 * Resizing via corner handles always preserves the original aspect ratio.
 *
 * v5.2: Added global opacity and arbitrary-angle motion blur.
 */
data class ImageElement(
    val id: String = java.util.UUID.randomUUID().toString(),
    /** User-facing layer metadata shared with pixel and text layers. */
    var name: String = "Image Layer",
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var blendMode: String = "Normal",
    var bitmap: Bitmap,
    var x: Float = 0f,
    var y: Float = 0f,
    var width: Float = 200f,
    var height: Float = 200f,
    var rotation: Float = 0f,
    val aspectRatio: Float = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f,

    // ── Opacity ───────────────────────────────────────────────────────────────
    // Global opacity for this image element (0 = invisible, 100 = fully opaque).
    var opacity: Int = 100,

    // ── Blur ─────────────────────────────────────────────────────────────────
    /** Blur radius in pixels (0 = no blur). Used for GAUSSIAN and as base for MOTION. */
    var blurRadius: Float = 0f,

    /** Type of blur to apply when blurRadius > 0. */
    var blurType: BlurType = BlurType.GAUSSIAN,

    /** Motion blur direction in degrees, clockwise from right (0° = →, 90° = ↓).
     *  Used for MOTION, MOTION_H (0°), and MOTION_V (90°). */
    var blurMotionAngle: Float = 0f,

    /** Total length of the motion-blur trail in pixels. Used for MOTION / MOTION_H / MOTION_V. */
    var blurMotionDistance: Float = 20f
) {
    /** Returns a Paint configured with the element's global opacity. */
    fun createDrawPaint(): Paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        alpha = (opacity / 100f * 255).toInt().coerceIn(0, 255)
    }

    val isMotionBlur: Boolean
        get() = blurRadius > 0f && blurType != BlurType.GAUSSIAN

    val motionBlurAngleRadians: Float
        get() {
            val angle = when (blurType) {
                BlurType.MOTION_H -> 0f
                BlurType.MOTION_V -> 90f
                else -> blurMotionAngle
            }
            return angle * PI.toFloat() / 180f
        }
}
