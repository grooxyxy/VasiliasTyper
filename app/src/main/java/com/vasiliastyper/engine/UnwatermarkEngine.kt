package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Offline inverse-alpha watermark remover.
 *
 * 1.0.0 Octopus keeps alpha-aware, multi-scale detection and follows the reference HTML
 * pipeline more closely: inverse compositing remains geometrically exact, while
 * a masked, edge-preserving surface pass suppresses JPEG residue only inside the
 * watermark support. Opaque samples with a flat black/white matte are normalised
 * into a usable alpha PNG before matching and removal.
 */
object UnwatermarkEngine {

    /** Native processing profiles. Both run directly on Android Bitmap pixels. */
    enum class Profile {
        OCTOPUS_ADAPTIVE,
        HTML_UNWATERMARKER_V1_6
    }

    data class Options(
        /** 0.94 is the calibrated neutral point for resized/JPEG webtoon pages. */
        val strength: Float = DEFAULT_BEST_STRENGTH,
        val alphaThreshold: Int = 3,
        val opaqueThreshold: Int = 240,
        val smoothOpaquePixels: Boolean = true,
        val autoAlignRadius: Int = 0,
        /** Mirrors the reference HTML's 0.05 px alignment search. */
        val automaticSubpixelAlignment: Boolean = true,
        val jpegArtifactSuppression: Boolean = true,
        val jpegFilterRadius: Int = 3,
        val jpegFilterThreshold: Int = 4,
        val autoTuneStrength: Boolean = true
    )

    /**
     * Builds an immutable native configuration for the selected UI engine.
     * HTML_UNWATERMARKER_V1_6 mirrors the supplied tool's defaults: alpha 1.0,
     * transparency threshold 3, opaque threshold 240, optional subpixel alignment,
     * and its radius-3/threshold-4 JPEG cleanup. Octopus retains adaptive tuning.
     */
    fun createOptions(
        profile: Profile,
        requestedStrength: Float,
        smoothOpaquePixels: Boolean,
        autoAlign: Boolean,
        adaptiveAlignRadius: Int,
        adaptiveJpegRadius: Int,
        adaptiveJpegThreshold: Int,
        presetAlphaAdjust: Float = 1f,
        presetJpegEnabled: Boolean = true,
        presetAutoTuneStrength: Boolean = true
    ): Options {
        val strength = (requestedStrength * presetAlphaAdjust).coerceIn(0.5f, 1.5f)
        return when (profile) {
            Profile.OCTOPUS_ADAPTIVE -> Options(
                strength = strength,
                smoothOpaquePixels = smoothOpaquePixels,
                autoAlignRadius = if (autoAlign) adaptiveAlignRadius else 0,
                automaticSubpixelAlignment = autoAlign,
                jpegArtifactSuppression = presetJpegEnabled,
                jpegFilterRadius = adaptiveJpegRadius,
                jpegFilterThreshold = adaptiveJpegThreshold,
                autoTuneStrength = presetAutoTuneStrength
            )
            Profile.HTML_UNWATERMARKER_V1_6 -> Options(
                strength = strength,
                alphaThreshold = 3,
                opaqueThreshold = 240,
                smoothOpaquePixels = smoothOpaquePixels,
                // Whole-pixel search in the supplied v1.6 source is disabled as a
                // failed experiment. Auto-fit still locates/resizes the sample first.
                autoAlignRadius = 0,
                automaticSubpixelAlignment = autoAlign,
                jpegArtifactSuppression = smoothOpaquePixels,
                jpegFilterRadius = 3,
                jpegFilterThreshold = 4,
                // The HTML default alphaAdjust is exactly 1.0; do not silently retune.
                autoTuneStrength = false
            )
        }
    }

    data class Result(
        val changedPixels: Int,
        val offsetX: Int,
        val offsetY: Int,
        val subpixelOffsetX: Float,
        val subpixelOffsetY: Float,
        val appliedStrength: Float
    )

    data class DetectionResult(
        val rect: Rect,
        val confidence: Float,
        val scale: Float
    )

    /**
     * Runtime profile generated from the actual canvas, sample and optional rough
     * selection. It replaces hard-coded "720 only" assumptions while keeping a
     * conservative, reproducible neutral strength.
     */
    data class AdaptivePreset(
        val expectedScale: Float,
        val recommendedStrength: Float,
        val autoAlignRadius: Int,
        val jpegFilterRadius: Int,
        val jpegFilterThreshold: Int,
        val minimumConfidence: Float
    )

    fun createAdaptivePreset(
        canvasWidth: Int,
        canvasHeight: Int,
        sampleWidth: Int,
        sampleHeight: Int,
        referenceCanvasWidth: Int? = null,
        hintRect: Rect? = null,
        baseStrength: Float = DEFAULT_BEST_STRENGTH,
        baseJpegRadius: Int = 3,
        baseJpegThreshold: Int = 4,
        preferHintScale: Boolean = false
    ): AdaptivePreset {
        require(canvasWidth > 0 && canvasHeight > 0) { "Ukuran kanvas tidak valid" }
        require(sampleWidth > 0 && sampleHeight > 0) { "Ukuran sampel watermark tidak valid" }

        val referenceScale = referenceCanvasWidth
            ?.takeIf { it > 0 }
            ?.let { canvasWidth.toFloat() / it.toFloat() }
        val hintScale = hintRect
            ?.takeUnless { it.isEmpty }
            ?.let { min(it.width().toFloat() / sampleWidth, it.height().toFloat() / sampleHeight) }
            ?.takeIf { it.isFinite() && it > 0f }
        val safeMaxScale = min(
            3f,
            min(canvasWidth.toFloat() / sampleWidth, canvasHeight.toFloat() / sampleHeight)
        ).coerceAtLeast(0.05f)
        val expectedScale = (if (preferHintScale) {
            hintScale ?: referenceScale
        } else {
            referenceScale ?: hintScale
        } ?: (canvasWidth / 720f)).coerceIn(0.05f, safeMaxScale)

        // Downscaled JPEG pages need slightly less alpha inversion; large native
        // pages tolerate a touch more. Auto-tune still performs final image-based
        // optimisation around this deterministic starting point.
        val scaleCompensation = when {
            expectedScale < 0.70f -> -0.025f
            expectedScale < 0.90f -> -0.010f
            expectedScale > 1.75f -> 0.025f
            expectedScale > 1.25f -> 0.010f
            else -> 0f
        }
        val strength = (baseStrength + scaleCompensation).coerceIn(0.5f, 1.5f)
        val alignRadius = when {
            expectedScale >= 1.75f -> 4
            expectedScale >= 1.15f -> 3
            expectedScale < 0.65f -> 1
            else -> 2
        }
        val jpegRadius = (baseJpegRadius + when {
            expectedScale < 0.70f -> 1
            expectedScale > 1.75f -> -1
            else -> 0
        }).coerceIn(1, 8)
        val threshold = (baseJpegThreshold + if (canvasHeight > canvasWidth * 8) 1 else 0)
            .coerceIn(0, 32)
        return AdaptivePreset(
            expectedScale = expectedScale,
            recommendedStrength = strength,
            autoAlignRadius = alignRadius,
            jpegFilterRadius = jpegRadius,
            jpegFilterThreshold = threshold,
            minimumConfidence = if (hintRect == null) 0.74f else 0.68f
        )
    }

