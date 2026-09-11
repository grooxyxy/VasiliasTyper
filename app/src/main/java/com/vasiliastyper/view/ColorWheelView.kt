package com.vasiliastyper.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Smooth HSV picker with a vector-rendered hue ring and saturation/value panel.
 *
 * The previous implementation rasterised the wheel pixel-by-pixel. On compact
 * screens the circular edges and hue transitions therefore looked stepped.
 * Gradients and anti-aliased vector strokes keep both the ring and its edges
 * smooth at every density without rebuilding a large bitmap while dragging.
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var onColorChanged: ((Int) -> Unit)? = null

    private val hsv = floatArrayOf(0f, 0f, 1f)
    private var alphaValue = 255
    private var centerX = 0f
    private var centerY = 0f
    private var outerRadius = 0f
    private var innerRadius = 0f
    private val svRect = RectF()
    private var draggingHue = false

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = Color.argb(125, 255, 255, 255)
    }
    private val saturationPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val svBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.5f
        color = Color.argb(180, 255, 255, 255)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    val color: Int
        get() = Color.HSVToColor(alphaValue, hsv)

    fun setColor(value: Int, notify: Boolean = false) {
        alphaValue = Color.alpha(value)
        Color.colorToHSV(value, hsv)
        updateShaders()
        invalidate()
        if (notify) onColorChanged?.invoke(color)
    }

    fun setAlphaValue(value: Int) {
        alphaValue = value.coerceIn(0, 255)
        onColorChanged?.invoke(color)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val preferred = (300f * resources.displayMetrics.density).toInt()
        val width = resolveSize(preferred, widthMeasureSpec)
        val height = resolveSize(width, heightMeasureSpec)
        val size = min(width, height)
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val wheelSize = min(w, h).toFloat()
        centerX = w / 2f
        centerY = h / 2f
        outerRadius = wheelSize * 0.475f
        innerRadius = wheelSize * 0.365f
        val halfSquare = innerRadius * 0.72f
        svRect.set(centerX - halfSquare, centerY - halfSquare, centerX + halfSquare, centerY + halfSquare)
        huePaint.strokeWidth = outerRadius - innerRadius
        updateShaders()
    }

    private fun updateShaders() {
        if (width <= 0 || height <= 0) return
        // Repeat red at the end so SweepGradient closes without a visible seam.
        val colors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
            Color.BLUE, Color.MAGENTA, Color.RED
        )
        huePaint.shader = SweepGradient(centerX, centerY, colors, null)

        val hueColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        // Draw the two gradients in separate passes. ComposeShader + MULTIPLY
        // renders as a fully black rectangle on several Android GPU drivers
        // because transparent black participates in the multiplication.
        saturationPaint.shader = LinearGradient(
            svRect.left, svRect.top, svRect.right, svRect.top,
            Color.WHITE, hueColor, Shader.TileMode.CLAMP
        )
        valuePaint.shader = LinearGradient(
            svRect.left, svRect.top, svRect.left, svRect.bottom,
            Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ringRadius = (innerRadius + outerRadius) / 2f
        canvas.drawCircle(centerX, centerY, ringRadius, huePaint)
        canvas.drawCircle(centerX, centerY, outerRadius, edgePaint)
        canvas.drawCircle(centerX, centerY, innerRadius, edgePaint)

        canvas.drawRoundRect(svRect, 5f, 5f, saturationPaint)
        canvas.drawRoundRect(svRect, 5f, 5f, valuePaint)
        canvas.drawRoundRect(svRect, 5f, 5f, svBorderPaint)

        val hueAngle = Math.toRadians(hsv[0].toDouble())
        drawMarker(
            canvas,
            centerX + cos(hueAngle).toFloat() * ringRadius,
            centerY + sin(hueAngle).toFloat() * ringRadius
        )
        drawMarker(
            canvas,
            svRect.left + hsv[1] * svRect.width(),
            svRect.bottom - hsv[2] * svRect.height()
        )
    }

    private fun drawMarker(canvas: Canvas, x: Float, y: Float) {
        val density = resources.displayMetrics.density
        val radius = density * 7f
        markerPaint.color = Color.argb(210, 0, 0, 0)
        markerPaint.strokeWidth = density * 4f
        canvas.drawCircle(x, y, radius, markerPaint)
        markerPaint.color = Color.WHITE
        markerPaint.strokeWidth = density * 2f
        canvas.drawCircle(x, y, radius, markerPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dx = event.x - centerX
        val dy = event.y - centerY
        val distance = sqrt(dx * dx + dy * dy)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingHue = distance >= innerRadius && distance <= outerRadius
                if (!draggingHue && !svRect.contains(event.x, event.y)) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> updateFromTouch(event.x, event.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                updateFromTouch(event.x, event.y)
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
        }
        return true
    }

    private fun updateFromTouch(x: Float, y: Float) {
        if (draggingHue) {
            hsv[0] = ((Math.toDegrees(atan2((y - centerY).toDouble(), (x - centerX).toDouble())) + 360.0) % 360.0).toFloat()
            updateShaders()
        } else {
            hsv[1] = ((x.coerceIn(svRect.left, svRect.right) - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            hsv[2] = (1f - (y.coerceIn(svRect.top, svRect.bottom) - svRect.top) / svRect.height()).coerceIn(0f, 1f)
        }
        invalidate()
        onColorChanged?.invoke(color)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
