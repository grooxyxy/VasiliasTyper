package com.vasiliastyper.engine

import android.graphics.RectF
import java.text.Normalizer
import kotlin.math.ceil
import kotlin.math.max

/**
 * Matches noisy OCR regions to the OCR side of an imported OCR/TRANSLATE script.
 *
 * Matching is intentionally tolerant for ML Kit: punctuation, whitespace and
 * common OCR substitutions are normalized before a weighted edit/ngram score is
 * calculated. Candidate matches are assigned globally by confidence, so the
 * source script does not have to follow the exact OCR region order on the page.
 */
object ScriptOcrMatcher {

    /**
     * [rect] is the exact OCR glyph/line box used for matching and inpainting.
     * [placementRect] may be a containing speech bubble and is used only to
     * position/shape translated text. Keeping both prevents bubble borders from
     * being erased when the OCR box is snapped to a larger bubble.
     */
    data class OcrRegion(
        val rect: RectF,
        val text: String,
        val placementRect: RectF = RectF(rect)
    )

    data class Match(
        val scriptIndex: Int,
        val sourceText: String,
        val translationText: String,
        val detectedText: String,
        /** Placement area, normally the containing bubble. */
        val rect: RectF,
        /** Exact OCR area that must be cleaned/inpainted. */
        val sourceRect: RectF,
        val confidence: Float
    )

    fun match(
        scriptLines: List<ScriptLine>,
        detectedRegions: List<OcrRegion>,
        minimumScore: Float = 0.68f
    ): List<Match> {
        val scripts = scriptLines.withIndex()
            .filter { !it.value.sourceText.isNullOrBlank() && !it.value.used }
        if (scripts.isEmpty() || detectedRegions.isEmpty()) return emptyList()

        val regions = detectedRegions
            .filter { it.text.isNotBlank() && it.rect.width() > 0f && it.rect.height() > 0f }
            .sortedWith(compareBy<OcrRegion> { it.rect.top }.thenBy { it.rect.left })
        if (regions.isEmpty()) return emptyList()

        data class Candidate(
            val scriptIndex: Int,
            val line: ScriptLine,
            val start: Int,
            val end: Int,
            val detectedText: String,
            val rect: RectF,
            val sourceRect: RectF,
            val score: Float
        )

        val candidates = mutableListOf<Candidate>()
        for ((scriptIndex, line) in scripts) {
            val source = line.sourceText.orEmpty()
            val normalizedLength = normalize(source).length
            val requiredScore = when {
                normalizedLength <= 2 -> max(minimumScore, 0.94f)
                normalizedLength <= 4 -> max(minimumScore, 0.86f)
                normalizedLength <= 7 -> max(minimumScore, 0.78f)
                else -> max(minimumScore, 0.68f)
            }

            // OCR sering memecah satu kalimat menjadi beberapa baris visual. Uji
            // gabungan hingga delapan region bertetangga. Kandidat tetap wajib
            // meliputi mayoritas kalimat, sehingga satu kata yang kebetulan sama
            // tidak pernah dianggap sebagai pasangan yang valid.
            for (regionIndex in regions.indices) {
                val joined = StringBuilder()
                val sourceUnion = RectF(regions[regionIndex].rect)
                val placementUnion = RectF(regions[regionIndex].placementRect)
                val maxEnd = minOf(regions.lastIndex, regionIndex + 7)
                for (endIndex in regionIndex..maxEnd) {
                    val current = regions[endIndex]
                    if (endIndex > regionIndex && !isNearby(sourceUnion, current.rect)) break
                    if (joined.isNotEmpty()) joined.append(' ')
                    joined.append(current.text)
                    if (endIndex > regionIndex) {
                        sourceUnion.union(current.rect)
                        placementUnion.union(current.placementRect)
                    }
                    val detectedText = joined.toString()
                    val score = similarity(source, detectedText)
                    if (score >= requiredScore && isWholeSentenceMatch(source, detectedText)) {
                        candidates += Candidate(
                            scriptIndex = scriptIndex,
                            line = line,
                            start = regionIndex,
                            end = endIndex,
                            detectedText = detectedText,
                            rect = RectF(placementUnion),
                            sourceRect = RectF(sourceUnion),
                            score = score
                        )
                    }
                }
            }
        }

        // Pilih kecocokan paling meyakinkan secara global. Ini lebih aman daripada
        // memaksa urutan script == urutan region dan tetap mencegah region ganda.
        val usedScripts = mutableSetOf<Int>()
        val usedRegions = mutableSetOf<Int>()
        val matches = mutableListOf<Match>()
        for (candidate in candidates.sortedWith(
            compareByDescending<Candidate> { it.score }
                .thenBy { it.end - it.start }
                .thenBy { it.scriptIndex }
        )) {
            if (candidate.scriptIndex in usedScripts) continue
            if ((candidate.start..candidate.end).any { it in usedRegions }) continue
            usedScripts += candidate.scriptIndex
            usedRegions += candidate.start..candidate.end
            matches += Match(
                scriptIndex = candidate.scriptIndex,
                sourceText = candidate.line.sourceText.orEmpty(),
                translationText = candidate.line.text,
                detectedText = candidate.detectedText,
                rect = candidate.rect,
                sourceRect = candidate.sourceRect,
                confidence = candidate.score
            )
        }
        return matches.sortedBy { it.scriptIndex }
    }