    private data class PreparedSample(val bitmap: Bitmap, val owned: Boolean)

    /**
     * Finds the most similar watermark instance at multiple scales.
     * [hintRect] is optional. When present it bounds the search to a generously
     * expanded region, so a slightly misplaced manual selection can still recover.
     */
    fun detect(
        target: Bitmap,
        watermarkSample: Bitmap,
        hintRect: Rect? = null,
        minimumConfidence: Float = 0.74f,
        expectedScale: Float? = null
    ): DetectionResult? {
        require(!target.isRecycled && !watermarkSample.isRecycled) { "Bitmap sudah di-recycle" }
        if (target.width < 8 || target.height < 8) return null
        if (!OpenCvInit.ensureInit()) return null

        val prepared = prepareSample(watermarkSample, cropToContent = true) ?: return null
        var targetRgba: Mat? = null
        var targetGray: Mat? = null
        var sampleRgba: Mat? = null
        var sampleGray: Mat? = null
        var sampleAlpha: Mat? = null
        var workGray: Mat? = null

        try {
            targetRgba = Mat()
            targetGray = Mat()
            sampleRgba = Mat()
            sampleGray = Mat()
            sampleAlpha = Mat()
            Utils.bitmapToMat(target, targetRgba)
            Utils.bitmapToMat(prepared.bitmap, sampleRgba)
            Imgproc.cvtColor(targetRgba, targetGray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(sampleRgba, sampleGray, Imgproc.COLOR_RGBA2GRAY)
            Core.extractChannel(sampleRgba, sampleAlpha, 3)

            // A hint is a bounded search centre, not merely a scale suggestion. This
            // is essential for 800x16000 pages: matching a small logo against every
            // vertical pixel for every scale was both slow and prone to false hits.
            val searchRect = buildSearchRect(
                target.width,
                target.height,
                prepared.bitmap.width,
                prepared.bitmap.height,
                hintRect,
                expectedScale
            )
            val searchGray = targetGray.submat(
                searchRect.top,
                searchRect.bottom,
                searchRect.left,
                searchRect.right
            )
            try {
                // Do not scale from page height alone. On a tall webtoon that made an
                // 800 px canvas only ~90 px wide and destroyed watermark details.
                val longest = max(searchRect.width(), searchRect.height())
                val detailFloor = 256f / searchRect.width().coerceAtLeast(1).toFloat()
                val workScale = min(1f, max(3200f / longest.toFloat(), detailFloor.coerceAtMost(1f)))
                workGray = if (workScale < 0.999f) {
                    Mat().also {
                        Imgproc.resize(
                            searchGray,
                            it,
                            Size(searchRect.width() * workScale.toDouble(), searchRect.height() * workScale.toDouble()),
                            0.0,
                            0.0,
                            Imgproc.INTER_AREA
                        )
                    }
                } else {
                    searchGray.clone()
                }

                val scales = buildScaleCandidates(
                    target.width,
                    target.height,
                    prepared.bitmap.width,
                    prepared.bitmap.height,
                    hintRect,
                    expectedScale
                )
                var bestScore = Double.NEGATIVE_INFINITY
                var bestLocationX = 0
                var bestLocationY = 0
                var bestWidth = 0
                var bestHeight = 0
                var bestScale = 1f

                fun evaluateScale(originalScale: Float) {
                    val scaledWidth = (prepared.bitmap.width * originalScale * workScale)
                        .roundToInt().coerceAtLeast(4)
                    val scaledHeight = (prepared.bitmap.height * originalScale * workScale)
                        .roundToInt().coerceAtLeast(4)
                    if (scaledWidth > workGray!!.cols() || scaledHeight > workGray!!.rows()) return

                    val resizedGray = Mat()
                    val resizedAlpha = Mat()
                    val mask = Mat()
                    val score = Mat()
                    try {
                        val interpolation = if (originalScale * workScale < 1f) {
                            Imgproc.INTER_AREA
                        } else {
                            Imgproc.INTER_CUBIC
                        }
                        Imgproc.resize(sampleGray, resizedGray, Size(scaledWidth.toDouble(), scaledHeight.toDouble()), 0.0, 0.0, interpolation)
                        Imgproc.resize(sampleAlpha, resizedAlpha, Size(scaledWidth.toDouble(), scaledHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
                        Imgproc.threshold(resizedAlpha, mask, 3.0, 255.0, Imgproc.THRESH_BINARY)
                        if (Core.countNonZero(mask) < 12) return

                        Imgproc.matchTemplate(workGray!!, resizedGray, score, Imgproc.TM_CCORR_NORMED, mask)
                        val mm = Core.minMaxLoc(score)
                        val candidate = mm.maxVal
                        if (candidate.isFinite() && candidate > bestScore) {
                            bestScore = candidate
                            bestLocationX = mm.maxLoc.x.roundToInt()
                            bestLocationY = mm.maxLoc.y.roundToInt()
                            bestWidth = scaledWidth
                            bestHeight = scaledHeight
                            bestScale = originalScale
                        }
                    } finally {
                        resizedGray.release()
                        resizedAlpha.release()
                        mask.release()
                        score.release()
                    }
                }

                scales.forEach(::evaluateScale)

                if (bestScore.isFinite() && bestWidth > 0 && bestHeight > 0) {
                    val coarseBestScale = bestScale
                    (-8..8)
                        .asSequence()
                        .map { step -> coarseBestScale * (1f + step * 0.005f) }
                        .filter { it > 0f }
                        .forEach(::evaluateScale)
                }

                if (!bestScore.isFinite() || bestScore < minimumConfidence || bestWidth <= 0 || bestHeight <= 0) {
                    return null
                }

                val inv = 1f / workScale
                val left = (searchRect.left + bestLocationX * inv).roundToInt().coerceIn(0, target.width)
                val top = (searchRect.top + bestLocationY * inv).roundToInt().coerceIn(0, target.height)
                val right = (searchRect.left + (bestLocationX + bestWidth) * inv).roundToInt().coerceIn(left + 1, target.width)
                val bottom = (searchRect.top + (bestLocationY + bestHeight) * inv).roundToInt().coerceIn(top + 1, target.height)
                return DetectionResult(Rect(left, top, right, bottom), bestScore.toFloat(), bestScale)
            } finally {
                searchGray.release()
            }

        } finally {
            targetRgba?.release()
            targetGray?.release()
            sampleRgba?.release()
            sampleGray?.release()
            sampleAlpha?.release()
            workGray?.release()
            if (prepared.owned && !prepared.bitmap.isRecycled) prepared.bitmap.recycle()
        }
    }

    fun remove(
        target: Bitmap,
        watermarkSample: Bitmap,
        requestedRect: Rect,
        options: Options = Options()
    ): Result {
        require(target.isMutable) { "Target bitmap harus mutable" }
        require(!target.isRecycled && !watermarkSample.isRecycled) { "Bitmap sudah di-recycle" }

        val rect = Rect(
            requestedRect.left.coerceIn(0, target.width),
            requestedRect.top.coerceIn(0, target.height),
            requestedRect.right.coerceIn(0, target.width),
            requestedRect.bottom.coerceIn(0, target.height)
        )
        if (rect.isEmpty || rect.width() < 1 || rect.height() < 1) {
            return Result(0, 0, 0, 0f, 0f, options.strength)
        }

        val prepared = prepareSample(watermarkSample, cropToContent = true)
            ?: return Result(0, 0, 0, 0f, 0f, options.strength)
        val scaled = if (prepared.bitmap.width == rect.width() && prepared.bitmap.height == rect.height()) {
            prepared.bitmap
        } else {
            // Android's regular bitmap resize interpolates transparent RGB as if it
            // were an opaque image. That produces dark/bright fringes and makes the
            // inverse-alpha formula appear to do nothing. The HTML reference uses
            // alpha-aware pica resizing, so preserve premultiplied colour explicitly.
            scaleSampleAlphaCorrect(prepared.bitmap, rect.width(), rect.height())
        }

        try {
            val width = rect.width()
            val height = rect.height()
            val targetPixels = IntArray(width * height)
            val watermarkPixels = IntArray(width * height)
            target.getPixels(targetPixels, 0, width, rect.left, rect.top, width, height)
            scaled.getPixels(watermarkPixels, 0, width, 0, 0, width, height)

            val radius = options.autoAlignRadius.coerceIn(0, 4)
            val offset = if (radius > 0) {
                findBestOffset(targetPixels, watermarkPixels, width, height, radius, options)
            } else {
                0 to 0
            }

            val sourcePixels = targetPixels.copyOf()
            val requestedStrength = options.strength.coerceIn(0.5f, 1.5f)
            val alphaThreshold = options.alphaThreshold.coerceIn(0, 254)
            val opaqueThreshold = options.opaqueThreshold.coerceIn(alphaThreshold + 1, 254)
            val wholePixelAligned = alignWatermarkPixels(
                watermarkPixels,
                width,
                height,
                offset.first,
                offset.second
            )
            val subpixelOffset = if (options.automaticSubpixelAlignment) {
                findBestSubpixelOffset(
                    sourcePixels,
                    wholePixelAligned,
                    width,
                    height,
                    requestedStrength,
                    alphaThreshold
                )
            } else {
                0f to 0f
            }
            val alignedWatermark = if (
                abs(subpixelOffset.first) >= 0.001f || abs(subpixelOffset.second) >= 0.001f
            ) {
                shiftWatermarkPremultiplied(
                    wholePixelAligned,
                    width,
                    height,
                    subpixelOffset.first,
                    subpixelOffset.second
                )
            } else {
                wholePixelAligned
            }
            // HTML's Guess Alpha is independent from its JPEG filter. Keep automatic
            // opacity fitting active even when a preset disables JPEG cleanup.
            val strength = if (
                options.autoTuneStrength &&
                alignedWatermark.size <= MAX_AUTO_TUNE_PIXELS
            ) {
                tuneJpegStrength(
                    sourcePixels,
                    alignedWatermark,
                    width,
                    height,
                    requestedStrength,
                    options
                )
            } else {
                requestedStrength
            }
            val correction = if (options.jpegArtifactSuppression) {
                buildJpegAwareCorrection(alignedWatermark, width, height, strength, options)
            } else {
                null
            }
            var changed = 0

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val targetIndex = y * width + x
                    val wm = alignedWatermark[targetIndex]
                    val adjustedAlpha = (Color.alpha(wm) * strength)
                        .roundToInt()
                        .coerceIn(0, 254)
                    val effectiveAlpha = correction?.maxAlpha?.get(targetIndex)
                        ?.times(255f)
                        ?.roundToInt()
                        ?.coerceIn(0, 254)
                        ?: adjustedAlpha
                    if (effectiveAlpha <= alphaThreshold) continue

                    val observed = sourcePixels[targetIndex]
                    val corrected = if (correction != null) {
                        inverseJpegAware(observed, targetIndex, correction)
                    } else {
                        val denominator = (255 - adjustedAlpha).coerceAtLeast(1)
                        Color.rgb(
                            inverseChannel(Color.red(observed), Color.red(wm), adjustedAlpha, denominator),
                            inverseChannel(Color.green(observed), Color.green(wm), adjustedAlpha, denominator),
                            inverseChannel(Color.blue(observed), Color.blue(wm), adjustedAlpha, denominator)
                        )
                    }
                    var red = Color.red(corrected)
                    var green = Color.green(corrected)
                    var blue = Color.blue(corrected)

                    if (options.smoothOpaquePixels && effectiveAlpha > opaqueThreshold) {
                        val neighbor = stableNeighbour(sourcePixels, width, height, x, y)
                        val mix = ((effectiveAlpha - opaqueThreshold).toFloat() /
                            (255 - opaqueThreshold).coerceAtLeast(1)).coerceIn(0f, 0.88f)
                        red = lerp(red, Color.red(neighbor), mix)
                        green = lerp(green, Color.green(neighbor), mix)
                        blue = lerp(blue, Color.blue(neighbor), mix)
                    }

                    val restored = Color.argb(Color.alpha(observed), red, green, blue)
                    if (restored != observed) {
                        targetPixels[targetIndex] = restored
                        changed++
                    }
                }
            }

            if (changed > 0) {
                if (options.jpegArtifactSuppression && correction != null) {
                    suppressJpegResiduals(
                        targetPixels,
                        correction.maxAlpha,
                        width,
                        height,
                        options
                    )
                }
                target.setPixels(targetPixels, 0, width, rect.left, rect.top, width, height)
            }
            return Result(
                changed,
                offset.first,
                offset.second,
                subpixelOffset.first,
                subpixelOffset.second,
                strength
            )
        } finally {
            if (scaled !== prepared.bitmap && !scaled.isRecycled) scaled.recycle()
            if (prepared.owned && !prepared.bitmap.isRecycled) prepared.bitmap.recycle()
        }
    }

    private fun buildSearchRect(
        targetWidth: Int,
        targetHeight: Int,
        sampleWidth: Int,
        sampleHeight: Int,
        hintRect: Rect?,
        expectedScale: Float?
    ): Rect {
        if (hintRect == null || hintRect.isEmpty) return Rect(0, 0, targetWidth, targetHeight)
        val scale = expectedScale?.takeIf { it.isFinite() && it > 0f } ?: 1f
        val expectedWidth = max(hintRect.width(), (sampleWidth * scale).roundToInt())
        val expectedHeight = max(hintRect.height(), (sampleHeight * scale).roundToInt())
        val paddingX = max(64, expectedWidth * 2)
        val paddingY = max(64, expectedHeight * 2)
        val left = (hintRect.left - paddingX).coerceIn(0, targetWidth - 1)
        val top = (hintRect.top - paddingY).coerceIn(0, targetHeight - 1)
        val right = (hintRect.right + paddingX).coerceIn(left + 1, targetWidth)
        val bottom = (hintRect.bottom + paddingY).coerceIn(top + 1, targetHeight)
        return Rect(left, top, right, bottom)
    }

    /** Build logarithmic scales plus candidates inferred from a rough selection. */
    private fun buildScaleCandidates(
        targetWidth: Int,
        targetHeight: Int,
        sampleWidth: Int,
        sampleHeight: Int,
        hintRect: Rect?,
        expectedScale: Float?
    ): List<Float> {
        // Keep a 40 px floor on normal pages, but scale it down on tiny preview
        // images (such as a 58 px-wide uploaded webtoon thumbnail). Confidence
        // filtering still rejects weak matches while allowing an 8–15 px logo.
        val minimumMatchPixels = min(40f, targetWidth * 0.16f).coerceAtLeast(8f)
        val minScale = max(0.05f, minimumMatchPixels / min(sampleWidth, sampleHeight).coerceAtLeast(1))
        val maxScale = min(
            3f,
            min(targetWidth.toFloat() / sampleWidth.coerceAtLeast(1), targetHeight.toFloat() / sampleHeight.coerceAtLeast(1))
        )
        if (maxScale < minScale) return emptyList()

        val values = linkedSetOf<Float>()
        if (expectedScale != null && expectedScale.isFinite() && expectedScale > 0f) {
            // Presets carry their reference canvas width (for example 720).
            // Search tightly around that scale: faster and less prone to a
            // same-looking logo variant winning at the wrong size.
            for (factor in floatArrayOf(0.94f, 0.97f, 0.985f, 1f, 1.015f, 1.03f, 1.06f)) {
                values += (expectedScale * factor).coerceIn(minScale, maxScale)
            }
        } else {
            // Template matching is sensitive to a 2–3% size mismatch. A geometric
            // step keeps enough precision without exploding work at large scales.
            var scale = minScale
            while (scale <= maxScale) {
                values += scale
                scale *= 1.08f
            }
            values += maxScale
        }
        if (hintRect != null && !hintRect.isEmpty) {
            val widthScale = hintRect.width().toFloat() / sampleWidth.coerceAtLeast(1)
            val heightScale = hintRect.height().toFloat() / sampleHeight.coerceAtLeast(1)
            val base = min(widthScale, heightScale).coerceIn(minScale, maxScale)
            for (factor in floatArrayOf(0.70f, 0.80f, 0.90f, 0.95f, 1f, 1.05f, 1.10f, 1.20f, 1.30f)) {
                values += (base * factor).coerceIn(minScale, maxScale)
            }
        }
        if (expectedScale == null) values += 1f.coerceIn(minScale, maxScale)
        return values.sorted()
    }

    /**
     * Crops transparent padding. If the file is fully opaque and its border is
     * near black/white, converts the matte to alpha and un-premultiplies RGB.
     */
    private fun prepareSample(source: Bitmap, cropToContent: Boolean): PreparedSample? {
        if (source.width < 1 || source.height < 1 || source.isRecycled) return null
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        var minAlpha = 255
        var maxAlpha = 0
        for (pixel in pixels) {
            val alpha = Color.alpha(pixel)
            minAlpha = min(minAlpha, alpha)
            maxAlpha = max(maxAlpha, alpha)
        }

        var normalised: Bitmap = source
        var ownsNormalised = false
        if (minAlpha >= 250) {
            val matte = estimateBorderColor(pixels, width, height)
            val matteR = Color.red(matte)
            val matteG = Color.green(matte)
            val matteB = Color.blue(matte)
            val nearBlack = max(matteR, max(matteG, matteB)) <= 32
            val nearWhite = min(matteR, min(matteG, matteB)) >= 223
            if (nearBlack || nearWhite) {
                val converted = IntArray(pixels.size)
                for (i in pixels.indices) {
                    val color = pixels[i]
                    val r = Color.red(color)
                    val g = Color.green(color)
                    val b = Color.blue(color)
                    val alpha = if (nearBlack) max(r, max(g, b)) else 255 - min(r, min(g, b))
                    if (alpha <= 2) {
                        converted[i] = Color.TRANSPARENT
                    } else {
                        val outR: Int
                        val outG: Int
                        val outB: Int
                        if (nearBlack) {
                            outR = (r * 255f / alpha).roundToInt().coerceIn(0, 255)
                            outG = (g * 255f / alpha).roundToInt().coerceIn(0, 255)
                            outB = (b * 255f / alpha).roundToInt().coerceIn(0, 255)
                        } else {
                            outR = (255f - (255 - r) * 255f / alpha).roundToInt().coerceIn(0, 255)
                            outG = (255f - (255 - g) * 255f / alpha).roundToInt().coerceIn(0, 255)
                            outB = (255f - (255 - b) * 255f / alpha).roundToInt().coerceIn(0, 255)
                        }
                        converted[i] = Color.argb(alpha.coerceIn(0, 255), outR, outG, outB)
                    }
                }
                normalised = Bitmap.createBitmap(converted, width, height, Bitmap.Config.ARGB_8888)
                ownsNormalised = true
                maxAlpha = converted.maxOfOrNull { Color.alpha(it) } ?: 0
            }
        }

        if (!cropToContent) return PreparedSample(normalised, ownsNormalised)
        val scan = if (normalised === source) pixels else IntArray(width * height).also {
            normalised.getPixels(it, 0, width, 0, 0, width, height)
        }
        val threshold = max(2, (maxAlpha * 0.02f).roundToInt())
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (Color.alpha(scan[y * width + x]) > threshold) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }
        if (right < left || bottom < top) {
            if (ownsNormalised && !normalised.isRecycled) normalised.recycle()
            return null
        }
        left = (left - 1).coerceAtLeast(0)
        top = (top - 1).coerceAtLeast(0)
        right = (right + 1).coerceAtMost(width - 1)
        bottom = (bottom + 1).coerceAtMost(height - 1)
        if (left == 0 && top == 0 && right == width - 1 && bottom == height - 1) {
            return PreparedSample(normalised, ownsNormalised)
        }

        val cropped = Bitmap.createBitmap(normalised, left, top, right - left + 1, bottom - top + 1)
        if (ownsNormalised && cropped !== normalised && !normalised.isRecycled) normalised.recycle()
        return PreparedSample(cropped, true)
    }

