package com.vasiliastyper.model

/**
 * Blur effect types available for ImageElement.
 *
 * GAUSSIAN  — standard soft blur in all directions (maps to BlurMaskFilter.Blur.NORMAL)
 * MOTION_H  — horizontal motion blur (convenience: angle = 0°)
 * MOTION_V  — vertical motion blur   (convenience: angle = 90°)
 * MOTION    — arbitrary-angle motion blur; uses ImageElement.blurMotionAngle
 */
enum class BlurType {
    GAUSSIAN,
    MOTION_H,
    MOTION_V,
    MOTION
}
