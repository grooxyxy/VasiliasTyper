package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Photoshop/Krita-style clone stamp core adapted for the Android canvas.
 *
 * The sampled layer is frozen when the source point is armed. This prevents a
 * stroke from sampling pixels it has just painted. Aligned mode keeps the same
 * source-to-destination offset between strokes, and each drag is committed as
 * one workspace-history operation by [CanvasView].
 */
class CloneStampEngine {
    companion object {
        private const val MAX_SNAPSHOT_PIXELS = 24_000_000L
    }

    var brushSize = 48f
    var hardness = 0.75f
    var opacity = 100
    var aligned = true

    private var sourceSnapshot: Bitmap? = null
    private var sampledPoint: PointF? = null
    private var strokeOffset: PointF? = null
    private var lastPoint: PointF? = null
    private var cachedMask: Bitmap? = null
    private var cachedMaskSize = -1
    private var cachedHardness = -1f

    val hasSource: Boolean
        get() = sourceSnapshot?.isRecycled == false && sampledPoint != null

    val sourcePoint: PointF?
        get() = sampledPoint?.let { PointF(it.x, it.y) }

    fun sampleSource(bitmap: Bitmap, point: PointF): Boolean {
        if (bitmap.isRecycled || bitmap.width.toLong() * bitmap.height > MAX_SNAPSHOT_PIXELS) {
            return false
        }
        sourceSnapshot?.let { if (!it.isRecycled) it.recycle() }
        sourceSnapshot = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        sampledPoint = PointF(point.x, point.y)
        strokeOffset = null
        lastPoint = null
        return sourceSnapshot != null
    }

    fun clearSource() {
        sourceSnapshot?.let { if (!it.isRecycled) it.recycle() }
        sourceSnapshot = null
        sampledPoint = null
        strokeOffset = null
        lastPoint = null
    }

    fun beginStroke(destination: PointF): Boolean {
        val source = sampledPoint ?: return false
        if (!aligned || strokeOffset == null) {
            strokeOffset = PointF(source.x - destination.x, source.y - destination.y)
        }
        lastPoint = null
        return true
    }

    fun continueStroke(destinationCanvas: Canvas, destination: PointF) {
        val previous = lastPoint
        val spacing = max(1.5f, brushSize * 0.12f)
        if (previous == null) {
            paintDab(destinationCanvas, destination)
        } else {
            val distance = hypot(destination.x - previous.x, destination.y - previous.y)
            val steps = max(1, ceil(distance / spacing).toInt())
            for (step in 1..steps) {
                val t = step / steps.toFloat()
                paintDab(
                    destinationCanvas,
                    PointF(
                        previous.x + (destination.x - previous.x) * t,
                        previous.y + (destination.y - previous.y) * t
                    )
                )
            }
        }
        lastPoint = PointF(destination.x, destination.y)
    }

    fun endStroke() {
        lastPoint = null
        if (!aligned) strokeOffset = null
    }

    fun sourceForDestination(destination: PointF): PointF? {
        val offset = strokeOffset ?: sampledPoint?.let {
            PointF(it.x - destination.x, it.y - destination.y)
        } ?: return null
        return PointF(destination.x + offset.x, destination.y + offset.y)
    }

    /** Draws the frozen sampled pixels clipped inside the live brush ring. */
    fun drawPreview(canvas: Canvas, destination: PointF, previewAlpha: Int = 145) {
        val snapshot = sourceSnapshot ?: return
        val sourceCenter = sourceForDestination(destination) ?: return
        val radius = brushSize / 2f
        val checkpoint = canvas.save()
        val clip = Path().apply { addCircle(destination.x, destination.y, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = previewAlpha.coerceIn(0, 255)
        }
        canvas.drawBitmap(
            snapshot,
            destination.x - sourceCenter.x,
            destination.y - sourceCenter.y,
            paint
        )
        canvas.restoreToCount(checkpoint)
    }

    private fun paintDab(destinationCanvas: Canvas, destination: PointF) {
        val snapshot = sourceSnapshot ?: return
        val sourceCenter = sourceForDestination(destination) ?: return
        val size = ceil(brushSize.coerceAtLeast(1f)).toInt().coerceAtLeast(1)
        val half = size / 2f
        val dab = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val dabCanvas = Canvas(dab)
        dabCanvas.drawBitmap(
            snapshot,
            half - sourceCenter.x,
            half - sourceCenter.y,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        dabCanvas.drawBitmap(
            alphaMask(size),
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            }
        )
        destinationCanvas.drawBitmap(
            dab,
            destination.x - half,
            destination.y - half,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (opacity.coerceIn(0, 100) * 255 / 100)
            }
        )
        dab.recycle()
    }

    private fun alphaMask(size: Int): Bitmap {
        val normalizedHardness = hardness.coerceIn(0f, 1f)
        val existing = cachedMask
        if (existing != null && !existing.isRecycled &&
            cachedMaskSize == size && cachedHardness == normalizedHardness
        ) {
            return existing
        }
        existing?.let { if (!it.isRecycled) it.recycle() }
        val mask = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val center = size / 2f
        val radius = max(0.5f, center)
        val hardStop = normalizedHardness.coerceAtMost(0.999f)
        val shader = if (hardStop <= 0f) {
            RadialGradient(
                center,
                center,
                radius,
                Color.WHITE,
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )
        } else {
            RadialGradient(
                center,
                center,
                radius,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, hardStop, 1f),
                Shader.TileMode.CLAMP
            )
        }
        Canvas(mask).drawCircle(
            center,
            center,
            radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        )
        cachedMask = mask
        cachedMaskSize = size
        cachedHardness = normalizedHardness
        return mask
    }
}
