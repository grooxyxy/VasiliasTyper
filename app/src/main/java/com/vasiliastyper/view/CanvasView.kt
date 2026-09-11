package com.vasiliastyper.view

import android.content.Context
import android.graphics.*
import android.graphics.RegionIterator
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.vasiliastyper.engine.BlemishRemovalEngine
import com.vasiliastyper.engine.BrushEngine
import com.vasiliastyper.engine.BrushInpainter
import com.vasiliastyper.engine.BubbleCleaner
import com.vasiliastyper.engine.AgnesAiInpainter
import com.vasiliastyper.engine.CloneStampEngine
import com.vasiliastyper.engine.CriminisiEngine
import com.vasiliastyper.engine.Inpainter
import com.vasiliastyper.engine.IdeogramInpainter
import com.vasiliastyper.engine.LamaMangaInpainter
import com.vasiliastyper.engine.LayerCompositor
import com.vasiliastyper.engine.NavierStokesInpainter
import com.vasiliastyper.engine.OpenCvPatchInpainter
import com.vasiliastyper.engine.ContentAwareFillEngine
import com.vasiliastyper.engine.ResynthesizerEngine
import com.vasiliastyper.engine.SmoothGradientInpainter
import com.vasiliastyper.engine.MagicWandSelector
import com.vasiliastyper.engine.TextRenderer
import com.vasiliastyper.engine.ProcessingConfig
import com.vasiliastyper.engine.TextMaskEngine
import com.vasiliastyper.model.*
import kotlin.math.*

class CanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onSelectionChanged: ((SelectionState) -> Unit)? = null
    var onTextTap: ((Float, Float) -> Unit)? = null
    var onTextEdit: ((TextElement) -> Unit)? = null
    /**
     * v5.1 — Fired whenever the user taps an existing TextElement (without
     * triggering the inline edit-badge). [element] is null when the user taps
     * empty canvas, signalling the floating quick-toolbar should be hidden.
     */
    var onTextSelected: ((element: TextElement?) -> Unit)? = null
    /** Fired when an ImageElement is selected directly on the canvas. */
    var onImageSelected: ((element: ImageElement?) -> Unit)? = null
    var onStatusUpdate: ((String, Float, Float) -> Unit)? = null
    var onEyedropperPick: ((Int) -> Unit)? = null
    var onEyedropperPreview: ((Int) -> Unit)? = null
    var onEyedropperCancel: (() -> Unit)? = null
    var onBrushSizeChanged: ((Float) -> Unit)? = null
    var onBubbleCleanRequest: ((Int, Int) -> Unit)? = null
    /** Invoked when executeBatchClean() is called; receives all queued tap points. */
    var onBubbleCleanBatchReady: ((tapPoints: List<Pair<Int, Int>>) -> Unit)? = null
    /** Called when the user taps a detected mask region while delete mode is active. */
    var onGeminiOverlayDeleteRequest: ((Int) -> Unit)? = null
    /** Called when the user taps an OCR region while OCR delete mode is active. */
    var onOcrOverlayDeleteRequest: ((Int) -> Unit)? = null
    /** Fired while/after the UNWM rectangle is moved or resized by its direct handles. */
    var onUnwatermarkSelectionTransform: ((RectF) -> Unit)? = null

    // ── Batch Bubble Clean state ──────────────────────────────────────────────
    /** When true, taps add points to the batch queue instead of cleaning immediately. */
    var batchCleanActive: Boolean = false
        set(value) { field = value; if (!value) batchCleanPoints.clear(); invalidate() }
    private val batchCleanPoints = mutableListOf<Pair<Int, Int>>()
    /** Current number of queued batch tap points. */
    val batchCleanCount: Int get() = batchCleanPoints.size

    // ── Workspace state ───────────────────────────────────────────────────────
    var layers: MutableList<Layer> = mutableListOf()
    var activeLayerIndex: Int = 0
    var textElements: MutableList<TextElement> = mutableListOf()
    var imageElements: MutableList<ImageElement> = mutableListOf()

    /** Shared bottom-to-top order for every drawable object on the canvas. */
    val canvasStack: MutableList<CanvasStackItem> = mutableListOf()

    /**
     * Removes stale references and inserts newly created objects without changing
     * the order chosen by the user. Background remains anchored at the bottom.
     */
    fun syncCanvasStack() {
        val validPixels = layers.mapTo(mutableSetOf()) { it.id }
        val validImages = imageElements.mapTo(mutableSetOf()) { it.id }
        val validTexts = textElements.mapTo(mutableSetOf()) { it.id }
        canvasStack.removeAll { item ->
            when (item.type) {
                CanvasStackType.PIXEL -> item.id !in validPixels
                CanvasStackType.IMAGE -> item.id !in validImages
                CanvasStackType.TEXT -> item.id !in validTexts
            }
        }

        val known = canvasStack.mapTo(mutableSetOf()) { it.type to it.id }
        layers.forEach { layer ->
            if ((CanvasStackType.PIXEL to layer.id) !in known) {
                canvasStack += CanvasStackItem(CanvasStackType.PIXEL, layer.id)
                known += CanvasStackType.PIXEL to layer.id
            }
        }
        imageElements.forEach { image ->
            if ((CanvasStackType.IMAGE to image.id) !in known) {
                canvasStack += CanvasStackItem(CanvasStackType.IMAGE, image.id)
                known += CanvasStackType.IMAGE to image.id
            }
        }
        textElements.forEach { text ->
            if ((CanvasStackType.TEXT to text.id) !in known) {
                canvasStack += CanvasStackItem(CanvasStackType.TEXT, text.id)
                known += CanvasStackType.TEXT to text.id
            }
        }

        val backgroundId = layers.firstOrNull()?.id ?: return
        val backgroundIndex = canvasStack.indexOfFirst {
            it.type == CanvasStackType.PIXEL && it.id == backgroundId
        }
        if (backgroundIndex > 0) {
            val background = canvasStack.removeAt(backgroundIndex)
            canvasStack.add(0, background)
        }
    }

    // Non-destructive real-time UNWM result, drawn above layers until committed.
    private var unwatermarkPreview: Bitmap? = null
    private val unwatermarkPreviewRect = Rect()
    private val unwatermarkPreviewPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = true
    }

    // ── Force / snap placement ke kanvas (bisa on/off oleh user) ──────────
    /** Magnet ke tengah kanvas saat drag teks/image. */
    var snapToCenterEnabled: Boolean = true
    /** Paksa teks/image tetap di dalam kanvas (clamp X/Y). */
    var clampToCanvasEnabled: Boolean = true
    /** Snap resize ke grid 20px. */
    var snapGridEnabled: Boolean = true

    // ── Before/After split comparison ─────────────────────────────────────────
    private var compareBeforeBitmap: Bitmap? = null
    var compareEnabled: Boolean = false
        set(value) { field = value && compareBeforeBitmap != null; invalidate() }
    var compareDivider: Float = 0.5f
        set(value) { field = value.coerceIn(0.05f, 0.95f); invalidate() }

    /**
     * Stores a bounded preview of the original page. The 4 MP ceiling avoids
     * doubling memory for very tall webtoon canvases while remaining sharp enough
     * for split-view quality control.
     */
    fun setCompareBaseline(bitmap: Bitmap?) {
        compareBeforeBitmap?.let { if (!it.isRecycled) it.recycle() }
        compareBeforeBitmap = null
        compareEnabled = false
        captureCompareBaseline(bitmap)
    }

    /** Capture the BEFORE image once, lazily, just before the first pixel edit. */
    fun ensureCompareBaseline(bitmap: Bitmap?) {
        if (compareBeforeBitmap == null) captureCompareBaseline(bitmap)
    }

    private fun captureCompareBaseline(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        val maxPixels = 4_000_000L
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        val scale = if (pixels > maxPixels) kotlin.math.sqrt(maxPixels.toDouble() / pixels).toFloat() else 1f
        compareBeforeBitmap = try {
            if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        }
        invalidate()
    }

    /** Captures only the requested canvas area for style matching. */
    fun captureRegion(bounds: RectF): Bitmap? {
        if (layers.isEmpty()) return null
        val clipped = RectF(bounds).apply {
            intersect(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat())
        }
        val outW = clipped.width().toInt().coerceAtLeast(1).coerceAtMost(1024)
        val outH = clipped.height().toInt().coerceAtLeast(1).coerceAtMost(1024)
        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val target = Canvas(bitmap)
        target.scale(outW / clipped.width(), outH / clipped.height())
        target.translate(-clipped.left, -clipped.top)
        drawCanvasStack(target)
        return bitmap
    }

    fun setUnwatermarkPreview(bitmap: Bitmap, rect: Rect) {
        clearUnwatermarkPreview(recycle = true, invalidateView = false)
        unwatermarkPreview = bitmap
        unwatermarkPreviewRect.set(rect)
        invalidate()
    }

    fun clearUnwatermarkPreview(recycle: Boolean = true, invalidateView: Boolean = true) {
        val old = unwatermarkPreview
        unwatermarkPreview = null
        unwatermarkPreviewRect.setEmpty()
        if (recycle && old != null && !old.isRecycled) old.recycle()
        if (invalidateView) invalidate()
    }

    // ── Batch clean marker Paints ─────────────────────────────────────────────
    private val batchMarkerFill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(160, 0, 180, 255); style = Paint.Style.FILL }
    private val batchMarkerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(230, 255, 255, 255); style = Paint.Style.STROKE }
    private val batchMarkerText   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textAlign = Paint.Align.CENTER }

    // ── Text detect overlay (colored boxes over detected regions) ─
    var geminiDetectOverlay: List<com.vasiliastyper.engine.PaddleDbNetDetector.DetectedRegion> = emptyList()
    var geminiOverlayDeleteMode: Boolean = false
    private val geminiOverlayFill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(55, 0, 220, 180); style = Paint.Style.FILL }
    private val geminiOverlayStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(220, 0, 220, 180); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val geminiOverlayText   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.argb(255, 0, 255, 200); textAlign = Paint.Align.LEFT; textSize = 26f }

    // OCR regions are independent from mask regions, so opening OCR never destroys
    // the user's mask detection state.
    var ocrDetectOverlay: List<com.vasiliastyper.engine.PaddleDbNetDetector.DetectedRegion> = emptyList()
    var ocrOverlayDeleteMode: Boolean = false
    private val ocrOverlayFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(42, 77, 155, 240); style = Paint.Style.FILL }
    private val ocrOverlayStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 77, 155, 240); style = Paint.Style.STROKE; strokeWidth = 3f }
    private val ocrOverlayText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.LEFT; textSize = 26f }

    // ── Tool state ────────────────────────────────────────────────────────────
    var currentTool: Tool = Tool.MAGIC_WAND
    var tolerance: Int = 32
    var brushEngine: BrushEngine = BrushEngine()
    private var brushLastPoint: PointF? = null
    private var brushCursorPoint: PointF? = null
    private var brushGestureStartPoint: PointF? = null
    private var brushGestureStartScreenY = 0f
    private var brushSizeAtGestureStart = 20f
    private var brushSizingMode = false
    private var brushStrokeStarted = false
    private val brushTouchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val brushSizeGestureRunnable = Runnable {
        if (currentTool == Tool.BRUSH && brushGestureStartPoint != null && !brushStrokeStarted) {
            brushSizingMode = true
            brushCursorPoint = brushGestureStartPoint
            onBrushSizeChanged?.invoke(brushEngine.brushSize)
            onStatusUpdate?.invoke(
                "Ukuran brush: ${brushEngine.brushSize.roundToInt()} px · geser atas/bawah",
                brushCursorPoint?.x ?: 0f,
                brushCursorPoint?.y ?: 0f
            )
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }
    private var contentAwareMask = Region()
    @Volatile private var contentAwareProcessing = false
    var contentAwareBackend: com.vasiliastyper.model.SmartFillBackend = com.vasiliastyper.model.SmartFillBackend.NAVIER_STOKES

    // ── Brush Inpaint (OpenCV Telea/NS) & RemovR (Resynthesizer) ────────────
    private var brushInpaintMask = Region()
    @Volatile private var brushInpaintProcessing = false
    private var removRMask = Region()
    @Volatile private var removRProcessing = false

    // Clone Stamp follows the frozen-source and aligned-offset model used by
    // metamountain/krita-clonestamp, adapted to Android touch input.
    private val cloneStampEngine = CloneStampEngine()
    private var cloneStampCursorPoint: PointF? = null
    private var cloneStampStrokeActive = false
    private var blemishCursorPoint: PointF? = null
    private var blemishTargetPoint: PointF? = null
    @Volatile private var blemishProcessing = false

    // ── Eyedropper mode ───────────────────────────────────────────────────────
    var eyedropperMode: Boolean = false
    private var eyedropperPreviewPoint: PointF? = null
    private var eyedropperPreviewColor: Int = Color.TRANSPARENT

    // ── Selection ─────────────────────────────────────────────────────────────
    val selection = SelectionState()
    private var freeSelectPath = Path()
    /** Live geometry is kept separate from committed selection data.
     *  This makes a second box/lasso visible while the finger is still moving. */
    private val selectionDraftRect = RectF()
    private val selectionDraftPath = Path()
    private var selectionDraftType = SelectionType.NONE
    var selectionCombineMode: SelectionCombineMode = SelectionCombineMode.ADD
    var addToSelection: Boolean
        get() = selectionCombineMode == SelectionCombineMode.ADD
        set(value) { selectionCombineMode = if (value) SelectionCombineMode.ADD else SelectionCombineMode.REPLACE }
    private var addModeRegion: Region? = null

    // ── Pinch / Zoom / Pan ────────────────────────────────────────────────────
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var canvasWidth = 800
    private var canvasHeight = 1200
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var lastPinchMidX = 0f
    private var lastPinchMidY = 0f
    private var isPinching = false
    private var gestureMoved = false
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var pendingTextTapPoint: PointF? = null
    private var pendingTextEditElement: TextElement? = null
    private var textViewportPanActive = false
    private var pendingMaskCloseIndex = -1

    // ── v6.2: Viewport culling for large canvases ─────────────────────────────
    // Prevents drawing off-screen layers/images/text on tall canvases (e.g. 709×15000).
    private val viewportRect = RectF()
    // Reusable Paint objects for image drawing (avoid per-frame allocation → GC churn / crash)
    private val imgMotionTrailPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val imgMotionSharpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val imgFallbackPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // ── v6.2: Throttled invalidate for drag operations on large canvases ──────
    // On very tall canvases, full invalidate() during drag can cause frame drops
    // and crashes. We throttle to ~30fps during drag using a dirty rect approach.
    private var lastInvalidateTimeMs = 0L
    private val INVALIDATE_THROTTLE_MS = 16L // ~60fps cap, prevents excessive redraws
    private val isLargeCanvas: Boolean get() = canvasWidth.toLong() * canvasHeight.toLong() > 3_000_000L

    /** Throttled invalidate for drag/resize operations — prevents crash on large canvases */
    private fun invalidateDrag() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastInvalidateTimeMs >= INVALIDATE_THROTTLE_MS) {
            lastInvalidateTimeMs = now
            invalidate()
        }
    }

    // ── Text transform state ──────────────────────────────────────────────────
    private var draggingTextId: String? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f
    private var resizingTextId: String? = null
    private var rotatingTextId: String? = null
    var activeTextId: String? = null

    // ── Perspective transform state (for text elements) ───────────────────────
    // perspDraggingId  = id of the text element being perspective-dragged
    // perspDraggingCorner = 0-3 for individual corner, -1 for move-all
    private var perspDraggingId: String? = null
    private var perspDraggingCorner: Int = -1
    private var perspDragLastX = 0f
    private var perspDragLastY = 0f

    // ── Mesh transform state (for text elements) ──────────────────────────
    // polyDraggingId = id of the text element whose mesh is being edited
    // polyDraggingIdx = index of the point being dragged, -1 for move-all
    private var polyDraggingId: String? = null
    private var polyDraggingIdx: Int = -1
    private var polyDragLastX = 0f
    private var polyDragLastY = 0f
    // When true, the next canvas tap adds a new point to the active mesh
    var polyAddPointMode: Boolean = false

    // ── Image element transform state ─────────────────────────────────────────
    private var draggingImageId: String? = null
    private var imgDragOffX = 0f
    private var imgDragOffY = 0f
    private var resizingImageId: String? = null
    private var rotatingImageId: String? = null
    var activeImageId: String? = null

    // ── v2.0.2: Text layer visibility ─────────────────────────────────────────
    var textLayerVisible: Boolean = true

    // ── Rotation delta tracking ───────────────────────────────────────────────
    private var rotStartAngle       = 0f
    private var rotStartFingerAngle = 0f

    // ── Snap-to-centre state ──────────────────────────────────────────────────
    private var snapToX = false
    private var snapToY = false

    // ── Text element history ───────────────────────────────────────────────────
    var onTextHistoryPush: (() -> Unit)? = null
    var onWorkspaceHistoryPush: (() -> Unit)? = null
    private val textUndoStack = ArrayDeque<List<TextElement>>()
    private val textRedoStack = ArrayDeque<List<TextElement>>()

    // ── Image element history (lightweight, keeps bitmap refs only) ────────────
    private val imageUndoStack = ArrayDeque<List<ImageElement>>()
    private val imageRedoStack = ArrayDeque<List<ImageElement>>()

    fun pushTextHistory() {
        textUndoStack.addLast(textElements.map { it.copy() })
        if (textUndoStack.size > 20) textUndoStack.removeFirst()
        textRedoStack.clear()
    }

    fun undoText(): Boolean {
        if (textUndoStack.size <= 1) return false
        val cur = textUndoStack.removeLast()
        textRedoStack.addLast(cur)
        textElements.clear()
        textElements.addAll(textUndoStack.last().map { it.copy() })
        invalidate(); return true
    }

    fun redoText(): Boolean {
        val next = textRedoStack.removeLastOrNull() ?: return false
        textUndoStack.addLast(next)
        textElements.clear()
        textElements.addAll(next.map { it.copy() })
        invalidate(); return true
    }

    fun pushImageHistory() {
        imageUndoStack.addLast(imageElements.map { it.copy() })
        if (imageUndoStack.size > 20) imageUndoStack.removeFirst()
        imageRedoStack.clear()
    }

    fun undoImage(): Boolean {
        if (imageUndoStack.size <= 1) return false
        val cur = imageUndoStack.removeLast()
        imageRedoStack.addLast(cur)
        imageElements.clear()
        imageElements.addAll(imageUndoStack.last().map { it.copy() })
        activeImageId = imageElements.lastOrNull()?.id
        invalidate(); return true
    }

    fun redoImage(): Boolean {
        val next = imageRedoStack.removeLastOrNull() ?: return false
        imageUndoStack.addLast(next)
        imageElements.clear()
        imageElements.addAll(next.map { it.copy() })
        activeImageId = imageElements.lastOrNull()?.id
        invalidate(); return true
    }

    // ── Selection drag start ──────────────────────────────────────────────────
    private var selStartX = 0f
    private var selStartY = 0f
    private var selectionGestureMoved = false
    private val selectionDragThreshold: Float
        get() = (6f / scaleFactor).coerceAtLeast(1.5f)
    private var selectionDashPhase = 0f

    // Direct UNWM rectangle manipulation. This is intentionally independent from
    // the global Move tool so an auto-detected watermark can be corrected in-place.
    var unwatermarkTransformEnabled: Boolean = false
        set(value) {
            field = value
            if (!value) {
                unwatermarkTransformMode = null
                unwatermarkTransformStartRect.setEmpty()
            }
            invalidate()
        }
    private var unwatermarkTransformMode: String? = null
    private val unwatermarkTransformStartRect = RectF()
    private var unwatermarkTransformStartX = 0f
    private var unwatermarkTransformStartY = 0f

    // ── Reusable Paint objects ────────────────────────────────────────────────
    private val selectionFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(42, 62, 209, 255); style = Paint.Style.FILL
    }
    private val selectionGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(115, 0, 188, 255); style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val selectionStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val addSelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#55F2C3"); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val subtractSelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8A80"); style = Paint.Style.STROKE; strokeWidth = 2.5f
    }
    private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.FILL
    }
    private val selectionHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00B8FF"); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val unwatermarkHandleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFB300"); style = Paint.Style.FILL
    }
    private val unwatermarkHandleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val unwatermarkHandleLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }

    // Text handle paints
    private val textBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 77, 155, 240); style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val textCornerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 77, 155, 240); style = Paint.Style.FILL
    }
    private val textCornerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val rotHandleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 80, 220, 100); style = Paint.Style.FILL
    }
    private val rotHandleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val rotLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 80, 220, 100); style = Paint.Style.STROKE; strokeWidth = 1.5f
    }

    // Perspective handle paints (purple — distinct from normal blue handles)
    private val perspCornerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 190, 60, 255); style = Paint.Style.FILL
    }
    private val perspBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 190, 60, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(6f, 3f), 0f)
    }

    // Mesh handle paints (teal/cyan — distinct from purple perspective)
    private val polyPointFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 200, 180); style = Paint.Style.FILL
    }
    private val polyLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 200, 180); style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(6f, 3f), 0f)
    }
    private val polyAddPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 200, 180); style = Paint.Style.FILL
    }

    // Image handle paints (orange theme)
    private val imgBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 160, 50); style = Paint.Style.STROKE; strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }
    private val imgCornerFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 140, 30); style = Paint.Style.FILL
    }
    private val imgCornerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val imgBitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }

    // ── Guide / crosshair toggle ──────────────────────────────────────────────
    var showGuides: Boolean = true

    private val guideXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(130, 0, 220, 255)
        style  = Paint.Style.STROKE; strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(14f, 7f), 0f)
    }
    private val guideYPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(130, 255, 50, 220)
        style  = Paint.Style.STROKE; strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(14f, 7f), 0f)
    }
    private val guideZDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(230, 255, 220, 0); style = Paint.Style.FILL
    }
    private val guideZRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(200, 0, 0, 0); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val snapXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(255, 0, 240, 255); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val snapYPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(255, 255, 60, 230); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val guideLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.WHITE; style = Paint.Style.FILL; textSize = 18f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(3f, 1f, 1f, Color.parseColor("#CC000000"))
    }

    // ── Gesture detectors ─────────────────────────────────────────────────────
    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(det: ScaleGestureDetector): Boolean {
                isPinching = true
                gestureMoved = true
                pendingTextTapPoint = null
                pendingTextEditElement = null
                textViewportPanActive = false
                pendingMaskCloseIndex = -1
                lastPinchMidX = det.focusX; lastPinchMidY = det.focusY
                return true
            }
            override fun onScale(det: ScaleGestureDetector): Boolean {
                val prev = scaleFactor
                scaleFactor = (scaleFactor * det.scaleFactor).coerceIn(0.05f, 20f)
                val ratio = scaleFactor / prev
                val fx = det.focusX; val fy = det.focusY
                translateX = fx + ratio * (translateX - lastPinchMidX)
                translateY = fy + ratio * (translateY - lastPinchMidY)
                lastPinchMidX = fx; lastPinchMidY = fy
                invalidate(); return true
            }
            override fun onScaleEnd(det: ScaleGestureDetector) { isPinching = false }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (currentTool == Tool.ZOOM || currentTool == Tool.PAN) {
                    translateX -= dx; translateY -= dy; invalidate(); return true
                }
                return false
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // A double-tap is handled by the normal tap-up path. Keeping editor
                // callbacks out of GestureDetector prevents a scale/scroll sequence
                // from being misclassified as an edit request.
                return currentTool == Tool.TEXT || currentTool == Tool.MESH_FORM
            }
        })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
    }

    fun setCanvasSize(w: Int, h: Int) { canvasWidth = w; canvasHeight = h; invalidate() }

    // ── Snap-to-centre helper ─────────────────────────────────────────────────
    private fun applySnapToCenter(x: Float, y: Float, w: Float, h: Float): Pair<Float, Float> {
        if (!snapToCenterEnabled) { snapToX = false; snapToY = false; return Pair(x, y) }
        val threshold = SNAP_THRESHOLD
        val cx = canvasWidth  / 2f; val cy = canvasHeight / 2f
        val elCx = x + w / 2f; val elCy = y + h / 2f
        snapToX = abs(elCx - cx) < threshold; snapToY = abs(elCy - cy) < threshold
        return Pair(if (snapToX) cx - w / 2f else x, if (snapToY) cy - h / 2f else y)
    }

    companion object {
        private const val SNAP_THRESHOLD = 18f
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        try {
            super.onDraw(canvas)
            if (layers.isEmpty()) return
            canvas.save()
            canvas.translate(translateX, translateY)
            canvas.scale(scaleFactor, scaleFactor)
            drawCanvasStack(canvas)
            drawUnwatermarkPreview(canvas)
            drawCompareBefore(canvas)
            if (showGuides) drawCanvasGuides(canvas)
            drawSelection(canvas)
            if (batchCleanActive && batchCleanPoints.isNotEmpty()) drawBatchMarkers(canvas)
            if (geminiDetectOverlay.isNotEmpty()) drawGeminiOverlay(canvas)
            if (ocrDetectOverlay.isNotEmpty()) drawOcrOverlay(canvas)
            drawContentAwareMask(canvas)
            drawBrushInpaintMask(canvas)
            drawRemovRMask(canvas)
            drawToolCursor(canvas)
            canvas.restore()
            drawEyedropperPreview(canvas)
        } catch (_: Exception) {
            // Prevent force-close from drawing errors (FIX 2)
        }
    }

    // ── Canvas centre guides ──────────────────────────────────────────────────

    private fun drawCanvasGuides(canvas: Canvas) {
        val cW = canvasWidth.toFloat(); val cH = canvasHeight.toFloat()
        val cx = cW / 2f; val cy = cH / 2f
        val sw = 1f / scaleFactor; val ts = 14f / scaleFactor
        val pad = 6f / scaleFactor; val dotR = 7f / scaleFactor

        guideXPaint.strokeWidth = sw; guideYPaint.strokeWidth = sw
        guideZRingPaint.strokeWidth = sw; guideLabelPaint.textSize = ts

        canvas.drawLine(cx, 0f, cx, cH, guideXPaint)
        if (snapToX) { snapXPaint.strokeWidth = 2f / scaleFactor; canvas.drawLine(cx, 0f, cx, cH, snapXPaint) }
        canvas.drawLine(0f, cy, cW, cy, guideYPaint)
        if (snapToY) { snapYPaint.strokeWidth = 2f / scaleFactor; canvas.drawLine(0f, cy, cW, cy, snapYPaint) }

        canvas.drawCircle(cx, cy, dotR, guideZDotPaint)
        canvas.drawCircle(cx, cy, dotR, guideZRingPaint)

        fun drawLabel(text: String, x: Float, y: Float, paint: Paint) {
            val textW = paint.measureText(text)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0); style = Paint.Style.FILL }
            canvas.drawRoundRect(x - pad / 2f, y - ts, x + textW + pad / 2f, y + pad, pad, pad, bgPaint)
            canvas.drawText(text, x, y, paint)
        }
        val xPaint = Paint(guideLabelPaint).apply { color = Color.argb(220, 0, 220, 255) }
        drawLabel("X", cx + pad, ts + pad, xPaint); drawLabel("X", cx + pad, cH - pad, xPaint)
        val yPaint = Paint(guideLabelPaint).apply { color = Color.argb(220, 255, 50, 220) }
        drawLabel("Y", pad, cy - pad, yPaint); drawLabel("Y", cW - ts * 2f, cy - pad, yPaint)
        val zPaint = Paint(guideLabelPaint).apply { color = Color.argb(230, 255, 220, 0) }
        drawLabel("Z", cx + dotR + pad, cy + ts / 2f, zPaint)
    }

    private fun drawSelectionCenterGuides(canvas: Canvas, bounds: RectF) {
        if (!showGuides) return
        val cx = bounds.centerX(); val cy = bounds.centerY()
        val ext = 28f / scaleFactor; val sw = 1f / scaleFactor
        val ts = 13f / scaleFactor; val pad = 5f / scaleFactor; val dotR = 6f / scaleFactor

        guideXPaint.strokeWidth = sw; guideYPaint.strokeWidth = sw
        guideZRingPaint.strokeWidth = sw; guideLabelPaint.textSize = ts

        canvas.drawLine(cx, bounds.top - ext, cx, bounds.bottom + ext, guideXPaint)
        canvas.drawLine(bounds.left - ext, cy, bounds.right + ext, cy, guideYPaint)
        canvas.drawCircle(cx, cy, dotR, guideZDotPaint)
        canvas.drawCircle(cx, cy, dotR, guideZRingPaint)

        fun drawLabel(text: String, x: Float, y: Float, paint: Paint) {
            val textW = paint.measureText(text)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(160, 0, 0, 0); style = Paint.Style.FILL }
            canvas.drawRoundRect(x - pad/2f, y - ts, x + textW + pad/2f, y + pad, pad, pad, bgPaint)
            canvas.drawText(text, x, y, paint)
        }
        val xPaint = Paint(guideLabelPaint).apply { color = Color.argb(220, 0, 220, 255) }
        drawLabel("X", cx + pad, bounds.top - ext + ts, xPaint)
        val yPaint = Paint(guideLabelPaint).apply { color = Color.argb(220, 255, 50, 220) }
        drawLabel("Y", bounds.right + ext - ts * 2f, cy - pad, yPaint)
        val zPaint = Paint(guideLabelPaint).apply { color = Color.argb(230, 255, 220, 0) }
        drawLabel("Z", cx + dotR + pad, cy + ts / 2f, zPaint)
    }

    private fun drawCompareBefore(canvas: Canvas) {
        if (!compareEnabled) return
        val before = compareBeforeBitmap ?: return
        if (before.isRecycled) return
        val splitX = canvasWidth * compareDivider
        canvas.save()
        canvas.clipRect(0f, 0f, splitX, canvasHeight.toFloat())
        canvas.drawBitmap(before, null, RectF(0f, 0f, canvasWidth.toFloat(), canvasHeight.toFloat()), unwatermarkPreviewPaint)
        canvas.restore()

        val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = (2f / scaleFactor).coerceAtLeast(1f)
        }
        canvas.drawLine(splitX, 0f, splitX, canvasHeight.toFloat(), dividerPaint)
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (14f / scaleFactor).coerceAtLeast(8f)
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText("BEFORE", 12f / scaleFactor, 24f / scaleFactor, labelPaint)
        val after = "AFTER"
        canvas.drawText(after, canvasWidth - labelPaint.measureText(after) - 12f / scaleFactor, 24f / scaleFactor, labelPaint)
    }

    private fun drawUnwatermarkPreview(canvas: Canvas) {
        val preview = unwatermarkPreview ?: return
        if (preview.isRecycled || unwatermarkPreviewRect.isEmpty) return
        canvas.drawBitmap(preview, null, unwatermarkPreviewRect, unwatermarkPreviewPaint)
    }

    private fun drawCanvasStack(canvas: Canvas) {
        syncCanvasStack()
        canvasStack.forEach { item ->
            when (item.type) {
                CanvasStackType.PIXEL -> drawLayers(canvas, item.id)
                CanvasStackType.IMAGE -> drawImageElements(canvas, item.id)
                CanvasStackType.TEXT -> drawTextElements(canvas, item.id)
            }
        }
    }

    /** Composite non-pixel elements with the same blend choices as pixel layers. */
    private fun saveElementBlendLayer(canvas: Canvas, mode: String): Int? {
        val porterDuffMode = when (mode) {
            "Multiply" -> PorterDuff.Mode.MULTIPLY
            "Screen" -> PorterDuff.Mode.SCREEN
            "Overlay" -> PorterDuff.Mode.OVERLAY
            else -> return null
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(porterDuffMode)
        }
        return canvas.saveLayer(null, paint)
    }

    private fun drawLayers(canvas: Canvas, onlyId: String? = null) {
        // v6.2: viewport culling — skip layers that are entirely off-screen.
        // On tall canvases (709×15000) this prevents drawBitmap from touching
        // millions of off-screen pixels every frame.
        val vw = width.toFloat()  / scaleFactor
        val vh = height.toFloat() / scaleFactor
        val vx = -translateX / scaleFactor
        val vy = -translateY / scaleFactor
        viewportRect.set(vx, vy, vx + vw, vy + vh)

        for (layer in layers) {
            if (onlyId != null && layer.id != onlyId) continue
            if (!layer.isEffectivelyVisible) continue
            if (layer.bitmap.isRecycled) continue
            // Cull: if the entire bitmap is outside the viewport, skip it
            if (layer.bitmap.width.toFloat() < viewportRect.left ||
                0f > viewportRect.right ||
                layer.bitmap.height.toFloat() < viewportRect.top ||
                0f > viewportRect.bottom) {
                continue
            }
            val layerIndex = layers.indexOf(layer)
            LayerCompositor.drawLayer(
                canvas = canvas,
                layer = layer,
                clippingBase = LayerCompositor.clippingBaseFor(layers, layerIndex),
                width = canvasWidth,
                height = canvasHeight
            )
        }
    }

    // ── Image elements ────────────────────────────────────────────────────────

    /** Render image blur on the software canvas without allocating a bitmap per frame. */
    private fun drawImageBitmapWithBlur(canvas: Canvas, img: ImageElement, src: Rect, dst: RectF) {
        val opacityAlpha = (img.opacity / 100f * 255).toInt().coerceIn(0, 255)
        val radius = img.blurRadius.coerceIn(0f, 80f)
        if (radius <= 0f) {
            imgFallbackPaint.reset()
            imgFallbackPaint.flags = Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG
            imgFallbackPaint.alpha = opacityAlpha
            canvas.drawBitmap(img.bitmap, src, dst, imgFallbackPaint)
            return
        }

        when (img.blurType) {
            BlurType.GAUSSIAN -> {
                imgFallbackPaint.reset()
                imgFallbackPaint.flags = Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG
                imgFallbackPaint.alpha = opacityAlpha
                imgFallbackPaint.maskFilter = BlurMaskFilter(radius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                canvas.drawBitmap(img.bitmap, src, dst, imgFallbackPaint)
                imgFallbackPaint.maskFilter = null
            }
            BlurType.MOTION_H, BlurType.MOTION_V, BlurType.MOTION -> {
                val angle = when (img.blurType) {
                    BlurType.MOTION_H -> 0f
                    BlurType.MOTION_V -> 90f
                    else -> img.blurMotionAngle
                }
                val distance = img.blurMotionDistance.coerceIn(1f, 120f)
                val radians = Math.toRadians(angle.toDouble())
                val dx = cos(radians).toFloat()
                val dy = sin(radians).toFloat()
                val passes = (radius / 4f).roundToInt().coerceIn(5, 13)
                imgMotionTrailPaint.reset()
                imgMotionTrailPaint.flags = Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG
                imgMotionTrailPaint.alpha = (opacityAlpha / passes).coerceAtLeast(10)
                for (pass in 0 until passes) {
                    val t = if (passes == 1) 0f else pass.toFloat() / (passes - 1) - 0.5f
                    val shifted = RectF(dst).apply { offset(dx * distance * t, dy * distance * t) }
                    canvas.drawBitmap(img.bitmap, src, shifted, imgMotionTrailPaint)
                }
            }
        }
    }

    private fun drawImageElements(canvas: Canvas, onlyId: String? = null) {
        // v6.2: viewport culling for image elements on tall canvases
        val vw = width.toFloat()  / scaleFactor
        val vh = height.toFloat() / scaleFactor
        val vx = -translateX / scaleFactor
        val vy = -translateY / scaleFactor
        viewportRect.set(vx, vy, vx + vw, vy + vh)

        for (img in imageElements) {
            if (onlyId != null && img.id != onlyId) continue
            if (!img.isVisible) continue
            // v6.2: Skip recycled bitmaps to prevent crash
            if (img.bitmap.isRecycled) continue
            // Viewport cull: skip images entirely off-screen
            if (img.x > viewportRect.right || img.x + img.width < viewportRect.left ||
                img.y > viewportRect.bottom || img.y + img.height < viewportRect.top) {
                continue
            }
            val blendSave = saveElementBlendLayer(canvas, img.blendMode)
            canvas.save()
            val cx = img.x + img.width / 2f; val cy = img.y + img.height / 2f
            canvas.rotate(img.rotation, cx, cy)
            val src = Rect(0, 0, img.bitmap.width, img.bitmap.height)
            val dst = RectF(img.x, img.y, img.x + img.width, img.y + img.height)

            try {
                drawImageBitmapWithBlur(canvas, img, src, dst)
            } catch (_: Exception) {
                imgFallbackPaint.reset()
                imgFallbackPaint.flags = Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG
                imgFallbackPaint.alpha = (img.opacity / 100f * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(img.bitmap, src, dst, imgFallbackPaint)
            }

            val showFullHandles = (currentTool == Tool.ADD_IMAGE && !img.isLocked)
            val showMoveHandles = (currentTool == Tool.MOVE && img.id == activeImageId && !img.isLocked)
            if (showFullHandles || showMoveHandles) {
                val hs = (28f / scaleFactor).coerceAtLeast(28f)
                imgBoxPaint.strokeWidth = 1.5f / scaleFactor
                imgCornerStroke.strokeWidth = 1.5f / scaleFactor

                canvas.drawRect(img.x, img.y, img.x + img.width, img.y + img.height, imgBoxPaint)

                // Corner resize handles — shown for both MOVE and ADD_IMAGE (FIX 8)
                for ((hx, hy) in listOf(
                    img.x to img.y, img.x + img.width to img.y,
                    img.x to img.y + img.height, img.x + img.width to img.y + img.height)) {
                    canvas.drawCircle(hx, hy, hs * 0.85f, imgCornerFill)
                    canvas.drawCircle(hx, hy, hs * 0.85f, imgCornerStroke)
                }

                if (showFullHandles) {
                    val hsMid = (20f / scaleFactor).coerceAtLeast(20f)
                    val rotOff = (56f / scaleFactor).coerceAtLeast(30f)
                    rotLinePaint.strokeWidth = 1.5f / scaleFactor
                    rotHandleStroke.strokeWidth = 1.5f / scaleFactor

                    val rotHy = img.y - rotOff
                    canvas.drawLine(cx, img.y, cx, rotHy, rotLinePaint)
                    canvas.drawCircle(cx, rotHy, hs, rotHandleFill)
                    canvas.drawCircle(cx, rotHy, hs, rotHandleStroke)

                    val midEdgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(220, 255, 220, 180); style = Paint.Style.FILL
                    }
                    val midEdgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(200, 200, 100, 20); style = Paint.Style.STROKE
                        strokeWidth = 1.5f / scaleFactor
                    }
                    for ((hx, hy) in listOf(
                        img.x to cy, img.x + img.width to cy,
                        cx to img.y, cx to img.y + img.height)) {
                        canvas.drawCircle(hx, hy, hsMid, midEdgeFill)
                        canvas.drawCircle(hx, hy, hsMid, midEdgeStroke)
                    }

                    val delOff = hs * 1.1f
                    val delX = img.x + img.width + delOff; val delY = img.y - delOff
                    val delPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E05252"); style = Paint.Style.FILL }
                    canvas.drawCircle(delX, delY, hs, delPaint)
                    val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE; style = Paint.Style.STROKE
                        strokeWidth = 1.5f / scaleFactor; strokeCap = Paint.Cap.ROUND
                    }
                    val d = hs * 0.55f
                    canvas.drawLine(delX-d, delY-d, delX+d, delY+d, xPaint)
                    canvas.drawLine(delX+d, delY-d, delX-d, delY+d, xPaint)
                }
            }
            canvas.restore()
            blendSave?.let(canvas::restoreToCount)
        }
    }

    // ── Text elements ─────────────────────────────────────────────────────────

    private fun drawTextElements(canvas: Canvas, onlyId: String? = null) {
        if (!textLayerVisible) return
        // v6.2: viewport culling for text elements on tall canvases
        val vw = width.toFloat()  / scaleFactor
        val vh = height.toFloat() / scaleFactor
        val vx = -translateX / scaleFactor
        val vy = -translateY / scaleFactor
        viewportRect.set(vx, vy, vx + vw, vy + vh)

        for (el in textElements) {
            if (onlyId != null && el.id != onlyId) continue
            if (!el.isVisible) continue
            // Viewport cull: skip text elements entirely off-screen (render + handles)
            if (el.x > viewportRect.right || el.x + el.width < viewportRect.left ||
                el.y > viewportRect.bottom || el.y + el.height < viewportRect.top) {
                continue
            }
            val blendSave = saveElementBlendLayer(canvas, el.blendMode)
            TextRenderer.renderToCanvas(canvas, el)
            blendSave?.let(canvas::restoreToCount)
            if (el.isLocked || (currentTool != Tool.TEXT && currentTool != Tool.MESH_FORM)) continue

            val isActive = (el.id == activeTextId)
            val hs    = (28f / scaleFactor).coerceAtLeast(28f)

            val poly = el.meshPoints
            val corners = el.perspCorners
            if (poly != null && poly.size >= 4) {
                // ── Mesh mode handles ────────────────────────────────────
                drawMeshHandles(canvas, el, poly, isActive, hs)
            } else if (corners != null && corners.size == 8) {
                // ── Perspective mode handles ──────────────────────────────────
                drawPerspectiveHandles(canvas, corners, isActive, hs)
            } else {
                // ── Normal handles ────────────────────────────────────────────
                drawNormalTextHandles(canvas, el, isActive, hs)
            }
        }
    }

    private fun drawPerspectiveHandles(canvas: Canvas, corners: FloatArray, isActive: Boolean, hs: Float) {
        if (corners.size < 8) return
        val cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4f
        val cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4f

        perspBoxPaint.strokeWidth = (if (isActive) 2f else 1.5f) / scaleFactor
        textCornerStroke.strokeWidth = 1.5f / scaleFactor
        rotHandleStroke.strokeWidth  = 1.5f / scaleFactor
        rotLinePaint.strokeWidth     = 1.5f / scaleFactor

        // Quadrilateral outline: TL→TR→BR→BL→TL
        val path = Path().apply {
            moveTo(corners[0], corners[1]); lineTo(corners[2], corners[3])
            lineTo(corners[6], corners[7]); lineTo(corners[4], corners[5]); close()
        }
        canvas.drawPath(path, perspBoxPaint)

        // Corner handles (purple)
        for (c in 0 until 4) {
            canvas.drawCircle(corners[c * 2], corners[c * 2 + 1], hs * 0.85f, perspCornerFill)
            canvas.drawCircle(corners[c * 2], corners[c * 2 + 1], hs * 0.85f, textCornerStroke)
        }

        // Rotation handle above midpoint of TL-TR edge
        val rotOff = (56f / scaleFactor).coerceAtLeast(30f)
        val midTopX = (corners[0] + corners[2]) / 2f
        val midTopY = (corners[1] + corners[3]) / 2f
        // Direction perpendicular to top edge, pointing "up"
        val edgeDx = corners[2] - corners[0]; val edgeDy = corners[3] - corners[1]
        val edgeLen = sqrt(edgeDx * edgeDx + edgeDy * edgeDy).coerceAtLeast(0.001f)
        val perpX = -edgeDy / edgeLen; val perpY = edgeDx / edgeLen
        val rotHx = midTopX + perpX * rotOff; val rotHy = midTopY + perpY * rotOff

        canvas.drawLine(midTopX, midTopY, rotHx, rotHy, rotLinePaint)
        canvas.drawCircle(rotHx, rotHy, hs, rotHandleFill)
        canvas.drawCircle(rotHx, rotHy, hs, rotHandleStroke)

        // Delete badge (top-right corner area)
        val delOff = hs * 1.1f
        val delX = corners[2] + perpX * delOff + edgeDx / edgeLen * delOff
        val delY = corners[3] + perpY * delOff + edgeDy / edgeLen * delOff
        val delPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E05252"); style = Paint.Style.FILL }
        canvas.drawCircle(delX, delY, hs, delPaint)
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = 1.5f / scaleFactor; strokeCap = Paint.Cap.ROUND
        }
        val d = hs * 0.55f
        canvas.drawLine(delX-d, delY-d, delX+d, delY+d, xPaint)
        canvas.drawLine(delX+d, delY-d, delX-d, delY+d, xPaint)

        // "PERSP" label at centroid when active
        if (isActive) {
            val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 190, 60, 255); textSize = 10f / scaleFactor
                textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            canvas.drawText("PERSP", cx, cy + 4f / scaleFactor, lblPaint)
        }
    }

    // ── Mesh handle drawing ──────────────────────────────────────────────
    // Draws a 4×4 control grid (3×3 cells), draggable point handles, a
    // rotation handle above the top row, and a delete badge.

    private fun drawMeshHandles(canvas: Canvas, el: TextElement, pts: FloatArray, isActive: Boolean, hs: Float) {
        if (pts.size < 8) return
        val cols = el.meshCols.coerceIn(1, 8)
        val rows = el.meshRows.coerceIn(1, 8)
        val expected = (cols + 1) * (rows + 1)
        if (pts.size / 2 < expected) return

        val gridW = cols + 1
        val gridH = rows + 1
        val cx = el.x + el.width / 2f
        val cy = el.y + el.height / 2f

        textCornerStroke.strokeWidth = 1.5f / scaleFactor
        rotHandleStroke.strokeWidth  = 1.5f / scaleFactor
        rotLinePaint.strokeWidth     = 1.5f / scaleFactor
        polyLinePaint.strokeWidth    = (if (isActive) 2f else 1.5f) / scaleFactor

        fun idx(c: Int, r: Int): Int = (r * gridW + c) * 2
        fun localPt(c: Int, r: Int): PointF = PointF(pts[idx(c, r)], pts[idx(c, r) + 1])
        fun ax(c: Int, r: Int): Float = rotatePoint(PointF(el.x + pts[idx(c, r)], el.y + pts[idx(c, r) + 1]), cx, cy, el.rotation).x
        fun ay(c: Int, r: Int): Float = rotatePoint(PointF(el.x + pts[idx(c, r)], el.y + pts[idx(c, r) + 1]), cx, cy, el.rotation).y

        // Draw mesh grid lines
        for (r in 0..rows) {
            val path = Path().apply {
                val p0 = rotatePoint(PointF(el.x + pts[idx(0, r)], el.y + pts[idx(0, r) + 1]), cx, cy, el.rotation)
                moveTo(p0.x, p0.y)
                for (c in 1..cols) {
                    val p = rotatePoint(PointF(el.x + pts[idx(c, r)], el.y + pts[idx(c, r) + 1]), cx, cy, el.rotation)
                    lineTo(p.x, p.y)
                }
            }
            canvas.drawPath(path, polyLinePaint)
        }
        for (c in 0..cols) {
            val path = Path().apply {
                val p0 = rotatePoint(PointF(el.x + pts[idx(c, 0)], el.y + pts[idx(c, 0) + 1]), cx, cy, el.rotation)
                moveTo(p0.x, p0.y)
                for (r in 1..rows) {
                    val p = rotatePoint(PointF(el.x + pts[idx(c, r)], el.y + pts[idx(c, r) + 1]), cx, cy, el.rotation)
                    lineTo(p.x, p.y)
                }
            }
            canvas.drawPath(path, polyLinePaint)
        }

        // Control points
        for (r in 0..rows) for (c in 0..cols) {
            val p = rotatePoint(PointF(el.x + pts[idx(c, r)], el.y + pts[idx(c, r) + 1]), cx, cy, el.rotation)
            canvas.drawCircle(p.x, p.y, hs * 0.8f, polyPointFill)
            canvas.drawCircle(p.x, p.y, hs * 0.8f, textCornerStroke)
        }

        // Rotation handle above top-center
        val topLeft = rotatePoint(PointF(el.x + pts[idx(cols / 2, 0)], el.y + pts[idx(cols / 2, 0) + 1]), cx, cy, el.rotation)
        val topRight = rotatePoint(PointF(el.x + pts[idx((cols + 1) / 2, 0)], el.y + pts[idx((cols + 1) / 2, 0) + 1]), cx, cy, el.rotation)
        val topMidX = (topLeft.x + topRight.x) / 2f
        val topMidY = (topLeft.y + topRight.y) / 2f
        val rotOff = (56f / scaleFactor).coerceAtLeast(30f)
        val edgeDx = topRight.x - topLeft.x
        val edgeDy = topRight.y - topLeft.y
        val edgeLen = sqrt(edgeDx * edgeDx + edgeDy * edgeDy).coerceAtLeast(0.001f)
        val perpX = -edgeDy / edgeLen
        val perpY = edgeDx / edgeLen
        val rotHx = topMidX + perpX * rotOff
        val rotHy = topMidY + perpY * rotOff
        canvas.drawLine(topMidX, topMidY, rotHx, rotHy, rotLinePaint)
        canvas.drawCircle(rotHx, rotHy, hs, rotHandleFill)
        canvas.drawCircle(rotHx, rotHy, hs, rotHandleStroke)

        // Delete badge placed below the mesh, centered, with extra spacing
        val bottomLeft = rotatePoint(PointF(el.x + pts[idx(0, rows)], el.y + pts[idx(0, rows) + 1]), cx, cy, el.rotation)
        val bottomRight = rotatePoint(PointF(el.x + pts[idx(cols, rows)], el.y + pts[idx(cols, rows) + 1]), cx, cy, el.rotation)
        val bottomMidX = (bottomLeft.x + bottomRight.x) / 2f
        val bottomMidY = (bottomLeft.y + bottomRight.y) / 2f
        val bottomEdgeDx = bottomRight.x - bottomLeft.x
        val bottomEdgeDy = bottomRight.y - bottomLeft.y
        val bottomEdgeLen = sqrt(bottomEdgeDx * bottomEdgeDx + bottomEdgeDy * bottomEdgeDy).coerceAtLeast(0.001f)
        val downX = bottomEdgeDy / bottomEdgeLen
        val downY = -bottomEdgeDx / bottomEdgeLen
        val upX = -downX
        val upY = -downY
        val badgeGap = (hs * 2.2f) + (10f / scaleFactor)
        val candDownX = bottomMidX + downX * badgeGap
        val candDownY = bottomMidY + downY * badgeGap
        val candUpX = bottomMidX + upX * badgeGap
        val candUpY = bottomMidY + upY * badgeGap
        val useDown = candDownY >= candUpY
        val delX = if (useDown) candDownX else candUpX
        val delY = if (useDown) candDownY else candUpY
        val delPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E05252"); style = Paint.Style.FILL }
        canvas.drawCircle(delX, delY, hs, delPaint)
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = 1.5f / scaleFactor; strokeCap = Paint.Cap.ROUND
        }
        val d = hs * 0.55f
        canvas.drawLine(delX - d, delY - d, delX + d, delY + d, xPaint)
        canvas.drawLine(delX + d, delY - d, delX - d, delY + d, xPaint)

        if (isActive) {
            val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(200, 0, 200, 180); textSize = 10f / scaleFactor
                textAlign = Paint.Align.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            canvas.drawText("MESH", cx, cy + 4f / scaleFactor, lblPaint)
        }
    }

    private fun drawNormalTextHandles(canvas: Canvas, el: TextElement, isActive: Boolean, hs: Float) {
        val cx = el.x + el.width / 2f; val cy = el.y + el.height / 2f
        val hsMid = (20f / scaleFactor).coerceAtLeast(20f)
        val rotOffset = (56f / scaleFactor).coerceAtLeast(30f)

        textBoxPaint.strokeWidth     = (if (isActive) 2f else 1.5f) / scaleFactor
        textCornerStroke.strokeWidth = 1.5f / scaleFactor
        rotHandleStroke.strokeWidth  = 1.5f / scaleFactor
        rotLinePaint.strokeWidth     = 1.5f / scaleFactor

        canvas.save()
        canvas.rotate(el.rotation, cx, cy)

        canvas.drawRect(el.x, el.y, el.x + el.width, el.y + el.height, textBoxPaint)

        val rotHy = el.y - rotOffset
        canvas.drawLine(cx, el.y, cx, rotHy, rotLinePaint)
        canvas.drawCircle(cx, rotHy, hs, rotHandleFill)
        canvas.drawCircle(cx, rotHy, hs, rotHandleStroke)

        for ((hx, hy) in listOf(
            el.x to el.y, el.x + el.width to el.y,
            el.x to el.y + el.height, el.x + el.width to el.y + el.height)) {
            canvas.drawCircle(hx, hy, hs * 0.85f, textCornerFill)
            canvas.drawCircle(hx, hy, hs * 0.85f, textCornerStroke)
        }

        val midEdgeFill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(220, 240, 240, 255); style = Paint.Style.FILL }
        val midEdgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 60, 120, 230); style = Paint.Style.STROKE; strokeWidth = 1.5f / scaleFactor
        }
        for ((hx, hy) in listOf(el.x to cy, el.x + el.width to cy, cx to el.y, cx to el.y + el.height)) {
            canvas.drawCircle(hx, hy, hsMid, midEdgeFill)
            canvas.drawCircle(hx, hy, hsMid, midEdgeStroke)
        }

        val delOff = hs * 1.1f
        val delX = el.x + el.width + delOff; val delY = el.y - delOff
        val delPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E05252"); style = Paint.Style.FILL }
        canvas.drawCircle(delX, delY, hs, delPaint)
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE
            strokeWidth = 1.5f / scaleFactor; strokeCap = Paint.Cap.ROUND
        }
        val d = hs * 0.55f
        canvas.drawLine(delX-d, delY-d, delX+d, delY+d, xPaint)
        canvas.drawLine(delX+d, delY-d, delX-d, delY+d, xPaint)

        val editX = el.x - delOff; val editY = el.y - delOff
        val editBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC3A6FE8"); style = Paint.Style.FILL }
        canvas.drawCircle(editX, editY, hs, editBgPaint)
        val editStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 1.5f / scaleFactor
        }
        canvas.drawCircle(editX, editY, hs, editStroke)

        if (isActive) {
            val rotOff2 = (56f / scaleFactor).coerceAtLeast(30f) * 1.4f
            val refPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(100, 220, 220, 255); style = Paint.Style.STROKE
                strokeWidth = 1.5f / scaleFactor
                pathEffect = DashPathEffect(floatArrayOf(8f / scaleFactor, 5f / scaleFactor), 0f)
            }
            canvas.drawLine(cx, cy, cx, el.y - rotOff2, refPaint)
            val normalDeg = ((el.rotation % 360f) + 360f) % 360f
            val degLabel  = "%.0f°".format(normalDeg)
            val badgeBgP  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#CC1A1A2E"); style = Paint.Style.FILL }
            val badgeTxtP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = 11f / scaleFactor; textAlign = Paint.Align.CENTER
            }
            val badgeCy = el.y - rotOff2 - (16f / scaleFactor)
            val badgeW  = 24f / scaleFactor
            canvas.drawRoundRect(cx - badgeW, badgeCy - 11f / scaleFactor,
                cx + badgeW, badgeCy + 4f / scaleFactor, 3f / scaleFactor, 3f / scaleFactor, badgeBgP)
            canvas.drawText(degLabel, cx, badgeCy, badgeTxtP)
        }

        canvas.restore()
    }

    private fun drawSelectionShape(draw: (Paint) -> Unit) {
        val inverseScale = 1f / scaleFactor.coerceAtLeast(0.01f)
        selectionGlowPaint.strokeWidth = 6f * inverseScale
        selectionStrokePaint.strokeWidth = 2f * inverseScale
        addSelStrokePaint.strokeWidth = 2.5f * inverseScale
        subtractSelStrokePaint.strokeWidth = 2.5f * inverseScale
        val dash = floatArrayOf(10f * inverseScale, 6f * inverseScale)
        val stroke = when (selectionCombineMode) {
            SelectionCombineMode.ADD -> addSelStrokePaint
            SelectionCombineMode.SUBTRACT -> subtractSelStrokePaint
            else -> selectionStrokePaint
        }
        stroke.pathEffect = DashPathEffect(dash, selectionDashPhase * inverseScale)
        draw(selectionFillPaint)
        draw(selectionGlowPaint)
        draw(stroke)
    }

    private fun drawSelectionHandles(canvas: Canvas, bounds: RectF) {
        if (bounds.isEmpty) return
        val radius = (5.5f / scaleFactor).coerceAtLeast(3.5f)
        selectionHandleStrokePaint.strokeWidth = 2f / scaleFactor.coerceAtLeast(0.01f)
        listOf(
            bounds.left to bounds.top,
            bounds.right to bounds.top,
            bounds.left to bounds.bottom,
            bounds.right to bounds.bottom
        ).forEach { (x, y) ->
            canvas.drawCircle(x, y, radius, selectionHandlePaint)
            canvas.drawCircle(x, y, radius, selectionHandleStrokePaint)
        }
    }

    private fun drawSelection(canvas: Canvas) {
        if (!selection.isActive && selectionDraftType == SelectionType.NONE) return
        selectionDashPhase = (selectionDashPhase + 1.4f) % 16f
        when (selection.type) {
            SelectionType.RECT -> {
                // Draw every committed part independently. `selection.rect` may hold
                // the union bounds for compatibility with older tools; drawing it here
                // produced one giant selection from the first bubble to the last one.
                if (selection.parts.isNotEmpty()) {
                    for (part in selection.parts) {
                        drawSelectionShape { canvas.drawRoundRect(part, 3f, 3f, it) }
                        drawSelectionHandles(canvas, part)
                        drawSelectionCenterGuides(canvas, RectF(part))
                    }
                } else {
                    drawSelectionShape { canvas.drawRoundRect(selection.rect, 3f, 3f, it) }
                    drawSelectionHandles(canvas, selection.rect)
                    drawSelectionCenterGuides(canvas, RectF(selection.rect))
                }
                if (unwatermarkTransformEnabled && selection.parts.size <= 1) {
                    drawUnwatermarkTransformHandles(canvas, selection.getBounds())
                }
            }
            SelectionType.OVAL -> {
                drawSelectionShape { canvas.drawOval(RectF(selection.rect), it) }
                drawSelectionHandles(canvas, selection.rect)
                drawSelectionCenterGuides(canvas, RectF(selection.rect))
            }
            SelectionType.FREE -> {
                drawSelectionShape { canvas.drawPath(selection.path, it) }
                val pb = RectF(); selection.path.computeBounds(pb, true)
                if (!pb.isEmpty) {
                    drawSelectionHandles(canvas, pb)
                    drawSelectionCenterGuides(canvas, pb)
                }
                // Per-part guides for multi-free selections
                for (part in selection.parts) {
                    drawSelectionCenterGuides(canvas, RectF(part))
                }
            }
            SelectionType.MAGIC_WAND -> {
                val activeRegion = selection.region
                val rb = activeRegion?.bounds
                if (activeRegion != null && rb != null && !rb.isEmpty) {
                    // Build one path and paint it once. Painting every RegionIterator span
                    // separately caused the lasso overlay to look striped/glitched.
                    val regionPath = Path()
                    val iterator = RegionIterator(activeRegion)
                    val span = Rect()
                    while (iterator.next(span)) {
                        regionPath.addRect(RectF(span), Path.Direction.CW)
                    }
                    drawSelectionShape { canvas.drawPath(regionPath, it) }
                    drawSelectionHandles(canvas, RectF(rb))
                    drawSelectionCenterGuides(canvas, RectF(rb))
                }
            }
            else -> {}
        }

        // Always draw the in-progress shape above committed areas. In add mode the
        // committed `parts` list intentionally stays untouched until ACTION_UP.
        when (selectionDraftType) {
            SelectionType.RECT -> if (!selectionDraftRect.isEmpty) {
                drawSelectionShape { canvas.drawRoundRect(selectionDraftRect, 3f, 3f, it) }
                drawSelectionHandles(canvas, selectionDraftRect)
            }
            SelectionType.FREE -> if (!selectionDraftPath.isEmpty) {
                drawSelectionShape { canvas.drawPath(selectionDraftPath, it) }
            }
            else -> Unit
        }
        postInvalidateDelayed(42L)
    }

    private fun drawUnwatermarkTransformHandles(canvas: Canvas, bounds: RectF) {
        if (bounds.isEmpty) return
        val radius = (12f / scaleFactor).coerceAtLeast(7f)
        unwatermarkHandleStrokePaint.strokeWidth = 2f / scaleFactor
        unwatermarkHandleLabelPaint.textSize = 9f / scaleFactor
        val handles = listOf(
            Triple(bounds.left, bounds.top, "↖"),
            Triple(bounds.right, bounds.top, "↗"),
            Triple(bounds.left, bounds.bottom, "↙"),
            Triple(bounds.right, bounds.bottom, "↘")
        )
        for ((x, y, label) in handles) {
            canvas.drawCircle(x, y, radius, unwatermarkHandleFillPaint)
            canvas.drawCircle(x, y, radius, unwatermarkHandleStrokePaint)
            canvas.drawText(label, x, y + 3f / scaleFactor, unwatermarkHandleLabelPaint)
        }
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 16, 38, 44)
            style = Paint.Style.FILL
        }
        val badgeWidth = 112f / scaleFactor
        val badgeHeight = 22f / scaleFactor
        val badgeTop = (bounds.top - badgeHeight - 8f / scaleFactor).coerceAtLeast(0f)
        canvas.drawRoundRect(
            bounds.centerX() - badgeWidth / 2f,
            badgeTop,
            bounds.centerX() + badgeWidth / 2f,
            badgeTop + badgeHeight,
            6f / scaleFactor,
            6f / scaleFactor,
            badgePaint
        )
        unwatermarkHandleLabelPaint.textSize = 10f / scaleFactor
        canvas.drawText("GESER / RESIZE UNWM", bounds.centerX(), badgeTop + 15f / scaleFactor, unwatermarkHandleLabelPaint)
    }

    /** Returns true only when a direct UNWM transform gesture owns this event. */
    private fun handleUnwatermarkSelectionTransform(event: MotionEvent, pt: PointF): Boolean {
        if (!unwatermarkTransformEnabled || !selection.isActive ||
            selection.type != SelectionType.RECT || selection.parts.size > 1
        ) return false
        val bounds = selection.getBounds()
        if (bounds.isEmpty) return false
        val hitRadius = (28f / scaleFactor).coerceAtLeast(16f)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                unwatermarkTransformMode = when {
                    dist(pt.x, pt.y, bounds.left, bounds.top) <= hitRadius -> "tl"
                    dist(pt.x, pt.y, bounds.right, bounds.top) <= hitRadius -> "tr"
                    dist(pt.x, pt.y, bounds.left, bounds.bottom) <= hitRadius -> "bl"
                    dist(pt.x, pt.y, bounds.right, bounds.bottom) <= hitRadius -> "br"
                    bounds.contains(pt.x, pt.y) -> "move"
                    else -> null
                }
                if (unwatermarkTransformMode == null) return false
                unwatermarkTransformStartRect.set(bounds)
                unwatermarkTransformStartX = pt.x
                unwatermarkTransformStartY = pt.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val mode = unwatermarkTransformMode ?: return false
                val dx = pt.x - unwatermarkTransformStartX
                val dy = pt.y - unwatermarkTransformStartY
                val next = RectF(unwatermarkTransformStartRect)
                val minSize = 12f
                when (mode) {
                    "move" -> {
                        next.offset(dx, dy)
                        if (next.left < 0f) next.offset(-next.left, 0f)
                        if (next.top < 0f) next.offset(0f, -next.top)
                        if (next.right > canvasWidth) next.offset(canvasWidth - next.right, 0f)
                        if (next.bottom > canvasHeight) next.offset(0f, canvasHeight - next.bottom)
                    }
                    "tl" -> { next.left = (unwatermarkTransformStartRect.left + dx).coerceAtMost(next.right - minSize); next.top = (unwatermarkTransformStartRect.top + dy).coerceAtMost(next.bottom - minSize) }
                    "tr" -> { next.right = (unwatermarkTransformStartRect.right + dx).coerceAtLeast(next.left + minSize); next.top = (unwatermarkTransformStartRect.top + dy).coerceAtMost(next.bottom - minSize) }
                    "bl" -> { next.left = (unwatermarkTransformStartRect.left + dx).coerceAtMost(next.right - minSize); next.bottom = (unwatermarkTransformStartRect.bottom + dy).coerceAtLeast(next.top + minSize) }
                    "br" -> { next.right = (unwatermarkTransformStartRect.right + dx).coerceAtLeast(next.left + minSize); next.bottom = (unwatermarkTransformStartRect.bottom + dy).coerceAtLeast(next.top + minSize) }
                }
                val maxLeft = (canvasWidth - 1).coerceAtLeast(0).toFloat()
                val maxTop = (canvasHeight - 1).coerceAtLeast(0).toFloat()
                next.left = next.left.coerceIn(0f, maxLeft)
                next.top = next.top.coerceIn(0f, maxTop)
                next.right = next.right.coerceIn(next.left + 1f, canvasWidth.toFloat())
                next.bottom = next.bottom.coerceIn(next.top + 1f, canvasHeight.toFloat())
                selection.rect.set(next)
                if (selection.parts.size == 1) selection.parts[0].set(next)
                onUnwatermarkSelectionTransform?.invoke(RectF(next))
                invalidateDrag()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (unwatermarkTransformMode == null) return false
                unwatermarkTransformMode = null
                val finalRect = selection.getBounds()
                onSelectionChanged?.invoke(selection)
                onUnwatermarkSelectionTransform?.invoke(finalRect)
                invalidate()
                return true
            }
        }
        return unwatermarkTransformMode != null
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureDownX = event.x
                gestureDownY = event.y
                lastTouchX = event.x
                lastTouchY = event.y
                gestureMoved = false
                pendingMaskCloseIndex = -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(event.x - gestureDownX, event.y - gestureDownY) > brushTouchSlop) {
                    gestureMoved = true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                gestureMoved = true
                pendingTextTapPoint = null
                pendingTextEditElement = null
                textViewportPanActive = false
                pendingMaskCloseIndex = -1
            }
        }

        // Eyedropper owns the gesture from ACTION_DOWN onward. Handle it before
        // GestureDetector/ScaleGestureDetector so a color pick can never trigger
        // text editing, pan, selection, or another canvas tool underneath it.
        if (eyedropperMode) {
            val pickPoint = screenToCanvas(event.x, event.y)
            updateStatusCursor(pickPoint.x, pickPoint.y)
            handleEyedropper(event, pickPoint)
            return true
        }

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (event.pointerCount >= 2) {
            gestureMoved = true
            pendingTextTapPoint = null
            pendingTextEditElement = null
            pendingMaskCloseIndex = -1
            val midX = (event.getX(0) + event.getX(1)) / 2f
            val midY = (event.getY(0) + event.getY(1)) / 2f
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN -> { lastPinchMidX = midX; lastPinchMidY = midY }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        translateX += midX - lastPinchMidX; translateY += midY - lastPinchMidY
                        invalidate(); lastPinchMidX = midX; lastPinchMidY = midY
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remainingIdx = if (event.actionIndex == 0) 1 else 0
                    lastTouchX = event.getX(remainingIdx); lastTouchY = event.getY(remainingIdx)
                }
            }
            return true
        }

        if (scaleDetector.isInProgress || isPinching) return true

        val pt = screenToCanvas(event.x, event.y)
        updateStatusCursor(pt.x, pt.y)

        if (handleUnwatermarkSelectionTransform(event, pt)) return true

        // Every sentence mask has its own close badge. It is always active, so the
        // user does not need to enable a separate delete mode first.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pendingMaskCloseIndex = hitGeminiOverlayCloseIndex(pt.x, pt.y)
                if (pendingMaskCloseIndex >= 0) return true
            }
            MotionEvent.ACTION_MOVE -> if (pendingMaskCloseIndex >= 0) return true
            MotionEvent.ACTION_UP -> if (pendingMaskCloseIndex >= 0) {
                val closeIndex = pendingMaskCloseIndex
                pendingMaskCloseIndex = -1
                if (!gestureMoved && closeIndex == hitGeminiOverlayCloseIndex(pt.x, pt.y)) {
                    onGeminiOverlayDeleteRequest?.invoke(closeIndex)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> pendingMaskCloseIndex = -1
        }

        if (ocrOverlayDeleteMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    val hit = hitOcrOverlayIndex(pt.x, pt.y)
                    if (hit >= 0) {
                        onOcrOverlayDeleteRequest?.invoke(hit)
                        invalidate()
                    }
                    return true
                }
            }
        }

        if (geminiOverlayDeleteMode) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> return true
                MotionEvent.ACTION_UP -> {
                    val hit = hitGeminiOverlayIndex(pt.x, pt.y)
                    if (hit >= 0) {
                        onGeminiOverlayDeleteRequest?.invoke(hit)
                        invalidate()
                    }
                    return true
                }
            }
        }

        when (currentTool) {
            Tool.PAN                           -> handleZoomPan(event)
            Tool.MOVE                          -> handleMoveElement(event, pt)
            Tool.RECT_SELECT                   -> handleBoxSelect(event, pt)
            Tool.TEXT_SHAPER                   -> handleBoxSelect(event, pt)
            Tool.FREE_SELECT                   -> handleFreeSelect(event, pt)
            Tool.MAGIC_WAND                    -> handleMagicWand(event, pt)
            Tool.BUBBLE_CLEAN                  -> handleBubbleClean(event, pt)
            Tool.BUBBLE_TRANSLATE              -> handleBoxSelect(event, pt)
            Tool.BRUSH                         -> handleBrush(event, pt)
            Tool.CONTENT_AWARE_BRUSH           -> handleContentAwareBrush(event, pt)
            Tool.BRUSH_INPAINT                 -> handleBrushInpaint(event, pt)
            Tool.REMOVR                        -> handleRemovR(event, pt)
            Tool.CLONE_STAMP                   -> handleCloneStamp(event, pt)
            Tool.BLEMISH_REMOVAL                 -> handleBlemishRemoval(event, pt)
            Tool.TEXT                          -> handleTextInteraction(event, pt)
            Tool.MESH_FORM                     -> handleTextInteraction(event, pt)
            Tool.TEXT_ERASE                    -> handleTextErase(event, pt)
            Tool.ADD_IMAGE                     -> handleImageInteraction(event, pt)
            Tool.REFERENCE_WINDOW              -> handleZoomPan(event)
            Tool.WATERMARK                     -> handleMoveElement(event, pt)
            Tool.CROP                          -> handleBoxSelect(event, pt)
            Tool.ZOOM                          -> handleZoomPan(event)
        }
        return true
    }

    // ── Eyedropper ────────────────────────────────────────────────────────────

    private fun handleEyedropper(event: MotionEvent, pt: PointF) {
        if (layers.isEmpty()) return
        val sample = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val sampleCanvas = Canvas(sample)
        sampleCanvas.translate(-pt.x, -pt.y)
        drawCanvasStack(sampleCanvas)
        val picked = sample.getPixel(0, 0)
        sample.recycle()
        // Preview disimpan dalam koordinat layar karena digambar setelah transform
        // kanvas dipulihkan. Dengan begitu magnifier selalu tepat di atas jari.
        eyedropperPreviewPoint = PointF(event.x, event.y)
        eyedropperPreviewColor = picked
        onEyedropperPreview?.invoke(picked)
        invalidate()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                onStatusUpdate?.invoke("Eyedrop: #${String.format("%06X", picked and 0xFFFFFF)}", pt.x, pt.y)
            }
            MotionEvent.ACTION_UP -> {
                eyedropperMode = false
                eyedropperPreviewPoint = null
                onEyedropperPick?.invoke(picked)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                eyedropperMode = false
                eyedropperPreviewPoint = null
                onEyedropperCancel?.invoke()
                invalidate()
            }
        }
    }

    private fun drawEyedropperPreview(canvas: Canvas) {
        val touch = eyedropperPreviewPoint ?: return
        val density = resources.displayMetrics.density
        val radius = 34f * density
        val margin = 8f * density
        val centerX = touch.x.coerceIn(radius + margin, width - radius - margin)
        val preferredY = touch.y - 82f * density
        val centerY = if (preferredY >= radius + margin) preferredY else touch.y + 82f * density

        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(105, 0, 0, 0) }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = eyedropperPreviewColor }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3f * density
        }
        val innerBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (Color.luminance(eyedropperPreviewColor) > 0.5) Color.BLACK else Color.WHITE
            style = Paint.Style.STROKE; strokeWidth = density
        }
        val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = density
        }

        canvas.drawLine(touch.x, touch.y, centerX, centerY, guide)
        canvas.drawCircle(touch.x, touch.y, 5f * density, border)
        canvas.drawCircle(centerX + 3f * density, centerY + 4f * density, radius + 4f * density, shadow)
        canvas.drawCircle(centerX, centerY, radius, fill)
        canvas.drawCircle(centerX, centerY, radius, border)
        canvas.drawCircle(centerX, centerY, radius - 5f * density, innerBorder)

        val hex = "#${String.format("%06X", eyedropperPreviewColor and 0xFFFFFF)}"
        val rgb = "R${Color.red(eyedropperPreviewColor)} G${Color.green(eyedropperPreviewColor)} B${Color.blue(eyedropperPreviewColor)}"
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 13f * density; typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#C7D2FE"); textSize = 9f * density; textAlign = Paint.Align.CENTER
        }
        val panelTop = centerY + radius + 7f * density
        val panelWidth = 136f * density
        val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 20, 24, 38) }
        canvas.drawRoundRect(
            centerX - panelWidth / 2f, panelTop,
            centerX + panelWidth / 2f, panelTop + 42f * density,
            10f * density, 10f * density, panel
        )
        canvas.drawText(hex, centerX, panelTop + 17f * density, labelPaint)
        canvas.drawText(rgb, centerX, panelTop + 33f * density, detailPaint)
    }

    // ── Move tool — also supports image corner resize (FIX 8) ────────────────
    private fun handleMoveElement(event: MotionEvent, pt: PointF) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingTextId = null; draggingImageId = null; resizingImageId = null
                lastTouchX = event.x; lastTouchY = event.y

                for (el in textElements.reversed()) {
                    if (!el.isVisible || el.isLocked) continue
                    val cx = el.x + el.width / 2f; val cy = el.y + el.height / 2f
                    val unrot = unrotatePoint(pt, cx, cy, el.rotation)
                    if (hitsUnrotatedBounds(unrot, el)) {
                        pushTextHistory(); draggingTextId = el.id; activeTextId = el.id
                        dragOffsetX = unrot.x - el.x; dragOffsetY = unrot.y - el.y; break
                    }
                }
                if (draggingTextId == null) {
                    for (img in imageElements.reversed()) {
                        if (!img.isVisible || img.isLocked) continue
                        val cx = img.x + img.width / 2f; val cy = img.y + img.height / 2f
                        val unrot = unrotatePoint(pt, cx, cy, img.rotation)
                        val hs = (28f / scaleFactor).coerceAtLeast(28f)
                        // Check corner resize handles first (FIX 8)
                        val cornerHit = when {
                            dist(unrot.x, unrot.y, img.x,             img.y             ) < hs * 2f -> "tl"
                            dist(unrot.x, unrot.y, img.x + img.width, img.y             ) < hs * 2f -> "tr"
                            dist(unrot.x, unrot.y, img.x,             img.y + img.height) < hs * 2f -> "bl"
                            dist(unrot.x, unrot.y, img.x + img.width, img.y + img.height) < hs * 2f -> "br"
                            else -> null
                        }
                        if (cornerHit != null) {
                            onWorkspaceHistoryPush?.invoke(); resizingImageId = "$cornerHit:${img.id}"; activeImageId = img.id; break
                        }
                        if (hitsUnrotatedBoundsImg(unrot, img)) {
                            onWorkspaceHistoryPush?.invoke(); draggingImageId = img.id; activeImageId = img.id
                            imgDragOffX = unrot.x - img.x; imgDragOffY = unrot.y - img.y; break
                        }
                    }
                }
                val selectedText = textElements.find { it.id == activeTextId }
                    .takeIf { draggingTextId != null }
                val selectedImage = imageElements.find { it.id == activeImageId }
                    .takeIf { draggingImageId != null || resizingImageId != null }
                when {
                    selectedText != null -> {
                        activeImageId = null
                        onTextSelected?.invoke(selectedText)
                        onImageSelected?.invoke(null)
                    }
                    selectedImage != null -> {
                        activeTextId = null
                        onTextSelected?.invoke(null)
                        onImageSelected?.invoke(selectedImage)
                    }
                    else -> {
                        activeTextId = null
                        activeImageId = null
                        onTextSelected?.invoke(null)
                        onImageSelected?.invoke(null)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                when {
                    draggingTextId != null -> {
                        textElements.find { it.id == draggingTextId }?.let { el ->
                            val cx = el.x + el.width / 2f; val cy = el.y + el.height / 2f
                            val unrot = unrotatePoint(pt, cx, cy, el.rotation)
                            el.x = unrot.x - dragOffsetX; el.y = unrot.y - dragOffsetY
                            val (sx, sy) = applySnapToCenter(el.x, el.y, el.width, el.height)
                            el.x = sx; el.y = sy; clampTextElementToCanvas(el); invalidate()
                        }
                    }
                    resizingImageId != null -> {
                        val tag = resizingImageId ?: return
                        val colonIdx = tag.indexOf(':'); if (colonIdx < 0) return
                        val handle = tag.substring(0, colonIdx); val elId = tag.substring(colonIdx + 1)
                        imageElements.find { it.id == elId }?.let { img ->
                            val cx = img.x + img.width / 2f; val cy = img.y + img.height / 2f
                            val unrot = unrotatePoint(pt, cx, cy, img.rotation)
                            val ar = img.aspectRatio; val minW = 20f
                            when (handle) {
                                "tl" -> { val brx=img.x+img.width; val bry=img.y+img.height; val nW=(brx-unrot.x).coerceAtLeast(minW); img.width=nW; img.height=nW/ar; img.x=brx-nW; img.y=bry-nW/ar }
                                "tr" -> { val bly=img.y+img.height; val nW=(unrot.x-img.x).coerceAtLeast(minW); img.width=nW; img.height=nW/ar; img.y=bly-nW/ar }
                                "bl" -> { val trx=img.x+img.width; val nW=(trx-unrot.x).coerceAtLeast(minW); img.width=nW; img.height=nW/ar; img.x=trx-nW }
                                "br" -> { val nW=(unrot.x-img.x).coerceAtLeast(minW); img.width=nW; img.height=nW/ar }
                            }
                            clampImageElementToCanvas(img)
                            invalidateDrag()
                        }
                    }
                    draggingImageId != null -> {
                        imageElements.find { it.id == draggingImageId }?.let { img ->
                            val cx = img.x + img.width / 2f; val cy = img.y + img.height / 2f
                            val unrot = unrotatePoint(pt, cx, cy, img.rotation)
                            img.x = unrot.x - imgDragOffX; img.y = unrot.y - imgDragOffY
                            val (sx, sy) = applySnapToCenter(img.x, img.y, img.width, img.height)
                            img.x = sx; img.y = sy; clampImageElementToCanvas(img); invalidateDrag()
                        }
                    }
                    else -> {
                        if (event.pointerCount == 1) {
                            translateX += event.x - lastTouchX; translateY += event.y - lastTouchY
                            lastTouchX = event.x; lastTouchY = event.y; invalidate()
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                draggingTextId = null; draggingImageId = null; resizingImageId = null
                snapToX = false; snapToY = false; invalidate()
            }
            else -> Unit
        }
    }

    private fun handleMove(event: MotionEvent, pt: PointF) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastTouchX = event.x; lastTouchY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    translateX += event.x - lastTouchX; translateY += event.y - lastTouchY
                    lastTouchX = event.x; lastTouchY = event.y; invalidate()
                }
            }
            else -> Unit
        }
    }

    // ── Image element interaction ─────────────────────────────────────────────

    private fun handleImageInteraction(event: MotionEvent, pt: PointF) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingImageId = null; resizingImageId = null; rotatingImageId = null
                var handled = false

                for (img in imageElements.reversed()) {
                    if (!img.isVisible || img.isLocked) continue
                    val cx = img.x + img.width / 2f; val cy = img.y + img.height / 2f
                    val unrot = unrotatePoint(pt, cx, cy, img.rotation)
                    val hs = (28f / scaleFactor).coerceAtLeast(28f)
                    val hsMid = (20f / scaleFactor).coerceAtLeast(20f)
                    val rotOff = (56f / scaleFactor).coerceAtLeast(30f)
                    val delOff = hs * 1.1f

                    val delX = img.x + img.width + delOff; val delY = img.y - delOff
                    if (dist(unrot.x, unrot.y, delX, delY) < hs * 2f) {
                        onWorkspaceHistoryPush?.invoke()
                        imageElements.remove(img)
                        activeImageId = imageElements.lastOrNull()?.id
                        onImageSelected?.invoke(imageElements.find { it.id == activeImageId })
                        invalidate(); handled = true; break
                    }

                    val rotHy = img.y - rotOff
                    if (dist(unrot.x, unrot.y, cx, rotHy) < hs * 2f) {
                        onWorkspaceHistoryPush?.invoke(); rotatingImageId = img.id; activeImageId = img.id
                        rotStartAngle = img.rotation
                        rotStartFingerAngle = atan2(pt.y - cy, pt.x - cx) * 180f / PI.toFloat()
                        handled = true; break
                    }

                    val cornerHit = when {
                        dist(unrot.x, unrot.y, img.x,              img.y             ) < hs * 2f -> "tl"
                        dist(unrot.x, unrot.y, img.x + img.width,  img.y             ) < hs * 2f -> "tr"
                        dist(unrot.x, unrot.y, img.x,              img.y + img.height) < hs * 2f -> "bl"
                        dist(unrot.x, unrot.y, img.x + img.width,  img.y + img.height) < hs * 2f -> "br"
                        else -> null
                    }
                    if (cornerHit != null) { onWorkspaceHistoryPush?.invoke(); resizingImageId = "$cornerHit:${img.id}"; activeImageId = img.id; handled = true; break }

                    val midHit = when {
                        dist(unrot.x, unrot.y, img.x,             cy              ) < hsMid * 2f -> "ml"
                        dist(unrot.x, unrot.y, img.x + img.width, cy              ) < hsMid * 2f -> "mr"
                        dist(unrot.x, unrot.y, cx,                img.y           ) < hsMid * 2f -> "mt"
                        dist(unrot.x, unrot.y, cx,                img.y+img.height) < hsMid * 2f -> "mb"
                        else -> null
                    }
                    if (midHit != null) { onWorkspaceHistoryPush?.invoke(); resizingImageId = "$midHit:${img.id}"; activeImageId = img.id; handled = true; break }

                    if (hitsUnrotatedBoundsImg(unrot, img)) {
                        onWorkspaceHistoryPush?.invoke(); draggingImageId = img.id; activeImageId = img.id
                        imgDragOffX = unrot.x - img.x; imgDragOffY = unrot.y - img.y; handled = true; break
                    }
                }
                if (!handled) activeImageId = null
                onImageSelected?.invoke(imageElements.find { it.id == activeImageId })
                if (activeImageId != null) {
                    activeTextId = null
                    onTextSelected?.invoke(null)
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                draggingImageId?.let { id ->
                    imageElements.find { it.id == id }?.let { img ->
                        val cx = img.x + img.width / 2f; val cy = img.y + img.height / 2f
                        val unrot = unrotatePoint(pt, cx, cy, img.rotation)
                        img.x = unrot.x - imgDragOffX; img.y = unrot.y - imgDragOffY
                        val (sx, sy) = applySnapToCenter(img.x, img.y, img.width, img.height)
                        img.x = sx; img.y = sy; clampImageElementToCanvas(img); invalidateDrag()
                    }
                }

                resizingImageId?.let { tag ->
                    val colonIdx = tag.indexOf(':'); if (colonIdx < 0) return@let
                    val handle = tag.substring(0, colonIdx); val elId = tag.substring(colonIdx + 1)
                    imageElements.find { it.id == elId }?.let { img ->
                        val cx = img.x + img.width / 2f; val cy = img.y + img.height / 2f
                        val unrot = unrotatePoint(pt, cx, cy, img.rotation)
                        val ar = img.aspectRatio; val minW = 20f
                        when (handle) {
                            "tl" -> { val brx=img.x+img.width; val bry=img.y+img.height; val nW=(brx-unrot.x).coerceAtLeast(minW); img.width=nW; img.height=nW/ar; img.x=brx-nW; img.y=bry-nW/ar }
                            "tr" -> { val bly=img.y+img.height; val nW=(unrot.x-img.x).coerceAtLeast(minW); img.width=nW; img.height=nW/ar; img.y=bly-nW/ar }
                            "bl" -> { val trx=img.x+img.width; val nW=(trx-unrot.x).coerceAtLeast(minW); img.width=nW; img.height=nW/ar; img.x=trx-nW }
                            "br" -> { val nW=(unrot.x-img.x).coerceAtLeast(minW); img.width=nW; img.height=nW/ar }
                            "ml" -> { val right=img.x+img.width; val nW=(right-unrot.x).coerceAtLeast(minW); img.x=right-nW; img.width=nW; img.height=nW/ar }
                            "mr" -> { val nW=(unrot.x-img.x).coerceAtLeast(minW); img.width=nW; img.height=nW/ar }
                            "mt" -> { val bot=img.y+img.height; val nH=(bot-unrot.y).coerceAtLeast(minW/ar); img.y=bot-nH; img.height=nH; img.width=nH*ar }
                            "mb" -> { val nH=(unrot.y-img.y).coerceAtLeast(minW/ar); img.height=nH; img.width=nH*ar }
                        }
                        clampImageElementToCanvas(img)
                        invalidateDrag()
                    }
                }

                rotatingImageId?.let { id ->
                    imageElements.find { it.id == id }?.let { img ->
                        val cx = img.x + img.width / 2f; val cy = img.y + img.height / 2f
                        val fingerAngle = atan2(pt.y - cy, pt.x - cx) * 180f / PI.toFloat()
                        val raw = rotStartAngle + (fingerAngle - rotStartFingerAngle)
                        img.rotation = smoothAngle(img.rotation, raw)
                        invalidateDrag()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                draggingImageId = null; resizingImageId = null; rotatingImageId = null
                snapToX = false; snapToY = false; invalidate()
            }
        }
    }

    // ── Text interaction ──────────────────────────────────────────────────────

    private fun handleTextInteraction(event: MotionEvent, pt: PointF) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                draggingTextId = null; resizingTextId = null; rotatingTextId = null
                perspDraggingId = null; perspDraggingCorner = -1
                polyDraggingId  = null; polyDraggingIdx  = -1
                pendingTextTapPoint = null
                pendingTextEditElement = null
                textViewportPanActive = false
                lastTouchX = event.x
                lastTouchY = event.y
                var handled = false

                for (el in textElements.reversed()) {
                    if (!el.isVisible || el.isLocked) continue
                    val hs    = (28f / scaleFactor).coerceAtLeast(28f)
                    val rotOff = (56f / scaleFactor).coerceAtLeast(30f)

                    // ── Mesh-mode element ────────────────────────────────
                    val poly = el.meshPoints
                    if (poly != null && poly.size >= 8) {
                        val cols = el.meshCols.coerceIn(1, 8)
                        val rows = el.meshRows.coerceIn(1, 8)
                        val gridW = cols + 1
                        val gridH = rows + 1
                        val expected = gridW * gridH
                        if (poly.size / 2 < expected) continue

                        fun idx(c: Int, r: Int): Int = (r * gridW + c) * 2
                        fun ax(c: Int, r: Int): Float = el.x + poly[idx(c, r)]
                        fun ay(c: Int, r: Int): Float = el.y + poly[idx(c, r) + 1]

                        val topMidX = (ax(cols / 2, 0) + ax((cols + 1) / 2, 0)) / 2f
                        val topMidY = (ay(cols / 2, 0) + ay((cols + 1) / 2, 0)) / 2f
                        val rotHx = topMidX
                        val rotHy = topMidY - rotOff
                        if (dist(pt.x, pt.y, rotHx, rotHy) < hs * 2f) {
                            pushTextHistory()
                            rotatingTextId = el.id; activeTextId = el.id
                            rotStartFingerAngle = atan2(pt.y - topMidY, pt.x - topMidX) * 180f / PI.toFloat()
                            rotStartAngle = 0f; handled = true; break
                        }

                        // Delete badge: placed below the mesh, centered, with extra spacing
                        val bottomLeft = rotatePoint(PointF(el.x + poly[idx(0, rows)], el.y + poly[idx(0, rows) + 1]), el.x + el.width / 2f, el.y + el.height / 2f, el.rotation)
                        val bottomRight = rotatePoint(PointF(el.x + poly[idx(cols, rows)], el.y + poly[idx(cols, rows) + 1]), el.x + el.width / 2f, el.y + el.height / 2f, el.rotation)
                        val bottomMidX = (bottomLeft.x + bottomRight.x) / 2f
                        val bottomMidY = (bottomLeft.y + bottomRight.y) / 2f
                        val bottomEdgeDx = bottomRight.x - bottomLeft.x
                        val bottomEdgeDy = bottomRight.y - bottomLeft.y
                        val bottomEdgeLen = sqrt(bottomEdgeDx * bottomEdgeDx + bottomEdgeDy * bottomEdgeDy).coerceAtLeast(0.001f)
                        val downX = bottomEdgeDy / bottomEdgeLen
                        val downY = -bottomEdgeDx / bottomEdgeLen
                        val upX = -downX
                        val upY = -downY
                        val badgeGap = (hs * 2.2f) + (10f / scaleFactor)
                        val candDownX = bottomMidX + downX * badgeGap
                        val candDownY = bottomMidY + downY * badgeGap
                        val candUpX = bottomMidX + upX * badgeGap
                        val candUpY = bottomMidY + upY * badgeGap
                        val delX = if (candDownY >= candUpY) candDownX else candUpX
                        val delY = if (candDownY >= candUpY) candDownY else candUpY
                        if (dist(pt.x, pt.y, delX, delY) < hs * 2f) {
                            pushTextHistory()
                            textElements.remove(el); invalidate(); handled = true; break
                        }

                        for (r in 0..rows) for (c in 0..cols) {
                            if (dist(pt.x, pt.y, ax(c, r), ay(c, r)) < hs * 2f) {
                                pushTextHistory()
                                polyDraggingId = el.id; polyDraggingIdx = r * gridW + c
                                activeTextId = el.id
                                polyDragLastX = pt.x; polyDragLastY = pt.y
                                handled = true; break
                            }
                        }
                        if (handled) break

                        val bounds = RectF(el.x, el.y, el.x + el.width, el.y + el.height)
                        if (bounds.contains(pt.x, pt.y)) {
                            pushTextHistory()
                            polyDraggingId = el.id; polyDraggingIdx = -1
                            activeTextId = el.id
                            polyDragLastX = pt.x; polyDragLastY = pt.y
                            handled = true; break
                        }
                        continue
                    }

                    // ── Perspective-mode element ───────────────────────────────
                    val corners = el.perspCorners
                    if (corners != null && corners.size == 8) {
                        val cx = (corners[0] + corners[2] + corners[4] + corners[6]) / 4f
                        val cy = (corners[1] + corners[3] + corners[5] + corners[7]) / 4f

                        // Rotation handle (above midpoint of TL-TR edge)
                        val edgeDx = corners[2] - corners[0]; val edgeDy = corners[3] - corners[1]
                        val edgeLen = sqrt(edgeDx * edgeDx + edgeDy * edgeDy).coerceAtLeast(0.001f)
                        val perpX = -edgeDy / edgeLen; val perpY = edgeDx / edgeLen
                        val midTopX = (corners[0] + corners[2]) / 2f
                        val midTopY = (corners[1] + corners[3]) / 2f
                        val rotHx = midTopX + perpX * rotOff; val rotHy = midTopY + perpY * rotOff

                        if (dist(pt.x, pt.y, rotHx, rotHy) < hs * 2f) {
                            pushTextHistory()
                            rotatingTextId = el.id; activeTextId = el.id
                            rotStartFingerAngle = atan2(pt.y - cy, pt.x - cx) * 180f / PI.toFloat()
                            rotStartAngle = 0f; handled = true; break
                        }

                        // Delete badge
                        val delOff = hs * 1.1f
                        val delX = corners[2] + perpX * delOff + edgeDx / edgeLen * delOff
                        val delY = corners[3] + perpY * delOff + edgeDy / edgeLen * delOff
                        if (dist(pt.x, pt.y, delX, delY) < hs * 2f) {
                            pushTextHistory(); textElements.remove(el); invalidate(); handled = true; break
                        }

                        // Individual perspective corners (0-3)
                        for (c in 0 until 4) {
                            if (dist(pt.x, pt.y, corners[c * 2], corners[c * 2 + 1]) < hs * 2f) {
                                pushTextHistory()
                                perspDraggingId = el.id; perspDraggingCorner = c
                                activeTextId = el.id
                                perspDragLastX = pt.x; perspDragLastY = pt.y
                                handled = true; break
                            }
                        }
                        if (handled) break

                        // Inside quad → move all corners
                        if (pointInQuad(pt.x, pt.y, corners)) {
                            pushTextHistory()
                            perspDraggingId = el.id; perspDraggingCorner = -1
                            activeTextId = el.id
                            perspDragLastX = pt.x; perspDragLastY = pt.y
                            handled = true; break
                        }
                        continue
                    }

                    // ── Normal (non-perspective) element ──────────────────────
                    val cx    = el.x + el.width  / 2f; val cy = el.y + el.height / 2f
                    val unrot = unrotatePoint(pt, cx, cy, el.rotation)
                    val hsMid = (20f / scaleFactor).coerceAtLeast(20f)
                    val delOff = hs * 1.1f

                    val delX = el.x + el.width + delOff; val delY = el.y - delOff
                    if (dist(unrot.x, unrot.y, delX, delY) < hs * 2.5f) {
                        pushTextHistory(); textElements.remove(el); invalidate(); handled = true; break
                    }

                    val editX = el.x - delOff; val editY = el.y - delOff
                    if (dist(unrot.x, unrot.y, editX, editY) < hs * 2.5f) {
                        pendingTextEditElement = el
                        activeTextId = el.id
                        handled = true
                        break
                    }

                    val rotHy = el.y - rotOff
                    if (dist(unrot.x, unrot.y, cx, rotHy) < hs * 2.5f) {
                        pushTextHistory(); rotatingTextId = el.id; activeTextId = el.id
                        rotStartAngle = el.rotation
                        rotStartFingerAngle = atan2(pt.y - cy, pt.x - cx) * 180f / PI.toFloat()
                        handled = true; break
                    }

                    val cornerHit = when {
                        dist(unrot.x, unrot.y, el.x,            el.y            ) < hs * 2.5f -> "tl"
                        dist(unrot.x, unrot.y, el.x + el.width, el.y            ) < hs * 2.5f -> "tr"
                        dist(unrot.x, unrot.y, el.x,            el.y + el.height) < hs * 2.5f -> "bl"
                        dist(unrot.x, unrot.y, el.x + el.width, el.y + el.height) < hs * 2.5f -> "br"
                        else -> null
                    }
                    if (cornerHit != null) {
                        pushTextHistory(); resizingTextId = "$cornerHit:${el.id}"; activeTextId = el.id; handled = true; break
                    }

                    val midHit = when {
                        dist(unrot.x, unrot.y, el.x,            cy              ) < hsMid * 2.5f -> "ml"
                        dist(unrot.x, unrot.y, el.x + el.width, cy              ) < hsMid * 2.5f -> "mr"
                        dist(unrot.x, unrot.y, cx,              el.y            ) < hsMid * 2.5f -> "mt"
                        dist(unrot.x, unrot.y, cx,              el.y + el.height) < hsMid * 2.5f -> "mb"
                        else -> null
                    }
                    if (midHit != null) {
                        pushTextHistory(); resizingTextId = "$midHit:${el.id}"; activeTextId = el.id; handled = true; break
                    }

                    if (hitsUnrotatedBounds(unrot, el)) {
                        pushTextHistory(); draggingTextId = el.id; activeTextId = el.id
                        dragOffsetX = unrot.x - el.x; dragOffsetY = unrot.y - el.y; handled = true; break
                    }
                }

                if (!handled) {
                    activeTextId = null
                    onTextSelected?.invoke(null)
                    pendingTextTapPoint = PointF(pt.x, pt.y)
                } else {
                    // notify host that a text element was just selected so a
                    // floating quick-edit toolbar can be shown above it
                    val sel = textElements.find { it.id == activeTextId }
                    onTextSelected?.invoke(sel)
                }
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                // A drag on empty canvas while Text is selected means viewport pan,
                // not "create text". The editor is reserved for a stationary tap-up.
                if ((pendingTextTapPoint != null || pendingTextEditElement != null) && gestureMoved) {
                    pendingTextTapPoint = null
                    pendingTextEditElement = null
                    textViewportPanActive = true
                }
                if (textViewportPanActive) {
                    translateX += event.x - lastTouchX
                    translateY += event.y - lastTouchY
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                    return
                }

                // ── Mesh point drag ─────────────────────────────────────
                polyDraggingId?.let { id ->
                    textElements.find { it.id == id }?.let { el ->
                        val pts = el.meshPoints ?: return@let
                        val centerX = el.x + el.width / 2f
                        val centerY = el.y + el.height / 2f
                        val hitPt = unrotatePoint(pt, centerX, centerY, el.rotation)
                        val lastPt = unrotatePoint(PointF(polyDragLastX, polyDragLastY), centerX, centerY, el.rotation)
                        val dx = hitPt.x - lastPt.x
                        val dy = hitPt.y - lastPt.y
                        if (polyDraggingIdx >= 0) {
                            val cols = el.meshCols.coerceIn(1, 8)
                            val idx = polyDraggingIdx.coerceAtMost(pts.size / 2 - 1)
                            val base = idx * 2
                            if (base + 1 < pts.size) {
                                pts[base]     = pts[base] + dx
                                pts[base + 1] = pts[base + 1] + dy
                            }
                        } else {
                            el.x += pt.x - polyDragLastX
                            el.y += pt.y - polyDragLastY
                            clampTextElementToCanvas(el)
                        }
                        polyDragLastX = pt.x; polyDragLastY = pt.y; invalidate()
                    }
                }

                // ── Perspective corner drag ───────────────────────────────────
                perspDraggingId?.let { id ->
                    textElements.find { it.id == id }?.let { el ->
                        val corners = el.perspCorners ?: return@let
                        val dx = pt.x - perspDragLastX; val dy = pt.y - perspDragLastY
                        if (perspDraggingCorner >= 0) {
                            // Move individual corner
                            corners[perspDraggingCorner * 2]     += dx
                            corners[perspDraggingCorner * 2 + 1] += dy
                        } else {
                            // Move all corners (translate)
                            for (i in corners.indices) {
                                if (i % 2 == 0) corners[i] += dx else corners[i] += dy
                            }
                        }
                        perspDragLastX = pt.x; perspDragLastY = pt.y; invalidate()
                    }
                }

                // ── Normal drag ───────────────────────────────────────────────
                draggingTextId?.let { id ->
                    textElements.find { it.id == id }?.let { el ->
                        val cx = el.x + el.width / 2f; val cy = el.y + el.height / 2f
                        val unrot = unrotatePoint(pt, cx, cy, el.rotation)
                        el.x = unrot.x - dragOffsetX; el.y = unrot.y - dragOffsetY
                        val (sx, sy) = applySnapToCenter(el.x, el.y, el.width, el.height)
                        el.x = sx; el.y = sy; clampTextElementToCanvas(el); invalidate()
                    }
                }

                // ── Corner / mid-edge resize ──────────────────────────────────
                resizingTextId?.let { tag ->
                    val colonIdx = tag.indexOf(':'); if (colonIdx < 0) return@let
                    val handle = tag.substring(0, colonIdx); val elId = tag.substring(colonIdx + 1)
                    textElements.find { it.id == elId }?.let { el ->
                        val cx = el.x + el.width / 2f; val cy = el.y + el.height / 2f
                        val unrot = unrotatePoint(pt, cx, cy, el.rotation)
                        when (handle) {
                            "tl" -> { val oldH=el.height; val brx=el.x+el.width; val bry=el.y+el.height; el.x=snapToGrid(unrot.x.coerceAtMost(brx-40f)); el.y=snapToGrid(unrot.y.coerceAtMost(bry-20f)); el.width=snapToGrid((brx-el.x).coerceAtLeast(40f)); el.height=snapToGrid((bry-el.y).coerceAtLeast(20f)); if(oldH>0f)el.fontSize=(el.fontSize*(el.height/oldH)).coerceIn(4f,512f) }
                            "tr" -> { val oldH=el.height; val bly=el.y+el.height; el.y=snapToGrid(unrot.y.coerceAtMost(bly-20f)); el.width=snapToGrid((unrot.x-el.x).coerceAtLeast(40f)); el.height=snapToGrid((bly-el.y).coerceAtLeast(20f)); if(oldH>0f)el.fontSize=(el.fontSize*(el.height/oldH)).coerceIn(4f,512f) }
                            "bl" -> { val oldH=el.height; val trx=el.x+el.width; el.x=snapToGrid(unrot.x.coerceAtMost(trx-40f)); el.width=snapToGrid((trx-el.x).coerceAtLeast(40f)); el.height=snapToGrid((unrot.y-el.y).coerceAtLeast(20f)); if(oldH>0f)el.fontSize=(el.fontSize*(el.height/oldH)).coerceIn(4f,512f) }
                            "br" -> { val oldH=el.height; el.width=snapToGrid((unrot.x-el.x).coerceAtLeast(40f)); el.height=snapToGrid((unrot.y-el.y).coerceAtLeast(20f)); if(oldH>0f)el.fontSize=(el.fontSize*(el.height/oldH)).coerceIn(4f,512f) }
                            "ml" -> { val right=el.x+el.width; el.x=snapToGrid(unrot.x.coerceAtMost(right-40f)); el.width=snapToGrid((right-el.x).coerceAtLeast(40f)) }
                            "mr" -> { el.width=snapToGrid((unrot.x-el.x).coerceAtLeast(40f)) }
                            "mt" -> { val bottom=el.y+el.height; el.y=snapToGrid(unrot.y.coerceAtMost(bottom-20f)); el.height=snapToGrid((bottom-el.y).coerceAtLeast(20f)); el.fontSize=TextRenderer.autoFitFontSize(el.text,el.width,el.height,el.typeface) }
                            "mb" -> { el.height=snapToGrid((unrot.y-el.y).coerceAtLeast(20f)); el.fontSize=TextRenderer.autoFitFontSize(el.text,el.width,el.height,el.typeface) }
                        }
                        invalidate()
                    }
                }

                // ── Rotate ────────────────────────────────────────────────────
                rotatingTextId?.let { id ->
                    textElements.find { it.id == id }?.let { el ->
                        val corners = el.perspCorners
                        if (corners != null && corners.size == 8) {
                            // Perspective mode: rotate all 4 corners around centroid
                            val cx = (corners[0]+corners[2]+corners[4]+corners[6])/4f
                            val cy = (corners[1]+corners[3]+corners[5]+corners[7])/4f
                            val fingerAngle = atan2(pt.y - cy, pt.x - cx) * 180f / PI.toFloat()
                            val delta = fingerAngle - rotStartFingerAngle
                            rotStartFingerAngle = fingerAngle
                            val rad = delta * PI.toFloat() / 180f
                            val cosR = cos(rad); val sinR = sin(rad)
                            for (c in 0 until 4) {
                                val dx = corners[c*2] - cx; val dy = corners[c*2+1] - cy
                                corners[c*2]   = cx + dx*cosR - dy*sinR
                                corners[c*2+1] = cy + dx*sinR + dy*cosR
                            }
                        } else {
                            // Normal mode: update rotation angle with snap
                            val cx = el.x + el.width / 2f; val cy = el.y + el.height / 2f
                            val fingerAngle = atan2(pt.y - cy, pt.x - cx) * 180f / PI.toFloat()
                            val raw = rotStartAngle + (fingerAngle - rotStartFingerAngle)
                            el.rotation = smoothAngle(el.rotation, raw)
                        }
                        invalidate()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                val wasTransforming = draggingTextId != null || resizingTextId != null ||
                                      rotatingTextId != null || perspDraggingId != null ||
                                      polyDraggingId != null
                val editTarget = pendingTextEditElement
                val tapPoint = pendingTextTapPoint
                pendingTextEditElement = null
                pendingTextTapPoint = null
                textViewportPanActive = false
                if (!gestureMoved && !isPinching && event.pointerCount == 1) {
                    when {
                        editTarget != null -> onTextEdit?.invoke(editTarget)
                        tapPoint != null -> onTextTap?.invoke(tapPoint.x, tapPoint.y)
                    }
                }
                draggingTextId = null; resizingTextId = null; rotatingTextId = null
                perspDraggingId = null; perspDraggingCorner = -1
                polyDraggingId  = null; polyDraggingIdx  = -1
                snapToX = false; snapToY = false
                if (wasTransforming) onTextHistoryPush?.invoke()
                invalidate()
            }
        }
    }

    // ── Box / Free / Magic Wand ───────────────────────────────────────────────

    private fun handleBoxSelect(event: MotionEvent, pt: PointF) {
        val cx = pt.x.coerceIn(0f, canvasWidth.toFloat())
        val cy = pt.y.coerceIn(0f, canvasHeight.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selStartX = cx
                selStartY = cy
                selectionGestureMoved = false
                selectionDraftType = SelectionType.RECT
                selectionDraftRect.set(cx, cy, cx, cy)
                addModeRegion = if (selectionCombineMode == SelectionCombineMode.ADD && selection.isActive) snapshotSelectionAsRegion() else null
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!selectionGestureMoved && dist(selStartX, selStartY, cx, cy) < selectionDragThreshold) return
                if (!selectionGestureMoved) {
                    selectionGestureMoved = true
                    if (selectionCombineMode == SelectionCombineMode.REPLACE) selection.clear()
                }
                selectionDraftRect.set(min(selStartX, cx), min(selStartY, cy), max(selStartX, cx), max(selStartY, cy))
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!selectionGestureMoved || selectionDraftRect.width() < 1f || selectionDraftRect.height() < 1f) {
                    addModeRegion = null
                    clearSelectionDraft()
                    invalidate()
                    return
                }
                val committed = RectF(selectionDraftRect)
                val exact = Region(
                    committed.left.toInt(),
                    committed.top.toInt(),
                    committed.right.toInt(),
                    committed.bottom.toInt()
                )
                commitSelectionRegion(SelectionType.RECT, committed, exact)
                selectionGestureMoved = false
                addModeRegion = null
                clearSelectionDraft()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                selectionGestureMoved = false
                addModeRegion = null
                clearSelectionDraft()
                invalidate()
            }
        }
    }

    private fun handleFreeSelect(event: MotionEvent, pt: PointF) {
        val cx = pt.x.coerceIn(0f, canvasWidth.toFloat())
        val cy = pt.y.coerceIn(0f, canvasHeight.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                selStartX = cx
                selStartY = cy
                selectionGestureMoved = false
                addModeRegion = if (selectionCombineMode == SelectionCombineMode.ADD && selection.isActive) snapshotSelectionAsRegion() else null
                freeSelectPath.reset()
                freeSelectPath.moveTo(cx, cy)
                selectionDraftPath.reset()
                selectionDraftPath.moveTo(cx, cy)
                selectionDraftType = SelectionType.FREE
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!selectionGestureMoved && dist(selStartX, selStartY, cx, cy) < selectionDragThreshold) return
                if (!selectionGestureMoved) {
                    selectionGestureMoved = true
                    if (selectionCombineMode == SelectionCombineMode.REPLACE) selection.clear()
                }
                freeSelectPath.lineTo(cx, cy)
                selectionDraftPath.set(freeSelectPath)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                if (!selectionGestureMoved) {
                    addModeRegion = null
                    freeSelectPath.reset()
                    clearSelectionDraft()
                    invalidate()
                    return
                }
                freeSelectPath.lineTo(cx, cy)
                freeSelectPath.close()
                selection.path.set(freeSelectPath)
                val bounds = RectF().also { freeSelectPath.computeBounds(it, true) }
                if (bounds.width() < 1f || bounds.height() < 1f) {
                    addModeRegion = null
                    selection.clear()
                    clearSelectionDraft()
                    invalidate()
                    return
                }
                val clip = Region(0, 0, canvasWidth, canvasHeight)
                val exact = Region().also { it.setPath(freeSelectPath, clip) }
                if (exact.isEmpty) {
                    selection.clear()
                } else {
                    commitSelectionRegion(SelectionType.FREE, bounds, exact)
                }
                selectionGestureMoved = false
                addModeRegion = null
                clearSelectionDraft()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                selectionGestureMoved = false
                addModeRegion = null
                freeSelectPath.reset()
                clearSelectionDraft()
                invalidate()
            }
        }
    }

    private fun handleMagicWand(event: MotionEvent, pt: PointF) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val layer = layers.getOrNull(activeLayerIndex) ?: return
            if (layer.bitmap.isRecycled || layer.bitmap.width <= 0 || layer.bitmap.height <= 0) return
            val bx = pt.x.toInt().coerceIn(0, layer.bitmap.width - 1)
            val by = pt.y.toInt().coerceIn(0, layer.bitmap.height - 1)
            try {
                val newRg = MagicWandSelector.select(layer.bitmap, bx, by, tolerance)
                val b = newRg.bounds
                if (b.isEmpty) return
                val bounds = RectF(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                when (selectionCombineMode) {
                    SelectionCombineMode.REPLACE -> {
                        selection.clear()
                        selection.type = SelectionType.MAGIC_WAND
                        selection.region = Region(newRg)
                        selection.rect.set(bounds)
                        selection.addPart(bounds, newRg)
                        selection.isActive = true
                        selection.isManualUnion = false
                    }
                    SelectionCombineMode.ADD -> {
                        if (!selection.isActive && selection.region == null && selection.parts.isEmpty()) {
                            selection.clear()
                            selection.type = SelectionType.MAGIC_WAND
                            selection.region = Region(newRg)
                            selection.rect.set(bounds)
                            selection.addPart(bounds, newRg)
                            selection.isActive = true
                        } else {
                            val existing = snapshotSelectionAsRegion()
                            existing.op(newRg, Region.Op.UNION)
                            selection.region = existing
                            selection.rect.set(selection.getBounds())
                            selection.addPart(bounds, newRg)
                            selection.isActive = true
                            selection.isManualUnion = true
                        }
                    }
                    SelectionCombineMode.SUBTRACT -> {
                        if (selection.subtractRegion(newRg)) {
                            selection.rect.set(selection.getBounds())
                        }
                    }
                }
                onSelectionChanged?.invoke(selection)
                invalidate()
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    // ── Auto Clean Bubble ─────────────────────────────────────────────────────
    //
    // v5.0: passes the composite of ALL visible pixel layers to the callback
    // (via onBubbleCleanRequest) so MainActivity can use it for detection.
    // The composite is created here cheaply and passed as an extra arg via a
    // new two-arg callback signature where needed. For backward-compat we keep
    // onBubbleCleanRequest as-is and handle composite in CanvasView itself:
    // If the active layer has no opaque pixels at the tap point (e.g. it is
    // empty / transparent), we forward the composite to BubbleCleaner and
    // apply the fill mask back to the active (mutable) layer.

    private fun handleBubbleClean(event: MotionEvent, pt: PointF) {
        if (event.actionMasked != MotionEvent.ACTION_DOWN) return
        val layer = layers.getOrNull(activeLayerIndex) ?: return
        val bx = pt.x.toInt().coerceIn(0, layer.bitmap.width  - 1)
        val by = pt.y.toInt().coerceIn(0, layer.bitmap.height - 1)
        if (batchCleanActive) {
            // Batch mode: queue the tap point, show visual marker, do NOT clean yet
            batchCleanPoints.add(bx to by)
            invalidate()
        } else {
            onBubbleCleanRequest?.invoke(bx, by)
        }
    }

    // ── Batch marker overlay ──────────────────────────────────────────────────
    /** Draw numbered circles at each queued tap point (canvas coordinate space). */
    private fun drawBatchMarkers(canvas: Canvas) {
        val r   = 18f / scaleFactor
        val ts  = 12f / scaleFactor
        batchMarkerStroke.strokeWidth = 2f / scaleFactor
        batchMarkerText.textSize      = ts
        batchCleanPoints.forEachIndexed { idx, (bx, by) ->
            val x = bx.toFloat(); val y = by.toFloat()
            canvas.drawCircle(x, y, r, batchMarkerFill)
            canvas.drawCircle(x, y, r, batchMarkerStroke)
            canvas.drawText("${idx + 1}", x, y + ts * 0.35f, batchMarkerText)
        }
    }

    // ── Public batch API ──────────────────────────────────────────────────────

    /** Remove all queued tap points without executing. */
    fun clearBatchQueue() { batchCleanPoints.clear(); invalidate() }

    /**
     * Execute the current batch queue: invokes [onBubbleCleanBatchReady] with
     * a snapshot of all queued points, then clears the queue.
     * Does nothing if queue is empty.
     */
    fun executeBatchClean() {
        if (batchCleanPoints.isEmpty()) return
        val pts = batchCleanPoints.toList()
        batchCleanPoints.clear()
        invalidate()
        onBubbleCleanBatchReady?.invoke(pts)
    }

    // Returns a mutable flat composite of all visible pixel layers.
    // Used by MainActivity's BubbleCleaner callback to detect text across layers.
    // White background: agar pixel transparan di PNG tidak dianggap hitam oleh BubbleCleaner.
    fun compositeVisibleLayers(): Bitmap? {
        val w = layers.firstOrNull()?.bitmap?.width  ?: return null
        val h = layers.firstOrNull()?.bitmap?.height ?: return null
        return try {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c   = Canvas(bmp)
            c.drawColor(android.graphics.Color.WHITE)
            for (layer in layers) {
                if (!layer.isVisible || layer.bitmap.isRecycled) continue
                c.drawBitmap(layer.bitmap, 0f, 0f, null)
            }
            bmp
        } catch (_: OutOfMemoryError) {
            // Retry once after reclaiming memory — protects against transient OOM
            // on very large pages (e.g. tall webtoons) on low-end devices.
            System.gc()
            try {
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c   = Canvas(bmp)
                c.drawColor(android.graphics.Color.WHITE)
                for (layer in layers) {
                    if (!layer.isVisible || layer.bitmap.isRecycled) continue
                    c.drawBitmap(layer.bitmap, 0f, 0f, null)
                }
                bmp
            } catch (_: OutOfMemoryError) { null }
        }
    }

    // ── Brush Erase tool — erases pixels on the active layer ─────────────────
    // The BRUSH tool now works as an erase brush (removes pixels via PorterDuff
    // CLEAR for hard mode, DST_OUT for soft mode), controlled by brushEngine.hardness.
    // ── Gemini overlay drawing ────────────────────────────────────────────────
    private val eraseCursorPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        color = android.graphics.Color.argb(200, 255, 80, 80)
        strokeWidth = 2f
    }
    private val eraseCursorFill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        color = android.graphics.Color.argb(30, 255, 80, 80)
    }

    private fun drawToolCursor(canvas: Canvas) {
        val pt = when (currentTool) {
            Tool.CLONE_STAMP -> cloneStampCursorPoint
            Tool.BLEMISH_REMOVAL -> blemishCursorPoint
            Tool.TEXT_ERASE -> textEraseCursorPt
            else -> brushCursorPoint
        } ?: return
        if (currentTool !in listOf(
                Tool.BRUSH,
                Tool.CONTENT_AWARE_BRUSH,
                Tool.BRUSH_INPAINT,
                Tool.REMOVR,
                Tool.CLONE_STAMP,
                Tool.BLEMISH_REMOVAL,
                Tool.TEXT_ERASE
            )
        ) return
        val r = (brushEngine.brushSize / 2f).coerceAtLeast(3f)
        if (currentTool == Tool.CLONE_STAMP) {
            cloneStampEngine.drawPreview(canvas, pt)
        }
        val fill = when (currentTool) {
            Tool.CONTENT_AWARE_BRUSH -> contentAwareCursorFill
            Tool.BRUSH_INPAINT -> brushInpaintCursorFill
            Tool.REMOVR -> removRCursorFill
            Tool.CLONE_STAMP -> cloneCursorFill
            Tool.BLEMISH_REMOVAL -> pointerCursorFill
            else -> eraseCursorFill
        }
        val stroke = when (currentTool) {
            Tool.CONTENT_AWARE_BRUSH -> contentAwareCursorStroke
            Tool.BRUSH_INPAINT -> brushInpaintCursorStroke
            Tool.REMOVR -> removRCursorStroke
            Tool.CLONE_STAMP -> cloneCursorStroke
            Tool.BLEMISH_REMOVAL -> pointerCursorStroke
            else -> eraseCursorPaint
        }
        stroke.strokeWidth = (2f / scaleFactor).coerceAtLeast(0.75f)
        canvas.drawCircle(pt.x, pt.y, r, fill)
        canvas.drawCircle(pt.x, pt.y, r, stroke)
        if (currentTool == Tool.BLEMISH_REMOVAL) {
            val arm = (min(r, 12f / scaleFactor)).coerceAtLeast(3f / scaleFactor)
            canvas.drawLine(pt.x - arm, pt.y, pt.x + arm, pt.y, stroke)
            canvas.drawLine(pt.x, pt.y - arm, pt.x, pt.y + arm, stroke)
            blemishTargetPoint?.let { target ->
                canvas.drawCircle(target.x, target.y, r, blemishTargetStroke)
                canvas.drawLine(target.x, target.y, pt.x, pt.y, blemishGuideStroke)
            }
        }
        if (currentTool == Tool.CLONE_STAMP) {
            cloneStampEngine.sourcePoint?.let { source ->
                val markerRadius = (8f / scaleFactor).coerceAtLeast(2f)
                canvas.drawCircle(source.x, source.y, markerRadius, cloneSourceStroke)
                canvas.drawLine(source.x - markerRadius, source.y, source.x + markerRadius, source.y, cloneSourceStroke)
                canvas.drawLine(source.x, source.y - markerRadius, source.x, source.y + markerRadius, cloneSourceStroke)
                canvas.drawLine(source.x, source.y, pt.x, pt.y, cloneGuideStroke)
            }
        }

        // Badge ukuran mengikuti cursor sehingga diameter brush selalu terbaca saat
        // menggambar maupun ketika swipe vertikal mengubah ukuran.
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = (12f / scaleFactor).coerceAtLeast(5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val label = "${brushEngine.brushSize.roundToInt()} px"
        val pad = 6f / scaleFactor
        val badgeLeft = pt.x + r + 8f / scaleFactor
        val baseline = pt.y - r.coerceAtMost(24f / scaleFactor)
        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (brushSizingMode) Color.parseColor("#E63B82F6") else Color.argb(210, 22, 27, 42)
        }
        val textWidth = textPaint.measureText(label)
        canvas.drawRoundRect(
            badgeLeft, baseline - 15f / scaleFactor,
            badgeLeft + textWidth + pad * 2f, baseline + 5f / scaleFactor,
            6f / scaleFactor, 6f / scaleFactor, badgePaint
        )
        canvas.drawText(label, badgeLeft + pad, baseline, textPaint)
    }

    private val cloneCursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(24, 99, 102, 241)
    }
    private val cloneCursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(245, 165, 180, 252)
    }
    private val cloneSourceStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(245, 250, 204, 21)
        strokeWidth = 2f
    }
    private val cloneGuideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(125, 250, 204, 21)
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    }
    private val pointerCursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(18, 255, 255, 255)
    }
    private val pointerCursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(245, 96, 230, 190)
    }
    private val blemishTargetStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(245, 255, 92, 92)
        strokeWidth = 2f
    }
    private val blemishGuideStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 96, 230, 190)
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    }

    private val contentAwareCursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(55, 0, 220, 180)
    }
    private val contentAwareCursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 0, 255, 200)
        strokeWidth = 2f
    }
    private val contentAwareMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(64, 0, 220, 180)
    }
    private val contentAwareMaskStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 0, 255, 200)
        strokeWidth = 2f
    }

    // Brush Inpaint cursor/mask — amber, echoing the OpenCV inpaint preview.
    private val brushInpaintCursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(55, 255, 170, 40)
    }
    private val brushInpaintCursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 255, 190, 60)
        strokeWidth = 2f
    }
    private val brushInpaintMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(64, 255, 170, 40)
    }
    private val brushInpaintMaskStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 255, 190, 60)
        strokeWidth = 2f
    }

    // RemovR cursor/mask — violet, matching the Resynthesizer "heal" metaphor.
    private val removRCursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(55, 170, 110, 255)
    }
    private val removRCursorStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(230, 190, 140, 255)
        strokeWidth = 2f
    }
    private val removRMaskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(64, 170, 110, 255)
    }
    private val removRMaskStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 190, 140, 255)
        strokeWidth = 2f
    }

    private fun drawContentAwareMask(canvas: Canvas) {
        if (contentAwareMask.isEmpty) return
        val iterator = RegionIterator(contentAwareMask)
        val rect = Rect()
        while (iterator.next(rect)) {
            canvas.drawRect(rect, contentAwareMaskPaint)
            canvas.drawRect(rect, contentAwareMaskStroke)
        }
    }

    private fun drawBrushInpaintMask(canvas: Canvas) {
        if (brushInpaintMask.isEmpty) return
        val iterator = RegionIterator(brushInpaintMask)
        val rect = Rect()
        while (iterator.next(rect)) {
            canvas.drawRect(rect, brushInpaintMaskPaint)
            canvas.drawRect(rect, brushInpaintMaskStroke)
        }
    }

    private fun drawRemovRMask(canvas: Canvas) {
        if (removRMask.isEmpty) return
        val iterator = RegionIterator(removRMask)
        val rect = Rect()
        while (iterator.next(rect)) {
            canvas.drawRect(rect, removRMaskPaint)
            canvas.drawRect(rect, removRMaskStroke)
        }
    }

    private fun drawGeminiOverlay(canvas: Canvas) {
        val sw = (3f / scaleFactor).coerceAtLeast(1f)
        val ts = (24f / scaleFactor).coerceAtLeast(8f)
        val badgeRadius = (14f / scaleFactor).coerceAtLeast(7f)
        val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(245, 190, 34, 34)
            style = Paint.Style.FILL
        }
        val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = (2f / scaleFactor).coerceAtLeast(1f)
        }
        val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = badgeRadius * 1.25f
            typeface = Typeface.DEFAULT_BOLD
        }
        geminiOverlayStroke.strokeWidth = sw
        geminiOverlayText.textSize = ts
        for ((idx, region) in geminiDetectOverlay.withIndex()) {
            val r = region.rect
            canvas.drawRoundRect(r, 3f / scaleFactor, 3f / scaleFactor, geminiOverlayFill)
            canvas.drawRoundRect(r, 3f / scaleFactor, 3f / scaleFactor, geminiOverlayStroke)
            val label = "#${idx + 1} kalimat"
            canvas.drawText(label, r.left + sw * 2, r.top + ts + sw * 2, geminiOverlayText)

            val closeX = r.right
            val closeY = r.top
            canvas.drawCircle(closeX, closeY, badgeRadius, badgeFill)
            canvas.drawCircle(closeX, closeY, badgeRadius, badgeStroke)
            val baseline = closeY - (badgeText.ascent() + badgeText.descent()) / 2f
            canvas.drawText("×", closeX, baseline, badgeText)
        }
    }

    private fun hitGeminiOverlayCloseIndex(x: Float, y: Float): Int {
        val hitRadius = (24f / scaleFactor).coerceAtLeast(12f)
        for (i in geminiDetectOverlay.lastIndex downTo 0) {
            val rect = geminiDetectOverlay[i].rect
            if (dist(x, y, rect.right, rect.top) <= hitRadius) return i
        }
        return -1
    }

    private fun hitGeminiOverlayIndex(x: Float, y: Float): Int {
        for (i in geminiDetectOverlay.lastIndex downTo 0) {
            if (geminiDetectOverlay[i].rect.contains(x, y)) return i
        }
        return -1
    }

    private fun drawOcrOverlay(canvas: Canvas) {
        val sw = (3f / scaleFactor).coerceAtLeast(1f)
        val ts = (24f / scaleFactor).coerceAtLeast(8f)
        ocrOverlayStroke.strokeWidth = sw
        ocrOverlayText.textSize = ts
        for ((index, region) in ocrDetectOverlay.withIndex()) {
            val rect = region.rect
            canvas.drawRect(rect, ocrOverlayFill)
            canvas.drawRect(rect, ocrOverlayStroke)
            canvas.drawText("OCR ${index + 1}", rect.left + sw * 2f, rect.top + ts + sw * 2f, ocrOverlayText)
        }
    }

    private fun hitOcrOverlayIndex(x: Float, y: Float): Int {
        for (i in ocrDetectOverlay.lastIndex downTo 0) {
            if (ocrDetectOverlay[i].rect.contains(x, y)) return i
        }
        return -1
    }

    /**
     * Makes the active pixel layer safe for painting without ever replacing it
     * with a failed bitmap copy. Large/hardware-backed images can throw before
     * the first brush dab, so every allocation and Canvas creation is guarded.
     */
    private fun prepareBrushCanvas(layer: Layer): Canvas? {
        if (layer.isLocked || layer.bitmap.isRecycled) return null
        return try {
            if (!layer.bitmap.isMutable || layer.bitmap.config == Bitmap.Config.HARDWARE) {
                val source = layer.bitmap
                val mutableCopy = source.copy(Bitmap.Config.ARGB_8888, true)
                    ?: return null
                layer.bitmap = mutableCopy
            }
            Canvas(layer.bitmap)
        } catch (_: OutOfMemoryError) {
            onStatusUpdate?.invoke("Brush tidak dapat dimulai: memori tidak cukup", 0f, 0f)
            null
        } catch (t: Throwable) {
            onStatusUpdate?.invoke(
                "Brush tidak dapat dimulai: ${t.message ?: t.javaClass.simpleName}",
                0f,
                0f
            )
            null
        }
    }

    /** Clears delayed gesture state when switching into/out of a brush tool. */
    fun resetBrushGestureState() {
        removeCallbacks(brushSizeGestureRunnable)
        brushLastPoint = null
        brushCursorPoint = null
        brushGestureStartPoint = null
        brushSizingMode = false
        brushStrokeStarted = false
        brushEngine.cancelStroke()
        invalidate()
    }

    private fun handleBrush(event: MotionEvent, pt: PointF) {
        val layer = layers.getOrNull(activeLayerIndex) ?: run {
            onStatusUpdate?.invoke("Brush memerlukan layer piksel aktif", pt.x, pt.y)
            return
        }
        if (layer.isLocked) {
            onStatusUpdate?.invoke("Brush tidak dapat dipakai: layer aktif terkunci", pt.x, pt.y)
            return
        }
        if (layer.bitmap.isRecycled) {
            onStatusUpdate?.invoke("Brush tidak dapat dipakai: bitmap layer sudah dilepas", pt.x, pt.y)
            return
        }
        val bitmapCanvas = prepareBrushCanvas(layer) ?: return
        brushCursorPoint = PointF(pt.x, pt.y)

        fun ensureStrokeStarted(anchor: PointF) {
            if (brushStrokeStarted) return
            removeCallbacks(brushSizeGestureRunnable)
            brushStrokeStarted = true
            onWorkspaceHistoryPush?.invoke()
            brushEngine.beginStroke(anchor.x, anchor.y)
            brushLastPoint = PointF(anchor.x, anchor.y)
            brushEngine.drawPoint(bitmapCanvas, anchor.x, anchor.y)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                brushGestureStartPoint = PointF(pt.x, pt.y)
                brushGestureStartScreenY = event.y
                brushSizeAtGestureStart = brushEngine.brushSize
                brushSizingMode = false
                brushStrokeStarted = false
                brushLastPoint = PointF(pt.x, pt.y)
                brushEngine.beginStroke(pt.x, pt.y)
                removeCallbacks(brushSizeGestureRunnable)
                postDelayed(brushSizeGestureRunnable, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (brushSizingMode) {
                    val delta = (brushGestureStartScreenY - event.y) * 0.55f
                    brushEngine.brushSize = (brushSizeAtGestureStart + delta).coerceIn(1f, 201f)
                    onBrushSizeChanged?.invoke(brushEngine.brushSize)
                    onStatusUpdate?.invoke(
                        "Ukuran brush: ${brushEngine.brushSize.roundToInt()} px",
                        pt.x,
                        pt.y
                    )
                } else {
                    val start = brushGestureStartPoint ?: pt
                    val movedScreen = hypot(
                        (pt.x - start.x) * scaleFactor,
                        (pt.y - start.y) * scaleFactor
                    )
                    if (movedScreen > brushTouchSlop || brushStrokeStarted) {
                        ensureStrokeStarted(start)
                        var previous = brushLastPoint ?: start
                        for (i in 0 until event.historySize) {
                            val historical = screenToCanvas(event.getHistoricalX(i), event.getHistoricalY(i))
                            brushEngine.drawSegment(bitmapCanvas, previous.x, previous.y, historical.x, historical.y)
                            previous = historical
                        }
                        brushEngine.drawSegment(bitmapCanvas, previous.x, previous.y, pt.x, pt.y)
                        brushLastPoint = PointF(pt.x, pt.y)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(brushSizeGestureRunnable)
                if (!brushSizingMode) {
                    val start = brushGestureStartPoint ?: pt
                    ensureStrokeStarted(start)
                    val previous = brushLastPoint ?: pt
                    brushEngine.drawSegment(bitmapCanvas, previous.x, previous.y, pt.x, pt.y)
                    brushEngine.drawPoint(bitmapCanvas, pt.x, pt.y)
                }
                brushLastPoint = null
                brushGestureStartPoint = null
                brushStrokeStarted = false
                brushSizingMode = false
                postDelayed({
                    if (!brushSizingMode && brushLastPoint == null) {
                        brushCursorPoint = null
                        invalidate()
                    }
                }, 650L)
            }
            MotionEvent.ACTION_CANCEL -> resetBrushGestureState()
        }
        invalidate()
    }

    /** Clears the current clone sample so the next canvas tap arms a new source. */
    fun clearCloneStampSource() {
        cloneStampEngine.clearSource()
        cloneStampCursorPoint = null
        cloneStampStrokeActive = false
        invalidate()
    }

    private fun syncCloneBrushSettings() {
        cloneStampEngine.brushSize = brushEngine.brushSize
        cloneStampEngine.hardness = brushEngine.hardness
        cloneStampEngine.opacity = brushEngine.opacity
    }

    private fun handleCloneStamp(event: MotionEvent, pt: PointF) {
        val layer = layers.getOrNull(activeLayerIndex) ?: return
        if (layer.isLocked || layer.bitmap.isRecycled) return
        cloneStampCursorPoint = PointF(pt.x, pt.y)
        syncCloneBrushSettings()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!cloneStampEngine.hasSource) {
                    val sampled = cloneStampEngine.sampleSource(layer.bitmap, pt)
                    onStatusUpdate?.invoke(
                        if (sampled) {
                            "Clone Stamp: sumber dipilih • drag untuk melukis • tap ikon Clone untuk sampling ulang"
                        } else {
                            "Clone Stamp: gambar terlalu besar untuk snapshot sumber"
                        },
                        pt.x,
                        pt.y
                    )
                    invalidate()
                    return
                }
                if (!layer.bitmap.isMutable) {
                    layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                }
                onWorkspaceHistoryPush?.invoke()
                cloneStampStrokeActive = cloneStampEngine.beginStroke(pt)
                if (cloneStampStrokeActive) {
                    cloneStampEngine.continueStroke(Canvas(layer.bitmap), pt)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!cloneStampStrokeActive) return
                val destination = Canvas(layer.bitmap)
                for (i in 0 until event.historySize) {
                    val historical = screenToCanvas(event.getHistoricalX(i), event.getHistoricalY(i))
                    cloneStampEngine.continueStroke(destination, historical)
                }
                cloneStampEngine.continueStroke(destination, pt)
            }
            MotionEvent.ACTION_UP -> {
                if (cloneStampStrokeActive) {
                    cloneStampEngine.continueStroke(Canvas(layer.bitmap), pt)
                    cloneStampEngine.endStroke()
                    cloneStampStrokeActive = false
                    onStatusUpdate?.invoke("Clone Stamp: stroke selesai", pt.x, pt.y)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cloneStampEngine.endStroke()
                cloneStampStrokeActive = false
            }
        }
        invalidateDrag()
    }

    fun resetBlemishSelection() {
        blemishTargetPoint = null
        blemishCursorPoint = null
        blemishProcessing = false
        invalidate()
    }

    private fun handleBlemishRemoval(event: MotionEvent, pt: PointF) {
        if (blemishProcessing) return
        val layer = layers.getOrNull(activeLayerIndex) ?: return
        if (layer.isLocked || layer.bitmap.isRecycled) return
        blemishCursorPoint = PointF(pt.x, pt.y)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val instruction = if (blemishTargetPoint == null) {
                    "Blemish: lepaskan jari pada noda"
                } else {
                    "Blemish: pilih tekstur bersih untuk healing"
                }
                onStatusUpdate?.invoke(instruction, pt.x, pt.y)
            }
            MotionEvent.ACTION_UP -> {
                val target = blemishTargetPoint
                if (target == null) {
                    blemishTargetPoint = PointF(pt.x, pt.y)
                    onStatusUpdate?.invoke(
                        "Target noda dipilih • sekarang tap area bersih",
                        pt.x,
                        pt.y
                    )
                    performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
                } else {
                    if (!layer.bitmap.isMutable) {
                        layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    }
                    val source = PointF(pt.x, pt.y)
                    blemishProcessing = true
                    onWorkspaceHistoryPush?.invoke()
                    onStatusUpdate?.invoke("Blemish: seamless healing…", pt.x, pt.y)
                    Thread {
                        val result = BlemishRemovalEngine.heal(
                            layer.bitmap,
                            target,
                            source,
                            (brushEngine.brushSize / 2f).coerceAtLeast(3f)
                        )
                        post {
                            blemishProcessing = false
                            if (result.success) blemishTargetPoint = null
                            onStatusUpdate?.invoke(result.message, target.x, target.y)
                            invalidate()
                        }
                    }.start()
                }
            }
            MotionEvent.ACTION_CANCEL -> blemishCursorPoint = null
        }
        invalidate()
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (currentTool !in listOf(
                Tool.BRUSH,
                Tool.CONTENT_AWARE_BRUSH,
                Tool.BRUSH_INPAINT,
                Tool.REMOVR,
                Tool.CLONE_STAMP,
                Tool.BLEMISH_REMOVAL,
                Tool.TEXT_ERASE
            )
        ) return super.onHoverEvent(event)
        val pt = screenToCanvas(event.x, event.y)
        when (currentTool) {
            Tool.CLONE_STAMP -> cloneStampCursorPoint = pt
            Tool.BLEMISH_REMOVAL -> blemishCursorPoint = pt
            Tool.TEXT_ERASE -> textEraseCursorPt = pt
            else -> brushCursorPoint = pt
        }
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            cloneStampCursorPoint = null
            blemishCursorPoint = null
            brushCursorPoint = null
            textEraseCursorPt = null
        }
        invalidate()
        return true
    }

    private fun handleContentAwareBrush(event: MotionEvent, pt: PointF) {
        if (contentAwareProcessing) return
        val layer = layers.getOrNull(activeLayerIndex) ?: return
        if (layer.isLocked || layer.bitmap.isRecycled) return
        val radius = (brushEngine.brushSize / 2f).coerceAtLeast(3f)
        brushCursorPoint = pt
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                contentAwareMask.setEmpty()
                brushLastPoint = PointF(pt.x, pt.y)
                addMaskSegment(pt, pt, radius)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                var previous = brushLastPoint ?: pt
                for (i in 0 until event.historySize) {
                    val historical = screenToCanvas(event.getHistoricalX(i), event.getHistoricalY(i))
                    addMaskSegment(previous, historical, radius)
                    previous = historical
                }
                addMaskSegment(previous, pt, radius)
                brushLastPoint = PointF(pt.x, pt.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                addMaskSegment(brushLastPoint ?: pt, pt, radius)
                brushLastPoint = null
                brushCursorPoint = null
                applyContentAwareMask(layer)
            }
            MotionEvent.ACTION_CANCEL -> {
                contentAwareMask.setEmpty()
                brushLastPoint = null
                brushCursorPoint = null
                invalidate()
            }
        }
    }

    private fun addMaskSegment(from: PointF, to: PointF, radius: Float) {
        addMaskSegmentTo(contentAwareMask, from, to, radius)
    }

    /** Appends an interpolated brush stroke (series of circles) into [target]. */
    private fun addMaskSegmentTo(target: Region, from: PointF, to: PointF, radius: Float) {
        val distance = hypot(to.x - from.x, to.y - from.y)
        val steps = max(1, ceil(distance / radius.coerceAtLeast(1f)).toInt())
        val clip = Region(0, 0, canvasWidth, canvasHeight)
        for (step in 0..steps) {
            val t = step / steps.toFloat()
            val x = from.x + (to.x - from.x) * t
            val y = from.y + (to.y - from.y) * t
            val circle = Path().apply { addCircle(x, y, radius, Path.Direction.CW) }
            val circleRegion = Region().apply { setPath(circle, clip) }
            target.op(circleRegion, Region.Op.UNION)
        }
    }

    private fun applyContentAwareMask(layer: Layer) {
        if (contentAwareMask.isEmpty) return
        if (!layer.bitmap.isMutable) layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val region = Region(contentAwareMask)
        contentAwareMask.setEmpty()
        contentAwareProcessing = true
        onWorkspaceHistoryPush?.invoke()
        onStatusUpdate?.invoke("Inpainting tekstur non-AI: merekonstruksi area brush…", 0f, 0f)
        invalidate()
        Thread {
            // Content-Aware brush selalu berjalan lokal dengan inpainting klasik.
            // Backend cloud/deep-learning tidak digunakan agar hasil dapat diulang,
            // tetap offline, dan tekstur asli diambil dari piksel sekitar mask.
            val result = ContentAwareFillEngine.fill(layer.bitmap, region) { progress ->
                post {
                    onStatusUpdate?.invoke(
                        "Inpainting tekstur non-AI ${(progress * 100f).toInt()}%",
                        0f,
                        0f
                    )
                }
            }
            post {
                contentAwareProcessing = false
                onStatusUpdate?.invoke(
                    if (result.success) {
                        "Inpainting tekstur selesai • ${result.processedPixels} piksel direkonstruksi"
                    } else {
                        "Inpainting tekstur gagal: ${result.message}"
                    },
                    0f,
                    0f
                )
                invalidate()
            }
        }.start()
    }

    // ── Brush Inpaint tool (Aditya5052/Image_Inpainting: OpenCV Telea/NS) ────
    // Paint a mask with the brush; on finger-up the masked pixels are rebuilt
    // with OpenCV's Fast Marching (Telea) or Navier-Stokes method.

    private fun handleBrushInpaint(event: MotionEvent, pt: PointF) {
        if (brushInpaintProcessing) return
        val layer = layers.getOrNull(activeLayerIndex) ?: return
        if (layer.isLocked || layer.bitmap.isRecycled) return
        val radius = (brushEngine.brushSize / 2f).coerceAtLeast(3f)
        brushCursorPoint = pt
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                brushInpaintMask.setEmpty()
                brushLastPoint = PointF(pt.x, pt.y)
                addMaskSegmentTo(brushInpaintMask, pt, pt, radius)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                var previous = brushLastPoint ?: pt
                for (i in 0 until event.historySize) {
                    val historical = screenToCanvas(event.getHistoricalX(i), event.getHistoricalY(i))
                    addMaskSegmentTo(brushInpaintMask, previous, historical, radius)
                    previous = historical
                }
                addMaskSegmentTo(brushInpaintMask, previous, pt, radius)
                brushLastPoint = PointF(pt.x, pt.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                addMaskSegmentTo(brushInpaintMask, brushLastPoint ?: pt, pt, radius)
                brushLastPoint = null
                brushCursorPoint = null
                applyBrushInpaintMask(layer)
            }
            MotionEvent.ACTION_CANCEL -> {
                brushInpaintMask.setEmpty()
                brushLastPoint = null
                brushCursorPoint = null
                invalidate()
            }
        }
    }

    private fun applyBrushInpaintMask(layer: Layer) {
        if (brushInpaintMask.isEmpty) return
        if (!layer.bitmap.isMutable) layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val region = Region(brushInpaintMask)
        brushInpaintMask.setEmpty()
        brushInpaintProcessing = true
        onWorkspaceHistoryPush?.invoke()
        onStatusUpdate?.invoke("Brush Inpaint: OpenCV patch synthesis…", 0f, 0f)
        invalidate()
        Thread {
            val result = BrushInpainter.inpaint(layer.bitmap, region, BrushInpainter.Method.AUTO) { progress ->
                post { onStatusUpdate?.invoke("Brush Inpaint ${(progress * 100f).toInt()}%", 0f, 0f) }
            }
            post {
                brushInpaintProcessing = false
                onStatusUpdate?.invoke(
                    if (result.success) {
                        "Brush Inpaint selesai • ${result.processedPixels} piksel dipulihkan"
                    } else {
                        "Brush Inpaint gagal: ${result.message}"
                    },
                    0f,
                    0f
                )
                invalidate()
            }
        }.start()
    }

    // ── RemovR tool (light-and-ray/resynthesizer-python-lib: heal selection) ─
    // The brush now paints a non-destructive selection overlay. Source pixels
    // remain intact until ACTION_UP, allowing the gradient model to sample clean
    // colours around the text without soft-eraser transparency or dark fringes.

    private fun handleRemovR(event: MotionEvent, pt: PointF) {
        if (removRProcessing) return
        val layer = layers.getOrNull(activeLayerIndex) ?: return
        if (layer.isLocked || layer.bitmap.isRecycled) return
        if (!layer.bitmap.isMutable) layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val radius = (brushEngine.brushSize / 2f).coerceAtLeast(3f)
        brushCursorPoint = pt
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removRMask.setEmpty()
                brushEngine.beginStroke(pt.x, pt.y)
                brushLastPoint = PointF(pt.x, pt.y)
                onWorkspaceHistoryPush?.invoke()
                addMaskSegmentTo(removRMask, pt, pt, radius)
                // Keep source pixels intact while painting the mask. The previous
                // soft-eraser preview destroyed the very gradient samples needed
                // for a seamless reconstruction and left semi-transparent halos.
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                var previous = brushLastPoint ?: pt
                for (i in 0 until event.historySize) {
                    val historical = screenToCanvas(event.getHistoricalX(i), event.getHistoricalY(i))
                    val stabilized = brushEngine.stabilizePoint(historical.x, historical.y)
                    val next = PointF(stabilized.first, stabilized.second)
                    addMaskSegmentTo(removRMask, previous, next, radius)
                    previous = next
                }
                val stabilized = brushEngine.stabilizePoint(pt.x, pt.y)
                val current = PointF(stabilized.first, stabilized.second)
                addMaskSegmentTo(removRMask, previous, current, radius)
                brushLastPoint = current
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                val stabilized = brushEngine.stabilizePoint(pt.x, pt.y)
                val current = PointF(stabilized.first, stabilized.second)
                addMaskSegmentTo(removRMask, brushLastPoint ?: current, current, radius)
                brushLastPoint = null
                brushCursorPoint = null
                applyRemovRMask(layer)
            }
            MotionEvent.ACTION_CANCEL -> {
                removRMask.setEmpty()
                brushLastPoint = null
                brushCursorPoint = null
                invalidate()
            }
        }
    }

    private fun applyRemovRMask(layer: Layer) {
        if (removRMask.isEmpty) return
        val region = Region(removRMask)
        removRMask.setEmpty()
        removRProcessing = true
        onStatusUpdate?.invoke("RemovR: menganalisis permukaan gradient…", 0f, 0f)
        invalidate()
        Thread {
            // First choice for smooth backgrounds: robust 2D colour-surface
            // reconstruction. This is neither Telea nor Navier-Stokes and does
            // not copy random patches, so diagonal/soft gradients stay continuous.
            val gradient = SmoothGradientInpainter.inpaint(layer.bitmap, region) { progress ->
                post { onStatusUpdate?.invoke("RemovR Gradient ${(progress * 100f).toInt()}%", 0f, 0f) }
            }
            var result = if (gradient.applied) {
                ResynthesizerEngine.Result(
                    true,
                    "Smooth gradient (${(gradient.confidence * 100f).toInt()}%)",
                    gradient.processedPixels
                )
            } else {
                post { onStatusUpdate?.invoke("RemovR: konteks bertekstur, menjalankan Resynthesizer…", 0f, 0f) }
                ResynthesizerEngine.healSelection(
                    layer.bitmap,
                    region,
                    params = ResynthesizerEngine.Params(
                        searchRadius = 220,
                        maxNeighbors = 24,
                        maxRandomCandidates = 80
                    )
                ) { progress ->
                    post { onStatusUpdate?.invoke("RemovR Texture ${(progress * 100f).toInt()}%", 0f, 0f) }
                }
            }
            // Texture-only fallback remains available for screentones and noisy
            // art, but smooth gradients never pass through either PDE algorithm.
            if (!result.success) {
                post { onStatusUpdate?.invoke("RemovR: mencoba metode Criminisi…", 0f, 0f) }
                val fallback = CriminisiEngine.inpaint(layer.bitmap, region) { progress ->
                    post { onStatusUpdate?.invoke("RemovR (Criminisi) ${(progress * 100f).toInt()}%", 0f, 0f) }
                }
                if (fallback.success) {
                    result = ResynthesizerEngine.Result(
                        true,
                        "Criminisi fallback",
                        fallback.processedPixels
                    )
                }
            }
            val finalResult = result
            post {
                removRProcessing = false
                onStatusUpdate?.invoke(
                    if (finalResult.success) {
                        "RemovR selesai • ${finalResult.processedPixels} piksel disintesis"
                    } else {
                        "RemovR gagal: ${finalResult.message}"
                    },
                    0f,
                    0f
                )
                invalidate()
            }
        }.start()
    }

    // ── Text Erase tool (FIX 5 + soft/hard brush) ────────────────────────────
    // Brush over a TextElement to remove it. Also erases pixels on the active
    // pixel layer so the bubble background is cleared in one stroke.
    // brushEngine.hardness (0-1) controls edge softness: 1.0 = hard, 0.0 = very soft.

    private fun buildErasePaint(brushR: Float): android.graphics.Paint {
        return android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.FILL
            if (brushEngine.hardness >= 0.95f) {
                // Hard erase: full-clear PorterDuff CLEAR
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            } else {
                // Soft erase: DST_OUT with blur — each stroke gradually erases
                color = android.graphics.Color.BLACK
                alpha = (brushEngine.hardness * 230 + 25).toInt().coerceIn(25, 255)
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
                val blurRadius = (brushR * (1f - brushEngine.hardness) * 0.55f).coerceAtLeast(1f)
                maskFilter = android.graphics.BlurMaskFilter(blurRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
        }
    }

    private fun eraseTextElementsAt(pt: PointF, brushR: Float) {
        val toRemove = textElements.filter { el ->
            val cx = el.x + el.width / 2f; val cy = el.y + el.height / 2f
            val unrot = unrotatePoint(pt, cx, cy, el.rotation)
            unrot.x >= el.x - brushR && unrot.x <= el.x + el.width + brushR &&
            unrot.y >= el.y - brushR && unrot.y <= el.y + el.height + brushR
        }
        if (toRemove.isNotEmpty()) {
            pushTextHistory()
            textElements.removeAll(toRemove.toSet())
            if (activeTextId != null && textElements.none { it.id == activeTextId }) activeTextId = null
            onTextSelected?.invoke(textElements.find { it.id == activeTextId })
        }
    }

    private var textEraseCursorPt: android.graphics.PointF? = null

    private fun handleTextErase(event: MotionEvent, pt: PointF) {
        val brushR = (brushEngine.brushSize / 2f).coerceAtLeast(6f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                textEraseCursorPt = pt
                eraseTextElementsAt(pt, brushR)
                // Also erase pixels on active layer
                val layer = layers.getOrNull(activeLayerIndex)
                if (layer != null && !layer.isLocked && layer.bitmap.isMutable) {
                    if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                        for (i in 0 until event.historySize) {
                            val hp = screenToCanvas(event.getHistoricalX(i), event.getHistoricalY(i))
                            eraseTextElementsAt(hp, brushR)
                            Canvas(layer.bitmap).drawCircle(hp.x, hp.y, brushR, buildErasePaint(brushR))
                        }
                    }
                    Canvas(layer.bitmap).drawCircle(pt.x, pt.y, brushR, buildErasePaint(brushR))
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                eraseTextElementsAt(pt, brushR)
                textEraseCursorPt = null
                invalidate()
            }
            else -> Unit
        }
    }

    private fun handleZoomPan(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastTouchX = event.x; lastTouchY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    translateX += event.x - lastTouchX; translateY += event.y - lastTouchY
                    lastTouchX = event.x; lastTouchY = event.y; invalidate()
                }
            }
        }
    }

    // ── Multi-selection helpers ───────────────────────────────────────────────

    private fun clearSelectionDraft() {
        selectionDraftType = SelectionType.NONE
        selectionDraftRect.setEmpty()
        selectionDraftPath.reset()
    }

    private fun commitSelectionRegion(selectionType: SelectionType, bounds: RectF, exact: Region) {
        val clipped = Region(exact).apply {
            op(Region(0, 0, canvasWidth, canvasHeight), Region.Op.INTERSECT)
        }
        if (clipped.isEmpty) return

        when (selectionCombineMode) {
            SelectionCombineMode.REPLACE -> {
                selection.clear()
                selection.type = selectionType
                selection.rect.set(bounds)
                selection.region = Region(clipped)
                selection.addPart(bounds, Region(clipped))
                selection.isActive = true
                selection.isManualUnion = selectionType != SelectionType.MAGIC_WAND
            }
            SelectionCombineMode.ADD -> {
                if (!selection.isActive && selection.region == null && selection.parts.isEmpty()) {
                    selection.clear()
                    selection.type = selectionType
                    selection.rect.set(bounds)
                    selection.region = Region(clipped)
                    selection.addPart(bounds, Region(clipped))
                    selection.isActive = true
                    selection.isManualUnion = selectionType != SelectionType.MAGIC_WAND
                } else {
                    val merged = snapshotSelectionAsRegion()
                    merged.op(clipped, Region.Op.UNION)
                    selection.region = merged
                    if (selection.type == SelectionType.NONE) selection.type = selectionType
                    selection.rect.set(selection.getBounds())
                    selection.isActive = true
                    selection.isManualUnion = true
                    selection.addPart(bounds, Region(clipped))
                }
            }
            SelectionCombineMode.SUBTRACT -> {
                if (selection.subtractRegion(clipped)) {
                    selection.rect.set(selection.getBounds())
                    selection.isActive = selection.hasSelection()
                }
            }
        }
        onSelectionChanged?.invoke(selection)
    }

    private fun snapshotSelectionAsRegion(): Region {
        val bmpW = layers.getOrNull(activeLayerIndex)?.bitmap?.width  ?: canvasWidth
        val bmpH = layers.getOrNull(activeLayerIndex)?.bitmap?.height ?: canvasHeight
        val clip = Region(0, 0, bmpW, bmpH)
        val rg = Region()
        if (selection.partRegions.isNotEmpty()) {
            selection.partRegions.forEach { rg.op(it, Region.Op.UNION) }
            return rg
        }
        when (selection.type) {
            SelectionType.MAGIC_WAND -> selection.region?.let { rg.op(it, Region.Op.REPLACE) }
            SelectionType.RECT -> if (!selection.rect.isEmpty)
                rg.set(selection.rect.left.toInt(), selection.rect.top.toInt(),
                       selection.rect.right.toInt(), selection.rect.bottom.toInt())
            SelectionType.OVAL, SelectionType.FREE -> rg.setPath(selection.path, clip)
            else -> {}
        }
        return rg
    }

    private fun unionNewRectIntoSavedRegion() {
        val saved = addModeRegion ?: return
        saved.op(
            Region(
                selection.rect.left.toInt(),
                selection.rect.top.toInt(),
                selection.rect.right.toInt(),
                selection.rect.bottom.toInt()
            ),
            Region.Op.UNION
        )
        selection.region = saved
        selection.type = SelectionType.RECT
        selection.isActive = true
        selection.isManualUnion = true
        addModeRegion = null
    }

    private fun unionFreePathIntoSavedRegion() {
        val saved = addModeRegion ?: return
        val bmpW = layers.getOrNull(activeLayerIndex)?.bitmap?.width ?: canvasWidth
        val bmpH = layers.getOrNull(activeLayerIndex)?.bitmap?.height ?: canvasHeight
        val clip = Region(0, 0, bmpW, bmpH)
        val newR = Region().also { it.setPath(selection.path, clip) }
        saved.op(newR, Region.Op.UNION)
        selection.region = saved
        selection.type = SelectionType.FREE
        selection.isActive = true
        selection.isManualUnion = true
        addModeRegion = null
    }

    private fun clampTextElementToCanvas(el: TextElement) {
        if (!clampToCanvasEnabled) return
        el.width = el.width.coerceAtLeast(10f).coerceAtMost(canvasWidth.toFloat())
        el.height = el.height.coerceAtLeast(10f).coerceAtMost(canvasHeight.toFloat())
        el.x = el.x.coerceIn(0f, (canvasWidth - el.width).coerceAtLeast(0f))
        el.y = el.y.coerceIn(0f, (canvasHeight - el.height).coerceAtLeast(0f))
    }

    private fun clampImageElementToCanvas(img: ImageElement) {
        if (!clampToCanvasEnabled) return
        img.width = img.width.coerceAtLeast(10f).coerceAtMost(canvasWidth.toFloat())
        img.height = img.height.coerceAtLeast(10f).coerceAtMost(canvasHeight.toFloat())
        img.x = img.x.coerceIn(0f, (canvasWidth - img.width).coerceAtLeast(0f))
        img.y = img.y.coerceIn(0f, (canvasHeight - img.height).coerceAtLeast(0f))
    }

    private fun smoothAngle(current: Float, target: Float, factor: Float = 0.35f): Float {
        val delta = ((target - current + 540f) % 360f) - 180f
        return current + delta * factor
    }

    // ── Transform math helpers ────────────────────────────────────────────────

    private fun snapToGrid(value: Float): Float {
        if (!snapGridEnabled) return value
        val GRID = 20f; return (value / GRID).roundToInt() * GRID
    }

    private fun unrotatePoint(pt: PointF, cx: Float, cy: Float, angleDeg: Float): PointF {
        val rad  = (-angleDeg * PI / 180.0).toFloat()
        val cosA = cos(rad); val sinA = sin(rad)
        val dx   = pt.x - cx; val dy = pt.y - cy
        return PointF(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
    }

    private fun rotatePoint(pt: PointF, cx: Float, cy: Float, angleDeg: Float): PointF {
        val rad  = (angleDeg * PI / 180.0).toFloat()
        val cosA = cos(rad); val sinA = sin(rad)
        val dx   = pt.x - cx; val dy = pt.y - cy
        return PointF(cx + dx * cosA - dy * sinA, cy + dx * sinA + dy * cosA)
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float =
        sqrt((ax - bx).pow(2) + (ay - by).pow(2))

    private fun hitsUnrotatedBounds(unrot: PointF, el: TextElement): Boolean =
        unrot.x in el.x..(el.x + el.width) && unrot.y in el.y..(el.y + el.height)

    private fun hitsUnrotatedBoundsImg(unrot: PointF, img: ImageElement): Boolean =
        unrot.x in img.x..(img.x + img.width) && unrot.y in img.y..(img.y + img.height)

    private fun hitsBounds(pt: PointF, el: TextElement): Boolean {
        val cx = el.x + el.width / 2f; val cy = el.y + el.height / 2f
        val unrot = unrotatePoint(pt, cx, cy, el.rotation)
        return hitsUnrotatedBounds(unrot, el)
    }

    // Check if point (px, py) is inside the quad defined by corners
    // corners[0,1]=TL  corners[2,3]=TR  corners[4,5]=BL  corners[6,7]=BR
    private fun pointInQuad(px: Float, py: Float, corners: FloatArray): Boolean {
        fun sign(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) =
            (ax - cx) * (by - cy) - (bx - cx) * (ay - cy)
        fun inTri(ax:Float,ay:Float,bx:Float,by:Float,cx:Float,cy:Float): Boolean {
            val d1=sign(px,py,ax,ay,bx,by); val d2=sign(px,py,bx,by,cx,cy); val d3=sign(px,py,cx,cy,ax,ay)
            return !((d1<0||d2<0||d3<0) && (d1>0||d2>0||d3>0))
        }
        return inTri(corners[0],corners[1],corners[2],corners[3],corners[6],corners[7]) ||
               inTri(corners[0],corners[1],corners[6],corners[7],corners[4],corners[5])
    }

    // ── Public utilities ──────────────────────────────────────────────────────

    fun screenToCanvas(sx: Float, sy: Float): PointF =
        PointF((sx - translateX) / scaleFactor, (sy - translateY) / scaleFactor)

    fun getZoomPercent(): Int = (scaleFactor * 100).toInt()

    fun zoomToFit() {
        if (width == 0 || height == 0) return
        val scX = width.toFloat()  / canvasWidth
        val scY = height.toFloat() / canvasHeight
        scaleFactor = min(scX, scY)
        translateX  = (width  - canvasWidth  * scaleFactor) / 2f
        translateY  = (height - canvasHeight * scaleFactor) / 2f
        invalidate()
    }

    fun zoomIn()  { scaleFactor = (scaleFactor * 1.25f).coerceAtMost(20f);    invalidate() }
    fun zoomOut() { scaleFactor = (scaleFactor / 1.25f).coerceAtLeast(0.05f); invalidate() }

    fun clearSelection() { selection.clear(); clearSelectionDraft(); invalidate() }

    /**
     * Copies only the pixels inside the active selection into a floating image
     * element. The source layer is never cleared or modified (copy, not cut),
     * and pixels outside lasso/oval/magic-wand selections stay transparent.
     *
     * A bounds-sized bitmap is used instead of a canvas-sized layer so copying
     * from very tall webtoons remains practical on low-memory devices.
     */
    fun copySelectionAsImageElement(): ImageElement? {
        val layer = layers.getOrNull(activeLayerIndex) ?: return null
        val source = layer.bitmap
        if (source.isRecycled || !selection.isActive) return null
        val region = buildSelectionRegion(source.width, source.height) ?: return null
        region.op(Region(0, 0, source.width, source.height), Region.Op.INTERSECT)
        if (region.isEmpty) return null

        val bounds = region.bounds
        if (bounds.isEmpty) return null
        val copy = try {
            Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }

        return try {
            val canvas = Canvas(copy)
            canvas.translate(-bounds.left.toFloat(), -bounds.top.toFloat())
            val iterator = RegionIterator(region)
            val run = Rect()
            while (iterator.next(run)) {
                val checkpoint = canvas.save()
                canvas.clipRect(run)
                canvas.drawBitmap(source, 0f, 0f, null)
                canvas.restoreToCount(checkpoint)
            }
            ImageElement(
                bitmap = copy,
                x = bounds.left.toFloat(),
                y = bounds.top.toFloat(),
                width = bounds.width().toFloat(),
                height = bounds.height().toFloat()
            )
        } catch (error: Throwable) {
            if (!copy.isRecycled) copy.recycle()
            null
        }
    }

    fun autoFitActiveText() {
        val el = textElements.find { it.id == activeTextId } ?: return
        pushTextHistory()
        el.fontSize = TextRenderer.autoFitFontSize(el.text, el.width, el.height, el.typeface)
        val fittedH = TextRenderer.computeWrappedHeight(
            el.text, el.fontSize, el.width, el.typeface,
            leading = el.leading, paragraphSpacing = el.paragraphSpacing
        )
        el.height   = fittedH.coerceAtLeast(el.fontSize * 1.3f)
        onTextHistoryPush?.invoke(); invalidate()
    }

    private fun updateStatusCursor(cx: Float, cy: Float) {
        onStatusUpdate?.invoke("${getZoomPercent()}%", cx, cy)
    }

    /**
     * Build a Region representing the current selection, clipped to the active layer bitmap.
     * Returns null when the selection is empty or cannot be resolved.
     *
     * v5.3 — central helper used by fillSelection() and applyInpaint() so both code paths
     * stay in sync. Previously fillSelection() did not handle FREE / OVAL paths and silently
     * returned false, which made the Fill White / Black / Inpaint buttons appear broken right
     * after the user finished drawing a freeform / lasso selection.
     */
    private fun buildSelectionRegion(bmpW: Int, bmpH: Int): Region? {
        if (!selection.isActive) return null
        val clip = Region(0, 0, bmpW, bmpH)

        fun rectFromBounds(b: RectF): Region? {
            if (b.isEmpty()) return null
            val r = Rect(
                b.left.toInt().coerceIn(0, bmpW),
                b.top.toInt().coerceIn(0, bmpH),
                b.right.toInt().coerceIn(0, bmpW),
                b.bottom.toInt().coerceIn(0, bmpH)
            )
            return if (r.isEmpty) null else Region(r)
        }

        return when (selection.type) {
            SelectionType.MAGIC_WAND -> selection.region?.takeUnless { it.isEmpty }

            SelectionType.RECT -> {
                // Prefer explicit parts (handles add-to-selection); otherwise use rect.
                val rg = Region()
                var has = false
                if (selection.parts.isNotEmpty()) {
                    selection.parts.forEach { part ->
                        val r = Rect(
                            part.left.toInt().coerceIn(0, bmpW),
                            part.top.toInt().coerceIn(0, bmpH),
                            part.right.toInt().coerceIn(0, bmpW),
                            part.bottom.toInt().coerceIn(0, bmpH)
                        )
                        if (!r.isEmpty) { rg.op(r, Region.Op.UNION); has = true }
                    }
                }
                if (has) rg else rectFromBounds(selection.rect)
            }

            SelectionType.OVAL, SelectionType.FREE -> {
                // v5.3 FIX: build region from the actual Path (was missing — caused Fill no-op).
                if (!selection.path.isEmpty) {
                    val rg = Region()
                    if (rg.setPath(selection.path, clip) && !rg.isEmpty) rg
                    else rectFromBounds(selection.getBounds()) // fallback to bbox
                } else {
                    rectFromBounds(selection.getBounds())
                }
            }

            else -> rectFromBounds(selection.getBounds())
        }
    }

    /**
     * Solid fill untuk selection biasa. Jalur ini tidak memakai detector/mask panel
     * dan tidak pernah memanggil inpainting.
     */
    fun fillSelection(fillColor: Int): Boolean {
        val layer = layers.getOrNull(activeLayerIndex) ?: return false
        if (layer.bitmap.isRecycled || !selection.isActive) return false
        if (!layer.bitmap.isMutable) {
            layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        return try {
            val region = buildSelectionRegion(layer.bitmap.width, layer.bitmap.height)
                ?: return false
            if (region.isEmpty) return false

            // Magic-wand fills (when the selection comes from a pixel flood-fill) use a dedicated
            // helper that can optionally skip dark ink pixels. Manual unions reset to MAGIC_WAND
            // type as well but have isManualUnion=true → treat them like a plain region fill so
            // the user gets exactly the area they painted.
            val didFill = if (selection.type == SelectionType.MAGIC_WAND && !selection.isManualUnion) {
                MagicWandSelector.fillRegion(layer.bitmap, region, fillColor, ignoreText = false)
                true
            } else {
                val c = Canvas(layer.bitmap)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = fillColor
                    style = Paint.Style.FILL
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
                }
                val it = RegionIterator(region)
                val r = Rect()
                var drew = false
                while (it.next(r)) { c.drawRect(r, p); drew = true }
                drew
            }
            if (didFill) postInvalidate()
            didFill
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    // ── Perspective enable / disable for active text element ──────────────────
    //
    // Call from MainActivity via a toolbar button or menu item.
    // Toggling ON  : bakes the current (x, y, rotation) into 4 canvas-space
    //                corner points, then zeros el.rotation so the new
    //                perspective quad IS the transform.
    // Toggling OFF : restores a rectangular bounding box centred on the quad
    //                centroid with the same width/height (rotation reset to 0).

    fun enablePerspectiveForActiveText() {
        val el = textElements.find { it.id == activeTextId } ?: return
        pushTextHistory()

        val existingCorners = el.perspCorners
        if (existingCorners != null && existingCorners.size >= 8) {
            // Disable perspective: fit back to axis-aligned rect at centroid
            val centX = (existingCorners[0] + existingCorners[2] + existingCorners[4] + existingCorners[6]) / 4f
            val centY = (existingCorners[1] + existingCorners[3] + existingCorners[5] + existingCorners[7]) / 4f
            el.x = centX - el.width  / 2f
            el.y = centY - el.height / 2f
            el.rotation = 0f
            el.perspCorners = null
        } else {
            // Enable perspective: bake current rotation into corner positions
            val cx = el.x + el.width  / 2f; val cy = el.y + el.height / 2f
            val rad  = el.rotation * PI.toFloat() / 180f
            val cosR = cos(rad); val sinR = sin(rad)
            fun rotPt(lx: Float, ly: Float): Pair<Float, Float> {
                val dx = lx - cx; val dy = ly - cy
                return Pair(cx + dx * cosR - dy * sinR, cy + dx * sinR + dy * cosR)
            }
            val (tlx, tly) = rotPt(el.x,            el.y)
            val (trx, try_)= rotPt(el.x + el.width, el.y)
            val (blx, bly) = rotPt(el.x,            el.y + el.height)
            val (brx, bry) = rotPt(el.x + el.width, el.y + el.height)
            el.perspCorners = floatArrayOf(tlx, tly, trx, try_, blx, bly, brx, bry)
            el.rotation = 0f // rotation is now encoded in the corners
        }

        onTextHistoryPush?.invoke()
        invalidate()
    }

    // ── Mesh enable / disable for active text element ─────────────────────
    //
    // Call from MainActivity via a toolbar button or menu item.
    // Toggling ON  : creates a dense control grid that can be pushed into
    //                a more free-form warp, clears any active perspective quad,
    //                zeros rotation, and enables point dragging.
    // Toggling OFF : clears the mesh and returns to the normal transform.

    fun enableMeshForActiveText() {
        val el = textElements.find { it.id == activeTextId } ?: return
        pushTextHistory()

        val existingPts = el.meshPoints
        if (existingPts != null && existingPts.size >= 8) {
            el.meshPoints = null
            polyAddPointMode = false
        } else {
            // Default 5x5 mesh (4x4 cells) — denser control points make the warp feel more free-form.
            val cols = 4
            val rows = 4
            el.meshCols = cols
            el.meshRows = rows
            val pts = FloatArray((cols + 1) * (rows + 1) * 2)
            val stepX = el.width / cols
            val stepY = el.height / rows
            var k = 0
            for (r in 0..rows) {
                for (c in 0..cols) {
                    pts[k++] = stepX * c
                    pts[k++] = stepY * r
                }
            }
            el.meshPoints = pts
            el.perspCorners = null
            el.rotation = 0f
            polyAddPointMode = false
        }

        onTextHistoryPush?.invoke()
        invalidate()
    }

    // ── SmartFill ────────────────────────────────────────────────────────────
    //
    // LaMa Manga: local ONNX inpainting tuned for comics and manga.
    // Navier-Stokes: OpenCV structural fill, light and edge-preserving.
    // Agnes Image 2.1 Flash: optional online image-to-image inpainting.
    //
    // IMPORTANT: call from a background thread (Dispatchers.IO).

    fun applyInpaint(onProgress: ((Float) -> Unit)? = null): Boolean {
        return applySmartFill(com.vasiliastyper.model.SmartFillBackend.LAMA_MANGA, onProgress)
    }

    fun applySmartFill(
        backend: com.vasiliastyper.model.SmartFillBackend,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        val layer = layers.getOrNull(activeLayerIndex) ?: return false
        if (layer.bitmap.isRecycled || !selection.isActive) return false
        if (!layer.bitmap.isMutable) {
            layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        return try {
            val inpaintRegion = resolveSmartFillRegion(layer.bitmap) ?: return false
            if (inpaintRegion.isEmpty) return false

            val ok = inpaintRegion(layer.bitmap, inpaintRegion, backend, onProgress)

            // Never replace a failed inpaint operation with a flat colour. That
            // old fallback made Navier-Stokes look like "Fill White" and destroyed
            // image detail. LaMa Manga already falls back to the local OpenCV patch engine;
            // if the selected backend fails, preserve the original pixels.
            postInvalidate()
            ok
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    /**
     * v10 — Inpaint the ACTIVE SELECTION (Rect/Lasso/Magic Wand) langsung
     * dengan engine patch OpenCV native (bukan Telea/Navier-Stokes).
     *
     * Berbeda dari [applySmartFill] yang selalu menurunkan mask teks dari ROI
     * detector, fungsi ini memakai region seleksi persis seperti yang digambar
     * pengguna — cocok untuk object removal: pengguna menyeleksi objek (Rect /
     * Lasso / Magic Wand) lalu satu ketukan mengisi area itu dengan tekstur
     * sekitarnya (patch synthesis / multi-scale / seamless otomatis).
     *
     * Panggil dari background thread. Mengembalikan Result engine agar UI bisa
     * menampilkan metode yang dipakai.
     */
    fun inpaintSelectionWithOpenCv(
        onProgress: ((Float) -> Unit)? = null
    ): BrushInpainter.Result {
        val layer = layers.getOrNull(activeLayerIndex)
            ?: return BrushInpainter.Result(false, "Layer aktif tidak tersedia")
        if (layer.bitmap.isRecycled) return BrushInpainter.Result(false, "Layer sudah dilepas")
        if (layer.isLocked) return BrushInpainter.Result(false, "Layer terkunci")
        if (!selection.isActive) return BrushInpainter.Result(false, "Tidak ada seleksi aktif")
        if (!layer.bitmap.isMutable) {
            layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        return try {
            val region = buildSelectionRegion(layer.bitmap.width, layer.bitmap.height)
                ?: return BrushInpainter.Result(false, "Region seleksi tidak valid")
            if (region.isEmpty) return BrushInpainter.Result(false, "Region seleksi kosong")

            val result = BrushInpainter.inpaint(
                layer.bitmap, region,
                BrushInpainter.Method.AUTO,
                onProgress
            )
            if (result.success) {
                clearSelection()
                postInvalidate()
            }
            result
        } catch (oom: OutOfMemoryError) {
            oom.printStackTrace()
            BrushInpainter.Result(false, "Memori tidak cukup — perkecil seleksi")
        } catch (t: Throwable) {
            t.printStackTrace()
            BrushInpainter.Result(false, "Inpaint gagal: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    /**
     * SmartFill entry point for regions produced by the Mask panel.
     *
     * A detector rectangle is only a search ROI; it is never the inpaint mask.
     * We derive a pixel-level text region inside it and refuse the operation if
     * no text pixels can be isolated. This prevents an entire speech bubble or
     * rectangular detector region from being inpainted.
     */
    fun applySmartFillToMask(
        mask: BooleanArray,
        maskWidth: Int,
        maskHeight: Int,
        offsetX: Int,
        offsetY: Int,
        backend: com.vasiliastyper.model.SmartFillBackend,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        val layer = layers.getOrNull(activeLayerIndex) ?: return false
        if (layer.bitmap.isRecycled || maskWidth <= 0 || maskHeight <= 0) return false
        if (mask.size != maskWidth * maskHeight) return false
        if (!layer.bitmap.isMutable) {
            layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        return try {
            val region = TextMaskEngine.maskToRegion(mask, maskWidth, maskHeight)
            if (region.isEmpty) return false
            region.translate(offsetX, offsetY)
            region.op(Region(0, 0, layer.bitmap.width, layer.bitmap.height), Region.Op.INTERSECT)
            if (region.isEmpty) return false
            val ok = inpaintRegion(layer.bitmap, region, backend, onProgress)
            if (ok) postInvalidate()
            ok
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }

    fun applySmartFillToTextInRect(
        searchBounds: RectF,
        backend: com.vasiliastyper.model.SmartFillBackend,
        sourceBitmap: Bitmap? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        val layer = layers.getOrNull(activeLayerIndex) ?: return false
        if (layer.bitmap.isRecycled || searchBounds.isEmpty) return false
        if (!layer.bitmap.isMutable) {
            layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        val bitmap = layer.bitmap

        val searchRect = Rect(
            floor(searchBounds.left).toInt().coerceIn(0, bitmap.width),
            floor(searchBounds.top).toInt().coerceIn(0, bitmap.height),
            ceil(searchBounds.right).toInt().coerceIn(0, bitmap.width),
            ceil(searchBounds.bottom).toInt().coerceIn(0, bitmap.height)
        )
        if (searchRect.isEmpty) return false

        var ownedComposite: Bitmap? = null
        return try {
            val source = sourceBitmap ?: compositeVisibleLayers()?.also { ownedComposite = it } ?: bitmap
            val preciseText = TextMaskEngine.detectTextRegion(
                context = context,
                bitmap = source,
                searchRect = searchRect,
                paddingPx = ProcessingConfig.MASK_DILATION_PX
            ) ?: return false
            preciseText.op(Region(searchRect), Region.Op.INTERSECT)
            if (preciseText.isEmpty) return false

            val ok = inpaintRegion(bitmap, preciseText, backend, onProgress)
            if (ok) postInvalidate()
            ok
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        } finally {
            val rendered = ownedComposite
            if (rendered != null && rendered !== bitmap && !rendered.isRecycled) rendered.recycle()
        }
    }

    private fun inpaintRegion(
        bitmap: Bitmap,
        region: Region,
        backend: com.vasiliastyper.model.SmartFillBackend,
        onProgress: ((Float) -> Unit)?
    ): Boolean = when (backend) {
        com.vasiliastyper.model.SmartFillBackend.LAMA_MANGA ->
            LamaMangaInpainter.inpaint(context, bitmap, region, true, onProgress)
        // v10: OPENCV_PATCH (patch synthesis + seamless clone native, BUKAN
        // Telea/Navier-Stokes). NAVIER_STOKES dipertahankan sebagai alias
        // legacy yang dipetakan ke engine yang sama.
        com.vasiliastyper.model.SmartFillBackend.OPENCV_PATCH,
        com.vasiliastyper.model.SmartFillBackend.NAVIER_STOKES ->
            BrushInpainter.inpaint(bitmap, region, BrushInpainter.Method.AUTO, onProgress).success
        com.vasiliastyper.model.SmartFillBackend.AGNES_IMAGE_AI ->
            AgnesAiInpainter.inpaint(context, bitmap, region, onProgress)
        com.vasiliastyper.model.SmartFillBackend.IDEOGRAM_AI ->
            IdeogramInpainter.inpaint(context, bitmap, region, onProgress)
    }

    private fun fillRegionFallback(bitmap: Bitmap, region: Region, fillColor: Int): Boolean {
        if (bitmap.isRecycled || !bitmap.isMutable || region.isEmpty) return false
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = fillColor
        }
        val iterator = RegionIterator(region)
        val rect = Rect()
        var changed = false
        while (iterator.next(rect)) {
            canvas.drawRect(rect, paint)
            changed = true
        }
        return changed
    }

    private fun estimateFallbackColor(bitmap: Bitmap, region: Region): Int {
        val b = region.bounds
        if (b.isEmpty) return Color.WHITE
        val pad = 4
        val left = (b.left - pad).coerceAtLeast(0)
        val top = (b.top - pad).coerceAtLeast(0)
        val right = (b.right + pad).coerceAtMost(bitmap.width)
        val bottom = (b.bottom + pad).coerceAtMost(bitmap.height)

        var rSum = 0L; var gSum = 0L; var bSum = 0L; var count = 0L
        fun sample(x: Int, y: Int) {
            if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) return
            val px = bitmap.getPixel(x, y)
            if (Color.alpha(px) < 16) return
            rSum += Color.red(px); gSum += Color.green(px); bSum += Color.blue(px); count++
        }

        val step = 4
        for (x in left until right step step) {
            sample(x, top)
            sample(x, bottom - 1)
        }
        for (y in top until bottom step step) {
            sample(left, y)
            sample(right - 1, y)
        }

        return if (count <= 0) Color.WHITE else Color.rgb(
            (rSum / count).toInt().coerceIn(0, 255),
            (gSum / count).toInt().coerceIn(0, 255),
            (bSum / count).toInt().coerceIn(0, 255)
        )
    }

    private fun resolveSmartFillRegion(bitmap: Bitmap): Region? {
        // Normal toolbar SmartFill always processes the complete user selection.
        // Text-only refinement belongs exclusively to applySmartFillToTextInRect(),
        // which is used by the Mask panel. Keeping these paths separate prevents
        // ordinary rectangle/lasso inpainting from silently changing into text-only.
        val region = buildSelectionRegion(bitmap.width, bitmap.height) ?: return null
        region.op(Region(0, 0, bitmap.width, bitmap.height), Region.Op.INTERSECT)
        return region.takeUnless { it.isEmpty }
    }

    private fun regionPixelArea(region: Region): Long {
        if (region.isEmpty) return 0L
        val iterator = RegionIterator(region)
        val rect = Rect()
        var area = 0L
        while (iterator.next(rect)) {
            area += rect.width().toLong() * rect.height().toLong()
        }
        return area
    }
}
