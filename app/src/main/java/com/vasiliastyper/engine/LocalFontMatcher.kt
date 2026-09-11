package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.vasiliastyper.adapter.FontItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline hybrid font matcher.
 *
 * Each font-bank candidate is rendered into a normalized probe bitmap and
 * compared with the canvas reference through a compact visual embedding:
 * ink coverage, edge density, aspect, row/column projections, transitions,
 * centre of mass and slant covariance. This is substantially more reliable
 * than ranking by font name, requires no network/API key, and works for custom
 * TTF/OTF fonts because it evaluates their actual glyph rendering.
 */
object LocalFontMatcher {

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

    private data class VisualEmbedding(
        val inkRatio: Float,
        val edgeDensity: Float,
        val aspect: Float,
        val centerX: Float,
        val centerY: Float,
        val slant: Float,
        val transitionsX: Float,
        val transitionsY: Float,
        val rowProjection: FloatArray,
        val columnProjection: FloatArray
    )

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val darkPixels: Int,
        val lightPixels: Int,
        val chromaticPixels: Int,
        val usedPixels: Int
    )

    suspend fun analyze(reference: Bitmap, bank: List<FontItem>): Analysis =
        withContext(Dispatchers.Default) {
            localAnalysis(reference, bank)
        }

    private fun localAnalysis(bitmap: Bitmap, bank: List<FontItem>): Analysis {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return emptyAnalysis(bank, "Referensi bitmap tidak valid; urutan font bank dipertahankan.")
        }

        val normalized = normalize(bitmap, 224)
        return try {
            val referenceEmbedding = extractEmbedding(normalized)
            val palette = extractPalette(normalized)
            val ranked = bank.asSequence()
                .mapIndexed { index, font ->
                    val probe = renderFontProbe(font, 224, 112)
                    val visualDistance = try {
                        embeddingDistance(referenceEmbedding, extractEmbedding(probe))
                    } finally {
                        probe.recycle()
                    }
                    val semanticPrior = semanticPrior(font, referenceEmbedding)
                    Triple(font, visualDistance - semanticPrior, index)
                }
                .sortedWith(compareBy<Triple<FontItem, Float, Int>> { it.second }.thenBy { it.third })
                .map { it.first }
                .take(16)
                .toList()

            val gradient = palette.chromaticPixels > palette.usedPixels / 7 &&
                colorDistance(palette.primary, palette.secondary) > 42f
            val highContrast = palette.darkPixels > palette.usedPixels / 14 &&
                palette.lightPixels > palette.usedPixels / 14
            val likelyShadow = referenceEmbedding.transitionsX > 0.18f &&
                referenceEmbedding.edgeDensity > 0.12f && palette.darkPixels > palette.usedPixels / 8
            val shape = when {
                abs(referenceEmbedding.slant) > 0.20f -> "ITALIC / SLANTED"
                referenceEmbedding.aspect > 4.6f -> "CONDENSED LINE"
                referenceEmbedding.aspect < 1.15f -> "STACKED / COMPACT"
                else -> "NORMAL"
            }

            Analysis(
                rankedFonts = ranked,
                fillMode = if (gradient) "GRADIENT" else "SOLID",
                fillStartColor = palette.primary,
                fillEndColor = if (gradient) palette.secondary else palette.primary,
                outlineEnabled = highContrast,
                outlineColor = if (Color.luminance(palette.primary) > 0.5f) Color.BLACK else Color.WHITE,
                outlineWidth = estimateOutlineWidth(referenceEmbedding),
                shadowEnabled = likelyShadow,
                shadowColor = if (Color.luminance(palette.primary) > 0.55f) Color.BLACK else Color.DKGRAY,
                shadowDx = 3f,
                shadowDy = 3f,
                shadowRadius = 4f,
                textShape = shape,
                source = "Hybrid Visual Font Matcher v2",
                note = "16 kandidat diranking dari glyph asli tiap font memakai embedding bentuk, proyeksi, edge, ketebalan, dan slant; seluruh proses offline."
            )
        } catch (t: Throwable) {
            emptyAnalysis(bank, "Analisis visual fallback: ${t.message ?: t.javaClass.simpleName}")
        } finally {
            if (normalized !== bitmap && !normalized.isRecycled) normalized.recycle()
        }
    }

    private fun normalize(bitmap: Bitmap, maxSide: Int): Bitmap {
        val scale = (maxSide.toFloat() / max(bitmap.width, bitmap.height)).coerceAtMost(1f)
        if (scale >= 0.999f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun renderFontProbe(font: FontItem, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = font.typeface
            textSize = height * 0.42f
        }
        val probeText = "Hamburge 123"
        val bounds = Rect()
        paint.getTextBounds(probeText, 0, probeText.length, bounds)
        val measured = paint.measureText(probeText).coerceAtLeast(1f)
        if (measured > width * 0.92f) paint.textSize *= (width * 0.92f / measured)
        paint.getTextBounds(probeText, 0, probeText.length, bounds)
        val x = ((width - paint.measureText(probeText)) / 2f).coerceAtLeast(2f)
        val y = height / 2f - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(probeText, x, y, paint)
        return bitmap
    }

    private fun extractEmbedding(bitmap: Bitmap): VisualEmbedding {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = IntArray(pixels.size)
        var luminanceSum = 0L
        for (i in pixels.indices) {
            val color = pixels[i]
            val value = if (Color.alpha(color) < 24) 255 else
                (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            gray[i] = value
            luminanceSum += value
        }
        val mean = (luminanceSum / gray.size.coerceAtLeast(1)).toInt()
        val darkForeground = mean >= 128
        val threshold = if (darkForeground) min(210, mean - 12) else max(45, mean + 12)
        fun isInk(index: Int): Boolean = if (darkForeground) gray[index] < threshold else gray[index] > threshold

        val rows = FloatArray(16)
        val columns = FloatArray(16)
        var ink = 0
        var edges = 0
        var transitionsX = 0
        var transitionsY = 0
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        var sumX = 0.0
        var sumY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var sumXY = 0.0

        for (y in 0 until height) {
            var previousInk = false
            for (x in 0 until width) {
                val index = y * width + x
                val currentInk = isInk(index)
                if (x > 0 && currentInk != previousInk) transitionsX++
                previousInk = currentInk
                if (!currentInk) continue
                ink++
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                sumX += x
                sumY += y
                sumXX += x.toDouble() * x
                sumYY += y.toDouble() * y
                sumXY += x.toDouble() * y
                rows[(y * rows.size / height).coerceIn(rows.indices)]++
                columns[(x * columns.size / width).coerceIn(columns.indices)]++
                if (x + 1 < width && !isInk(index + 1)) edges++
                if (y + 1 < height && !isInk(index + width)) edges++
            }
        }
        for (x in 0 until width) {
            var previousInk = false
            for (y in 0 until height) {
                val currentInk = isInk(y * width + x)
                if (y > 0 && currentInk != previousInk) transitionsY++
                previousInk = currentInk
            }
        }

        val safeInk = ink.coerceAtLeast(1)
        val centerX = (sumX / safeInk / width).toFloat()
        val centerY = (sumY / safeInk / height).toFloat()
        val varianceX = (sumXX / safeInk - (sumX / safeInk) * (sumX / safeInk)).coerceAtLeast(1.0)
        val varianceY = (sumYY / safeInk - (sumY / safeInk) * (sumY / safeInk)).coerceAtLeast(1.0)
        val covariance = sumXY / safeInk - (sumX / safeInk) * (sumY / safeInk)
        val slant = (covariance / sqrt(varianceX * varianceY)).toFloat().coerceIn(-1f, 1f)
        val boxWidth = (maxX - minX + 1).coerceAtLeast(1)
        val boxHeight = (maxY - minY + 1).coerceAtLeast(1)
        normalizeProjection(rows)
        normalizeProjection(columns)

        return VisualEmbedding(
            inkRatio = ink.toFloat() / pixels.size.coerceAtLeast(1),
            edgeDensity = edges.toFloat() / safeInk,
            aspect = boxWidth.toFloat() / boxHeight,
            centerX = centerX,
            centerY = centerY,
            slant = slant,
            transitionsX = transitionsX.toFloat() / (width * height).coerceAtLeast(1),
            transitionsY = transitionsY.toFloat() / (width * height).coerceAtLeast(1),
            rowProjection = rows,
            columnProjection = columns
        )
    }

    private fun normalizeProjection(values: FloatArray) {
        val total = values.sum().coerceAtLeast(1f)
        for (i in values.indices) values[i] /= total
    }

    private fun embeddingDistance(a: VisualEmbedding, b: VisualEmbedding): Float {
        fun projectionDistance(x: FloatArray, y: FloatArray): Float =
            x.indices.sumOf { abs(x[it] - y[it]).toDouble() }.toFloat() / x.size
        return abs(a.inkRatio - b.inkRatio) * 2.4f +
            abs(a.edgeDensity - b.edgeDensity) * 1.4f +
            abs(a.aspect - b.aspect).coerceAtMost(5f) * 0.18f +
            abs(a.centerX - b.centerX) * 0.35f +
            abs(a.centerY - b.centerY) * 0.35f +
            abs(a.slant - b.slant) * 0.42f +
            abs(a.transitionsX - b.transitionsX) * 1.2f +
            abs(a.transitionsY - b.transitionsY) * 1.2f +
            projectionDistance(a.rowProjection, b.rowProjection) * 5.2f +
            projectionDistance(a.columnProjection, b.columnProjection) * 4.4f
    }

    private fun semanticPrior(font: FontItem, reference: VisualEmbedding): Float {
        val name = font.displayName.lowercase()
        var prior = 0f
        if (font.isComic) prior += 0.015f
        if (reference.inkRatio > 0.30f && listOf("bold", "black", "heavy", "impact").any(name::contains)) prior += 0.08f
        if (reference.aspect > 3.5f && listOf("condensed", "narrow", "manga").any(name::contains)) prior += 0.06f
        if (abs(reference.slant) > 0.20f && listOf("italic", "oblique", "script").any(name::contains)) prior += 0.06f
        return prior
    }

    private fun extractPalette(bitmap: Bitmap): Palette {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val buckets = linkedMapOf<Int, Int>()
        var dark = 0
        var light = 0
        var chromatic = 0
        var used = 0
        for (color in pixels) {
            if (Color.alpha(color) < 32) continue
            val r = Color.red(color)
            val g = Color.green(color)
            val b = Color.blue(color)
            val luminance = (r * 299 + g * 587 + b * 114) / 1000
            if (luminance < 72) dark++
            if (luminance > 205) light++
            if (max(r, max(g, b)) - min(r, min(g, b)) > 38) chromatic++
            if (luminance in 24..232) {
                val quantized = Color.rgb(r / 32 * 32, g / 32 * 32, b / 32 * 32)
                buckets[quantized] = (buckets[quantized] ?: 0) + 1
                used++
            }
        }
        val ranked = buckets.entries.sortedByDescending { it.value }.map { it.key }
        val primary = ranked.firstOrNull() ?: Color.WHITE
        val secondary = ranked.firstOrNull { colorDistance(primary, it) > 42f }
            ?: adjustBrightness(primary, if (Color.luminance(primary) > 0.5f) 0.68f else 1.28f)
        return Palette(primary, secondary, dark, light, chromatic, used.coerceAtLeast(1))
    }

    private fun estimateOutlineWidth(embedding: VisualEmbedding): Float =
        (2f + embedding.edgeDensity * 4f).coerceIn(2f, 7f)

    private fun colorDistance(a: Int, b: Int): Float {
        val dr = (Color.red(a) - Color.red(b)).toFloat()
        val dg = (Color.green(a) - Color.green(b)).toFloat()
        val db = (Color.blue(a) - Color.blue(b)).toFloat()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    private fun adjustBrightness(color: Int, factor: Float): Int = Color.rgb(
        (Color.red(color) * factor).toInt().coerceIn(0, 255),
        (Color.green(color) * factor).toInt().coerceIn(0, 255),
        (Color.blue(color) * factor).toInt().coerceIn(0, 255)
    )

    private fun emptyAnalysis(bank: List<FontItem>, note: String) = Analysis(
        rankedFonts = bank.take(16),
        fillMode = "SOLID",
        fillStartColor = Color.WHITE,
        fillEndColor = Color.WHITE,
        outlineEnabled = false,
        outlineColor = Color.BLACK,
        outlineWidth = 3f,
        shadowEnabled = false,
        shadowColor = Color.BLACK,
        shadowDx = 3f,
        shadowDy = 3f,
        shadowRadius = 4f,
        textShape = "NORMAL",
        source = "Hybrid Visual Font Matcher v2",
        note = note
    )
}
