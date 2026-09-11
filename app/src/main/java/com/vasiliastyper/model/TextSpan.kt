package com.vasiliastyper.model

import android.graphics.Typeface

/**
 * A "span" represents a sub-range [start, end) of characters inside a
 * [TextElement] that overrides one or more of the element-level styling
 * properties. Any field left `null` falls back to the parent TextElement value.
 *
 * Multiple spans are allowed per element. They are applied in order; if two
 * spans overlap on the same character, the later one wins for the overlapping
 * properties.
 */
data class TextSpan(
    var start: Int = 0,
    var end:   Int = 0,

    // Font / face
    var fontName: String? = null,
    @Transient var typeface: Typeface? = null,
    var isBold:   Boolean? = null,
    var isItalic: Boolean? = null,
    // Per-span text size in canvas pixels. null inherits the parent element size.
    var fontSize: Float? = null,

    // Color
    var color: Int? = null,

    // v5.2 — Per-span opacity override (0-100). null = inherit element.opacity.
    // This is applied on top of element-level opacity: if element.opacity=80
    // and span.opacity=50, those characters effectively appear at 50% opacity
    // within the element layer.
    var opacity: Int? = null,

    // Effect override — null = inherit element.effect
    // Allowed values: "NONE","OUTLINE","SHADOW","OUTLINE_SHADOW"
    // (gradient / texture / warp are element-wide, not per-span)
    var effect: String? = null,

    // Outline overrides
    var outlineColor:   Int?   = null,
    var outlineWidth:   Float? = null,
    var outlineOpacity: Int?   = null,

    // Shadow overrides
    var shadowColor:   Int?   = null,
    var shadowDx:      Float? = null,
    var shadowDy:      Float? = null,
    var shadowRadius:  Float? = null,
    var shadowOpacity: Int?   = null,

    // v5.1 — Per-span texture override (filename URI). When set, this span's
    // glyphs are filled with the supplied bitmap instead of the parent
    // element's [textureUri]. Null = inherit element-level texture (if any)
    // or the flat fill colour when no texture is set anywhere.
    var textureUri: String? = null
) {
    /** True if this span covers character at absolute index [pos]. */
    fun covers(pos: Int): Boolean = pos in start until end

    /** Span length in characters. */
    val length: Int get() = (end - start).coerceAtLeast(0)
}
