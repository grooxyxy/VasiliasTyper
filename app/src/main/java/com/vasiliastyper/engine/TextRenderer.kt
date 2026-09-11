package com.vasiliastyper.engine

import android.content.Context
import android.graphics.*
import android.net.Uri
import com.vasiliastyper.model.TextAlign
import com.vasiliastyper.model.TextEffect
import com.vasiliastyper.model.TextElement
import com.vasiliastyper.model.TextSpan
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object TextRenderer {

    @Volatile var appContext: Context? = null

    private val textureCache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 12
    }

    fun renderToCanvas(canvas: Canvas, element: TextElement) {
        val mesh = element.meshPoints
        if (mesh != null && mesh.size >= 4) {
            renderMesh(canvas, element, mesh)
        } else {
            val corners = element.perspCorners
            if (corners != null && corners.size == 8) {
                renderPerspective(canvas, element, corners)
            } else {
                renderNormal(canvas, element)
            }
        }
    }

    // ── Normal render ─────────────────────────────────────────────────────────

    private fun renderNormal(canvas: Canvas, element: TextElement) {
        canvas.save()
        val cx = element.x + element.width  / 2f
        val cy = element.y + element.height / 2f
        canvas.rotate(element.rotation, cx, cy)

        val hasBlur = element.blurType != "NONE" &&
                      (element.blurRadius > 0f || element.blurMotionDistance > 0f)
        if (hasBlur) {
            renderWithBlurEffect(canvas, element)
        } else {
            val elementAlpha = (element.opacity / 100f * 255).toInt().coerceIn(0, 255)
            val needsLayer = elementAlpha < 255
            val hasOutline = element.enableOutline ||
                element.effect == TextEffect.OUTLINE || element.effect == TextEffect.OUTLINE_SHADOW
            val hasShadow = element.enableShadow ||
                element.effect == TextEffect.SHADOW || element.effect == TextEffect.OUTLINE_SHADOW
            val outlinePad = if (hasOutline) element.outlineWidth.coerceAtLeast(0f) * 2f else 0f
            val shadowPadX = if (hasShadow) element.shadowRadius.coerceAtLeast(0f) +
                element.shadowSpread.coerceAtLeast(0f) + kotlin.math.abs(element.shadowDx) else 0f
            val shadowPadY = if (hasShadow) element.shadowRadius.coerceAtLeast(0f) +
                element.shadowSpread.coerceAtLeast(0f) + kotlin.math.abs(element.shadowDy) else 0f
            val effectPadX = maxOf(outlinePad, shadowPadX, element.blurMotionDistance.coerceAtLeast(0f)) + 2f
            val pathPadY = if (!element.textPathMode.equals("NONE", ignoreCase = true)) {
                kotlin.math.abs(element.textPathAmount.coerceIn(-100f, 100f)) / 100f *
                    minOf(element.height * 0.4f, element.fontSize * 2f)
            } else 0f
            val effectPadY = maxOf(outlinePad, shadowPadY, element.blurMotionDistance.coerceAtLeast(0f), pathPadY) + 2f
            // Jangan potong stroke/shadow pada batas kotak teks. Bounds lama tepat
            // sebesar elemen sehingga efek sebenarnya aktif tetapi tampak hilang.
            val effectBounds = RectF(
                element.x - effectPadX,
                element.y - effectPadY,
                element.x + element.width + effectPadX,
                element.y + element.height + effectPadY
            )
            if (needsLayer) canvas.saveLayerAlpha(effectBounds, elementAlpha)
            canvas.clipRect(effectBounds)
            renderElementContent(canvas, element)
            if (needsLayer) canvas.restore()
        }
        canvas.restore()
    }

    // ── Perspective render ────────────────────────────────────────────────────

    private fun renderPerspective(canvas: Canvas, element: TextElement, corners: FloatArray) {
        val (bw, bh) = BitmapSafety.fitWithinMaxPixels(element.width.toInt(), element.height.toInt())
        val tmp = try {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) { renderNormal(canvas, element); return }
        try {
            val tmpCanvas = Canvas(tmp)
            tmpCanvas.clipRect(0f, 0f, bw.toFloat(), bh.toFloat())
            val localEl = element.copy(x = 0f, y = 0f, rotation = 0f, perspCorners = null,
                                       blurType = "NONE", blurRadius = 0f, opacity = 100)
            renderElementContent(tmpCanvas, localEl)

            val src = floatArrayOf(0f, 0f, bw.toFloat(), 0f, 0f, bh.toFloat(), bw.toFloat(), bh.toFloat())
            val matrix = Matrix()
            matrix.setPolyToPoly(src, 0, corners, 0, 4)

            val renderBmp = blurBitmapForElement(tmp, element)
            val elementAlpha = (element.opacity / 100f * 255).toInt().coerceIn(0, 255)
            val perspPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = elementAlpha
            }
            canvas.drawBitmap(renderBmp, matrix, perspPaint)
            if (renderBmp !== tmp) renderBmp.recycle()
        } finally { tmp.recycle() }
    }


    // ── Mesh render (ibispaint-style warp) ──────────────────────────────────
    //
    // The mesh is stored as local element coordinates in a flat array
    // [x0,y0, x1,y1, ...]. We use a 4×4 vertex grid (3×3 cells), which gives
    // a smooth warp and stays light enough for low-end devices.

    private fun renderMesh(canvas: Canvas, element: TextElement, points: FloatArray) {
        val cols = element.meshCols.coerceIn(1, 8)
        val rows = element.meshRows.coerceIn(1, 8)
        val expected = (cols + 1) * (rows + 1) * 2
        if (points.size < expected) {
            renderNormal(canvas, element)
            return
        }

        val (bw, bh) = BitmapSafety.fitWithinMaxPixels(element.width.toInt(), element.height.toInt())
        val tmp = try {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            renderNormal(canvas, element)
            return
        }

        try {
            val tmpCanvas = Canvas(tmp)
            tmpCanvas.clipRect(0f, 0f, bw.toFloat(), bh.toFloat())
            val localEl = element.copy(
                x = 0f,
                y = 0f,
                width = bw.toFloat(),
                height = bh.toFloat(),
                rotation = 0f,
                perspCorners = null,
                meshPoints = null,
                blurType = "NONE",
                blurRadius = 0f,
                blurMotionAngle = 0f,
                blurMotionDistance = 0f,
                opacity = 100
            )
            renderElementContent(tmpCanvas, localEl)

            val renderBmp = blurBitmapForElement(tmp, element)
            val elementAlpha = (element.opacity / 100f * 255).toInt().coerceIn(0, 255)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = elementAlpha
            }

            val meshPoints = if (bw != element.width.toInt() || bh != element.height.toInt()) {
                val sx = bw / element.width.coerceAtLeast(1f)
                val sy = bh / element.height.coerceAtLeast(1f)
                FloatArray(points.size).also { out ->
                    var i = 0
                    while (i < points.size) {
                        out[i] = points[i] * sx
                        out[i + 1] = points[i + 1] * sy
                        i += 2
                    }
                }
            } else points

            canvas.save()
            canvas.translate(element.x, element.y)
            if (element.rotation != 0f) {
                canvas.rotate(element.rotation, bw / 2f, bh / 2f)
            }
            canvas.drawBitmapMesh(renderBmp, cols, rows, meshPoints, 0, null, 0, paint)
            canvas.restore()
            if (renderBmp !== tmp) renderBmp.recycle()
        } catch (_: Exception) {
            renderNormal(canvas, element)
        } finally {
            tmp.recycle()
        }
    }

    private fun renderWithBlurEffect(canvas: Canvas, element: TextElement) {
        val (bw, bh) = BitmapSafety.fitWithinMaxPixels(element.width.toInt(), element.height.toInt())
        val tmp = try {
            Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            renderElementContent(canvas, element)
            return
        }

        try {
            val tmpCanvas = Canvas(tmp)
            tmpCanvas.clipRect(0f, 0f, bw.toFloat(), bh.toFloat())
            val localEl = element.copy(
                x = 0f,
                y = 0f,
                width = bw.toFloat(),
                height = bh.toFloat(),
                rotation = 0f,
                perspCorners = null,
                meshPoints = null,
                blurType = "NONE",
                blurRadius = 0f,
                blurMotionAngle = 0f,
                blurMotionDistance = 0f,
                opacity = 100
            )
            renderElementContent(tmpCanvas, localEl)

            val outBmp = blurBitmapForElement(tmp, element)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (element.opacity / 100f * 255).toInt().coerceIn(0, 255)
            }
            canvas.drawBitmap(outBmp, element.x, element.y, paint)
            if (outBmp !== tmp) outBmp.recycle()
        } finally {
            tmp.recycle()
        }
    }

    private fun blurBitmapForElement(src: Bitmap, element: TextElement): Bitmap {
        val type = element.blurType.uppercase()
        return when {
            type == "GAUSSIAN" && element.blurRadius > 0f ->
                approxGaussianBlur(src, element.blurRadius)
            type == "MOTION_H" && element.blurRadius > 0f ->
                motionBlur(src, 0f, element.blurMotionDistance.coerceAtLeast(element.blurRadius))
            type == "MOTION_V" && element.blurRadius > 0f ->
                motionBlur(src, 90f, element.blurMotionDistance.coerceAtLeast(element.blurRadius))
            type == "MOTION" && element.blurRadius > 0f ->
                motionBlur(src, element.blurMotionAngle, element.blurMotionDistance.coerceAtLeast(element.blurRadius))
            else -> src
        }
    }

    private fun approxGaussianBlur(src: Bitmap, radius: Float): Bitmap {
        val scale = (1f / (1f + radius.coerceIn(1f, 120f) / 10f)).coerceIn(0.12f, 0.8f)
        val sw = (src.width * scale).toInt().coerceIn(1, src.width)
        val sh = (src.height * scale).toInt().coerceIn(1, src.height)
        val small = if (sw == src.width && sh == src.height) {
            src
        } else {
            Bitmap.createScaledBitmap(src, sw, sh, true)
        }
        if (small === src) return src
        return Bitmap.createScaledBitmap(small, src.width, src.height, true).also {
            small.recycle()
        }
    }

    private fun motionBlur(src: Bitmap, angleDeg: Float, distance: Float): Bitmap {
        // v6.1: batasi max passes 18→8 untuk mencegah OOM di device low-end
        val passes = (distance / 8f).toInt().coerceIn(3, 8)
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val outCanvas = Canvas(out)
        val alphaStep = (255 / passes).coerceAtLeast(18)
        val rad = angleDeg * PI.toFloat() / 180f
        val dx = cos(rad)
        val dy = sin(rad)
        for (i in 0 until passes) {
            val t = if (passes <= 1) 0f else (i - (passes - 1) / 2f) / ((passes - 1) / 2f)
            val offX = dx * distance * t
            val offY = dy * distance * t
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                alpha = ((alphaStep * (1f - kotlin.math.abs(t) * 0.35f)).toInt()).coerceIn(16, 255)
            }
            outCanvas.drawBitmap(src, offX, offY, paint)
        }
        return out
    }

    private fun renderElementContent(canvas: Canvas, element: TextElement) {
        val spans = element.spans
        if (!spans.isNullOrEmpty() && element.textPathMode.equals("NONE", ignoreCase = true)) {
            drawTextSpans(canvas, element, spans)
            return
        }
        val displayText = applyTextTransform(element.text, element.textTransform)
        val displayEl = if (displayText != element.text) element.copy(text = displayText) else element
        val tf        = buildTypeface(displayEl)
        val basePaint = buildBasePaint(displayEl, tf)
        // Every effect is independent. Legacy enum values still activate their
        // matching layer so older projects render exactly as before.
        val doShadow = displayEl.enableShadow ||
            displayEl.effect == TextEffect.SHADOW || displayEl.effect == TextEffect.OUTLINE_SHADOW
        val doOutline = displayEl.enableOutline ||
            displayEl.effect == TextEffect.OUTLINE || displayEl.effect == TextEffect.OUTLINE_SHADOW
        val doGradient = displayEl.enableGradient || displayEl.effect == TextEffect.GRADIENT
        val doTexture = displayEl.enableTexture || displayEl.effect == TextEffect.TEXTURE

        // Layer 1: shadow.
        if (doShadow) {
            drawThickShadow(
                canvas = canvas,
                basePaint = basePaint,
                color = displayEl.shadowColor,
                opacity = displayEl.shadowOpacity,
                radius = displayEl.shadowRadius,
                spread = displayEl.shadowSpread,
                dx = displayEl.shadowDx,
                dy = displayEl.shadowDy
            ) { shadowPaint ->
                drawText(canvas, displayEl.text, displayEl, shadowPaint)
            }
        }
        // Layer 2: outline stroke (solid or gradient).
        if (doOutline) {
            val oc = withOpacity(displayEl.outlineColor, displayEl.outlineOpacity)
            val op = Paint(basePaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = displayEl.outlineWidth * 2
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
            if (displayEl.enableOutlineGradient) {
                op.shader = LinearGradient(
                    displayEl.x,
                    displayEl.y,
                    displayEl.x,
                    displayEl.y + displayEl.height,
                    displayEl.outlineGradStartColor,
                    displayEl.outlineGradEndColor,
                    Shader.TileMode.CLAMP
                )
                op.alpha = (displayEl.outlineOpacity / 100f * 255).toInt().coerceIn(0, 255)
            } else {
                op.color = oc
            }
            drawText(canvas, displayEl.text, displayEl, op)
        }
        // Layer 3: fill. Gradient and texture are combined with MULTIPLY when
        // both are enabled, giving the texture the selected gradient colours.
        val fillPaint = buildStackedFillPaint(displayEl, basePaint, doGradient, doTexture)
        if (displayEl.effect == TextEffect.WARP) {
            renderWarped(canvas, displayEl, fillPaint)
        } else {
            drawText(canvas, displayEl.text, displayEl, fillPaint)
        }
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private fun buildTypeface(element: TextElement): Typeface {
        val base = element.typeface
            ?: appContext?.let { FontResolver.resolve(it, element.fontName) }
            ?: Typeface.DEFAULT
        val style = when {
            element.isBold && element.isItalic -> Typeface.BOLD_ITALIC
            element.isBold                     -> Typeface.BOLD
            element.isItalic                   -> Typeface.ITALIC
            else                               -> Typeface.NORMAL
        }
        return Typeface.create(base, style)
    }

    private fun buildBasePaint(element: TextElement, tf: Typeface): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color     = element.color
            textSize  = element.fontSize
            typeface  = tf
            textAlign = paintAlign(element.align)
        }

    private fun paintAlign(align: TextAlign) = when (align) {
        TextAlign.LEFT   -> Paint.Align.LEFT
        TextAlign.CENTER -> Paint.Align.CENTER
        TextAlign.RIGHT  -> Paint.Align.RIGHT
    }



private fun applyTextTransform(text: String, transform: String?): String {
    return when (transform?.trim()?.uppercase()) {
        "UPPER" -> text.uppercase()
        "LOWER" -> text.lowercase()
        "TITLE" -> toTitleCase(text)
        else -> text
    }
}

private fun toTitleCase(text: String): String {
    if (text.isEmpty()) return text
    val out = StringBuilder(text.length)
    var nextUpper = true
    for (ch in text) {
        if (ch.isLetter()) {
            out.append(if (nextUpper) ch.uppercaseChar() else ch.lowercaseChar())
            nextUpper = false
        } else {
            out.append(ch)
            nextUpper = ch.isWhitespace() || ch == '-' || ch == '_' || ch == '/' || ch == '.' || ch == ':' || ch == '\'' || ch == '"' || ch == '(' || ch == '[' || ch == '{'
        }
    }
    return out.toString()
}

    private fun withOpacity(color: Int, opacity: Int): Int {
        val a = (Color.alpha(color) * opacity / 100f).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    /**
     * Draws a Photoshop/IbisPaint-like shadow with an independent solid spread.
     * The glyph is translated first, then expanded with a round stroke and blurred
     * around its translated position. This keeps legacy spread=0 rendering natural
     * while allowing a much heavier shadow without abusing blur radius.
     */
    private inline fun drawThickShadow(
        canvas: Canvas,
        basePaint: Paint,
        color: Int,
        opacity: Int,
        radius: Float,
        spread: Float,
        dx: Float,
        dy: Float,
        draw: (Paint) -> Unit
    ) {
        val safeColor = withOpacity(color, opacity.coerceIn(0, 100))
        val safeSpread = spread.takeIf(Float::isFinite)?.coerceIn(0f, 128f) ?: 0f
        val safeRadius = radius.takeIf(Float::isFinite)?.coerceIn(0f, 128f) ?: 0f
        val shadowPaint = Paint(basePaint).apply {
            this.color = safeColor
            style = if (safeSpread > 0f) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            strokeWidth = safeSpread * 2f
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            if (safeRadius > 0f) setShadowLayer(safeRadius, 0f, 0f, safeColor)
        }
        canvas.save()
        canvas.translate(
            dx.takeIf(Float::isFinite)?.coerceIn(-256f, 256f) ?: 0f,
            dy.takeIf(Float::isFinite)?.coerceIn(-256f, 256f) ?: 0f
        )
        draw(shadowPaint)
        canvas.restore()
        shadowPaint.clearShadowLayer()
    }

    // ── Word-wrap ─────────────────────────────────────────────────────────────

    private fun trackedWidth(text: String, paint: Paint, tracking: Float): Float =
        paint.measureText(text) + (text.length - 1).coerceAtLeast(0) * tracking

    private fun drawTrackedLine(
        canvas: Canvas,
        line: String,
        startX: Float,
        y: Float,
        paint: Paint,
        tracking: Float
    ) {
        if (tracking == 0f || line.length <= 1) {
            canvas.drawText(line, startX, y, paint)
            return
        }
        val savedAlign = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        var x = startX
        line.forEach { ch ->
            val glyph = ch.toString()
            canvas.drawText(glyph, x, y, paint)
            x += paint.measureText(glyph) + tracking
        }
        paint.textAlign = savedAlign
    }

    fun wrapLines(text: String, paint: Paint, maxWidth: Float, tracking: Float = 0f): List<String> {
        if (maxWidth <= 0f) return text.split("\n")
        val result = mutableListOf<String>()

        fun splitWithoutLosingCharacters(word: String): List<String> {
            if (trackedWidth(word, paint, tracking) <= maxWidth) return listOf(word)

            // Existing hyphens are legal break points. Keep the hyphen on the
            // preceding line: "hati-hati" becomes "hati-" / "hati".
            val hyphenParts = mutableListOf<String>()
            var start = 0
            word.forEachIndexed { index, char ->
                if (char == '-') {
                    hyphenParts += word.substring(start, index + 1)
                    start = index + 1
                }
            }
            if (start < word.length) hyphenParts += word.substring(start)

            val atomicParts = if (hyphenParts.size > 1) hyphenParts else word.map { it.toString() }
            val chunks = mutableListOf<String>()
            var chunk = ""
            for (part in atomicParts) {
                val candidate = chunk + part
                if (chunk.isNotEmpty() && trackedWidth(candidate, paint, tracking) > maxWidth) {
                    chunks += chunk
                    chunk = part
                } else {
                    chunk = candidate
                }
            }
            if (chunk.isNotEmpty()) chunks += chunk
            return chunks.ifEmpty { listOf(word) }
        }

        for (para in text.split("\n")) {
            if (para.isEmpty()) {
                result += ""
                continue
            }
            var line = ""
            for (word in para.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (trackedWidth(candidate, paint, tracking) <= maxWidth) {
                    line = candidate
                    continue
                }

                // If the whole word fits on a fresh line, move it intact. Only
                // split when the selection itself is too narrow for that word.
                if (line.isNotEmpty()) {
                    result += line
                }
                val chunks = splitWithoutLosingCharacters(word)
                chunks.dropLast(1).forEach { result += it }
                line = chunks.last()
            }
            if (line.isNotEmpty()) result += line
        }
        return result.ifEmpty { listOf("") }
    }

    /** Extra px antar paragraf ala ibispaint (-50 … +50). Dihitung per baris
     * kosong (pemisah paragraf) agar minus merapatkan dan plus merenggang. */
    private fun paragraphGap(element: TextElement): Float =
        element.paragraphSpacing.takeIf(Float::isFinite)?.coerceIn(-50f, 50f) ?: 0f

    private fun paragraphExtra(lines: List<String>, gap: Float): Float =
        if (gap == 0f) 0f else lines.count { it.isBlank() } * gap
    fun computeWrappedHeight(
        text: String,
        fontSize: Float,
        boxWidth: Float,
        typeface: Typeface? = null,
        leading: Float = 120f,
        paragraphSpacing: Float = 0f
    ): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize      = fontSize
            this.typeface = typeface ?: Typeface.DEFAULT
        }
        val lines = wrapLines(text, paint, boxWidth)
        // Leading boleh -50% (rapat); jangan kembalikan tinggi negatif/nol.
        val glyph = (paint.fontMetrics.descent - paint.fontMetrics.ascent).coerceAtLeast(1f)
        val gap = paragraphSpacing.takeIf(Float::isFinite)?.coerceIn(-50f, 50f) ?: 0f
        return (lines.size * fontSize * (leading / 100f) + paragraphExtra(lines, gap)).coerceAtLeast(glyph)
    }

    // ── Justify helper ────────────────────────────────────────────────────────
    // Draws a single line with word spacing expanded to fill maxWidth exactly.
    // Falls back to normal draw for single-word lines.

    private fun drawJustifiedLine(
        canvas: Canvas,
        line: String,
        startX: Float,
        y: Float,
        maxWidth: Float,
        paint: Paint
    ) {
        val words = line.trim().split(" ")
        if (words.size <= 1) {
            canvas.drawText(line, startX, y, paint)
            return
        }
        val savedAlign = paint.textAlign
        paint.textAlign = Paint.Align.LEFT

        val totalWordsWidth = words.sumOf { paint.measureText(it).toDouble() }.toFloat()
        val extraSpace      = (maxWidth - totalWordsWidth).coerceAtLeast(0f)
        val gap             = extraSpace / (words.size - 1)

        var x = startX
        for ((i, word) in words.withIndex()) {
            canvas.drawText(word, x, y, paint)
            x += paint.measureText(word) + gap
        }
        paint.textAlign = savedAlign
    }

    // ── Effect renderers ──────────────────────────────────────────────────────

    private fun renderWithShadow(canvas: Canvas, element: TextElement, paint: Paint) {
        val shadowColor = withOpacity(element.shadowColor, element.shadowOpacity)
        paint.setShadowLayer(element.shadowRadius, element.shadowDx, element.shadowDy, shadowColor)
        drawText(canvas, element.text, element, paint)
        paint.clearShadowLayer()
    }

    private fun renderWithOutline(canvas: Canvas, element: TextElement, basePaint: Paint) {
        val outlineColor = withOpacity(element.outlineColor, element.outlineOpacity)
        val outlinePaint = Paint(basePaint).apply {
            color       = outlineColor
            style       = Paint.Style.STROKE
            strokeWidth = element.outlineWidth * 2
            strokeJoin  = Paint.Join.ROUND
            strokeCap   = Paint.Cap.ROUND
        }
        drawText(canvas, element.text, element, outlinePaint)
        drawText(canvas, element.text, element, basePaint)
    }

    private fun renderWithOutlineAndShadow(canvas: Canvas, element: TextElement, basePaint: Paint) {
        val shadowColor = withOpacity(element.shadowColor, element.shadowOpacity)
        val shadowPaint = Paint(basePaint).apply {
            setShadowLayer(element.shadowRadius, element.shadowDx, element.shadowDy, shadowColor)
        }
        drawText(canvas, element.text, element, shadowPaint)
        shadowPaint.clearShadowLayer()

        val outlineColor = withOpacity(element.outlineColor, element.outlineOpacity)
        val outlinePaint = Paint(basePaint).apply {
            color       = outlineColor
            style       = Paint.Style.STROKE
            strokeWidth = element.outlineWidth * 2
            strokeJoin  = Paint.Join.ROUND
            strokeCap   = Paint.Cap.ROUND
        }
        drawText(canvas, element.text, element, outlinePaint)
        drawText(canvas, element.text, element, basePaint)
    }

    private fun buildStackedFillPaint(
        element: TextElement,
        basePaint: Paint,
        useGradient: Boolean,
        useTexture: Boolean
    ): Paint {
        if (!useGradient && !useTexture) return basePaint
        val paint = Paint(basePaint)
        val gradient = if (useGradient) buildGradientShader(element) else null
        val texture = if (useTexture) buildTextureShader(element) else null
        paint.shader = when {
            gradient != null && texture != null -> ComposeShader(
                gradient,
                texture,
                PorterDuff.Mode.MULTIPLY
            )
            gradient != null -> gradient
            else -> texture
        }
        if (useTexture && !useGradient && element.color != Color.WHITE) {
            paint.colorFilter = PorterDuffColorFilter(element.color, PorterDuff.Mode.MULTIPLY)
        }
        return paint
    }

    private fun buildGradientShader(element: TextElement): Shader {
        val colors = element.gradientColors
            .takeIf { it.size >= 2 }
            ?.toIntArray()
            ?: intArrayOf(element.gradientStartColor, element.gradientEndColor)
        val angleRad = Math.toRadians(element.gradientAngle.toDouble())
        val directionX = cos(angleRad).toFloat()
        val directionY = sin(angleRad).toFloat()
        val centerX = element.x + element.width / 2f
        val centerY = element.y + element.height / 2f
        val radius = kotlin.math.abs(directionX) * element.width / 2f +
            kotlin.math.abs(directionY) * element.height / 2f
        return LinearGradient(
            centerX - directionX * radius,
            centerY - directionY * radius,
            centerX + directionX * radius,
            centerY + directionY * radius,
            colors,
            null,
            Shader.TileMode.CLAMP
        )
    }

    private fun buildTextureShader(element: TextElement): Shader {
        val uri = element.textureUri
        val ctx = appContext
        val texture = if (!uri.isNullOrBlank() && ctx != null) {
            decodeTextureCached(ctx, uri) ?: buildProceduralTexture(Color.WHITE)
        } else {
            buildProceduralTexture(Color.WHITE)
        }
        return BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun buildProceduralTexture(tint: Int): Bitmap {
        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val c   = Canvas(bmp)
        val p   = Paint().apply { color = tint; strokeWidth = 2f }
        c.drawLine(0f, 0f, 16f, 16f, p)
        c.drawLine(16f, 0f, 0f, 16f, p)
        return bmp
    }

    private fun decodeTextureCached(ctx: Context, uriStr: String): Bitmap? {
        synchronized(textureCache) { textureCache[uriStr]?.let { if (!it.isRecycled) return it } }
        return try {
            ctx.contentResolver.openInputStream(Uri.parse(uriStr))?.use { ins ->
                val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bm = BitmapFactory.decodeStream(ins, null, opts) ?: return null
                val maxSide = 512
                val scale   = (maxSide.toFloat() / kotlin.math.max(bm.width, bm.height)).coerceAtMost(1f)
                val finalBmp = if (scale < 1f) {
                    val nW = (bm.width * scale).toInt().coerceAtLeast(1)
                    val nH = (bm.height * scale).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(bm, nW, nH, true).also { if (it !== bm) bm.recycle() }
                } else bm
                synchronized(textureCache) { textureCache[uriStr] = finalBmp }
                finalBmp
            }
        } catch (e: Exception) { e.printStackTrace(); null }
    }

    fun invalidateTextureCache(uriStr: String?) {
        if (uriStr.isNullOrBlank()) return
        synchronized(textureCache) { textureCache.remove(uriStr)?.recycle() }
    }

    private fun renderWarped(canvas: Canvas, element: TextElement, paint: Paint) {
        val lines       = wrapLines(element.text, paint, element.width, element.tracking)
        // Leading -50% = sangat rapat; clamp agar baris tidak terbalik/hilang.
        val lineHeight  = (element.fontSize * (element.leading / 100f)).coerceAtLeast(1f)
        val paraGap     = paragraphGap(element)
        val totalHeight = (lines.size * lineHeight + paragraphExtra(lines, paraGap)).coerceAtLeast(1f)
        val amplitude   = element.fontSize * 0.15f
        val frequency   = PI / (element.fontSize * 2)
        val startY      = element.y + (element.height - totalHeight) / 2f + element.fontSize
        var charIndex   = 0
        var lineY       = startY

        for ((lineIdx, line) in lines.withIndex()) {
            val baseY     = lineY
            val lineWidth = trackedWidth(line, paint, element.tracking)
            val lineStartX = when (element.align) {
                TextAlign.LEFT   -> element.x
                TextAlign.CENTER -> element.x + element.width / 2f - lineWidth / 2f
                TextAlign.RIGHT  -> element.x + element.width - lineWidth
            }
            var xOff = 0f
            for (ch in line.toCharArray()) {
                val chStr   = ch.toString()
                val yOffset = (sin(frequency * charIndex) * amplitude).toFloat()
                canvas.drawText(chStr, lineStartX + xOff, baseY + yOffset, paint)
                xOff      += paint.measureText(chStr) + element.tracking
                charIndex++
            }
            charIndex++
            lineY += lineHeight
            if (line.isBlank()) lineY += paraGap
        }
    }

    // ── Core draw ─────────────────────────────────────────────────────────────
    // v5.3: uses element.leading for line height; supports element.justify

    private fun drawText(canvas: Canvas, text: String, element: TextElement, paint: Paint) {
        if (!element.textPathMode.equals("NONE", ignoreCase = true)) {
            drawTextOnPath(canvas, text, element, paint)
        } else {
            drawStraightText(canvas, text, element, paint)
        }
    }

    /**
     * Draws each glyph along a configurable non-destructive baseline. This keeps
     * outline, shadow, gradient and texture passes aligned because every pass uses
     * the same geometry and only changes Paint.
     */
    private fun drawTextOnPath(canvas: Canvas, text: String, element: TextElement, paint: Paint) {
        val insetX = maxOf(element.fontSize * 0.08f, element.width * 0.08f, 2f)
            .coerceAtMost(element.width * 0.22f)
        val insetY = maxOf(element.fontSize * 0.06f, element.height * 0.10f, 2f)
            .coerceAtMost(element.height * 0.22f)
        val left = element.x + insetX
        val contentW = (element.width - insetX * 2f).coerceAtLeast(1f)
        val top = element.y + insetY
        val contentH = (element.height - insetY * 2f).coerceAtLeast(1f)
        val lines = wrapLines(text, paint, contentW, element.tracking)
        val fm = paint.fontMetrics
        val lineAdvance = maxOf(element.fontSize * (element.leading / 100f), (fm.descent - fm.ascent) * 1.05f)
        val glyphHeight = (fm.descent - fm.ascent).coerceAtLeast(1f)
        val paraGap = paragraphGap(element)
        val totalHeight = glyphHeight + (lines.size - 1).coerceAtLeast(0) * lineAdvance +
            paragraphExtra(lines, paraGap)
        val firstBaseline = top + ((contentH - totalHeight) / 2f).coerceAtLeast(0f) - fm.ascent
        val amplitude = element.textPathAmount.coerceIn(-100f, 100f) / 100f *
            minOf(element.height * 0.4f, element.fontSize * 2f)
        val cycles = element.textPathCycles.takeIf(Float::isFinite)?.coerceIn(0.5f, 5f) ?: 1.5f
        val mode = element.textPathMode.trim().uppercase()
        val savedAlign = paint.textAlign
        paint.textAlign = Paint.Align.LEFT

        fun offsetAt(normalizedX: Float): Float {
            val t = normalizedX.coerceIn(-1f, 1f)
            return when (mode) {
                "CURVE_UP" -> amplitude * (t * t - 1f)
                "CURVE_DOWN" -> amplitude * (1f - t * t)
                "WAVE" -> (sin(((t + 1f) * 0.5f * cycles * 2f * PI).toDouble()) * amplitude).toFloat()
                "ARCH" -> (-cos(t * PI.toFloat() / 2f) * amplitude)
                "VALLEY" -> (cos(t * PI.toFloat() / 2f) * amplitude)
                else -> 0f
            }
        }

        lines.forEachIndexed { lineIndex, line ->
            val lineWidth = trackedWidth(line, paint, element.tracking).coerceAtLeast(1f)
            val startX = when (element.align) {
                TextAlign.LEFT -> left
                TextAlign.CENTER -> left + (contentW - lineWidth) / 2f
                TextAlign.RIGHT -> left + contentW - lineWidth
            }
            var xOffset = 0f
            // Akumulasi gap paragraf dari baris-baris kosong sebelumnya.
            var paraBefore = 0f
            for (i in 0 until lineIndex) if (lines[i].isBlank()) paraBefore += paraGap
            line.forEach { ch ->
                val glyph = ch.toString()
                val glyphWidth = paint.measureText(glyph)
                val centerX = xOffset + glyphWidth / 2f
                val normalized = centerX / lineWidth * 2f - 1f
                val delta = 0.01f
                val slope = (offsetAt(normalized + delta) - offsetAt(normalized - delta)) /
                    (delta * contentW).coerceAtLeast(0.001f)
                val rotation = Math.toDegrees(atan2(slope, 1f).toDouble()).toFloat().coerceIn(-70f, 70f)
                canvas.save()
                canvas.translate(startX + centerX, firstBaseline + lineIndex * lineAdvance + paraBefore + offsetAt(normalized))
                canvas.rotate(rotation)
                canvas.drawText(glyph, -glyphWidth / 2f, 0f, paint)
                canvas.restore()
                xOffset += glyphWidth + element.tracking
            }
        }
        paint.textAlign = savedAlign
    }

    private fun drawStraightText(canvas: Canvas, text: String, element: TextElement, paint: Paint) {
        // Keep a proportional quiet zone around lettering. Font-only padding was
        // almost invisible on large detected bubbles, so auto-fit could grow text
        // until it touched the outline. Dimension-based padding is constant-time and
        // therefore does not add measurable work to Script/Place All.
        val insetX = maxOf(element.fontSize * 0.08f, element.width * 0.08f, 2f)
            .coerceAtMost(element.width * 0.22f)
        val insetY = maxOf(element.fontSize * 0.06f, element.height * 0.10f, 2f)
            .coerceAtMost(element.height * 0.22f)
        val left = element.x + insetX
        val right = (element.x + element.width - insetX).coerceAtLeast(left + 1f)
        val top = element.y + insetY
        val bottom = (element.y + element.height - insetY).coerceAtLeast(top + 1f)

        val contentW = (right - left).coerceAtLeast(1f)
        val contentH = (bottom - top).coerceAtLeast(1f)

        val lines = wrapLines(text, paint, contentW, element.tracking)
        val fm = paint.fontMetrics
        val lineAdvance = maxOf(element.fontSize * (element.leading / 100f), (fm.descent - fm.ascent) * 1.05f)
        val glyphHeight = (fm.descent - fm.ascent).coerceAtLeast(1f)
        val paraGap = paragraphGap(element)
        val totalHeight = glyphHeight + (lines.size - 1).coerceAtLeast(0) * lineAdvance +
            paragraphExtra(lines, paraGap)

        // Center the actual glyph block, not an extra line advance after the last
        // baseline. This gives equal visual breathing room above and below.
        val firstBaseline = top + ((contentH - totalHeight) / 2f).coerceAtLeast(0f) - fm.ascent
        var y = firstBaseline

        for ((idx, line) in lines.withIndex()) {
            val isLastLine = idx == lines.size - 1
            if (element.justify && !isLastLine && line.contains(' ')) {
                drawJustifiedLine(canvas, line, left, y, contentW, paint)
            } else {
                val lineWidth = trackedWidth(line, paint, element.tracking)
                val x = when (element.align) {
                    TextAlign.LEFT -> left
                    TextAlign.CENTER -> left + (contentW - lineWidth) / 2f
                    TextAlign.RIGHT -> right - lineWidth
                }
                if (element.tracking == 0f) {
                    val alignedX = when (element.align) {
                        TextAlign.LEFT -> left
                        TextAlign.CENTER -> left + contentW / 2f
                        TextAlign.RIGHT -> right
                    }
                    canvas.drawText(line, alignedX, y, paint)
                } else {
                    drawTrackedLine(canvas, line, x, y, paint, element.tracking)
                }
            }
            y += lineAdvance
            if (line.isBlank()) y += paraGap
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MULTI-SPAN RENDERER
    // ══════════════════════════════════════════════════════════════════════════

    private data class SpanResolved(
        val paint: Paint,
        val outlineColor: Int?,
        val outlineWidth: Float,
        val outlineOpacity: Int,
        val shadowColor: Int?,
        val shadowDx: Float, val shadowDy: Float,
        val shadowRadius: Float, val shadowSpread: Float, val shadowOpacity: Int,
        val drawShadow: Boolean, val drawOutline: Boolean,
        val textureUri: String?,
        val spanAlpha: Int
    )

    private fun resolveSpanAt(
        element: TextElement,
        spans: List<TextSpan>,
        absPos: Int,
        baseTypeface: Typeface
    ): SpanResolved {
        val matching = spans.lastOrNull { it.covers(absPos) }

        val tf = run {
            val base = matching?.typeface ?: (element.typeface ?: Typeface.DEFAULT)
            val isB  = matching?.isBold   ?: element.isBold
            val isI  = matching?.isItalic ?: element.isItalic
            val style = when {
                isB && isI -> Typeface.BOLD_ITALIC
                isB        -> Typeface.BOLD
                isI        -> Typeface.ITALIC
                else       -> Typeface.NORMAL
            }
            Typeface.create(base, style)
        }

        val color     = matching?.color ?: element.color
        val spanAlpha = ((matching?.opacity ?: 100) / 100f * 255).toInt().coerceIn(0, 255)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color     = color
            // Keep old projects compatible: null/invalid values inherit the parent size.
            this.textSize  = matching?.fontSize
                ?.takeIf { it.isFinite() && it > 0f }
                ?.coerceIn(4f, 400f)
                ?: element.fontSize
            this.typeface  = tf
            this.textAlign = Paint.Align.LEFT
        }

        val effOverride = matching?.effect
        val effectivelyOutline = when (effOverride) {
            "OUTLINE", "OUTLINE_SHADOW" -> true
            "NONE", "SHADOW"            -> false
            null -> element.enableOutline ||
                element.effect == TextEffect.OUTLINE || element.effect == TextEffect.OUTLINE_SHADOW
            else -> false
        }
        val effectivelyShadow = when (effOverride) {
            "SHADOW", "OUTLINE_SHADOW"  -> true
            "NONE", "OUTLINE"           -> false
            null -> element.enableShadow ||
                element.effect == TextEffect.SHADOW || element.effect == TextEffect.OUTLINE_SHADOW
            else -> false
        }
        val effectiveTextureUri = matching?.textureUri
            ?: if (element.effect == TextEffect.TEXTURE) element.textureUri else null

        return SpanResolved(
            paint          = paint,
            outlineColor   = matching?.outlineColor ?: element.outlineColor,
            outlineWidth   = matching?.outlineWidth ?: element.outlineWidth,
            outlineOpacity = matching?.outlineOpacity ?: element.outlineOpacity,
            shadowColor    = matching?.shadowColor ?: element.shadowColor,
            shadowDx       = matching?.shadowDx ?: element.shadowDx,
            shadowDy       = matching?.shadowDy ?: element.shadowDy,
            shadowRadius   = matching?.shadowRadius ?: element.shadowRadius,
            shadowSpread   = element.shadowSpread,
            shadowOpacity  = matching?.shadowOpacity ?: element.shadowOpacity,
            drawShadow     = effectivelyShadow,
            drawOutline    = effectivelyOutline,
            textureUri     = effectiveTextureUri,
            spanAlpha      = spanAlpha
        )
    }

    private fun drawTextSpans(canvas: Canvas, element: TextElement, spans: List<TextSpan>) {
        val baseTypeface = buildTypeface(element)
        val layoutPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = element.fontSize; typeface = baseTypeface
        }
        val text = applyTextTransform(element.text, element.textTransform)
        val insetX = maxOf(element.fontSize * 0.08f, element.width * 0.08f, 2f)
            .coerceAtMost(element.width * 0.22f)
        val insetY = maxOf(element.fontSize * 0.06f, element.height * 0.10f, 2f)
            .coerceAtMost(element.height * 0.22f)
        val left = element.x + insetX
        val right = (element.x + element.width - insetX).coerceAtLeast(left + 1f)
        val top = element.y + insetY
        val bottom = (element.y + element.height - insetY).coerceAtLeast(top + 1f)
        val contentW = (right - left).coerceAtLeast(1f)
        val contentH = (bottom - top).coerceAtLeast(1f)

        val lines = wrapLines(text, layoutPaint, contentW)
        val fm = layoutPaint.fontMetrics
        val lineAdvance = maxOf(element.fontSize * (element.leading / 100f), (fm.descent - fm.ascent) * 1.05f)
        val glyphHeight = (fm.descent - fm.ascent).coerceAtLeast(1f)
        val paraGap = paragraphGap(element)
        val totalHeight = glyphHeight + (lines.size - 1).coerceAtLeast(0) * lineAdvance +
            paragraphExtra(lines, paraGap)
        var baseY = top + ((contentH - totalHeight) / 2f).coerceAtLeast(0f) - fm.ascent
        var absPos = 0

        for ((lineIdx, line) in lines.withIndex()) {
            while (absPos < text.length && (text[absPos] == ' ' || text[absPos] == '\n')) {
                absPos++
                if (text.getOrNull(absPos - 1) == '\n') break
            }

            val charPaints = ArrayList<Pair<Char, SpanResolved>>(line.length)
            var lineWidth  = 0f
            var probe      = absPos
            for (ch in line) {
                val res = resolveSpanAt(element, spans, probe, baseTypeface)
                charPaints.add(ch to res)
                lineWidth += res.paint.measureText(ch.toString())
                probe++
            }

            val isLastLine = lineIdx == lines.size - 1
            val lineStartX = when {
                element.justify && !isLastLine -> left
                else -> when (element.align) {
                    TextAlign.LEFT -> left
                    TextAlign.CENTER -> left + contentW / 2f - lineWidth / 2f
                    TextAlign.RIGHT -> right - lineWidth
                }
            }

            // For justify mode: compute per-character x positions with expanded word gaps
            val charXPositions: List<Float> = if (element.justify && !isLastLine && line.contains(' ')) {
                val words = line.trim().split(" ")
                val totalWordsWidth = charPaints.sumOf { (ch, r) -> r.paint.measureText(ch.toString()).toDouble() }.toFloat()
                val extraSpace      = (contentW - totalWordsWidth).coerceAtLeast(0f)
                val gap             = extraSpace / (words.size - 1).coerceAtLeast(1)
                val positions       = mutableListOf<Float>()
                var x = lineStartX
                var wordIdx = 0
                var charInWord = 0
                for ((i, cp) in charPaints.withIndex()) {
                    if (cp.first == ' ') {
                        positions.add(x)
                        x += cp.second.paint.measureText(" ") + gap
                        wordIdx++; charInWord = 0
                    } else {
                        positions.add(x)
                        x += cp.second.paint.measureText(cp.first.toString())
                        charInWord++
                    }
                }
                positions
            } else {
                val positions = mutableListOf<Float>()
                var x = lineStartX
                for ((ch, res) in charPaints) {
                    positions.add(x)
                    x += res.paint.measureText(ch.toString())
                }
                positions
            }

            // Pass 1: shadows
            for ((i, cp) in charPaints.withIndex()) {
                val (ch, res) = cp
                if (res.drawShadow && res.shadowColor != null) {
                    drawThickShadow(
                        canvas = canvas,
                        basePaint = res.paint,
                        color = res.shadowColor,
                        opacity = res.shadowOpacity,
                        radius = res.shadowRadius,
                        spread = res.shadowSpread,
                        dx = res.shadowDx,
                        dy = res.shadowDy
                    ) { shadowPaint ->
                        canvas.drawText(ch.toString(), charXPositions[i], baseY, shadowPaint)
                    }
                }
            }
            // Pass 2: outlines
            for ((i, cp) in charPaints.withIndex()) {
                val (ch, res) = cp
                if (res.drawOutline && res.outlineColor != null) {
                    val op = Paint(res.paint).apply {
                        color       = withOpacity(res.outlineColor, res.outlineOpacity)
                        style       = Paint.Style.STROKE
                        strokeWidth = res.outlineWidth * 2
                        strokeJoin  = Paint.Join.ROUND
                        strokeCap   = Paint.Cap.ROUND
                    }
                    canvas.drawText(ch.toString(), charXPositions[i], baseY, op)
                }
            }
            // Pass 3: fills
            for ((i, cp) in charPaints.withIndex()) {
                val (ch, res) = cp
                val fillPaint: Paint = when {
                    res.textureUri != null -> {
                        val ctx      = appContext
                        val tint     = res.paint.color
                        val texBmp   = if (ctx != null) decodeTextureCached(ctx, res.textureUri)
                            ?: buildProceduralTexture(tint) else buildProceduralTexture(tint)
                        Paint(res.paint).apply {
                            shader = BitmapShader(texBmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                            if (tint != Color.WHITE)
                                colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
                            alpha = res.spanAlpha
                        }
                    }
                    res.spanAlpha < 255 -> Paint(res.paint).apply { alpha = res.spanAlpha }
                    else                -> res.paint
                }
                canvas.drawText(ch.toString(), charXPositions[i], baseY, fillPaint)
                absPos++
            }
            baseY += lineAdvance
            if (line.isBlank()) baseY += paraGap
        }
    }

    // ── Auto-fit ──────────────────────────────────────────────────────────────

    fun autoFitFontSize(
        text: String,
        boxWidth: Float,
        boxHeight: Float,
        typeface: android.graphics.Typeface? = null,
        maxFontSize: Float = 120f,
        minFontSize: Float = 8f,
        leading: Float = 120f,
        roundBubbleMode: Boolean = false
    ): Float {
        if (boxWidth <= 0f || boxHeight <= 0f) return minFontSize
        if (text.isBlank()) return minFontSize

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface ?: android.graphics.Typeface.DEFAULT
        }

        fun fits(size: Float): Boolean {
            paint.textSize = size
            val insetX = maxOf(size * 0.08f, boxWidth * 0.10f, 2f)
                .coerceAtMost(boxWidth * 0.24f)
            val insetY = maxOf(size * 0.06f, boxHeight * 0.12f, 2f)
                .coerceAtMost(boxHeight * 0.24f)
            val contentW = (boxWidth - insetX * 2f).coerceAtLeast(1f)
            val contentH = (boxHeight - insetY * 2f).coerceAtLeast(1f)
            // The largest axis-aligned rectangle safely inside an oval uses about
            // 70% of its diameter. This prevents corners of text from touching a
            // round bubble while keeping rectangular caption boxes efficient.
            val roundSafeSide = minOf(contentW, contentH) * 0.72f
            val fitW = if (roundBubbleMode) roundSafeSide else contentW
            val fitH = if (roundBubbleMode) roundSafeSide else contentH
            val wrapped = wrapLines(text, paint, fitW)
            val fm = paint.fontMetrics
            val lineAdvance = maxOf(size * (leading / 100f), (fm.descent - fm.ascent) * 1.05f)
            val glyphHeight = (fm.descent - fm.ascent).coerceAtLeast(1f)
            val totalHeight = glyphHeight + (wrapped.size - 1).coerceAtLeast(0) * lineAdvance
            val maxLineW = wrapped.maxOfOrNull { paint.measureText(it) } ?: 0f
            return maxLineW <= fitW && totalHeight <= fitH
        }

        if (!text.contains('\n') && text.length <= 24) {
            paint.textSize = maxFontSize.coerceAtLeast(minFontSize)
            val insetX = maxOf(paint.textSize * 0.08f, boxWidth * 0.10f, 2f)
                .coerceAtMost(boxWidth * 0.24f)
            val insetY = maxOf(paint.textSize * 0.06f, boxHeight * 0.12f, 2f)
                .coerceAtMost(boxHeight * 0.24f)
            val contentW = (boxWidth - insetX * 2f).coerceAtLeast(1f)
            val contentH = (boxHeight - insetY * 2f).coerceAtLeast(1f)
            val roundSafeSide = minOf(contentW, contentH) * 0.72f
            val fitW = if (roundBubbleMode) roundSafeSide else contentW
            val fitH = if (roundBubbleMode) roundSafeSide else contentH
            val fm = paint.fontMetrics
            val lineAdvance = maxOf(paint.textSize * (leading / 100f), (fm.descent - fm.ascent) * 1.05f)
            if (paint.measureText(text) <= fitW && lineAdvance <= fitH) {
                return maxFontSize.coerceIn(minFontSize, maxFontSize)
            }
        }

        var lo = minFontSize.coerceAtLeast(1f)
        var hi = maxFontSize.coerceAtLeast(lo)
        if (!fits(lo)) return lo
        if (fits(hi)) return hi

        // Six binary-search steps are sub-pixel accurate at comic font sizes and
        // reduce repeated wrapping work during Place All on long scripts.
        repeat(6) {
            val mid = (lo + hi) / 2f
            if (fits(mid)) lo = mid else hi = mid
        }
        return lo.coerceIn(minFontSize, maxFontSize)
    }
}