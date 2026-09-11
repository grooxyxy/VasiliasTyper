package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Touch-friendly healing brush based on CzJLee/Blemish-Removal.
 *
 * The user selects the blemish (target) first and a clean texture (source)
 * second. The clean patch is blended at the target with OpenCV NORMAL_CLONE,
 * which preserves the target illumination instead of simply copying pixels.
 */
object BlemishRemovalEngine {

    data class Result(val success: Boolean, val message: String)

    fun heal(
        bitmap: Bitmap,
        target: PointF,
        source: PointF,
        requestedRadius: Float
    ): Result {
        if (bitmap.isRecycled || !bitmap.isMutable) {
            return Result(false, "Layer harus berupa bitmap mutable")
        }
        if (!OpenCvInit.ensureInit()) {
            return Result(false, "OpenCV tidak dapat diinisialisasi")
        }

        val radius = requestedRadius.roundToInt().coerceIn(3, 160)
        if (!circleFits(source, radius, bitmap.width, bitmap.height)) {
            return Result(false, "Area sumber terlalu dekat tepi kanvas")
        }
        if (!circleFits(target, radius, bitmap.width, bitmap.height)) {
            return Result(false, "Blemish terlalu dekat tepi kanvas")
        }

        // Keep the destination crop compact. seamlessClone does not need the
        // source and destination to live in the same Mat, so distant samples do
        // not allocate a huge strip on tall webtoon pages.
        val margin = max(12, ceil(radius * 0.75f).toInt())
        val destinationRect = Rect(
            (target.x.roundToInt() - radius - margin).coerceAtLeast(0),
            (target.y.roundToInt() - radius - margin).coerceAtLeast(0),
            (target.x.roundToInt() + radius + margin + 1).coerceAtMost(bitmap.width),
            (target.y.roundToInt() + radius + margin + 1).coerceAtMost(bitmap.height)
        )
        val sourceRect = Rect(
            source.x.roundToInt() - radius,
            source.y.roundToInt() - radius,
            source.x.roundToInt() + radius + 1,
            source.y.roundToInt() + radius + 1
        )

        val destinationBitmap = Bitmap.createBitmap(
            bitmap,
            destinationRect.left,
            destinationRect.top,
            destinationRect.width(),
            destinationRect.height()
        )
        val sourceBitmap = Bitmap.createBitmap(
            bitmap,
            sourceRect.left,
            sourceRect.top,
            sourceRect.width(),
            sourceRect.height()
        )

        val sourceRgba = Mat()
        val sourceRgb = Mat()
        val destinationRgba = Mat()
        val destinationRgb = Mat()
        val mask = Mat(sourceRect.height(), sourceRect.width(), CvType.CV_8UC1, Scalar(0.0))
        val blendedRgb = Mat()
        val blendedRgba = Mat()
        var outputBitmap: Bitmap? = null

        return try {
            Utils.bitmapToMat(sourceBitmap, sourceRgba)
            Utils.bitmapToMat(destinationBitmap, destinationRgba)
            Imgproc.cvtColor(sourceRgba, sourceRgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(destinationRgba, destinationRgb, Imgproc.COLOR_RGBA2RGB)

            Imgproc.circle(
                mask,
                Point(radius.toDouble(), radius.toDouble()),
                radius,
                Scalar(255.0),
                -1,
                Imgproc.LINE_AA,
                0
            )
            // A small blur feathers the circular patch, matching the reference
            // implementation while avoiding a visible hard ring.
            Imgproc.GaussianBlur(mask, mask, org.opencv.core.Size(5.0, 5.0), 0.0)

            val localTarget = Point(
                (target.x - destinationRect.left).toDouble(),
                (target.y - destinationRect.top).toDouble()
            )
            Photo.seamlessClone(
                sourceRgb,
                destinationRgb,
                mask,
                localTarget,
                blendedRgb,
                Photo.NORMAL_CLONE
            )
            Imgproc.cvtColor(blendedRgb, blendedRgba, Imgproc.COLOR_RGB2RGBA)
            outputBitmap = Bitmap.createBitmap(
                destinationRect.width(),
                destinationRect.height(),
                Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(blendedRgba, outputBitmap)

            val pixels = IntArray(destinationRect.width() * destinationRect.height())
            outputBitmap.getPixels(
                pixels, 0, destinationRect.width(), 0, 0,
                destinationRect.width(), destinationRect.height()
            )
            bitmap.setPixels(
                pixels, 0, destinationRect.width(),
                destinationRect.left, destinationRect.top,
                destinationRect.width(), destinationRect.height()
            )
            Result(true, "Blemish berhasil di-heal")
        } catch (error: Throwable) {
            Result(false, "Healing gagal: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            sourceRgba.release()
            sourceRgb.release()
            destinationRgba.release()
            destinationRgb.release()
            mask.release()
            blendedRgb.release()
            blendedRgba.release()
            sourceBitmap.recycle()
            destinationBitmap.recycle()
            outputBitmap?.recycle()
        }
    }

    private fun circleFits(point: PointF, radius: Int, width: Int, height: Int): Boolean {
        return point.x - radius >= 0f && point.y - radius >= 0f &&
            point.x + radius < width && point.y + radius < height
    }
}
