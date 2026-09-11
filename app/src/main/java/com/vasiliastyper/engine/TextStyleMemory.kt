package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.vasiliastyper.adapter.FontItem
import com.vasiliastyper.model.TextAlign
import com.vasiliastyper.model.TextStyle
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/**
 * Detects reusable typography from a selected canvas region.
 *
 * The matcher deliberately runs on a small normalized bitmap. This keeps font-bank
 * matching responsive on long webtoon pages and prevents an accidental full-size
 * bitmap allocation. Results are persisted as a small style memory so the latest
 * matches remain available after reopening the app.
 */
object TextStyleMemory {
    data class MatchResult(
        val style: TextStyle,
        val confidence: Int,
        val tracking: Float,
        val normalizedX: Float,
        val normalizedY: Float
    )

    private const val PREF_FILE = "text_style_memory"
    private const val KEY_ITEMS = "items"
    private const val MAX_ITEMS = 40

    fun analyze(
        source: Bitmap,
        text: String,
        fonts: List<FontItem>,
        normalizedX: Float,
        normalizedY: Float
    ): MatchResult {
        require(source.width > 0 && source.height > 0) { "Area deteksi kosong" }
        val sample = normalize(source)
        try {
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            val border = borderColor(sample)
            val distances = pixels.map { colorDistance(it, border) }.sorted()
            val threshold = distances[(distances.size * 0.72f).toInt().coerceIn(0, distances.lastIndex)]
                .coerceAtLeast(28)
            val ink = BooleanArray(pixels.size)
            var inkCount = 0
            var minX = sample.width
            var minY = sample.height
            var maxX = -1
            var maxY = -1
            val inkColors = ArrayList<Int>()
            pixels.forEachIndexed { index, color ->
                val x = index % sample.width
                val y = index / sample.width
                if (colorDistance(color, border) >= threshold) {
                    ink[index] = true
                    inkCount++
                    minX = minOf(minX, x); minY = minOf(minY, y)
                    maxX = maxOf(maxX, x); maxY = maxOf(maxY, y)
                    inkColors += color
                }
            }
            if (inkCount < max(8, pixels.size / 300)) {
                throw IllegalStateException("Teks tidak cukup jelas pada area yang dipilih")
            }

            val foreground = medianColor(inkColors)
            val inkW = (maxX - minX + 1).coerceAtLeast(1)
            val inkH = (maxY - minY + 1).coerceAtLeast(1)
            val estimatedSize = (inkH * 1.18f * source.height / sample.height)
                .coerceIn(8f, 208f)
            val alignment = when {
                minX <= sample.width * 0.12f -> TextAlign.LEFT
                maxX >= sample.width * 0.88f -> TextAlign.RIGHT
                else -> TextAlign.CENTER
            }
            val fontMatch = matchFont(text.ifBlank { "Sample" }, ink, sample.width, sample.height, fonts, inkH * 1.18f)
            val measured = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = estimatedSize
                typeface = fontMatch.first.typeface
            }.measureText(text.ifBlank { "Sample" })
            val actualWidth = inkW * source.width.toFloat() / sample.width
            val tracking = if (text.length > 1) {
                ((actualWidth - measured) / (text.length - 1)).coerceIn(-12f, 40f)
            } else 0f

            val outline = estimateOutline(pixels, ink, sample.width, sample.height, border, foreground)
            val style = TextStyle(
                name = "Canvas Match",
                folder = "Detected",
                fontSize = estimatedSize,
                fontName = fontMatch.first.displayName,
                color = foreground,
                isBold = fontMatch.second,
                effect = if (outline.first > 0.8f) "OUTLINE" else "NONE",
                outlineWidth = outline.first,
                outlineColor = outline.second,
                align = alignment.name,
                tracking = tracking
            )
            val contrastScore = (colorDistance(foreground, border) / 4).coerceIn(0, 55)
            val densityScore = (inkCount * 100 / pixels.size).coerceIn(0, 25)
            val confidence = (20 + contrastScore + densityScore).coerceIn(35, 96)
            return MatchResult(style, confidence, tracking, normalizedX, normalizedY)
        } finally {
            if (sample !== source && !sample.isRecycled) sample.recycle()
        }
    }

    fun remember(context: Context, result: MatchResult) {
        val existing = loadRaw(context)
        val item = JSONObject().apply {
            put("fontName", result.style.fontName)
            put("fontSize", result.style.fontSize.toDouble())
            put("color", result.style.color)
            put("outlineColor", result.style.outlineColor)
            put("outlineWidth", result.style.outlineWidth.toDouble())
            put("align", result.style.align)
            put("tracking", result.tracking.toDouble())
            put("x", result.normalizedX.toDouble())
            put("y", result.normalizedY.toDouble())
            put("confidence", result.confidence)
            put("savedAt", System.currentTimeMillis())
        }
        val out = JSONArray().put(item)
        for (i in 0 until minOf(existing.length(), MAX_ITEMS - 1)) out.put(existing.optJSONObject(i))
        context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_ITEMS, out.toString()).apply()
    }

    private fun loadRaw(context: Context): JSONArray = try {
        JSONArray(context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).getString(KEY_ITEMS, "[]"))
    } catch (_: Exception) {
        JSONArray()
    }

    private fun normalize(source: Bitmap): Bitmap {
        val maxSide = 192
        val scale = minOf(1f, maxSide.toFloat() / max(source.width, source.height))
        if (scale >= 1f) return source
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun borderColor(bitmap: Bitmap): Int {
        val colors = ArrayList<Int>()
        val stepX = (bitmap.width / 20).coerceAtLeast(1)
        val stepY = (bitmap.height / 20).coerceAtLeast(1)
        for (x in 0 until bitmap.width step stepX) {
            colors += bitmap.getPixel(x, 0)
            colors += bitmap.getPixel(x, bitmap.height - 1)
        }
        for (y in 0 until bitmap.height step stepY) {
            colors += bitmap.getPixel(0, y)
            colors += bitmap.getPixel(bitmap.width - 1, y)
        }
        return medianColor(colors)
    }

    private fun medianColor(colors: List<Int>): Int {
        if (colors.isEmpty()) return Color.WHITE
        fun channel(selector: (Int) -> Int): Int = colors.map(selector).sorted()[colors.size / 2]
        return Color.rgb(channel(Color::red), channel(Color::green), channel(Color::blue))
    }

    private fun colorDistance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun matchFont(
        text: String,
        target: BooleanArray,
        width: Int,
        height: Int,
        fonts: List<FontItem>,
        estimatedSize: Float
    ): Pair<FontItem, Boolean> {
        val candidates = fonts.ifEmpty { listOf(FontItem("Default", Typeface.DEFAULT)) }
        var best = candidates.first() to false
        var bestScore = Long.MAX_VALUE
        for (font in candidates.take(80)) {
            for (bold in listOf(false, true)) {
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textAlign = Paint.Align.CENTER
                    textSize = estimatedSize.coerceIn(10f, height * 0.85f)
                    typeface = Typeface.create(font.typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
                }
                val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
                canvas.drawText(text.take(48), width / 2f, baseline, paint)
                val rendered = IntArray(width * height)
                bmp.getPixels(rendered, 0, width, 0, 0, width, height)
                var mismatch = 0L
                var renderedInk = 0
                for (i in rendered.indices) {
                    val on = Color.alpha(rendered[i]) > 48
                    if (on) renderedInk++
                    if (on != target[i]) mismatch++
                }
                mismatch += abs(renderedInk - target.count { it }).toLong() / 2L
                bmp.recycle()
                if (mismatch < bestScore) {
                    bestScore = mismatch
                    best = font to bold
                }
            }
        }
        return best
    }

    private fun estimateOutline(
        pixels: IntArray,
        ink: BooleanArray,
        width: Int,
        height: Int,
        background: Int,
        foreground: Int
    ): Pair<Float, Int> {
        val ring = ArrayList<Int>()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            if (ink[i]) continue
            val nearInk = ink[i - 1] || ink[i + 1] || ink[i - width] || ink[i + width]
            if (nearInk && colorDistance(pixels[i], background) > 24 && colorDistance(pixels[i], foreground) > 24) {
                ring += pixels[i]
            }
        }
        if (ring.size < ink.count { it } / 20) return 0f to Color.BLACK
        return 2f to medianColor(ring)
    }
}