    /**
     * Last-resort matcher for pages where OCR found valid boxes but decoded the
     * characters too poorly for similarity matching (common on stylised CJK text).
     * It partitions OCR boxes in reading order and pairs them positionally with
     * the structured script. The caller must use this only after both normal and
     * conservative matching return no result.
     */
    fun matchByPosition(
        scriptLines: List<ScriptLine>,
        detectedRegions: List<OcrRegion>
    ): List<Match> {
        val scripts = scriptLines.withIndex()
            .filter { !it.value.sourceText.isNullOrBlank() && !it.value.used }
        val regions = detectedRegions
            .filter { it.text.isNotBlank() && it.rect.width() > 0f && it.rect.height() > 0f }
            .sortedWith(compareBy<OcrRegion> { it.rect.top }.thenBy { it.rect.left })
        if (scripts.isEmpty() || regions.isEmpty()) return emptyList()

        // Avoid destructive guesses when OCR count is implausibly different from
        // the imported script. Up to eight visual OCR fragments per sentence is
        // supported, matching the grouping limit used by the confidence matcher.
        if (regions.size > scripts.size * 8) return emptyList()

        val pairCount = minOf(scripts.size, regions.size)
        val matches = mutableListOf<Match>()
        for (pairIndex in 0 until pairCount) {
            val (scriptIndex, line) = scripts[pairIndex]
            val start = (pairIndex * regions.size) / pairCount
            val endExclusive = ((pairIndex + 1) * regions.size) / pairCount
            val group = regions.subList(start, endExclusive.coerceAtLeast(start + 1))
            val sourceRect = RectF(group.first().rect)
            val placementRect = RectF(group.first().placementRect)
            group.drop(1).forEach { region ->
                sourceRect.union(region.rect)
                placementRect.union(region.placementRect)
            }
            val detectedText = group.joinToString(" ") { it.text }
            matches += Match(
                scriptIndex = scriptIndex,
                sourceText = line.sourceText.orEmpty(),
                translationText = line.text,
                detectedText = detectedText,
                rect = placementRect,
                sourceRect = sourceRect,
                confidence = similarity(line.sourceText.orEmpty(), detectedText)
            )
        }
        return matches
    }

