package com.vasiliastyper.engine

import android.graphics.Color
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

/**
 * WebtoonContextEnhancer — Post-processing for local ONNX inpainting output.
 *
 * FIX vs original version:
 *   The original `restoreGradients()` step applied a wide (15 px Gaussian) soft
 *   mask to blend the ORIGINAL image back into the model result.  For a typical
 *   text-bubble mask (~30–80 px wide) the 15 px blur dominated the entire mask
 *   area, causing 30–50 % of the original to bleed through — making model output
 *   look like Telea / algorithmic inpainting.  That step is removed.
 *
 * Current pipeline (two passes only):
 *   ① adaptLocalColors — match mean LAB colour at the boundary ring to eliminate
 *      any colour shift between the generated fill and surrounding artwork.
 *   ② blendStructurally — distance-transform feathering: original outside mask,
 *      enhanced inside, capped feather width (15 px max) so the centre of even
 *      small masks is fully filled by model output with no original bleed.
 *
 * New public method `adaptColorsOnly` is used by LamaMangaInpainter on the
 * already-Gaussian-blended result to fix any residual colour shift.
 *
 * Thread-safe; all state in local Mat objects.
 */
class WebtoonContextEnhancer {

    // ── Called by LamaMangaInpainter (dynamic bbox pipeline) ───────────────────────────

    /**
     * Adjust colours in the masked region to match the surrounding boundary ring,
     * without any structural blending with the original (the ONNX model already handled that).
     */
    fun adaptColorsOnly(
        original:  IntArray,
        inpainted: IntArray,
        mask:      BooleanArray,
        W: Int, H: Int
    ): IntArray {
        if (!OpenCvInit.ensureInit()) return inpainted
        if (!mask.any { it }) return inpainted

        val origMat = intArrayToRgbMat(original,  W, H)
        val inpMat  = intArrayToRgbMat(inpainted, W, H)
        val maskMat = boolArrayToBinaryMat(mask, W, H)

        return try {
            val adapted = adaptLocalColors(origMat, inpMat, maskMat)
            try {
                val adapPx = rgbMatToIntArray(adapted, W, H)
                IntArray(W * H) { i -> if (mask[i]) adapPx[i] else original[i] }
            } finally { adapted.release() }
        } catch (e: Exception) {
            e.printStackTrace()
            inpainted
        } finally {
            origMat.release(); inpMat.release(); maskMat.release()
        }
    }

    // ── Called by refineInPlace (legacy tiling path, kept for compatibility) ──

    /**
     * Enhance model output for webtoon/manga backgrounds.
     * restoreGradients intentionally removed — see class KDoc for reason.
     */
    fun enhance(
        originalTile: Mat,
        inpaintedTile: Mat,
        mask: Mat
    ): Mat {
        val colorAdapted = adaptLocalColors(originalTile, inpaintedTile, mask)
        val blended      = blendStructurally(originalTile, colorAdapted, mask)
        colorAdapted.release()
        return blended
    }

    /**
     * Drop-in replacement for WebtoonRefiner.refineInPlace().
     * Only pixels where [tileMask] is true are changed.
     */
    fun refineInPlace(
        originalPixels:  IntArray,
        inpaintedPixels: IntArray,
        tileMask:        BooleanArray,
        tw: Int, th: Int
    ) {
        if (!OpenCvInit.ensureInit()) return
        if (!tileMask.any { it }) return

        val origMat  = intArrayToRgbMat(originalPixels,  tw, th)
        val inpMat   = intArrayToRgbMat(inpaintedPixels, tw, th)
        val maskMat  = boolArrayToBinaryMat(tileMask, tw, th)

        try {
            val enhanced = enhance(origMat, inpMat, maskMat)
            try {
                val resultPixels = rgbMatToIntArray(enhanced, tw, th)
                for (i in inpaintedPixels.indices) {
                    if (tileMask[i]) inpaintedPixels[i] = resultPixels[i]
                }
            } finally { enhanced.release() }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            origMat.release(); inpMat.release(); maskMat.release()
        }
    }

    // ── Pipeline steps ────────────────────────────────────────────────────────