    private fun estimateBorderColor(pixels: IntArray, width: Int, height: Int): Int {
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val stepX = max(1, width / 64)
        val stepY = max(1, height / 64)
        var x = 0
        while (x < width) {
            for (y in intArrayOf(0, height - 1)) {
                val color = pixels[y * width + x]
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color); count++
            }
            x += stepX
        }
        var y = stepY
        while (y < height - 1) {
            for (edgeX in intArrayOf(0, width - 1)) {
                val color = pixels[y * width + edgeX]
                red += Color.red(color); green += Color.green(color); blue += Color.blue(color); count++
            }
            y += stepY
        }
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }

    private fun inverseChannel(observed: Int, watermark: Int, alpha: Int, denominator: Int): Int =
        ((255f * observed - alpha.toFloat() * watermark) / denominator.toFloat())
            .roundToInt()
            .coerceIn(0, 255)

    private data class JpegCorrection(
        val alphaY: FloatArray,
        val alphaChroma: FloatArray,
        val premulY: FloatArray,
        val premulCb: FloatArray,
        val premulCr: FloatArray,
        val maxAlpha: FloatArray
    )

    /**
     * Alpha-correct bilinear resize equivalent to the reference HTML's pica
     * `alpha: true` path. RGB is accumulated in premultiplied form and converted
     * back only after interpolation, preventing transparent-border colour bleed.
     */
    private fun scaleSampleAlphaCorrect(source: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val sourceWidth = source.width
        val sourceHeight = source.height
        val sourcePixels = IntArray(sourceWidth * sourceHeight)
        source.getPixels(sourcePixels, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
        val output = IntArray(outWidth * outHeight)
        val scaleX = sourceWidth.toFloat() / outWidth.toFloat()
        val scaleY = sourceHeight.toFloat() / outHeight.toFloat()

        for (y in 0 until outHeight) {
            val sourceY = (y + 0.5f) * scaleY - 0.5f
            val y0 = floor(sourceY).toInt().coerceIn(0, sourceHeight - 1)
            val y1 = (y0 + 1).coerceAtMost(sourceHeight - 1)
            val fy = (sourceY - floor(sourceY)).coerceIn(0f, 1f)
            for (x in 0 until outWidth) {
                val sourceX = (x + 0.5f) * scaleX - 0.5f
                val x0 = floor(sourceX).toInt().coerceIn(0, sourceWidth - 1)
                val x1 = (x0 + 1).coerceAtMost(sourceWidth - 1)
                val fx = (sourceX - floor(sourceX)).coerceIn(0f, 1f)
                output[y * outWidth + x] = interpolatePremultiplied(
                    sourcePixels[y0 * sourceWidth + x0],
                    sourcePixels[y0 * sourceWidth + x1],
                    sourcePixels[y1 * sourceWidth + x0],
                    sourcePixels[y1 * sourceWidth + x1],
                    fx,
                    fy
                )
            }
        }
        return Bitmap.createBitmap(output, outWidth, outHeight, Bitmap.Config.ARGB_8888)
    }

    /** Joint X/Y subpixel search inspired by automaticSubpixelAlignment in v1.6. */
    private fun findBestSubpixelOffset(
        image: IntArray,
        watermark: IntArray,
        width: Int,
        height: Int,
        strength: Float,
        alphaThreshold: Int
    ): Pair<Float, Float> {
        if (width < 4 || height < 4) return 0f to 0f
        var bestX = 0f
        var bestY = 0f
        var bestScore = alignmentResidualScore(
            image,
            watermark,
            width,
            height,
            strength,
            alphaThreshold
        )
        for (yStep in -3..3) {
            for (xStep in -3..3) {
                if (xStep == 0 && yStep == 0) continue
                val dx = xStep * 0.25f
                val dy = yStep * 0.25f
                val shifted = shiftWatermarkPremultiplied(watermark, width, height, dx, dy)
                val score = alignmentResidualScore(
                    image,
                    shifted,
                    width,
                    height,
                    strength,
                    alphaThreshold
                ) + (abs(dx) + abs(dy)) * 1e-5
                if (score < bestScore) {
                    bestScore = score
                    bestX = dx
                    bestY = dy
                }
            }
        }
        return bestX to bestY
    }

    private fun alignmentResidualScore(
        image: IntArray,
        watermark: IntArray,
        width: Int,
        height: Int,
        strength: Float,
        alphaThreshold: Int
    ): Double {
        val step = max(1, min(width, height) / 96)
        var sumXY = 0.0
        var sumXX = 0.0
        var sumYY = 0.0
        var samples = 0
        var y = 1
        while (y < height - 1) {
            var x = 1
            while (x < width - 1) {
                val index = y * width + x
                val left = index - 1
                val above = index - width
                val alpha = (Color.alpha(watermark[index]) * strength).roundToInt().coerceIn(0, 250)
                val leftAlpha = (Color.alpha(watermark[left]) * strength).roundToInt().coerceIn(0, 250)
                val aboveAlpha = (Color.alpha(watermark[above]) * strength).roundToInt().coerceIn(0, 250)
                if (max(alpha, max(leftAlpha, aboveAlpha)) > alphaThreshold) {
                    fun restoredLumaAt(pixelIndex: Int, a: Int): Double {
                        val wm = watermark[pixelIndex]
                        val observed = image[pixelIndex]
                        val denominator = (255 - a).coerceAtLeast(1)
                        return rgbToY(
                            inverseChannel(Color.red(observed), Color.red(wm), a, denominator).toFloat(),
                            inverseChannel(Color.green(observed), Color.green(wm), a, denominator).toFloat(),
                            inverseChannel(Color.blue(observed), Color.blue(wm), a, denominator).toFloat()
                        ).toDouble()
                    }
                    val restored = restoredLumaAt(index, alpha)
                    val restoredLeft = restoredLumaAt(left, leftAlpha)
                    val restoredAbove = restoredLumaAt(above, aboveAlpha)
                    val wmLuma = rgbToY(
                        Color.red(watermark[index]).toFloat(),
                        Color.green(watermark[index]).toFloat(),
                        Color.blue(watermark[index]).toFloat()
                    ) * alpha / 255f
                    val wmLeft = rgbToY(
                        Color.red(watermark[left]).toFloat(),
                        Color.green(watermark[left]).toFloat(),
                        Color.blue(watermark[left]).toFloat()
                    ) * leftAlpha / 255f
                    val wmAbove = rgbToY(
                        Color.red(watermark[above]).toFloat(),
                        Color.green(watermark[above]).toFloat(),
                        Color.blue(watermark[above]).toFloat()
                    ) * aboveAlpha / 255f
                    val restoredX = restored - restoredLeft
                    val restoredY = restored - restoredAbove
                    val logoX = (wmLuma - wmLeft).toDouble()
                    val logoY = (wmLuma - wmAbove).toDouble()
                    sumXY += restoredX * logoX + restoredY * logoY
                    sumXX += restoredX * restoredX + restoredY * restoredY
                    sumYY += logoX * logoX + logoY * logoY
                    samples += 2
                }
                x += step
            }
            y += step
        }
        if (samples < 16 || sumXX <= 1e-6 || sumYY <= 1e-6) return Double.POSITIVE_INFINITY
        return abs(sumXY / kotlin.math.sqrt(sumXX * sumYY))
    }

    private fun shiftWatermarkPremultiplied(
        pixels: IntArray,
        width: Int,
        height: Int,
        offsetX: Float,
        offsetY: Float
    ): IntArray {
        if (abs(offsetX) < 0.001f && abs(offsetY) < 0.001f) return pixels
        val output = IntArray(pixels.size)
        for (y in 0 until height) {
            val sourceY = y - offsetY
            val yFloor = floor(sourceY).toInt()
            val fy = sourceY - yFloor
            for (x in 0 until width) {
                val sourceX = x - offsetX
                val xFloor = floor(sourceX).toInt()
                val fx = sourceX - xFloor
                fun pixel(sampleX: Int, sampleY: Int): Int =
                    if (sampleX in 0 until width && sampleY in 0 until height) {
                        pixels[sampleY * width + sampleX]
                    } else {
                        Color.TRANSPARENT
                    }
                output[y * width + x] = interpolatePremultiplied(
                    pixel(xFloor, yFloor),
                    pixel(xFloor + 1, yFloor),
                    pixel(xFloor, yFloor + 1),
                    pixel(xFloor + 1, yFloor + 1),
                    fx,
                    fy
                )
            }
        }
        return output
    }

    private fun interpolatePremultiplied(
        topLeft: Int,
        topRight: Int,
        bottomLeft: Int,
        bottomRight: Int,
        fx: Float,
        fy: Float
    ): Int {
        val weights = floatArrayOf(
            (1f - fx) * (1f - fy),
            fx * (1f - fy),
            (1f - fx) * fy,
            fx * fy
        )
        val colors = intArrayOf(topLeft, topRight, bottomLeft, bottomRight)
        var alpha = 0f
        var redPremultiplied = 0f
        var greenPremultiplied = 0f
        var bluePremultiplied = 0f
        for (index in colors.indices) {
            val normalizedAlpha = Color.alpha(colors[index]) / 255f
            val weight = weights[index]
            alpha += normalizedAlpha * weight
            redPremultiplied += Color.red(colors[index]) * normalizedAlpha * weight
            greenPremultiplied += Color.green(colors[index]) * normalizedAlpha * weight
            bluePremultiplied += Color.blue(colors[index]) * normalizedAlpha * weight
        }
        if (alpha <= 1e-6f) return Color.TRANSPARENT
        return Color.argb(
            (alpha * 255f).roundToInt().coerceIn(0, 255),
            (redPremultiplied / alpha).roundToInt().coerceIn(0, 255),
            (greenPremultiplied / alpha).roundToInt().coerceIn(0, 255),
            (bluePremultiplied / alpha).roundToInt().coerceIn(0, 255)
        )
    }

    private fun alignWatermarkPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        offsetX: Int,
        offsetY: Int
    ): IntArray {
        if (offsetX == 0 && offsetY == 0) return pixels
        val aligned = IntArray(pixels.size)
        for (y in 0 until height) {
            val sourceY = y - offsetY
            if (sourceY !in 0 until height) continue
            for (x in 0 until width) {
                val sourceX = x - offsetX
                if (sourceX in 0 until width) {
                    aligned[y * width + x] = pixels[sourceY * width + sourceX]
                }
            }
        }
        return aligned
    }

    /**
     * JPEG 4:2:0 stores chroma at lower spatial resolution than luma. Inverting
     * every RGB channel with the sharp PNG alpha therefore creates cyan/red
     * outlines. Blur alpha and premultiplied watermark contribution together in
     * YCbCr space, using a wider kernel only for chroma.
     */
    private fun buildJpegAwareCorrection(
        watermark: IntArray,
        width: Int,
        height: Int,
        strength: Float,
        options: Options
    ): JpegCorrection {
        val size = watermark.size
        val alpha = FloatArray(size)
        val premulY = FloatArray(size)
        val premulCb = FloatArray(size)
        val premulCr = FloatArray(size)
        for (i in watermark.indices) {
            val color = watermark[i]
            val a = (Color.alpha(color) / 255f * strength).coerceIn(0f, 0.92f)
            val r = Color.red(color).toFloat()
            val g = Color.green(color).toFloat()
            val b = Color.blue(color).toFloat()
            alpha[i] = a
            premulY[i] = a * rgbToY(r, g, b)
            premulCb[i] = a * rgbToCb(r, g, b)
            premulCr[i] = a * rgbToCr(r, g, b)
        }

        val radius = options.jpegFilterRadius.coerceIn(0, 8)
        val threshold = options.jpegFilterThreshold.coerceIn(0, 32)
        // JPEG 4:2:0 softens chroma much more than luma. Blurring luma alpha
        // leaves a bright/dark halo around the logo; keep luma geometrically
        // exact and only widen chroma. Radius 3 maps to sigma 1.35, calibrated
        // against downscaled 720 px Jjaptoon JPEG pages.
        val lumaSigma = if (radius == 0) 0f else min(0.35f, 0.15f + radius * 0.05f)
        val chromaSigma = if (radius == 0) 0f else 0.65f + radius * 0.25f
        val alphaY = gaussianBlur(alpha, width, height, lumaSigma)
        val alphaChroma = gaussianBlur(alpha, width, height, chromaSigma)
        val filteredY = gaussianBlur(premulY, width, height, lumaSigma)
        val filteredCb = gaussianBlur(premulCb, width, height, chromaSigma)
        val filteredCr = gaussianBlur(premulCr, width, height, chromaSigma)

        // The preset threshold is expressed in 8-bit alpha levels. Preserve exact
        // transparency away from the logo instead of modifying the whole rectangle.
        val minimum = threshold / 255f
        val maxAlpha = FloatArray(size)
        for (i in 0 until size) {
            if (max(alphaY[i], alphaChroma[i]) < minimum && alpha[i] <= minimum) {
                alphaY[i] = 0f
                alphaChroma[i] = 0f
                filteredY[i] = 0f
                filteredCb[i] = 0f
                filteredCr[i] = 0f
            }
            maxAlpha[i] = max(alphaY[i], alphaChroma[i])
        }
        return JpegCorrection(alphaY, alphaChroma, filteredY, filteredCb, filteredCr, maxAlpha)
    }

    /**
     * Selects a small correction around the user's strength. The score measures
     * residual logo-edge correlation after inverse compositing: under-correction
     * keeps the edge direction, over-correction reverses it, and the optimum is
     * close to zero. This is deterministic and uses no image model/network.
     */
    private fun tuneJpegStrength(
        observed: IntArray,
        watermark: IntArray,
        width: Int,
        height: Int,
        requestedStrength: Float,
        options: Options
    ): Float {
        if (width < 3 || height < 3) return requestedStrength
        val coarseFactors = floatArrayOf(0.78f, 0.84f, 0.90f, 0.96f, 1f, 1.04f, 1.10f, 1.16f, 1.22f)
        val step = max(1, min(width, height) / 180)
        var bestStrength = requestedStrength
        var bestScore = Double.POSITIVE_INFINITY

        fun evaluate(candidateStrength: Float) {
            val candidate = candidateStrength.coerceIn(0.45f, 1.5f)
            val correction = buildJpegAwareCorrection(watermark, width, height, candidate, options)
            var sumXY = 0.0
            var sumXX = 0.0
            var sumYY = 0.0
            var samples = 0
            var y = 1
            while (y < height) {
                var x = 1
                while (x < width) {
                    val index = y * width + x
                    val left = index - 1
                    val above = index - width
                    if (
                        correction.maxAlpha[index] > MIN_TUNE_ALPHA ||
                        correction.maxAlpha[left] > MIN_TUNE_ALPHA ||
                        correction.maxAlpha[above] > MIN_TUNE_ALPHA
                    ) {
                        val restored = restoredLuma(observed[index], index, correction)
                        val restoredLeft = restoredLuma(observed[left], left, correction)
                        val restoredAbove = restoredLuma(observed[above], above, correction)
                        val logoX = correction.premulY[index] - correction.premulY[left]
                        val logoY = correction.premulY[index] - correction.premulY[above]
                        val restoredX = restored - restoredLeft
                        val restoredY = restored - restoredAbove
                        sumXY += restoredX * logoX + restoredY * logoY
                        sumXX += restoredX * restoredX + restoredY * restoredY
                        sumYY += logoX * logoX + logoY * logoY
                        samples += 2
                    }
                    x += step
                }
                y += step
            }
            val correlation = if (samples > 16 && sumXX > 1e-6 && sumYY > 1e-6) {
                abs(sumXY / kotlin.math.sqrt(sumXX * sumYY))
            } else {
                1.0
            }
            val distancePenalty = abs(candidate - requestedStrength) * 0.002
            val score = correlation + distancePenalty
            if (score < bestScore) {
                bestScore = score
                bestStrength = candidate
            }
        }

        coarseFactors.forEach { factor -> evaluate(requestedStrength * factor) }
        val coarseBest = bestStrength
        floatArrayOf(0.965f, 0.98f, 0.99f, 1f, 1.01f, 1.02f, 1.035f)
            .forEach { factor -> evaluate(coarseBest * factor) }
        return bestStrength
    }

    private fun restoredLuma(observed: Int, index: Int, correction: JpegCorrection): Double {
        val sourceY = rgbToY(
            Color.red(observed).toFloat(),
            Color.green(observed).toFloat(),
            Color.blue(observed).toFloat()
        )
        return ((sourceY - correction.premulY[index]) /
            (1f - correction.alphaY[index]).coerceAtLeast(0.08f)).toDouble()
    }

    private fun inverseJpegAware(observed: Int, index: Int, correction: JpegCorrection): Int {
        val r = Color.red(observed).toFloat()
        val g = Color.green(observed).toFloat()
        val b = Color.blue(observed).toFloat()
        val y = ((rgbToY(r, g, b) - correction.premulY[index]) /
            (1f - correction.alphaY[index]).coerceAtLeast(0.08f)).coerceIn(0f, 255f)
        val cb = ((rgbToCb(r, g, b) - correction.premulCb[index]) /
            (1f - correction.alphaChroma[index]).coerceAtLeast(0.08f)).coerceIn(-128f, 127f)
        val cr = ((rgbToCr(r, g, b) - correction.premulCr[index]) /
            (1f - correction.alphaChroma[index]).coerceAtLeast(0.08f)).coerceIn(-128f, 127f)
        val outR = (y + 1.402f * cr).roundToInt().coerceIn(0, 255)
        val outG = (y - 0.344136f * cb - 0.714136f * cr).roundToInt().coerceIn(0, 255)
        val outB = (y + 1.772f * cb).roundToInt().coerceIn(0, 255)
        return Color.rgb(outR, outG, outB)
    }

    private fun rgbToY(r: Float, g: Float, b: Float): Float =
        0.299f * r + 0.587f * g + 0.114f * b

    private fun rgbToCb(r: Float, g: Float, b: Float): Float =
        -0.168736f * r - 0.331264f * g + 0.5f * b

    private fun rgbToCr(r: Float, g: Float, b: Float): Float =
        0.5f * r - 0.418688f * g - 0.081312f * b

    private fun gaussianBlur(
        input: FloatArray,
        width: Int,
        height: Int,
        sigma: Float
    ): FloatArray {
        if (sigma < 0.1f) return input.copyOf()
        val radius = max(1, kotlin.math.ceil(sigma * 3f).toInt())
        val kernel = FloatArray(radius * 2 + 1)
        var kernelSum = 0f
        for (i in -radius..radius) {
            val value = kotlin.math.exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + radius] = value
            kernelSum += value
        }
        for (i in kernel.indices) kernel[i] /= kernelSum

        val horizontal = FloatArray(input.size)
        val output = FloatArray(input.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) {
                    val sampleX = (x + k).coerceIn(0, width - 1)
                    sum += input[y * width + sampleX] * kernel[k + radius]
                }
                horizontal[y * width + x] = sum
            }
        }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) {
                    val sampleY = (y + k).coerceIn(0, height - 1)
                    sum += horizontal[sampleY * width + x] * kernel[k + radius]
                }
                output[y * width + x] = sum
            }
        }
        return output
    }

    /**
     * Reference HTML's JPEG filter applies a surface blur only where the
     * watermark transition mask is present. This bounded bilateral pass does
     * the same job without smearing line art across strong colour edges.
     */
    private fun suppressJpegResiduals(
        pixels: IntArray,
        support: FloatArray,
        width: Int,
        height: Int,
        options: Options
    ) {
        if (
            width < 2 || height < 2 || pixels.size != support.size ||
            pixels.size > MAX_SURFACE_FILTER_PIXELS
        ) return
        val radius = (options.jpegFilterRadius + 1).coerceIn(1, 4)
        // Keep the range gate narrow enough to remove JPEG ringing without
        // averaging across webtoon line art. The previous 10x multiplier blurred
        // the supplied fixture; a 4x slope lowered support MAE by about 11%.
        val rangeSigma = (8f + options.jpegFilterThreshold.coerceIn(0, 32) * 4f)
            .coerceAtLeast(8f)
        val rangeDenominator = 2f * rangeSigma * rangeSigma
        val spatialSigma = max(0.8f, radius * 0.75f)
        val spatialDenominator = 2f * spatialSigma * spatialSigma
        val minimumSupport = max(
            options.alphaThreshold / 255f,
            options.jpegFilterThreshold.coerceIn(0, 32) / 255f
        )
        val source = pixels.copyOf()

        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (support[index] <= minimumSupport) continue
                val center = source[index]
                val centerR = Color.red(center)
                val centerG = Color.green(center)
                val centerB = Color.blue(center)
                var red = 0f
                var green = 0f
                var blue = 0f
                var weightSum = 0f

                for (dy in -radius..radius) {
                    val sampleY = (y + dy).coerceIn(0, height - 1)
                    for (dx in -radius..radius) {
                        val sampleX = (x + dx).coerceIn(0, width - 1)
                        val sample = source[sampleY * width + sampleX]
                        val dr = (Color.red(sample) - centerR).toFloat()
                        val dg = (Color.green(sample) - centerG).toFloat()
                        val db = (Color.blue(sample) - centerB).toFloat()
                        val colourDistance = (dr * dr + dg * dg + db * db) / 3f
                        val spatialDistance = (dx * dx + dy * dy).toFloat()
                        val weight = kotlin.math.exp(
                            -colourDistance / rangeDenominator -
                                spatialDistance / spatialDenominator
                        )
                        red += Color.red(sample) * weight
                        green += Color.green(sample) * weight
                        blue += Color.blue(sample) * weight
                        weightSum += weight
                    }
                }
                if (weightSum > 0f) {
                    pixels[index] = Color.argb(
                        Color.alpha(center),
                        (red / weightSum).roundToInt().coerceIn(0, 255),
                        (green / weightSum).roundToInt().coerceIn(0, 255),
                        (blue / weightSum).roundToInt().coerceIn(0, 255)
                    )
                }
            }
        }
    }

    /** Lightweight integer alignment search using sparse edge residuals. */
    private fun findBestOffset(
        image: IntArray,
        watermark: IntArray,
        width: Int,
        height: Int,
        radius: Int,
        options: Options
    ): Pair<Int, Int> {
        val step = max(1, min(width, height) / 72)
        var bestX = 0
        var bestY = 0
        var bestError = Double.POSITIVE_INFINITY

        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                var error = 0.0
                var samples = 0
                var y = max(1, dy + 1)
                while (y < min(height - 1, height + dy - 1)) {
                    var x = max(1, dx + 1)
                    while (x < min(width - 1, width + dx - 1)) {
                        val wmX = x - dx
                        val wmY = y - dy
                        val wm = watermark[wmY * width + wmX]
                        val alpha = (Color.alpha(wm) * options.strength).roundToInt().coerceIn(0, 250)
                        if (alpha > options.alphaThreshold) {
                            val index = y * width + x
                            val denominator = 255 - alpha
                            val r = inverseChannel(Color.red(image[index]), Color.red(wm), alpha, denominator)
                            val g = inverseChannel(Color.green(image[index]), Color.green(wm), alpha, denominator)
                            val b = inverseChannel(Color.blue(image[index]), Color.blue(wm), alpha, denominator)
                            val left = image[index - 1]
                            val right = image[index + 1]
                            val meanR = (Color.red(left) + Color.red(right)) / 2
                            val meanG = (Color.green(left) + Color.green(right)) / 2
                            val meanB = (Color.blue(left) + Color.blue(right)) / 2
                            error += abs(r - meanR) + abs(g - meanG) + abs(b - meanB)
                            samples++
                        }
                        x += step
                    }
                    y += step
                }
                if (samples > 0) error /= samples.toDouble()
                if (samples > 0 && error < bestError) {
                    bestError = error
                    bestX = dx
                    bestY = dy
                }
            }
        }
        return bestX to bestY
    }

    private fun stableNeighbour(pixels: IntArray, width: Int, height: Int, x: Int, y: Int): Int {
        val candidates = intArrayOf(
            y * width + (x - 1).coerceAtLeast(0),
            y * width + (x + 1).coerceAtMost(width - 1),
            (y - 1).coerceAtLeast(0) * width + x,
            (y + 1).coerceAtMost(height - 1) * width + x
        )
        var red = 0
        var green = 0
        var blue = 0
        var alpha = 0
        candidates.forEach { index ->
            val color = pixels[index]
            red += Color.red(color)
            green += Color.green(color)
            blue += Color.blue(color)
            alpha += Color.alpha(color)
        }
        return Color.argb(alpha / candidates.size, red / candidates.size, green / candidates.size, blue / candidates.size)
    }

    private fun lerp(from: Int, to: Int, amount: Float): Int =
        (from + (to - from) * amount).roundToInt().coerceIn(0, 255)

    const val DEFAULT_BEST_STRENGTH = 0.94f
    private const val MAX_AUTO_TUNE_PIXELS = 250_000
    private const val MAX_SURFACE_FILTER_PIXELS = 1_000_000
    private const val MIN_TUNE_ALPHA = 0.015f
}
