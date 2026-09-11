package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.RectF
import com.vasiliastyper.model.BubbleDetection
import com.vasiliastyper.model.BubbleDetectionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Local, offline speech-bubble detector.
 *
 * Unlike the old implementation, tall webtoons are processed as overlapping
 * full-width stripes. A 800x20000 page therefore remains 800 px wide instead
 * of being globally reduced to roughly 56 px, which used to erase bubble
 * outlines and text evidence.
 *
 * Two complementary passes are fused:
 *  1. bright connected regions find borderless/soft white bubbles;
 *  2. closed dark contours find outlined bubbles on non-white backgrounds.
 *
 * No OCR or network model is required. The returned confidence is based on
 * contour shape, interior brightness, edge density, and ink inside the region.
 */
object BubbleDetector {

    private const val MIN_REGION_AREA = 420f
    private const val MAX_REGION_AREA_RATIO = 0.48f
    private const val DUPLICATE_IOU = 0.36f
    private const val DUPLICATE_INTERSECTION_OVER_SMALLER = 0.72f
    private const val MIN_CIRCULARITY = 0.12f
    private const val MIN_INTERIOR_LUMINANCE = 166f
    private const val MAX_INTERIOR_DARK_RATIO = 0.26f
    private const val MAX_INTERIOR_EDGE_DENSITY = 0.18f
    private const val MAX_DARK_INTERIOR_LUMINANCE = 92f
    private const val MIN_DARK_INTERIOR_RATIO = 0.58f
    private const val MIN_LIGHT_TEXT_RATIO = 0.004f

    /** Compatibility API used by the editor and mask post-processor. */
    fun detect(
        bitmap: Bitmap,
        config: BubbleDetectionConfig = BubbleDetectionConfig()
    ): List<RectF> = detectDetailed(bitmap, config).map { RectF(it.bounds) }

    fun detectDetailed(
        bitmap: Bitmap,
        config: BubbleDetectionConfig = BubbleDetectionConfig()
    ): List<BubbleDetection> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        if (!OpenCvInit.ensureInit()) return emptyList()

        val stripeHeight = min(bitmap.height, config.stripeHeightPx)
        val detections = mutableListOf<BubbleDetection>()

        for (top in StripeTiling.startPositions(bitmap.height, stripeHeight, config.stripeOverlapPx, 256)) {
            val bottom = min(bitmap.height, top + stripeHeight)
            val height = bottom - top
            if (height <= 0) continue

            val stripe = try {
                Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            } catch (_: Throwable) {
                continue
            }

            try {
                val local = detectStripe(stripe, config)
                val rejectTopEdge = top > 0
                val rejectBottomEdge = bottom < bitmap.height
                for (item in local) {
                    val r = item.bounds
                    // A contour cut by a stripe boundary is incomplete. The same
                    // bubble is seen whole in the overlapping neighbouring stripe.
                    if (rejectTopEdge && r.top <= 2f) continue
                    if (rejectBottomEdge && r.bottom >= height - 2f) continue
                    detections.add(
                        item.copy(
                            bounds = RectF(r.left, r.top + top, r.right, r.bottom + top)
                        )
                    )
                }
            } finally {
                if (!stripe.isRecycled) stripe.recycle()
            }
        }