    /**
     * Match local colour statistics at boundary to eliminate colour shift.
     * Works in LAB space for perceptual accuracy.
     */
    private fun adaptLocalColors(original: Mat, inpainted: Mat, mask: Mat): Mat {
        val labOrig = Mat(); Imgproc.cvtColor(original,  labOrig, Imgproc.COLOR_RGB2Lab)
        val labInp  = Mat(); Imgproc.cvtColor(inpainted, labInp,  Imgproc.COLOR_RGB2Lab)
        val result  = labInp.clone()
        val mask32f = Mat(); mask.convertTo(mask32f, CvType.CV_32F, 1.0 / 255.0)

        // FIX: kernel must be released explicitly — it was leaking before
        val kernel  = Mat.ones(Size(11.0, 11.0), CvType.CV_8U)
        val dilated = Mat(); val eroded = Mat()
        Imgproc.dilate(mask32f, dilated, kernel, Point(-1.0, -1.0), 4)
        Imgproc.erode( mask32f, eroded,  kernel, Point(-1.0, -1.0), 2)
        val boundary = Mat(); Core.subtract(dilated, eroded, boundary)
        Imgproc.threshold(boundary, boundary, 0.5, 1.0, Imgproc.THRESH_BINARY)
        val boundary8u = Mat(); boundary.convertTo(boundary8u, CvType.CV_8U, 255.0)

        for (ch in 0..2) {
            val origCh = Mat(); Core.extractChannel(labOrig, origCh, ch)
            val inpCh  = Mat(); Core.extractChannel(labInp,  inpCh,  ch)
            val origMean = Core.mean(origCh, boundary8u).`val`[0]
            val inpMean  = Core.mean(inpCh,  boundary8u).`val`[0]
            val diff     = origMean - inpMean
            if (kotlin.math.abs(diff) > 0.5) {
                val adjusted = Mat()
                Core.add(inpCh, Scalar(diff, 0.0, 0.0, 0.0), adjusted)
                Core.insertChannel(adjusted, result, ch)
                adjusted.release()
            }
            origCh.release(); inpCh.release()
        }
        Imgproc.cvtColor(result, result, Imgproc.COLOR_Lab2RGB)
        // FIX: kernel added to release list — was omitted before, causing a Mat leak
        listOf(labOrig, labInp, mask32f, dilated, eroded, boundary, boundary8u, kernel).forEach { it.release() }
        return result
    }

    /**
     * Feathered blend: original outside mask, enhanced inside.
     *
     * FIX vs original: distance transform clamped at FEATHER_PX (15 px) instead
     * of NORM_MINMAX, so the centre of even small masks is always alpha=1.0
     * (fully model output) with no original bleed-through.
     */
    private fun blendStructurally(original: Mat, enhanced: Mat, mask: Mat): Mat {
        val featherPx = 15.0

        val dist = Mat()
        Imgproc.distanceTransform(mask, dist, Imgproc.DIST_L2, 3)
        // Clamp at featherPx → divide → [0,1]; pixels >15 px inside mask = 1.0
        // Note: Imgproc.threshold with THRESH_TRUNC clips values above the threshold.
        Imgproc.threshold(dist, dist, featherPx, featherPx, Imgproc.THRESH_TRUNC)
        Core.divide(dist, Scalar(featherPx), dist)
        Imgproc.GaussianBlur(dist, dist, Size(9.0, 9.0), 0.0)

        // FIX: store clones in named variables so they can be released after merge.
        // Previously dist.clone() created untracked Mats that were never released.
        val distClone1 = dist.clone()
        val distClone2 = dist.clone()
        val dist3 = Mat()
        Core.merge(listOf(dist, distClone1, distClone2), dist3)
        dist.release(); distClone1.release(); distClone2.release()

        val ones3    = Mat(dist3.size(), dist3.type(), Scalar(1.0, 1.0, 1.0))
        val invDist3 = Mat(); Core.subtract(ones3, dist3, invDist3); ones3.release()

        val orig32f = Mat(); original.convertTo(orig32f, CvType.CV_32F)
        val enh32f  = Mat(); enhanced.convertTo(enh32f,  CvType.CV_32F)

        val bgPart    = Mat(); Core.multiply(orig32f, invDist3, bgPart)
        val fgPart    = Mat(); Core.multiply(enh32f,  dist3,    fgPart)
        val blended32 = Mat(); Core.add(bgPart, fgPart, blended32)

        val result = Mat(); blended32.convertTo(result, CvType.CV_8U)
        listOf(dist3, invDist3, orig32f, enh32f, bgPart, fgPart, blended32).forEach { it.release() }
        return result
    }

    // ── Conversion helpers ────────────────────────────────────────────────────

    private fun intArrayToRgbMat(pixels: IntArray, w: Int, h: Int): Mat {
        val mat = Mat(h, w, CvType.CV_8UC3)
        val buf = ByteArray(w * h * 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            buf[i * 3]     = Color.red(p).toByte()
            buf[i * 3 + 1] = Color.green(p).toByte()
            buf[i * 3 + 2] = Color.blue(p).toByte()
        }
        mat.put(0, 0, buf); return mat
    }

    private fun rgbMatToIntArray(mat: Mat, w: Int, h: Int): IntArray {
        val buf = ByteArray(w * h * 3)
        mat.get(0, 0, buf)
        return IntArray(w * h) { i ->
            Color.rgb(
                buf[i * 3].toInt() and 0xFF,
                buf[i * 3 + 1].toInt() and 0xFF,
                buf[i * 3 + 2].toInt() and 0xFF
            )
        }
    }

    private fun boolArrayToBinaryMat(mask: BooleanArray, w: Int, h: Int): Mat {
        val mat = Mat(h, w, CvType.CV_8UC1)
        val buf = ByteArray(w * h) { i -> if (mask[i]) 255.toByte() else 0.toByte() }
        mat.put(0, 0, buf); return mat
    }
}
