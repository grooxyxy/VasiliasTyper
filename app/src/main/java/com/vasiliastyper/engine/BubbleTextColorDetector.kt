package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Detects the dominant speech-bubble color and returns the WCAG-compliant black
 * or white text color with the highest contrast. The dominant-color histogram is
 * intentionally robust against the old glyphs that may still be present when OCR
 * or Script placement starts.
 */
object BubbleTextColorDetector {

    data class Analysis(
        val backgroundColor: Int,
        val textColor: Int,
        val contrastRatio: Double,
        val confidence: Float
    )

    fun analyze(bitmap: Bitmap, requestedArea: RectF): Analysis? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0 || requestedArea.isEmpty) {
            return null
        }

        val left = requestedArea.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = requestedArea.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = kotlin.math.ceil(requestedArea.right.toDouble()).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = kotlin.math.ceil(requestedArea.bottom.toDouble()).toInt().coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return null

        // Avoid outlines/panel borders while retaining enough of a narrow bubble.
        val insetX = min((width * 0.08f).toInt(), max(0, width / 4))
        val insetY = min((height * 0.08f).toInt(), max(0, height / 4))
        val sampleLeft = (left + insetX).coerceAtMost(right - 1)
        val sampleTop = (top + insetY).coerceAtMost(bottom - 1)
        val sampleRight = (right - insetX).coerceAtLeast(sampleLeft + 1)
        val sampleBottom = (bottom - insetY).coerceAtLeast(sampleTop + 1)

        val sampleArea = (sampleRight - sampleLeft).toLong() * (sampleBottom - sampleTop).toLong()
        val step = kotlin.math.sqrt((sampleArea / 2500.0).coerceAtLeast(1.0)).toInt().coerceAtLeast(1)
        val buckets = IntArray(4096)
        var total = 0

        for (y in sampleTop until sampleBottom step step) {
            for (x in sampleLeft until sampleRight step step) {
                val pixel = compositeOnWhite(bitmap.getPixel(x, y))
                val bucket = ((Color.red(pixel) shr 4) shl 8) or
                    ((Color.green(pixel) shr 4) shl 4) or
                    (Color.blue(pixel) shr 4)
                buckets[bucket]++
                total++
            }
        }
        if (total == 0) return null

        val dominantBucket = buckets.indices.maxByOrNull { buckets[it] } ?: return null
        val dominantCount = buckets[dominantBucket]
        var red = 0L
        var green = 0L
        var blue = 0L
        var selected = 0

        // Average the winning quantized cluster to recover the actual bubble color.
        for (y in sampleTop until sampleBottom step step) {
            for (x in sampleLeft until sampleRight step step) {
                val pixel = compositeOnWhite(bitmap.getPixel(x, y))
                val bucket = ((Color.red(pixel) shr 4) shl 8) or
                    ((Color.green(pixel) shr 4) shl 4) or
                    (Color.blue(pixel) shr 4)
                if (bucket == dominantBucket) {
                    red += Color.red(pixel)
                    green += Color.green(pixel)
                    blue += Color.blue(pixel)
                    selected++
                }
            }
        }
        if (selected == 0) return null

        val background = Color.rgb(
            (red / selected).toInt().coerceIn(0, 255),
            (green / selected).toInt().coerceIn(0, 255),
            (blue / selected).toInt().coerceIn(0, 255)
        )
        val blackContrast = contrastRatio(background, Color.BLACK)
        val whiteContrast = contrastRatio(background, Color.WHITE)
        val textColor = if (blackContrast >= whiteContrast) Color.BLACK else Color.WHITE
        val ratio = max(blackContrast, whiteContrast)
        val dominance = dominantCount.toFloat() / total.toFloat()
        return Analysis(background, textColor, ratio, dominance.coerceIn(0f, 1f))
    }

    fun bestTextColor(bitmap: Bitmap, area: RectF, fallback: Int = Color.BLACK): Int =
        analyze(bitmap, area)?.textColor ?: fallback

    private fun compositeOnWhite(color: Int): Int {
        val alpha = Color.alpha(color)
        if (alpha >= 255) return color
        val inverse = 255 - alpha
        return Color.rgb(
            (Color.red(color) * alpha + 255 * inverse) / 255,
            (Color.green(color) * alpha + 255 * inverse) / 255,
            (Color.blue(color) * alpha + 255 * inverse) / 255
        )
    }

    private fun contrastRatio(first: Int, second: Int): Double {
        val light = max(relativeLuminance(first), relativeLuminance(second))
        val dark = min(relativeLuminance(first), relativeLuminance(second))
        return (light + 0.05) / (dark + 0.05)
    }

    private fun relativeLuminance(color: Int): Double {
        fun channel(value: Int): Double {
            val normalized = value / 255.0
            return if (normalized <= 0.03928) normalized / 12.92
            else Math.pow((normalized + 0.055) / 1.055, 2.4)
        }
        return channel(Color.red(color)) * 0.2126 +
            channel(Color.green(color)) * 0.7152 +
            channel(Color.blue(color)) * 0.0722
    }
}
