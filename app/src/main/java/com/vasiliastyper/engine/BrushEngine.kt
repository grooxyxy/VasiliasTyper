package com.vasiliastyper.engine

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Regular colour brush shared by the canvas and its option controls.
 *
 * The engine keeps a small amount of stroke state so the canvas can draw a
 * smoother line with lower allocation pressure on every move event.
 */
class BrushEngine {
    /**
     * PATCH stamps overlapping circular dabs (the existing raster-style brush).
     * VECTOR_LINE renders every gesture segment as a continuous anti-aliased line.
     */
    enum class Mode { PATCH, VECTOR_LINE }

    private val path = Path()
    private var lastX = 0f
    private var lastY = 0f
    private var stabilizedX = 0f
    private var stabilizedY = 0f
    private var hasStabilizedPoint = false

    var brushSize: Float = 20f
        set(value) { field = value.takeIf(Float::isFinite)?.coerceIn(1f, 201f) ?: 20f }
    var color: Int = Color.BLACK
    var opacity: Int = 100
        set(value) { field = value.coerceIn(0, 100) }
    var hardness: Float = 0.9f
        set(value) { field = value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.9f }
    var mode: Mode = Mode.PATCH

    /** 0 = raw pen, 1 = heavy stabilizer. */
    var stabilizer: Float = 0.72f
        set(value) { field = value.takeIf(Float::isFinite)?.coerceIn(0f, 0.95f) ?: 0.72f }

    /** 0 = no fade, 1 = strong fade. */
    var forceFade: Float = 0.22f
        set(value) { field = value.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.22f }

    /** Distance between dabs as a fraction of brush diameter (0.01..1.0). */
    var spacing: Float = 0.12f
        set(value) { field = value.takeIf(Float::isFinite)?.coerceIn(0.01f, 1f) ?: 0.12f }

    /** Paint deposited by each dab (0.01..1.0), independent from global opacity. */
    var flow: Float = 1f
        set(value) { field = value.takeIf(Float::isFinite)?.coerceIn(0.01f, 1f) ?: 1f }

    private data class PaintKey(
        val style: Paint.Style,
        val mode: Mode,
        val brushSize: Float,
        val color: Int,
        val opacity: Int,
        val hardness: Float,
        val stabilizer: Float,
        val forceFade: Float,
        val spacing: Float,
        val flow: Float
    )

    private var cachedStrokeKey: PaintKey? = null
    private var cachedStrokePaint: Paint? = null
    private var cachedFillKey: PaintKey? = null
    private var cachedFillPaint: Paint? = null

    fun beginStroke(x: Float, y: Float) {
        path.reset()
        if (!x.isFinite() || !y.isFinite()) {
            hasStabilizedPoint = false
            return
        }
        path.moveTo(x, y)
        lastX = x
        lastY = y
        stabilizedX = x
        stabilizedY = y
        hasStabilizedPoint = true
    }

    fun stabilizePoint(x: Float, y: Float): Pair<Float, Float> {
        if (!x.isFinite() || !y.isFinite()) return lastX to lastY
        if (!hasStabilizedPoint) {
            beginStroke(x, y)
            return x to y
        }

        val response = (1f - stabilizer.coerceIn(0f, 0.95f)).coerceIn(0.05f, 1f)
        stabilizedX += (x - stabilizedX) * response
        stabilizedY += (y - stabilizedY) * response
        return stabilizedX to stabilizedY
    }

    fun continueStroke(x: Float, y: Float) {
        val (sx, sy) = stabilizePoint(x, y)
        val midX = (lastX + sx) / 2f
        val midY = (lastY + sy) / 2f
        path.quadTo(lastX, lastY, midX, midY)
        lastX = sx
        lastY = sy
    }

    fun endStroke(canvas: Canvas) {
        canvas.drawPath(path, createStrokePaint())
        cancelStroke()
    }

    /** Discards pending path state when a gesture/tool is cancelled. */
    fun cancelStroke() {
        path.reset()
        hasStabilizedPoint = false
        lastX = 0f
        lastY = 0f
        stabilizedX = 0f
        stabilizedY = 0f
    }

    fun drawPoint(canvas: Canvas, x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        canvas.drawCircle(x, y, brushSize / 2f, createDabPaint())
    }

    fun drawSegment(canvas: Canvas, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        if (!fromX.isFinite() || !fromY.isFinite() || !toX.isFinite() || !toY.isFinite()) return
        val (sx, sy) = stabilizePoint(toX, toY)
        if (mode == Mode.VECTOR_LINE) {
            canvas.drawLine(fromX, fromY, sx, sy, createStrokePaint())
            return
        }

        val dx = sx - fromX
        val dy = sy - fromY
        val distance = hypot(dx, dy)
        val dabDistance = (brushSize * spacing.coerceIn(0.01f, 1f)).coerceAtLeast(1f)
        val steps = ceil(distance / dabDistance).toInt().coerceAtLeast(1)
        val paint = createDabPaint()
        for (step in 1..steps) {
            val fraction = step.toFloat() / steps
            canvas.drawCircle(
                fromX + dx * fraction,
                fromY + dy * fraction,
                brushSize / 2f,
                paint
            )
        }
    }

    fun createStrokePaint(): Paint = cachedStrokePaintFor(Paint.Style.STROKE)

    fun createDabPaint(): Paint = cachedStrokePaintFor(Paint.Style.FILL)

    private fun cachedStrokePaintFor(paintStyle: Paint.Style): Paint {
        val key = PaintKey(
            style = paintStyle,
            mode = mode,
            brushSize = brushSize,
            color = color,
            opacity = opacity,
            hardness = hardness,
            stabilizer = stabilizer,
            forceFade = forceFade,
            spacing = spacing,
            flow = flow
        )

        if (paintStyle == Paint.Style.STROKE) {
            val cached = cachedStrokePaint
            if (cached != null && cachedStrokeKey == key) return cached
            val built = buildPaint(paintStyle)
            cachedStrokePaint = built
            cachedStrokeKey = key
            return built
        }

        val cached = cachedFillPaint
        if (cached != null && cachedFillKey == key) return cached
        val built = buildPaint(paintStyle)
        cachedFillPaint = built
        cachedFillKey = key
        return built
    }

    private fun buildPaint(paintStyle: Paint.Style): Paint {
        val baseAlpha = (opacity / 100f * flow.coerceIn(0.01f, 1f) * 255f).toInt().coerceIn(0, 255)
        val fadePenalty = (forceFade.coerceIn(0f, 1f) * 0.28f).coerceIn(0f, 0.28f)
        val alpha = (baseAlpha * (1f - fadePenalty)).toInt().coerceIn(0, 255)
        val softness = (1f - hardness.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        val blur = if (softness > 0.001f) {
            max((brushSize * softness * 0.45f), 0.5f)
        } else {
            0f
        }

        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@BrushEngine.color
            this.alpha = alpha
            style = paintStyle
            strokeWidth = brushSize.coerceAtLeast(1f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            if (blur > 0f) {
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            }
        }
    }
}
