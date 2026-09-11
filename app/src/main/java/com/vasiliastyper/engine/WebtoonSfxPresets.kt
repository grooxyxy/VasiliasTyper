package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Color
import com.vasiliastyper.model.TextStyle

/**
 * Editable Webtoon SFX presets based on hand-lettered comic sound effects.
 *
 * Every entry is a regular [TextStyle]. [TextStyle.previewText] is metadata used
 * only by Style Manager, so applying a preset never replaces the user's text.
 * Font, fill, outline, shadow, gradient, spacing and alignment stay editable.
 */
object WebtoonSfxPresets {
    const val FOLDER = "Webtoon SFX"

    private const val PREF_FILE = "webtoon_sfx_presets"
    private const val KEY_SEPARATED_VERSION = "separated_version"
    private const val SEPARATED_VERSION = 1

    // The reference sheet deliberately mixes letterforms instead of rendering every
    // sound with one generic comic face. Keep these names aligned with the bundled
    // files under assets/fonts/WebtoonSFX; FontResolver matches them by file name.
    private const val FONT_HAND = "Kalam-Regular"
    private const val FONT_HAND_BOLD = "Kalam-Bold"
    private const val FONT_MARKER = "PermanentMarker-Regular"
    private const val FONT_IMPACT = "Bangers-Regular"
    private const val FONT_ROUGH = "RockSalt-Regular"
    private const val FONT_CASUAL = "GloriaHallelujah-Regular"
    private const val FONT_PLAYFUL = "Schoolbell-Regular"
    private const val FONT_THIN = "ComingSoon-Regular"
    private const val FONT_BUBBLE = "Chewy-Regular"
    private const val FONT_TALL = "CoveredByYourGrace-Regular"
    private const val FONT_AIRY = "ShadowsIntoLightTwo-Regular"