    /**
     * Conservative fallback for pages whose OCR is too noisy for the normal
     * confidence gate. It preserves script and page reading order, then chooses
     * the best contiguous OCR group for each source line. A minimum character
     * coverage is still required so unrelated regions are never replaced merely
     * because they occupy the same ordinal position.
     */
    fun matchByReadingOrder(
        scriptLines: List<ScriptLine>,
        detectedRegions: List<OcrRegion>
    ): List<Match> {
        val scripts = scriptLines.withIndex()
            .filter { !it.value.sourceText.isNullOrBlank() && !it.value.used }
        val regions = detectedRegions
            .filter { it.text.isNotBlank() && it.rect.width() > 0f && it.rect.height() > 0f }
            .sortedWith(compareBy<OcrRegion> { it.rect.top }.thenBy { it.rect.left })
        if (scripts.isEmpty() || regions.isEmpty()) return emptyList()

        val result = mutableListOf<Match>()
        var regionCursor = 0
        for ((scriptIndex, line) in scripts) {
            if (regionCursor >= regions.size) break
            val source = line.sourceText.orEmpty()
            var bestEnd = -1
            var bestText = ""
            var bestRect: RectF? = null
            var bestSourceRect: RectF? = null
            var bestScore = 0f
            val searchEnd = minOf(regions.lastIndex, regionCursor + 11)

            for (start in regionCursor..searchEnd) {
                val joined = StringBuilder()
                val sourceUnion = RectF(regions[start].rect)
                val placementUnion = RectF(regions[start].placementRect)
                val groupEnd = minOf(regions.lastIndex, start + 7)
                for (end in start..groupEnd) {
                    val current = regions[end]
                    if (end > start && !isNearby(sourceUnion, current.rect)) break
                    if (joined.isNotEmpty()) joined.append(' ')
                    joined.append(current.text)
                    if (end > start) {
                        sourceUnion.union(current.rect)
                        placementUnion.union(current.placementRect)
                    }
                    val candidateText = joined.toString()
                    val score = similarity(source, candidateText)
                    val sourceLength = normalize(source).length.coerceAtLeast(1)
                    val candidateLength = normalize(candidateText).length.coerceAtLeast(1)
                    val coverage = minOf(sourceLength, candidateLength).toFloat() /
                        max(sourceLength, candidateLength).toFloat()
                    val accepted = coverage >= 0.42f && score >= 0.30f
                    if (accepted && score > bestScore) {
                        bestScore = score
                        bestEnd = end
                        bestText = candidateText
                        bestRect = RectF(placementUnion)
                        bestSourceRect = RectF(sourceUnion)
                    }
                }
                if (bestEnd >= start) break
            }

            val rect = bestRect ?: continue
            result += Match(
                scriptIndex = scriptIndex,
                sourceText = source,
                translationText = line.text,
                detectedText = bestText,
                rect = rect,
                sourceRect = bestSourceRect ?: RectF(rect),
                confidence = bestScore
            )
            regionCursor = bestEnd + 1
        }
        return result
    }

    internal fun similarity(expected: String, actual: String): Float {
        val a = normalize(expected)
        val b = normalize(actual)
        if (a.isEmpty() || b.isEmpty()) return 0f
        if (a == b) return 1f
        if (a.contains(b) || b.contains(a)) {
            val coverage = minOf(a.length, b.length).toFloat() / max(a.length, b.length).toFloat()
            // Substring hanya mendapat bonus jika hampir seluruh kalimat tercakup.
            // Ini mencegah satu kata identik mendapat skor tinggi terhadap kalimat panjang.
            if (coverage >= MIN_SENTENCE_COVERAGE) {
                return (0.72f + coverage * 0.24f).coerceAtMost(0.97f)
            }
        }

        val edit = 1f - levenshtein(a, b).toFloat() / max(a.length, b.length).toFloat()
        val ngram = diceCoefficient(a, b)
        return (edit * 0.68f + ngram * 0.32f).coerceIn(0f, 1f)
    }

