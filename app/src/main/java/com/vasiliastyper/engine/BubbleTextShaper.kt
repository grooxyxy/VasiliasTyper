package com.vasiliastyper.engine

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Automatic comic-bubble text shaper based on Kitsu Text Shaper's Psacs-kp v3
 * approach. It evaluates multiple line counts and target silhouettes to choose
 * a silhouette-aware line partition. Generated candidates preserve every word
 * and punctuation mark, adding only explicit line breaks so TextRenderer draws
 * ellipse, round, tall, wide, and diamond profiles as real text silhouettes.
 */
object BubbleTextShaper {

    const val MIN_WIDTH_SCALE = 0.48f
    const val MAX_WIDTH_SCALE = 1.00f

    enum class Profile(val label: String) {
        AUTO("Auto"),
        BALANCED("Ellipse"),
        ROUND("Round"),
        TALL("Tall"),
        WIDE("Wide"),
        HYPHENATION("Diamond")
    }

    data class Candidate(
        val profile: Profile,
        val text: String,
        val lineCount: Int,
        val fittedFontSize: Float,
        val score: Float,
        /** Fraction of the selected bubble width used by the shaped text box. */
        val widthScale: Float = 1f
    )

    private data class Layout(
        val lines: List<String>,
        val rawCost: Float,
        val maxWidth: Float
    )

    fun inferProfile(area: RectF): Profile {
        val ratio = area.width() / area.height().coerceAtLeast(1f)
        return when {
            ratio >= 1.65f -> Profile.WIDE
            ratio <= 0.72f -> Profile.TALL
            ratio in 0.82f..1.22f -> Profile.ROUND
            else -> Profile.BALANCED
        }
    }

    fun shapeBest(
        text: String,
        area: RectF,
        typeface: Typeface? = null,
        maxFontSize: Float = 72f,
        profile: Profile = Profile.AUTO
    ): Candidate = generate(text, area, typeface, maxFontSize, profile, 1).first()

    fun generate(
        text: String,
        area: RectF,
        typeface: Typeface? = null,
        maxFontSize: Float = 72f,
        profile: Profile = Profile.AUTO,
        limit: Int = 10
    ): List<Candidate> {
        val originalText = text
        val source = text.replace(Regex("\\s+"), " ").trim()
        val resolved = if (profile == Profile.AUTO) inferProfile(area) else profile
        if (source.isEmpty()) {
            return listOf(Candidate(resolved, originalText, 1, maxFontSize, 0f, 1f))
        }

        val words = source.split(' ').filter(String::isNotEmpty)
        if (words.size < 2) {
            val fitted = TextRenderer.autoFitFontSize(
                originalText,
                area.width().coerceAtLeast(1f),
                area.height().coerceAtLeast(1f),
                typeface,
                maxFontSize = maxFontSize.coerceAtLeast(8f),
                roundBubbleMode = resolved == Profile.ROUND
            )
            return listOf(Candidate(resolved, originalText, 1, fitted, fitted, 1f))
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = maxFontSize.coerceAtLeast(8f)
            this.typeface = typeface ?: Typeface.DEFAULT
        }
        val spaceWidth = paint.measureText(" ")
        val wordWidths = FloatArray(words.size) { paint.measureText(words[it]) }
        val maxLines = min(12, min(words.size, max(3, words.size / 2 + 1)))
        val targetAspect = targetAspect(resolved, area)
        val layouts = mutableListOf<Pair<Int, Layout>>()
        val minLines = if (resolved == Profile.HYPHENATION && words.size >= 3) 3 else 2

        for (lineCount in minLines..maxLines) {
            bestPartition(words, wordWidths, spaceWidth, lineCount, resolved)?.let {
                layouts += lineCount to it
            }
        }
        if (layouts.isEmpty()) {
            layouts += 1 to Layout(listOf(source), 0f, paint.measureText(source))
        }

        return layouts.map { (lineCount, layout) ->
            // Explicit breaks are essential: a rectangular maxWidth alone cannot
            // produce a diamond/ellipse silhouette. The partition preserves all
            // words and punctuation while controlling each centered line's width.
            val shapedText = layout.lines.joinToString("\n")
            val initialFitted = TextRenderer.autoFitFontSize(
                shapedText,
                area.width().coerceAtLeast(1f),
                area.height().coerceAtLeast(1f),
                typeface,
                maxFontSize = maxFontSize.coerceAtLeast(8f),
                roundBubbleMode = resolved == Profile.ROUND
            )
            val scale = initialFitted / paint.textSize
            val occupiedWidth = layout.maxWidth * scale
            val profileMinScale = when (resolved) {
                Profile.HYPHENATION -> 0.48f
                Profile.TALL -> 0.58f
                Profile.ROUND -> 0.68f
                Profile.BALANCED -> 0.72f
                Profile.WIDE -> 0.86f
                Profile.AUTO -> 0.72f
            }
            val naturalScale = (occupiedWidth / area.width().coerceAtLeast(1f))
                .coerceIn(profileMinScale, MAX_WIDTH_SCALE)
            val lineRange = (maxLines - 2).coerceAtLeast(1)
            val lineProgress = ((lineCount - 2).toFloat() / lineRange).coerceIn(0f, 1f)
            val profileScale = MAX_WIDTH_SCALE -
                (MAX_WIDTH_SCALE - profileMinScale) * lineProgress
            val widthScale = (profileScale * 0.55f + naturalScale * 0.45f)
                .coerceIn(profileMinScale, MAX_WIDTH_SCALE)
            val shapedWidth = area.width().coerceAtLeast(1f) * widthScale
            val fitted = TextRenderer.autoFitFontSize(
                shapedText,
                shapedWidth,
                area.height().coerceAtLeast(1f),
                typeface,
                maxFontSize = maxFontSize.coerceAtLeast(8f),
                roundBubbleMode = resolved == Profile.ROUND
            )
            val wrappedLineCount = TextRenderer.wrapLines(shapedText, Paint(paint).apply {
                textSize = fitted
            }, shapedWidth).size.coerceAtLeast(1)
            val blockAspect = (wrappedLineCount * fitted * 1.4f) /
                shapedWidth.coerceAtLeast(1f)
            val aspectPenalty = ln((blockAspect / targetAspect).coerceAtLeast(0.05f)).let { it * it }
            val silhouetteBonus = when {
                resolved == Profile.HYPHENATION && lineCount >= 3 -> 4f
                resolved == Profile.ROUND && lineCount >= 3 -> 2f
                else -> 0f
            }
            val score = fitted - layout.rawCost * 2.5f - aspectPenalty * 3f + silhouetteBonus
            Candidate(resolved, shapedText, wrappedLineCount, fitted, score, widthScale)
        }
            .distinctBy { Pair(it.lineCount, (it.widthScale * 100f).toInt()) }
            .sortedByDescending { it.score }
            .take(limit.coerceAtLeast(1))
    }