    /**
     * One-time migration for releases that copied bundled presets into the user-style
     * SharedPreferences. It removes only known bundled names, preserving custom styles
     * even when the user placed them in the same folder.
     *
     * Call this from a background dispatcher. Presets themselves are generated only
     * when the dedicated Preset Browser is opened.
     */
    fun separateLegacyPresets(context: Context): Int {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_SEPARATED_VERSION, 0) >= SEPARATED_VERSION) return 0

        val bundledNames = defaultStyles().mapTo(hashSetOf()) { it.name.lowercase() }
        val existing = StyleManager.loadStyles(context)
        val remaining = existing.filterNot {
            it.folder.equals(FOLDER, ignoreCase = true) && it.name.lowercase() in bundledNames
        }.toMutableList()
        val removed = existing.size - remaining.size
        if (removed > 0) {
            StyleManager.saveStyles(context, remaining)
            val separationPersisted = StyleManager.loadStyles(context).none {
                it.folder.equals(FOLDER, ignoreCase = true) && it.name.lowercase() in bundledNames
            }
            if (!separationPersisted) return 0
        }
        if (remaining.none { it.folder.equals(FOLDER, ignoreCase = true) }) {
            StyleManager.removeFolder(context, FOLDER)
        }
        prefs.edit().putInt(KEY_SEPARATED_VERSION, SEPARATED_VERSION).commit()
        return removed
    }

    private fun sfx(
        name: String,
        preview: String,
        category: String,
        description: String,
        font: String = FONT_HAND_BOLD,
        size: Float = 68f,
        fill: Int = Color.BLACK,
        bold: Boolean = false,
        italic: Boolean = false,
        tracking: Float = 0f,
        outlineWidth: Float = 0f,
        outlineColor: Int = Color.WHITE,
        outlineOpacity: Int = 100,
        shadowDx: Float = 0f,
        shadowDy: Float = 0f,
        shadowRadius: Float = 0f,
        shadowColor: Int = Color.BLACK,
        shadowOpacity: Int = 0,
        gradient: List<Int> = emptyList(),
        gradientAngle: Float = 90f,
        leading: Float = 100f
    ): TextStyle {
        val hasOutline = outlineWidth > 0f
        val hasShadow = shadowOpacity > 0 && (shadowDx != 0f || shadowDy != 0f || shadowRadius > 0f)
        val hasGradient = gradient.size >= 2
        val effect = when {
            hasGradient -> "GRADIENT"
            hasOutline && hasShadow -> "OUTLINE_SHADOW"
            hasOutline -> "OUTLINE"
            hasShadow -> "SHADOW"
            else -> "NONE"
        }
        return TextStyle(
            name = name,
            folder = FOLDER,
            previewText = preview,
            presetCategory = category,
            presetDescription = description,
            fontSize = size,
            fontName = font,
            color = fill,
            isBold = bold,
            isItalic = italic,
            effect = effect,
            outlineWidth = outlineWidth,
            outlineOpacity = outlineOpacity,
            outlineColor = outlineColor,
            shadowDx = shadowDx,
            shadowDy = shadowDy,
            shadowRadius = shadowRadius,
            shadowColor = shadowColor,
            shadowOpacity = shadowOpacity,
            gradientStartColor = gradient.firstOrNull() ?: fill,
            gradientEndColor = gradient.lastOrNull() ?: fill,
            gradientColors = gradient.toMutableList(),
            gradientAngle = gradientAngle,
            tracking = tracking,
            leading = leading,
            enableOutline = hasOutline,
            enableShadow = hasShadow,
            enableGradient = hasGradient,
            textTransform = "UPPER"
        )
    }

    // Built once and reused. The old implementation rebuilt all preset objects and
    // parsed every colour on each button tap, adding avoidable work exactly when the
    // browser should become visible. Lazy publication is safe when migration and UI
    // request the collection at the same time.
    private val cachedDefaultStyles: List<TextStyle> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        listOf(
        // Impact and collision: condensed, heavy and high-contrast.
        sfx("Impact · BOOM!", "BOOM!", "Impact", "Ledakan besar; outline tebal mudah dibaca di panel ramai.", FONT_IMPACT, 112f, Color.WHITE, true, false, -3f, 9f, Color.BLACK, shadowDx = 9f, shadowDy = 10f, shadowRadius = 1f, shadowColor = Color.parseColor("#B51622"), shadowOpacity = 95),
        sfx("Impact · KABOOM", "KABOOM", "Impact", "Versi ledakan miring dengan bayangan merah dramatis.", FONT_IMPACT, 108f, Color.parseColor("#FFE15B"), true, true, -4f, 8f, Color.BLACK, shadowDx = 12f, shadowDy = 12f, shadowRadius = 2f, shadowColor = Color.parseColor("#D62F2F"), shadowOpacity = 100),
        sfx("Impact · SLAM", "SLAM", "Impact", "Benturan keras; marker padat dan sedikit miring.", FONT_MARKER, 98f, Color.BLACK, true, true, -2f),
        sfx("Impact · THUD", "THUD", "Impact", "Benturan berat dengan bentuk komik bulat dan padat.", FONT_BUBBLE, 88f, Color.BLACK, true, false, -2f),
        sfx("Impact · PUSH", "PUSH", "Impact", "Dorongan kuat; brush italic memberi arah gerak.", FONT_MARKER, 88f, Color.BLACK, true, true, -4f),
        sfx("Impact · GRAB", "GRAB", "Impact", "Aksi cepat dengan tulisan tangan tinggi seperti referensi.", FONT_TALL, 82f, Color.BLACK, true, false, -1f),
        sfx("Impact · SQUEEZE", "SQUEEZE", "Impact", "Tekanan rapat dengan coretan playful yang terkompresi.", FONT_PLAYFUL, 70f, Color.BLACK, true, false, -7f),
        sfx("Impact · PAT PAT", "PAT PAT", "Impact", "Ketukan lembut memakai tulisan tangan santai.", FONT_PLAYFUL, 68f, Color.BLACK, false, false, 1f, leading = 82f),
        sfx("Impact · PLOP", "PLOP", "Impact", "Jatuh ringan; bentuk komik bulat dan playful.", FONT_BUBBLE, 74f, Color.BLACK, false, false, 1f),

        // Motion: slanted and airy, matching whoosh/fwip/swish references.
        sfx("Motion · FWIP", "FWIP", "Motion", "Gerak sangat cepat; huruf tinggi, ringan, dan miring.", FONT_TALL, 88f, Color.BLACK, true, true, -2f),
        sfx("Motion · SWISH", "SWISH", "Motion", "Sapuan brush cepat dengan fill biru muda dan outline gelap.", FONT_MARKER, 84f, Color.parseColor("#E9FAFF"), true, true, 2f, 4f, Color.parseColor("#173C55"), gradient = listOf(Color.WHITE, Color.parseColor("#74D8FF")), gradientAngle = 90f),
        sfx("Motion · WHOOSH", "WHOOSH", "Motion", "Gerak udara panjang; tulisan tipis dan tracking renggang.", FONT_AIRY, 80f, Color.BLACK, true, true, 7f),
        sfx("Motion · SPRING", "SPRING", "Motion", "Gerak lenting; karakter playful dengan italic ringan.", FONT_PLAYFUL, 68f, Color.BLACK, false, true, 4f),
        sfx("Motion · SHAAA", "SHAAA", "Motion", "Sapuan panjang memakai huruf tinggi dengan jarak lebar.", FONT_TALL, 78f, Color.BLACK, false, true, 9f),

        // Reactions and voices.
        sfx("Reaction · GASP", "GASP", "Reaction", "Kaget singkat dengan tulisan tangan tipis.", FONT_THIN, 76f, Color.BLACK, false, true, 1f),
        sfx("Reaction · AAAGH!", "AAAGH!", "Reaction", "Teriakan tajam dengan coretan kasar dan tidak rata.", FONT_ROUGH, 86f, Color.BLACK, true, true, -3f),
        sfx("Reaction · HAHAHA", "HAHAHA", "Reaction", "Tawa terbuka; tulisan kasual dan tracking ritmis.", FONT_CASUAL, 70f, Color.BLACK, false, false, 5f),
        sfx("Reaction · SOB", "SOB", "Reaction", "Tangis pendek dengan tulisan tangan tipis dan organik.", FONT_THIN, 70f, Color.BLACK, false, false, 2f),
        sfx("Reaction · SNIFF", "SNIFF", "Reaction", "Suara kecil memakai tulisan ringan dan berjarak.", FONT_AIRY, 62f, Color.BLACK, false, false, 2f),
        sfx("Reaction · UM…", "UM…", "Reaction", "Ragu atau jeda dengan bentuk komik bulat yang ekspresif.", FONT_BUBBLE, 72f, Color.BLACK, false, false, 0f),
        sfx("Reaction · TREMBLE", "TREMBLE", "Reaction", "Gemetar atau takut; coretan kasar, italic, dan renggang.", FONT_ROUGH, 58f, Color.BLACK, false, true, 4f),
        sfx("Reaction · CHATTER", "CHATTER", "Reaction", "Gigi bergemeletuk memakai tulisan kasual tidak seragam.", FONT_CASUAL, 60f, Color.BLACK, false, true, 2f),

        // Quiet, ambience and small Foley.
        sfx("Quiet · TSSS~", "TSSS~", "Quiet", "Desis halus dengan huruf sangat ringan dan renggang.", FONT_AIRY, 58f, Color.parseColor("#34303A"), false, true, 8f),
        sfx("Quiet · GLANCE", "GLANCE", "Quiet", "Gerak mata kecil dengan tulisan tipis yang bersih.", FONT_THIN, 56f, Color.BLACK, false, false, 4f),
        sfx("Quiet · DING DONG", "DING DONG", "Quiet", "Bunyi bel hollow: bentuk bulat, fill putih, dan outline hitam.", FONT_BUBBLE, 66f, Color.WHITE, false, false, 2f, 3f, Color.BLACK),
        sfx("Quiet · SILENCE…", "SILENCE…", "Quiet", "Momen sunyi; tulisan tipis, outline lembut, dan jarak lebar.", FONT_THIN, 54f, Color.parseColor("#F4F4F4"), false, true, 7f, 2f, Color.parseColor("#4E4E55")),

        // Colour-ready decorative presets, still fully editable.
        sfx("Decorative · MAGIC", "MAGIC", "Decorative", "Gradient ungu-biru, outline dan glow untuk efek magis.", FONT_IMPACT, 84f, Color.WHITE, true, false, 2f, 5f, Color.parseColor("#3A2165"), shadowDx = 0f, shadowDy = 4f, shadowRadius = 10f, shadowColor = Color.parseColor("#55D9FF"), shadowOpacity = 85, gradient = listOf(Color.WHITE, Color.parseColor("#74E7FF"), Color.parseColor("#B078FF"))),
        sfx("Decorative · SPARKLE", "SPARKLE", "Decorative", "Kuning berkilau untuk reaksi manis atau cahaya.", FONT_HAND_BOLD, 64f, Color.parseColor("#FFF7B2"), true, false, 5f, 2f, Color.WHITE, shadowDx = 0f, shadowDy = 2f, shadowRadius = 7f, shadowColor = Color.parseColor("#E3A52C"), shadowOpacity = 70, gradient = listOf(Color.WHITE, Color.parseColor("#FFF09A"), Color.parseColor("#FFC933"))),
        sfx("Decorative · FIRE", "FIRE", "Decorative", "Gradient panas dengan outline merah gelap.", FONT_IMPACT, 88f, Color.parseColor("#FFF2A6"), true, true, 0f, 5f, Color.parseColor("#7A1608"), shadowDx = 4f, shadowDy = 7f, shadowRadius = 6f, shadowColor = Color.parseColor("#D4310A"), shadowOpacity = 85, gradient = listOf(Color.parseColor("#FFF9C7"), Color.parseColor("#FFB319"), Color.parseColor("#FF3B0D")))
        )
    }

    /**
     * Return independent, sanitised objects. The editor is allowed to change every
     * preset field, so exposing the cached mutable TextStyle instances could corrupt
     * later applications and race the asynchronous preview renderer.
     */
    fun defaultStyles(): List<TextStyle> = cachedDefaultStyles.map(TextStyle::safeCopyForApply)

}