    /**
     * Validasi cakupan kalimat sebelum kandidat boleh ditempatkan.
     *
     * - Panjang OCR harus mencakup setidaknya 68% panjang kalimat sumber.
     * - Untuk kalimat multi-kata, sedikitnya 60% kata (minimal dua kata) harus
     *   ditemukan. Perbandingan kata toleran terhadap satu kesalahan OCR kecil.
     * - Aksara tanpa spasi (CJK) tetap divalidasi melalui cakupan karakter.
     */
    internal fun isWholeSentenceMatch(expected: String, actual: String): Boolean {
        val normalizedExpected = normalize(expected)
        val normalizedActual = normalize(actual)
        if (normalizedExpected.isEmpty() || normalizedActual.isEmpty()) return false

        val lengthCoverage = minOf(normalizedExpected.length, normalizedActual.length).toFloat() /
            max(normalizedExpected.length, normalizedActual.length).toFloat()
        if (lengthCoverage < MIN_SENTENCE_COVERAGE) return false

        val expectedWords = words(expected)
        if (expectedWords.size < 2) return true
        val actualWords = words(actual)
        if (actualWords.size < 2) return false

        val remaining = actualWords.toMutableList()
        var matchedWords = 0
        for (expectedWord in expectedWords) {
            val index = remaining.indexOfFirst { actualWord -> wordsEquivalent(expectedWord, actualWord) }
            if (index >= 0) {
                matchedWords++
                remaining.removeAt(index)
            }
        }
        val requiredWords = max(2, ceil(expectedWords.size * MIN_WORD_COVERAGE).toInt())
        return matchedWords >= requiredWords
    }

    private fun words(value: String): List<String> = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map(::normalize)
        .filter { it.isNotEmpty() }

    private fun wordsEquivalent(expected: String, actual: String): Boolean {
        if (expected == actual) return true
        val longest = max(expected.length, actual.length)
        if (longest < 4) return false
        val allowedErrors = if (longest >= 8) 2 else 1
        return levenshtein(expected, actual) <= allowedErrors
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace('０', '0')
        .replace('１', '1')
        .replace('５', '5')
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")
        .replace('丨', '一')
        .replace('曰', '日')
        .trim()

    private const val MIN_SENTENCE_COVERAGE = 0.68f
    private const val MIN_WORD_COVERAGE = 0.60f

    private fun isNearby(union: RectF, next: RectF): Boolean {
        val verticalGap = next.top - union.bottom
        val lineHeight = max(union.height(), next.height()).coerceAtLeast(1f)
        if (verticalGap > lineHeight * 2.5f) return false
        val horizontalOverlap = minOf(union.right, next.right) - maxOf(union.left, next.left)
        val sameColumn = horizontalOverlap >= -lineHeight * 1.5f
        return verticalGap >= -lineHeight * 1.5f && sameColumn
    }

    private fun diceCoefficient(a: String, b: String): Float {
        val size = if (minOf(a.length, b.length) < 4) 1 else 2
        val left = ngrams(a, size)
        val right = ngrams(b, size).toMutableMap()
        if (left.isEmpty() || right.isEmpty()) return 0f
        var intersection = 0
        for ((token, count) in left) {
            val shared = minOf(count, right[token] ?: 0)
            intersection += shared
            if (shared > 0) right[token] = (right[token] ?: 0) - shared
        }
        val leftCount = left.values.sum()
        val rightCount = ngrams(b, size).values.sum()
        return (2f * intersection) / (leftCount + rightCount).coerceAtLeast(1)
    }

    private fun ngrams(value: String, size: Int): Map<String, Int> {
        if (value.length < size) return mapOf(value to 1)
        val out = mutableMapOf<String, Int>()
        for (index in 0..value.length - size) {
            val token = value.substring(index, index + size)
            out[token] = (out[token] ?: 0) + 1
        }
        return out
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                val substitution = previous[j] + if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, substitution)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }
}
