package com.vasiliastyper.engine

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfRect
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.MSER
import org.opencv.imgproc.Imgproc

/**
 * PreciseTextSegmenter
 *
 * Helper kecil untuk menghasilkan mask piksel teks yang ketat di area ROI.
 * Dipakai oleh TextMaskEngine dan SmartPixelMaskRefiner.
 */
object PreciseTextSegmenter {

    private const val MSER_DELTA = 5
    // FIX #2: turunkan ambang agar teks kecil/tipis ikut jadi mask bentuk teks.
    private const val MSER_MIN_AREA = 12
    private const val MSER_MAX_AREA = 80_000
    private const val MSER_MAX_VARIATION = 0.28
    private const val MSER_MIN_DIVERSITY = 0.18
    private const val MIN_HEIGHT_PX = 3

    fun extractTextMask(rgb: Mat, fullMask: Mat): Mat {
        val width = rgb.cols()
        val height = rgb.rows()
        val result = Mat.zeros(height, width, CvType.CV_8UC1)

        if (width <= 0 || height <= 0) return result

        val gray = Mat()
        val blur = Mat()
        val mserMask = Mat.zeros(height, width, CvType.CV_8UC1)
        val adaptiveMask = Mat.zeros(height, width, CvType.CV_8UC1)
        val combined = Mat.zeros(height, width, CvType.CV_8UC1)

        try {
            when (rgb.channels()) {
                1 -> rgb.copyTo(gray)
                3 -> Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
                4 -> Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGBA2GRAY)
                else -> Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
            }

            Imgproc.GaussianBlur(gray, blur, Size(3.0, 3.0), 0.0)

            buildMserMask(gray, mserMask)
            buildAdaptiveMask(blur, adaptiveMask)

            Core.bitwise_or(mserMask, adaptiveMask, combined)

            if (!fullMask.empty()) {
                val roi = Mat()
                try {
                    Core.bitwise_and(combined, fullMask, roi)
                    roi.copyTo(result)
                } finally {
                    roi.release()
                }
            } else {
                combined.copyTo(result)
            }

            return result
        } catch (_: Throwable) {
            return result
        } finally {
            gray.release()
            blur.release()
            mserMask.release()
            adaptiveMask.release()
            combined.release()
        }
    }

    private fun buildMserMask(gray: Mat, out: Mat) {
        out.setTo(Scalar(0.0))
        try {
            val mser = MSER.create(
                MSER_DELTA, MSER_MIN_AREA, MSER_MAX_AREA,
                MSER_MAX_VARIATION, MSER_MIN_DIVERSITY,
                200, 1.01, 0.003, 5
            )
            val bboxes = MatOfRect()
            val points = ArrayList<org.opencv.core.MatOfPoint>()
            try {
                mser.detectRegions(gray, points, bboxes)

                val rects = bboxes.toArray()
                for (i in rects.indices) {
                    val r = rects[i]
                    val area = r.width.toLong() * r.height.toLong()
                    val aspect = r.width.toDouble() / r.height.coerceAtLeast(1)
                    if (r.height < MIN_HEIGHT_PX || r.width < 2) continue
                    if (area < MSER_MIN_AREA || area > MSER_MAX_AREA) continue
                    if (aspect < 0.04 || aspect > 14.0) continue

                    // Gunakan piksel region MSER asli, bukan bounding box penuh.
                    // Bounding box lama menghapus ruang di antara huruf dan dapat
                    // menyentuh outline balon saat Fill White/inpainting.
                    Imgproc.drawContours(out, points, i, Scalar(255.0), -1)
                }
            } finally {
                points.forEach { it.release() }
                bboxes.release()
            }
        } catch (_: Throwable) {
            // Ignore MSER issues; adaptive mask still provides fallback.
        }
    }

    private fun buildAdaptiveMask(blur: Mat, out: Mat) {
        out.setTo(Scalar(0.0))
        val tmp = Mat()
        val totalPixels = (blur.cols().toDouble() * blur.rows().toDouble()).coerceAtLeast(1.0)
        try {
            fun addCandidate(candidate: Mat) {
                val coverage = Core.countNonZero(candidate).toDouble() / totalPixels
                if (coverage in 0.00005..0.35) {
                    Core.bitwise_or(out, candidate, out)
                }
            }

            // One stable adaptive pass is enough when combined with MSER and
            // black/white-hat masks. The former three-pass loop tripled ROI work
            // and tended to collect anti-aliased bubble outlines as text.
            Imgproc.adaptiveThreshold(
                blur, tmp, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                21, 10.0
            )
            addCandidate(tmp)

            val kernel9 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            val tophat = Mat()
            val blackhat = Mat()
            try {
                Imgproc.morphologyEx(blur, tophat, Imgproc.MORPH_TOPHAT, kernel9)
                Imgproc.morphologyEx(blur, blackhat, Imgproc.MORPH_BLACKHAT, kernel9)
                Imgproc.threshold(tophat, tophat, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                Imgproc.threshold(blackhat, blackhat, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
                addCandidate(tophat)
                addCandidate(blackhat)
            } finally {
                kernel9.release()
                tophat.release()
                blackhat.release()
            }

            // Deliberately skip the old Canny pass: it mostly selected speech
            // bubble/panel edges, created halos, and added another full ROI scan.
        } finally {
            tmp.release()
        }
    }
}