    /** Dynamic-programming word partition with Kitsu-style silhouette targets. */
    private fun bestPartition(
        words: List<String>,
        widths: FloatArray,
        spaceWidth: Float,
        lineCount: Int,
        profile: Profile
    ): Layout? {
        if (words.size < lineCount) return null
        val prefix = FloatArray(widths.size + 1)
        for (i in widths.indices) prefix[i + 1] = prefix[i] + widths[i]
        fun rangeWidth(from: Int, to: Int): Float =
            prefix[to] - prefix[from] + (to - from - 1).coerceAtLeast(0) * spaceWidth

        val totalWidth = rangeWidth(0, words.size)
        val shapeWeights = FloatArray(lineCount) { index ->
            val t = (2f * index + 1f) / lineCount - 1f
            val base = when (profile) {
                Profile.HYPHENATION -> 1f - abs(t)
                Profile.BALANCED, Profile.WIDE -> cos(Math.PI.toFloat() * t / 2f)
                else -> sqrt(max(0f, 1f - t * t))
            }
            max(base.pow(0.6f), 0.15f)
        }
        if (profile == Profile.WIDE) {
            for (i in shapeWeights.indices) shapeWeights[i] = max(shapeWeights[i], 0.72f)
        } else if (profile == Profile.TALL) {
            for (i in shapeWeights.indices) shapeWeights[i] = shapeWeights[i].pow(1.18f)
        }
        val weightSum = shapeWeights.sum().coerceAtLeast(0.01f)
        val targets = FloatArray(lineCount) { totalWidth * shapeWeights[it] / weightSum }
        val peakTarget = targets.maxOrNull()?.coerceAtLeast(spaceWidth) ?: spaceWidth
        val maxAllowed = peakTarget * 1.20f
        val infinity = Float.POSITIVE_INFINITY
        val dp = Array(lineCount + 1) { FloatArray(words.size + 1) { infinity } }
        val previous = Array(lineCount + 1) { IntArray(words.size + 1) { -1 } }
        dp[0][0] = 0f

        for (line in 1..lineCount) {
            val minEnd = line
            val maxEnd = words.size - (lineCount - line)
            for (end in minEnd..maxEnd) {
                for (start in (line - 1) until end) {
                    val oldCost = dp[line - 1][start]
                    if (!oldCost.isFinite()) continue
                    val width = rangeWidth(start, end)
                    if (width > maxAllowed && end - start > 1) continue
                    val target = targets[line - 1].coerceAtLeast(1f)
                    val deviation = (width - target) / target
                    var cost = oldCost + deviation * deviation

                    // Prefer punctuation at a line end, exactly as the Kitsu model.
                    val endsWithPunctuation = words[end - 1].lastOrNull()?.let {
                        it in ".,!?;:…\"”’)]"
                    } == true
                    if (end < words.size && endsWithPunctuation) {
                        cost -= 0.02f
                    }
                    // Avoid tiny edge fragments and single-word middle lines.
                    if ((line == 1 || line == lineCount) && words.subList(start, end).sumOf { it.length } < 3) {
                        cost += 2f
                    }
                    if (line in 2 until lineCount && end - start == 1) cost += 0.18f

                    if (cost < dp[line][end]) {
                        dp[line][end] = cost
                        previous[line][end] = start
                    }
                }
            }
        }

        if (!dp[lineCount][words.size].isFinite()) return null
        val lines = ArrayDeque<String>()
        var end = words.size
        for (line in lineCount downTo 1) {
            val start = previous[line][end]
            if (start < 0) return null
            lines.addFirst(words.subList(start, end).joinToString(" "))
            end = start
        }
        val maxWidth = lines.maxOf { line ->
            val parts = line.split(' ')
            parts.sumOf { part -> widths[words.indexOf(part)].toDouble() }.toFloat() +
                (parts.size - 1).coerceAtLeast(0) * spaceWidth
        }
        return Layout(lines.toList(), dp[lineCount][words.size] / lineCount, maxWidth)
    }

    private fun targetAspect(profile: Profile, area: RectF): Float = when (profile) {
        Profile.TALL -> 1.35f
        Profile.WIDE -> 0.55f
        Profile.ROUND -> 0.95f
        Profile.HYPHENATION -> 1f
        else -> (area.height() / area.width().coerceAtLeast(1f)).coerceIn(0.55f, 1.35f)
    }
}