        return mergeDetections(detections, bitmap.width, bitmap.height, config.minimumConfidence)
    }


    /**
     * Hybrid bubble detection without a learned YOLO model.
     *
     * OpenCV supplies real bubble-boundary proposals while ML Kit supplies text
     * evidence and separate text groups. A text block must be mostly inside a
     * contour, so captions/SFX that merely touch a balloon are not absorbed. The
     * grouped OCR evidence also splits a connected contour when two balloons touch.
     */
    suspend fun detectHybrid(
        bitmap: Bitmap,
        config: BubbleDetectionConfig = BubbleDetectionConfig(),
        lang: String = "auto"
    ): List<BubbleDetection> = withContext(Dispatchers.Default) {
        val mlKitRegions = withTimeoutOrNull(20_000L) {
            MlKitMaskDetector.detectSuspend(bitmap, lang)
        }.orEmpty()
        val textGroups = groupMlKitLines(mlKitRegions)
        val textGroupBounds = textGroups.map(::groupBounds)
        val contours = detectDetailed(
            bitmap,
            config.copy(minimumConfidence = min(config.minimumConfidence, 0.50f))
        )

        fun supportScore(bubble: RectF): Float {
            var best = 0f
            for (support in textGroupBounds) {
                val contained = intersectionArea(bubble, support) / area(support).coerceAtLeast(1f)
                if (contained < 0.68f) continue
                val centerInside = bubble.contains(support.centerX(), support.centerY())
                if (!centerInside) continue
                best = max(best, max(iou(bubble, support), 0.30f + contained * 0.20f))
            }
            return best.coerceIn(0f, 1f)
        }

        val contourProposals = contours.mapNotNull { item ->
            val support = supportScore(item.bounds)
            // When OCR succeeds, every accepted bubble needs a text group mostly
            // enclosed by its contour. Nearby text outside the balloon stays separate.
            if (textGroupBounds.isNotEmpty() && support < 0.30f) {
                null
            } else {
                item.copy(
                    confidence = (item.confidence * 0.84f + support * 0.38f).coerceIn(0f, 1f),
                    source = if (support > 0f) BubbleDetection.Source.HYBRID else item.source,
                    textPreview = textGroups
                        .filter { group -> item.bounds.contains(groupBounds(group).centerX(), groupBounds(group).centerY()) }
                        .flatten()
                        .joinToString(" ") { it.text }
                        .take(96)
                )
            }
        }

        val separatedProposals = splitProposalsByTextGroups(
            contourProposals,
            textGroupBounds,
            bitmap.width,
            bitmap.height
        )
        mergeDetections(
            separatedProposals,
            bitmap.width,
            bitmap.height,
            config.minimumConfidence,
            config.maximumDetections
        )
    }

    /**
     * ML Kit based "pseudo bubble" mode.
     *
     * ML Kit supplies text lines only. We first group lines conservatively, then
     * expand every group to a bubble-sized proposal. If the local contour pass
     * finds one bubble that contains this group (and no neighbouring group), its
     * real contour bounds win. This keeps nearby narration/caption text separate
     * in layouts where a caption sits directly below a speech bubble.
     */
    suspend fun detectMlKitPseudo(
        bitmap: Bitmap,
        config: BubbleDetectionConfig = BubbleDetectionConfig(),
        lang: String = "auto"
    ): List<BubbleDetection> = withContext(Dispatchers.Default) {
        val textLines = withTimeoutOrNull(20_000L) {
            MlKitMaskDetector.detectSuspend(bitmap, lang)
        }.orEmpty()
        if (textLines.isEmpty()) return@withContext emptyList()

        val groups = groupMlKitLines(textLines)
        val contours = detectDetailed(
            bitmap,
            config.copy(minimumConfidence = min(config.minimumConfidence, 0.52f))
        )
        val groupCenters = groups.map { groupBounds(it).let { b -> b.centerX() to b.centerY() } }

        val proposals = groups.mapIndexedNotNull { index, group ->
            val textBounds = groupBounds(group)
            if (textBounds.isEmpty) return@mapIndexedNotNull null

            val contour = contours
                .asSequence()
                .filter { candidate ->
                    val b = candidate.bounds
                    b.contains(textBounds.centerX(), textBounds.centerY()) &&
                        b.left <= textBounds.left && b.top <= textBounds.top &&
                        b.right >= textBounds.right && b.bottom >= textBounds.bottom &&
                        area(b) <= area(textBounds) * 16f &&
                        groupCenters.withIndex().none { (otherIndex, center) ->
                            otherIndex != index && b.contains(center.first, center.second)
                        }
                }
                .minByOrNull { area(it.bounds) }

            val bounds = contour?.bounds?.let(::RectF)
                ?: expandTextGroupToPseudoBubble(textBounds, group, bitmap.width, bitmap.height, config)
            if (bounds.width() <= 0f || bounds.height() <= 0f) return@mapIndexedNotNull null

            BubbleDetection(
                bounds = bounds,
                confidence = contour?.confidence?.coerceAtLeast(0.68f) ?: 0.68f,
                source = BubbleDetection.Source.ML_KIT,
                textPreview = group.joinToString(" ") { it.text }.take(96)
            )
        }

        dedupePseudoDetections(proposals)
            .sortedWith(compareBy<BubbleDetection> { it.bounds.top }.thenBy { it.bounds.left })
            .take(config.maximumDetections)
    }

    private fun groupMlKitLines(
        lines: List<MlKitMaskDetector.DetectedRegion>
    ): List<List<MlKitMaskDetector.DetectedRegion>> {
        val sorted = lines
            .filter { it.rect.width() > 0f && it.rect.height() > 0f }
            .sortedWith(compareBy<MlKitMaskDetector.DetectedRegion> { it.rect.top }.thenBy { it.rect.left })
        val groups = mutableListOf<MutableList<MlKitMaskDetector.DetectedRegion>>()

        for (line in sorted) {
            var bestGroup = -1
            var bestDistance = Float.MAX_VALUE
            for (index in groups.indices.reversed()) {
                val bounds = groupBounds(groups[index])
                val referenceHeight = min(bounds.height(), line.rect.height()).coerceAtLeast(1f)
                if (bounds.bottom < line.rect.top - referenceHeight * 2.2f) break

                val overlapX = max(0f, min(bounds.right, line.rect.right) - max(bounds.left, line.rect.left))
                val overlapY = max(0f, min(bounds.bottom, line.rect.bottom) - max(bounds.top, line.rect.top))
                val gapX = max(0f, max(bounds.left, line.rect.left) - min(bounds.right, line.rect.right))
                val gapY = max(0f, max(bounds.top, line.rect.top) - min(bounds.bottom, line.rect.bottom))
                val centerShift = kotlin.math.abs(bounds.centerX() - line.rect.centerX())

                val samePrintedLine = overlapY >= referenceHeight * 0.42f &&
                    gapX <= referenceHeight * 1.15f
                val nextPrintedLine = gapY <= referenceHeight * 1.10f &&
                    overlapX >= min(bounds.width(), line.rect.width()) * 0.28f &&
                    centerShift <= max(bounds.width(), line.rect.width()) * 0.36f
                if (!samePrintedLine && !nextPrintedLine) continue

                val distance = gapX + gapY + centerShift * 0.15f
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestGroup = index
                }
            }
            if (bestGroup >= 0) groups[bestGroup] += line else groups += mutableListOf(line)
        }
        return groups
    }

    private fun groupBounds(lines: List<MlKitMaskDetector.DetectedRegion>): RectF {
        if (lines.isEmpty()) return RectF()
        return RectF(lines.first().rect).also { bounds ->
            lines.drop(1).forEach { bounds.union(it.rect) }
        }
    }

    private fun expandTextGroupToPseudoBubble(
        textBounds: RectF,
        lines: List<MlKitMaskDetector.DetectedRegion>,
        canvasWidth: Int,
        canvasHeight: Int,
        config: BubbleDetectionConfig
    ): RectF {
        val averageLineHeight = lines.map { it.rect.height() }.average().toFloat().coerceAtLeast(1f)
        val ratio = config.mlKitBubblePaddingRatio
        val padX = max(textBounds.width() * ratio, averageLineHeight * 1.25f)
        val padY = max(textBounds.height() * ratio, averageLineHeight * 1.55f)
        return RectF(
            (textBounds.left - padX).coerceIn(0f, canvasWidth.toFloat()),
            (textBounds.top - padY).coerceIn(0f, canvasHeight.toFloat()),
            (textBounds.right + padX).coerceIn(0f, canvasWidth.toFloat()),
            (textBounds.bottom + padY).coerceIn(0f, canvasHeight.toFloat())
        )
    }

    private fun dedupePseudoDetections(input: List<BubbleDetection>): List<BubbleDetection> {
        val kept = mutableListOf<BubbleDetection>()
        for (candidate in input.sortedByDescending { it.confidence }) {
            val duplicate = kept.any { existing ->
                iou(existing.bounds, candidate.bounds) >= 0.78f ||
                    intersectionOverSmaller(existing.bounds, candidate.bounds) >= 0.90f
            }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    /**
     * Split one connected contour using already-grouped ML Kit text evidence.
     * The dominant separator may be vertical or horizontal, so touching bubbles
     * beside each other are handled as well as bubbles stacked on a webtoon page.
     */
    private fun splitProposalsByTextGroups(
        proposals: List<BubbleDetection>,
        textRects: List<RectF>,
        canvasWidth: Int,
        canvasHeight: Int
    ): List<BubbleDetection> {
        if (proposals.isEmpty() || textRects.isEmpty()) return proposals
        val output = mutableListOf<BubbleDetection>()

        for (proposal in proposals) {
            val inside = textRects.filter { text ->
                proposal.bounds.contains(text.centerX(), text.centerY()) &&
                    intersectionArea(proposal.bounds, text) / area(text).coerceAtLeast(1f) >= 0.68f
            }
            if (inside.size < 2) {
                output += proposal
                continue
            }

            val byX = inside.sortedBy { it.centerX() }
            val byY = inside.sortedBy { it.centerY() }
            val xGaps = byX.zipWithNext().map { (left, right) ->
                (right.left - left.right).coerceAtLeast(0f)
            }
            val yGaps = byY.zipWithNext().map { (top, bottom) ->
                (bottom.top - top.bottom).coerceAtLeast(0f)
            }
            val xScore = (xGaps.maxOrNull() ?: 0f) / proposal.bounds.width().coerceAtLeast(1f)
            val yScore = (yGaps.maxOrNull() ?: 0f) / proposal.bounds.height().coerceAtLeast(1f)
            val splitHorizontally = xScore > yScore
            val ordered = if (splitHorizontally) byX else byY
            val gaps = if (splitHorizontally) xGaps else yGaps
            val proposalSpan = if (splitHorizontally) proposal.bounds.width() else proposal.bounds.height()

            val allSeparatorsStrong = gaps.allIndexed { index, gap ->
                val first = ordered[index]
                val second = ordered[index + 1]
                val textReference = if (splitHorizontally) {
                    min(first.width(), second.width())
                } else {
                    min(first.height(), second.height())
                }.coerceAtLeast(1f)
                gap >= max(textReference * 0.55f, proposalSpan * 0.055f)
            }
            val centerSpread = if (splitHorizontally) {
                ordered.last().centerX() - ordered.first().centerX()
            } else {
                ordered.last().centerY() - ordered.first().centerY()
            }
            if (!allSeparatorsStrong || centerSpread < proposalSpan * 0.30f) {
                output += proposal
                continue
            }

            val boundaries = ordered.zipWithNext().map { (first, second) ->
                if (splitHorizontally) {
                    (first.right + second.left) * 0.5f
                } else {
                    (first.bottom + second.top) * 0.5f
                }
            }
            ordered.forEachIndexed { index, text ->
                val split = if (splitHorizontally) {
                    RectF(
                        boundaries.getOrNull(index - 1) ?: proposal.bounds.left,
                        proposal.bounds.top,
                        boundaries.getOrNull(index) ?: proposal.bounds.right,
                        proposal.bounds.bottom
                    )
                } else {
                    RectF(
                        proposal.bounds.left,
                        boundaries.getOrNull(index - 1) ?: proposal.bounds.top,
                        proposal.bounds.right,
                        boundaries.getOrNull(index) ?: proposal.bounds.bottom
                    )
                }
                split.intersect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
                if (split.width() > 0f && split.height() > 0f &&
                    split.contains(text.centerX(), text.centerY())
                ) {
                    output += proposal.copy(
                        bounds = split,
                        confidence = (proposal.confidence * 0.98f).coerceIn(0f, 1f)
                    )
                }
            }
        }
        return output
    }

    private inline fun <T> List<T>.allIndexed(predicate: (Int, T) -> Boolean): Boolean {
        for (index in indices) if (!predicate(index, this[index])) return false
        return true
    }

    private fun detectStripe(
        bitmap: Bitmap,
        config: BubbleDetectionConfig
    ): List<BubbleDetection> {
        val scale = if (bitmap.width > config.maximumProcessingWidthPx) {
            config.maximumProcessingWidthPx.toFloat() / bitmap.width.toFloat()
        } else {
            1f
        }
        val work = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                config.maximumProcessingWidthPx,
                max(1, (bitmap.height * scale).roundToInt()),
                true
            )
        } else {
            bitmap
        }

        val rgba = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()
        val bright = Mat()
        val dark = Mat()
        val darkFilled = Mat()
        val brightKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(11.0, 11.0))
        val outlineKernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))

        return try {
            Utils.bitmapToMat(work, rgba)
            when (rgba.channels()) {
                4 -> Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
                3 -> Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGB2GRAY)
                else -> rgba.copyTo(gray)
            }
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(blurred, edges, 42.0, 125.0)

            // Bright-region pass: merge text holes while preserving the bubble body.
            Imgproc.threshold(blurred, bright, 178.0, 255.0, Imgproc.THRESH_BINARY)
            Imgproc.morphologyEx(bright, bright, Imgproc.MORPH_CLOSE, brightKernel)
            Imgproc.morphologyEx(bright, bright, Imgproc.MORPH_OPEN, outlineKernel)

            // Dark-region pass: black speech bubbles with light lettering. Closing
            // removes the letter holes without joining neighbouring dark bubbles.
            Imgproc.threshold(blurred, darkFilled, 88.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.morphologyEx(darkFilled, darkFilled, Imgproc.MORPH_CLOSE, brightKernel)
            Imgproc.morphologyEx(darkFilled, darkFilled, Imgproc.MORPH_OPEN, outlineKernel)

            // Closed-outline pass: adaptive threshold handles tinted/gradient pages.
            Imgproc.adaptiveThreshold(
                blurred,
                dark,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                31,
                7.0
            )
            Imgproc.morphologyEx(dark, dark, Imgproc.MORPH_CLOSE, outlineKernel)

            val raw = mutableListOf<BubbleDetection>()
            raw.addAll(collectCandidates(bright, gray, edges, config, BubbleDetection.Source.BRIGHT_REGION))
            raw.addAll(collectCandidates(darkFilled, gray, edges, config, BubbleDetection.Source.DARK_REGION))
            raw.addAll(collectCandidates(dark, gray, edges, config, BubbleDetection.Source.CLOSED_OUTLINE))

            val inverseScale = 1f / scale
            raw.map { item ->
                if (scale >= 1f) item else item.copy(
                    bounds = RectF(
                        item.bounds.left * inverseScale,
                        item.bounds.top * inverseScale,
                        item.bounds.right * inverseScale,
                        item.bounds.bottom * inverseScale
                    )
                )
            }
        } finally {
            rgba.release()
            gray.release()
            blurred.release()
            edges.release()
            bright.release()
            dark.release()
            darkFilled.release()
            brightKernel.release()
            outlineKernel.release()
            if (work !== bitmap && !work.isRecycled) work.recycle()
        }
    }

    private fun collectCandidates(
        binary: Mat,
        gray: Mat,
        edges: Mat,
        config: BubbleDetectionConfig,
        source: BubbleDetection.Source
    ): List<BubbleDetection> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        val contourInput = binary.clone()
        try {
            Imgproc.findContours(contourInput, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        } finally {
            contourInput.release()
            hierarchy.release()
        }

        val width = gray.cols()
        val height = gray.rows()
        val canvasArea = (width.toLong() * height.toLong()).coerceAtLeast(1L).toFloat()
        val results = mutableListOf<BubbleDetection>()

        try {
            for (contour in contours) {
                val rect = Imgproc.boundingRect(contour)
                val contourArea = Imgproc.contourArea(contour).toFloat()
                val rectArea = (rect.width.toFloat() * rect.height.toFloat()).coerceAtLeast(1f)
                val minWidth = max(config.minimumWidthPx, (width * 0.04f).roundToInt())
                val minHeight = max(config.minimumHeightPx, (width * 0.022f).roundToInt())
                val circularity = contourCircularity(contour, contourArea)
                if (rect.width < minWidth ||
                    rect.height < minHeight ||
                    contourArea < MIN_REGION_AREA ||
                    rectArea > canvasArea * MAX_REGION_AREA_RATIO ||
                    circularity < MIN_CIRCULARITY
                ) continue

                val aspect = max(rect.width, rect.height).toFloat() /
                    min(rect.width, rect.height).coerceAtLeast(1).toFloat()
                if (aspect > config.maximumAspectRatio) continue
                if (spansCanvas(rect, width, height)) continue

                val extent = (contourArea / rectArea).coerceIn(0f, 1f)
                if (extent < 0.24f) continue

                val inset = insetRect(rect)
                if (inset.width <= 1 || inset.height <= 1) continue
                val grayRoi = gray.submat(inset)
                val edgeRoi = edges.submat(inset)
                try {
                    val mean = Core.mean(grayRoi).`val`[0].toFloat()
                    val edgeDensity = (Core.countNonZero(edgeRoi).toFloat() /
                        (inset.width * inset.height).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
                    val darkRatio = estimateDarkRatio(grayRoi)

                    val isDarkBubble = source == BubbleDetection.Source.DARK_REGION
                    val lightRatio = if (isDarkBubble) estimateLightRatio(grayRoi) else 0f
                    if (isDarkBubble) {
                        if (mean > MAX_DARK_INTERIOR_LUMINANCE ||
                            darkRatio < MIN_DARK_INTERIOR_RATIO ||
                            lightRatio < MIN_LIGHT_TEXT_RATIO ||
                            edgeDensity !in 0.001f..MAX_INTERIOR_EDGE_DENSITY
                        ) continue
                    } else {
                        // White/tinted bubble: keep the original bright-interior rules.
                        if (mean < MIN_INTERIOR_LUMINANCE || darkRatio !in 0.006f..MAX_INTERIOR_DARK_RATIO) continue
                        if (edgeDensity !in 0.002f..MAX_INTERIOR_EDGE_DENSITY) continue
                    }

                    val interiorScore = if (isDarkBubble) {
                        ((MAX_DARK_INTERIOR_LUMINANCE - mean) / MAX_DARK_INTERIOR_LUMINANCE).coerceIn(0f, 1f)
                    } else {
                        ((mean - MIN_INTERIOR_LUMINANCE) / (255f - MIN_INTERIOR_LUMINANCE)).coerceIn(0f, 1f)
                    }
                    val shapeScore = (((extent - 0.24f) / 0.62f) * 0.65f + circularity * 0.35f).coerceIn(0f, 1f)
                    val inkScore = if (isDarkBubble) {
                        (lightRatio / 0.08f).coerceIn(0f, 1f)
                    } else when {
                        darkRatio in 0.012f..0.22f -> 1f
                        darkRatio < 0.012f -> (darkRatio / 0.012f).coerceIn(0f, 1f)
                        else -> ((MAX_INTERIOR_DARK_RATIO - darkRatio) / 0.10f).coerceIn(0f, 1f)
                    }
                    val edgeScore = (edgeDensity / 0.045f).coerceIn(0f, 1f)
                    val confidence = (
                        interiorScore * 0.34f +
                            shapeScore * 0.27f +
                            inkScore * 0.24f +
                            edgeScore * 0.15f
                        ).coerceIn(0f, 1f)

                    if (confidence >= config.minimumConfidence) {
                        val padX = max(3, (rect.width * 0.025f).roundToInt())
                        val padY = max(3, (rect.height * 0.035f).roundToInt())
                        results.add(BubbleDetection(
                            bounds = RectF(
                                (rect.x - padX).coerceAtLeast(0).toFloat(),
                                (rect.y - padY).coerceAtLeast(0).toFloat(),
                                (rect.x + rect.width + padX).coerceAtMost(width).toFloat(),
                                (rect.y + rect.height + padY).coerceAtMost(height).toFloat()
                            ),
                            confidence = confidence,
                            source = source
                        ))
                    }
                } finally {
                    grayRoi.release()
                    edgeRoi.release()
                }
            }
        } finally {
            contours.forEach { it.release() }
        }
        return results
    }

    private fun contourCircularity(contour: MatOfPoint, contourArea: Float): Float {
        val curve = org.opencv.core.MatOfPoint2f(*contour.toArray())
        return try {
            val perimeter = Imgproc.arcLength(curve, true).toFloat()
            if (perimeter <= 0f) 0f else {
                (4f * Math.PI.toFloat() * contourArea / (perimeter * perimeter)).coerceIn(0f, 1f)
            }
        } finally {
            curve.release()
        }
    }

    private fun estimateDarkRatio(gray: Mat): Float {
        val darkPixels = Mat()
        return try {
            Imgproc.threshold(gray, darkPixels, 170.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.countNonZero(darkPixels).toFloat() / (gray.rows() * gray.cols()).coerceAtLeast(1).toFloat()
        } finally {
            darkPixels.release()
        }
    }

    private fun estimateLightRatio(gray: Mat): Float {
        val lightPixels = Mat()
        return try {
            Imgproc.threshold(gray, lightPixels, 180.0, 255.0, Imgproc.THRESH_BINARY)
            Core.countNonZero(lightPixels).toFloat() / (gray.rows() * gray.cols()).coerceAtLeast(1).toFloat()
        } finally {
            lightPixels.release()
        }
    }

    private fun insetRect(rect: Rect): Rect {
        val insetX = (rect.width * 0.10f).roundToInt().coerceAtMost((rect.width - 2) / 2)
        val insetY = (rect.height * 0.10f).roundToInt().coerceAtMost((rect.height - 2) / 2)
        return Rect(
            rect.x + insetX,
            rect.y + insetY,
            (rect.width - insetX * 2).coerceAtLeast(1),
            (rect.height - insetY * 2).coerceAtLeast(1)
        )
    }

    private fun spansCanvas(rect: Rect, width: Int, height: Int): Boolean {
        val spansWidth = rect.x <= 2 && rect.x + rect.width >= width - 2
        val spansHeight = rect.y <= 2 && rect.y + rect.height >= height - 2
        return spansWidth || spansHeight
    }

    private fun mergeDetections(
        input: List<BubbleDetection>,
        canvasWidth: Int,
        canvasHeight: Int,
        minimumConfidence: Float,
        maximumDetections: Int = 120
    ): List<BubbleDetection> {
        if (input.isEmpty()) return emptyList()
        // Confidence remains the primary ordering key, while a small area tie-break
        // makes complete contours win over almost-identical half-bubble proposals.
        val sorted = input.sortedWith(
            compareByDescending<BubbleDetection> { it.confidence }
                .thenByDescending { area(it.bounds) }
        )
        val kept = mutableListOf<BubbleDetection>()

        for (candidate in sorted) {
            val match = kept.indexOfFirst { existing ->
                iou(existing.bounds, candidate.bounds) >= DUPLICATE_IOU ||
                    intersectionOverSmaller(existing.bounds, candidate.bounds) >= DUPLICATE_INTERSECTION_OVER_SMALLER ||
                    sameBubbleCenter(existing.bounds, candidate.bounds)
            }
            if (match < 0) {
                kept += candidate
                continue
            }

            val existing = kept[match]
            val fusedConfidence = max(existing.confidence, candidate.confidence).coerceAtMost(1f)
            val fusedSource = if (existing.source == candidate.source) existing.source else BubbleDetection.Source.HYBRID
            val overlapOfSmaller = intersectionOverSmaller(existing.bounds, candidate.bounds)
            val existingArea = area(existing.bounds)
            val candidateArea = area(candidate.bounds)
            val smallerArea = min(existingArea, candidateArea).coerceAtLeast(1f)
            val largerArea = max(existingArea, candidateArea).coerceAtLeast(1f)
            val nestedPartial = overlapOfSmaller >= DUPLICATE_INTERSECTION_OVER_SMALLER &&
                smallerArea / largerArea < 0.82f

            // A bright-region pass can stop at lettering or a soft gradient and return
            // only the upper/lower half of a bubble. When another detector contains
            // that proposal, retain the larger contour instead of the old "tightest
            // box wins" rule. This keeps complete bubble bounds while the duplicate
            // thresholds still prevent neighbouring bubbles from being joined.
            val preferredBounds = when {
                nestedPartial && existingArea >= candidateArea -> existing.bounds
                nestedPartial -> candidate.bounds
                existing.source == BubbleDetection.Source.ML_KIT && candidate.source != BubbleDetection.Source.ML_KIT -> candidate.bounds
                candidate.source == BubbleDetection.Source.ML_KIT && existing.source != BubbleDetection.Source.ML_KIT -> existing.bounds
                existingArea >= candidateArea -> existing.bounds
                else -> candidate.bounds
            }
            val preview = if (existing.textPreview.length >= candidate.textPreview.length) {
                existing.textPreview
            } else {
                candidate.textPreview
            }
            kept[match] = BubbleDetection(RectF(preferredBounds), fusedConfidence, fusedSource, preview)
        }

        return kept.asSequence()
            .filter { it.confidence >= minimumConfidence }
            .map { item ->
                item.copy(bounds = RectF(
                    item.bounds.left.coerceIn(0f, canvasWidth.toFloat()),
                    item.bounds.top.coerceIn(0f, canvasHeight.toFloat()),
                    item.bounds.right.coerceIn(0f, canvasWidth.toFloat()),
                    item.bounds.bottom.coerceIn(0f, canvasHeight.toFloat())
                ))
            }
            .filter { it.bounds.width() > 0f && it.bounds.height() > 0f }
            .sortedWith(compareBy<BubbleDetection> { it.bounds.top }.thenBy { it.bounds.left })
            .take(maximumDetections)
            .toList()
    }

    private fun sameBubbleCenter(a: RectF, b: RectF): Boolean {
        val dx = kotlin.math.abs(a.centerX() - b.centerX())
        val dy = kotlin.math.abs(a.centerY() - b.centerY())
        val toleranceX = min(a.width(), b.width()) * 0.22f
        val toleranceY = min(a.height(), b.height()) * 0.22f
        return dx <= toleranceX && dy <= toleranceY &&
            intersectionOverSmaller(a, b) >= 0.45f
    }

    private fun intersectionOverSmaller(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val smaller = min(area(a), area(b)).coerceAtLeast(1f)
        return intersection / smaller
    }

    private fun area(rect: RectF): Float = rect.width().coerceAtLeast(0f) * rect.height().coerceAtLeast(0f)

    private fun intersectionArea(a: RectF, b: RectF): Float {
        val width = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val height = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        return width * height
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        if (intersection <= 0f) return 0f
        val union = area(a) + area(b) - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
