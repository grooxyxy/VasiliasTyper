package com.vasiliastyper.model

import android.graphics.Color

data class TextStyle(
    var name: String = "New Style",
    var folder: String = "Default",
    /** Example shown only in Style Manager; never replaces the user's wording. */
    var previewText: String = "Ag",
    /** Optional grouping label for bundled presets. */
    var presetCategory: String = "",
    /** Short editing/use hint displayed below the preset. */
    var presetDescription: String = "",
    var fontSize: Float = 36f,
    var fontName: String = "Default",
    var color: Int = Color.WHITE,
    /** Global text opacity, independent from ARGB colour alpha. */
    var opacity: Int = 100,
    var isBold: Boolean = false,
    var isItalic: Boolean = false,
    var effect: String = "NONE",
    var outlineWidth: Float = 4f,
    var outlineOpacity: Int = 100,
    var outlineColor: Int = Color.BLACK,
    var shadowDx: Float = 4f,
    var shadowDy: Float = 4f,
    var shadowRadius: Float = 6f,
    /** Solid expansion around the shadow glyph, similar to Photoshop spread. */
    var shadowSpread: Float = 0f,
    var shadowColor: Int = Color.BLACK,
    var shadowOpacity: Int = 80,
    var gradientStartColor: Int = Color.WHITE,
    var gradientEndColor: Int = Color.GRAY,
    /** Ordered fill-gradient stops; supports two or more colours. */
    var gradientColors: MutableList<Int> = mutableListOf(),
    /** Direction in degrees: 0 = left→right, 90 = top→bottom. */
    var gradientAngle: Float = 90f,
    var align: String = "CENTER",
    /** Extra spacing between glyphs, expressed in canvas pixels. */
    var tracking: Float = 0f,
    var leading: Float = 120f,
    var justify: Boolean = false,
    var textPathMode: String = "NONE",
    var textPathAmount: Float = 35f,
    var textPathCycles: Float = 1.5f,
    // Stackable effects: every visual layer can be enabled independently.
    var enableOutline: Boolean = false,
    var enableShadow: Boolean = false,
    var enableGradient: Boolean = false,
    var enableTexture: Boolean = false,
    // v8.0 — text transform (NONE | UPPER | LOWER | TITLE)
    var textTransform: String = "NONE",
    // v8.0 — outline gradient
    var enableOutlineGradient: Boolean = false,
    var outlineGradStartColor: Int = Color.BLACK,
    var outlineGradEndColor: Int = Color.GRAY
) {
    /**
     * Returns an independent, bounded style for UI/editor application.
     *
     * Legacy preferences and imported styles can contain non-finite or out-of-range
     * numeric values. They can also share the mutable gradient list with a cached
     * preset. Sanitising at this boundary prevents invalid Paint/Shader parameters
     * and prevents editor changes from mutating the preset cache.
     */
    fun safeCopyForApply(): TextStyle {
        fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

        val safeEffect = effect.uppercase().takeIf {
            it in setOf("NONE", "OUTLINE", "SHADOW", "OUTLINE_SHADOW", "GRADIENT", "TEXTURE", "WARP")
        } ?: "NONE"
        val safeGradient = gradientColors.take(32).toMutableList().apply {
            if (size < 2) {
                clear()
                add(gradientStartColor)
                add(gradientEndColor)
            }
        }

        return copy(
            name = name.trim().ifBlank { "Style" }.take(256),
            folder = folder.trim().ifBlank { "Default" }.take(256),
            previewText = previewText.trim().ifBlank { "Ag" }.take(64),
            fontSize = fontSize.finiteOr(36f).coerceIn(1f, 512f),
            fontName = fontName.trim().ifBlank { "Default" }.take(256),
            effect = safeEffect,
            opacity = opacity.coerceIn(0, 100),
            outlineWidth = outlineWidth.finiteOr(0f).coerceIn(0f, 128f),
            outlineOpacity = outlineOpacity.coerceIn(0, 100),
            shadowDx = shadowDx.finiteOr(0f).coerceIn(-256f, 256f),
            shadowDy = shadowDy.finiteOr(0f).coerceIn(-256f, 256f),
            shadowRadius = shadowRadius.finiteOr(0f).coerceIn(0f, 128f),
            shadowSpread = shadowSpread.finiteOr(0f).coerceIn(0f, 128f),
            shadowOpacity = shadowOpacity.coerceIn(0, 100),
            gradientColors = safeGradient,
            gradientAngle = gradientAngle.finiteOr(90f).mod(360f),
            align = align.uppercase().takeIf { it in setOf("LEFT", "CENTER", "RIGHT") } ?: "CENTER",
            tracking = tracking.finiteOr(0f).coerceIn(-128f, 512f),
            leading = leading.finiteOr(120f).coerceIn(-50f, 1000f),
            textPathMode = textPathMode.uppercase().takeIf {
                it in setOf("NONE", "CURVE_UP", "CURVE_DOWN", "WAVE", "ARCH", "VALLEY")
            } ?: "NONE",
            textPathAmount = textPathAmount.finiteOr(35f).coerceIn(-100f, 100f),
            textPathCycles = textPathCycles.finiteOr(1.5f).coerceIn(0.5f, 5f),
            textTransform = textTransform.uppercase().takeIf {
                it in setOf("NONE", "UPPER", "LOWER", "TITLE")
            } ?: "NONE"
        )
    }
}
