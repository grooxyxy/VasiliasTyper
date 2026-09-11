package com.vasiliastyper.engine

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/**
 * ColorWheelView — Responsive HSV color wheel for VasiliasTyper color picker.
 *
 * Layout (from outside in):
 *  • Outer ring  — hue (360°)
 *  • Inner disk  — saturation (horizontal) × brightness (vertical)
 *
 * Touch interaction:
 *  • Drag inside the hue ring → changes hue
 *  • Drag inside the SV disk  → changes saturation + brightness
 *
 * Caller receives every update via [onColorChanged].
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Invoked on every hue/saturation/brightness change with the new ARGB color. */
    var onColorChanged: ((Int) -> Unit)? = null

    // ── Current HSV + alpha ───────────────────────────────────────────────────
    private var hue        = 0f
    private var saturation = 1f
    private var brightness = 1f
    private var alpha      = 255

    // ── Geometry (computed in onSizeChanged) ──────────────────────────────────
    private var cx       = 0f
    private var cy       = 0f
    private var rOuter   = 0f   // outer edge of hue ring
    private var rInner   = 0f   // inner edge of hue ring / outer edge of SV disk
    private var ringW    = 0f   // ring thickness

    // ── Cached rendering objects ──────────────────────────────────────────────
    private val ringPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val svPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private var svBitmap    : Bitmap? = null
    private val clipPath    = Path()

    private val selShadow   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = Color.BLACK; this.alpha = 160
    }
    private val selWhite    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.WHITE
    }

    // ── Touch state ───────────────────────────────────────────────────────────
    private enum class Zone { HUE_RING, SV_DISK, NONE }
    private var activeZone = Zone.NONE

    // ═════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════

    /** Apply an initial ARGB color to the wheel (does NOT invoke [onColorChanged]). */
    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue        = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        alpha      = Color.alpha(color)
        rebuildSvBitmap()
        invalidate()
    }

    /** Current ARGB color represented by the wheel selectors. */
    fun getColor(): Int = Color.HSVToColor(alpha, floatArrayOf(hue, saturation, brightness))

    /** Update the alpha component without moving the wheel selectors. */
    fun setAlpha(a: Int) {
        alpha = a.coerceIn(0, 255)
        invalidate()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Layout
    // ═════════════════════════════════════════════════════════════════════════

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
            .coerceAtLeast(MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val size   = minOf(w, h).toFloat()
        cx         = w / 2f
        cy         = h / 2f
        rOuter     = size / 2f - 6f
        ringW      = rOuter * 0.175f          // ~17 % of radius
        rInner     = rOuter - ringW

        // Rebuild hue ring shader
        val hueColors = IntArray(361) { i -> Color.HSVToColor(floatArrayOf(i.toFloat(), 1f, 1f)) }
        ringPaint.shader = SweepGradient(cx, cy, hueColors, null)
        ringPaint.strokeWidth = ringW

        // Clip path for SV bitmap
        clipPath.reset()
        clipPath.addCircle(cx, cy, rInner - 2f, Path.Direction.CW)

        rebuildSvBitmap()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Rendering
    // ═════════════════════════════════════════════════════════════════════════

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // ── Hue ring ─────────────────────────────────────────────────────────
        val ringRadius = rInner + ringW / 2f
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)

        // ── SV disk ───────────────────────────────────────────────────────────
        val bmp = svBitmap
        if (bmp != null && !bmp.isRecycled) {
            val saved = canvas.save()
            canvas.clipPath(clipPath)
            val bmpLeft = cx - rInner
            val bmpTop  = cy - rInner
            canvas.drawBitmap(bmp, bmpLeft, bmpTop, svPaint)
            canvas.restoreToCount(saved)
        }

        // ── Hue selector dot ─────────────────────────────────────────────────
        val hRad  = Math.toRadians((hue - 90.0))
        val hx    = cx + ringRadius * cos(hRad).toFloat()
        val hy    = cy + ringRadius * sin(hRad).toFloat()
        canvas.drawCircle(hx, hy, ringW * 0.48f, selShadow)
        canvas.drawCircle(hx, hy, ringW * 0.48f, selWhite)

        // ── SV selector dot ───────────────────────────────────────────────────
        val svX   = cx - rInner + saturation  * rInner * 2f
        val svY   = cy + rInner - brightness  * rInner * 2f
        val svX2  = svX.coerceIn(cx - rInner, cx + rInner)
        val svY2  = svY.coerceIn(cy - rInner, cy + rInner)
        canvas.drawCircle(svX2, svY2, ringW * 0.36f, selShadow)
        val svRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f
            color = if (brightness > 0.5f) Color.BLACK else Color.WHITE
        }
        canvas.drawCircle(svX2, svY2, ringW * 0.36f, svRimPaint)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Touch
    // ═════════════════════════════════════════════════════════════════════════

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val dx   = event.x - cx
        val dy   = event.y - cy
        val dist = sqrt(dx * dx + dy * dy)

        if (event.action == MotionEvent.ACTION_DOWN) {
            activeZone = when {
                dist in (rInner - ringW * 0.3f)..rOuter -> Zone.HUE_RING
                dist < rInner                            -> Zone.SV_DISK
                else                                     -> Zone.NONE
            }
        }

        if (activeZone == Zone.HUE_RING) {
            var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
            if (angle < 0f)   angle += 360f
            if (angle > 360f) angle -= 360f
            hue = angle
            rebuildSvBitmap()
            invalidate()
            onColorChanged?.invoke(getColor())
        } else if (activeZone == Zone.SV_DISK) {
            saturation = ((dx + rInner) / (rInner * 2f)).coerceIn(0f, 1f)
            brightness = (1f - (dy + rInner) / (rInner * 2f)).coerceIn(0f, 1f)
            invalidate()
            onColorChanged?.invoke(getColor())
        }

        return activeZone != Zone.NONE
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═════════════════════════════════════════════════════════════════════════

    private fun rebuildSvBitmap() {
        if (rInner <= 0f) return
        val size = (rInner * 2).toInt().coerceAtLeast(2)

        val old = svBitmap
        if (old != null && old.width == size && old.height == size && !old.isRecycled) {
            // Repaint in place — avoids Bitmap allocations on every hue change
            renderSvBitmap(old, size, hue)
        } else {
            old?.recycle()
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            renderSvBitmap(bmp, size, hue)
            svBitmap = bmp
        }
    }

    private fun renderSvBitmap(bmp: Bitmap, size: Int, hueValue: Float) {
        val pixels = IntArray(size * size)
        val rcx    = size / 2f
        val rcy    = size / 2f
        val r2     = rcx * rcx            // radius squared for circular clip

        for (y in 0 until size) {
            val sv = 1f - y.toFloat() / (size - 1)
            for (x in 0 until size) {
                val dx = x - rcx
                val dy = y - rcy
                val s  = x.toFloat() / (size - 1)
                pixels[y * size + x] = if (dx * dx + dy * dy <= r2)
                    Color.HSVToColor(floatArrayOf(hueValue, s, sv))
                else
                    Color.TRANSPARENT
            }
        }
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        svBitmap?.recycle()
        svBitmap = null
    }
}
