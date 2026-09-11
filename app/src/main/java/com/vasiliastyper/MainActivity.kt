package com.vasiliastyper

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.vasiliastyper.adapter.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.vasiliastyper.databinding.ActivityMainBinding
import com.vasiliastyper.databinding.DialogStyleManagerBinding
import com.vasiliastyper.databinding.DialogTextEditorBinding
import com.vasiliastyper.engine.*
import com.vasiliastyper.model.*
import com.vasiliastyper.viewmodel.WorkspaceViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.Locale
import java.util.UUID
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val vm: WorkspaceViewModel by viewModels()
    private val workspaceLoadErrorHandler = CoroutineExceptionHandler { _, error ->
        Log.e("VasiliasTyper", "Workspace gagal dimuat", error)
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            Toast.makeText(
                this,
                "Proyek gagal dimuat. Workspace aman akan dibuka.",
                Toast.LENGTH_LONG
            ).show()
            if (vm.activeWorkspace == null && this::binding.isInitialized) {
                runCatching {
                    val workspace = vm.createWorkspace()
                    rememberWorkspaceProjectId(workspace, workspace.id)
                    currentProjectId = workspace.id
                    addTab(workspace)
                    bindWorkspace(workspace.id)
                }.onFailure { fallbackError ->
                    returnToHome(fallbackError)
                }
            }
        }
    }

    // ── BubbleCleaner mode ────────────────────────────────────────────────────
    // Tap optionsLabel saat BUBBLE_CLEAN aktif untuk toggle.
    private var bubbleCleanMode: BubbleCleaner.CleanMode = BubbleCleaner.CleanMode.PRECISE
    private var bubbleCleanerEnabledFix = true
    private var bubbleDetectionJob: Job? = null
    private var maskDetectionJob: Job? = null

    // ── Batch page processor + crash recovery ─────────────────────────────────
    private var batchQueueState: BatchRecoveryStore.QueueState? = null
    private var batchProcessorJob: Job? = null
    private var pendingBatchOperation: String = BatchRecoveryStore.OP_CLEAN_ONLY
    private var batchDialog: AlertDialog? = null
    private var batchStatusView: TextView? = null
    private var batchProgressView: ProgressBar? = null
    private var activeBatchWorkspaceId: String? = null

    // ── Bubble Translate tool state ────────────────────────────────────────────
    // 0 = Fill White, 1 = Fill Black, 2 = Inpainting
    private var btFillMode: Int = 0
    // null = Auto-match, otherwise the name of the saved TextStyle to use
    private var btSelectedStyleName: String? = null

    // ── Export state ──────────────────────────────────────────────────────────
    private var pendingExportFormat  = Bitmap.CompressFormat.PNG
    private var pendingExportQuality = 100
    private var pendingExportAllTabs = false

    private data class ExportPreset(
        val name: String,
        val formatName: String,
        val quality: Int
    )

    private val builtInExportPresets = listOf(
        ExportPreset("Webtoon PNG", "PNG", 100),
        ExportPreset("JPG High Quality", "JPEG", 95),
        ExportPreset("JPG Small", "JPEG", 82),
        ExportPreset("WebP Lossless", "WEBP_LOSSLESS", 100),
        ExportPreset("WebP Balanced", "WEBP_LOSSY", 88)
    )
    private var pendingPickerEyedropper: ((Int) -> Unit)? = null
    private var brushControlsExpanded = true
    private var activeTextEditorDialog: Dialog? = null
    private var activeStyleManagerDialog: Dialog? = null
    private var activeStyleSaveDialog: AlertDialog? = null
    private var aiChatPanelDialog: BottomSheetDialog? = null
    private var aiChatRequestJob: Job? = null
    private var textEditorDialogOpen = false
    private var styleManagerDialogOpen = false
    private var pendingStyleLoadAction: ((com.vasiliastyper.model.TextStyle) -> Unit)? = null

    // Font discovery is cached and never blocks the first text-editor frame.
    @Volatile private var cachedFontList: List<FontItem>? = null
    private var fontWarmupJob: Job? = null

    // ── Watermark image state ──────────────────────────────────────────────────
    private var watermarkBitmap: Bitmap? = null
    private var watermarkImageName: String = "Belum dipilih"

    // ── Offline watermark-remover state ─────────────────────────────────────────
    private var unwatermarkSample: Bitmap? = null
    private var unwatermarkSampleName: String = "Belum ada sampel"
    private val unwatermarkPresets = mutableListOf<WatermarkPresetLoader.Preset>()
    private var activeUnwatermarkPreset: WatermarkPresetLoader.Preset? = null
    private var unwatermarkPreviewJob: Job? = null
    private var unwatermarkPreviewGeneration = 0
    private var updatingUnwatermarkPresetSpinner = false
    private var activeUnwatermarkProfile = UnwatermarkEngine.Profile.OCTOPUS_ADAPTIVE
    /** True after the user drags/resizes an auto result; hybrid mode must preserve it. */
    private var unwatermarkSelectionManuallyAdjusted = false

    // ── Reference window state ────────────────────────────────────────────────
    private val referenceImages = mutableListOf<Uri>()
    private var referenceDialog: AlertDialog? = null

    // ── Tab state ─────────────────────────────────────────────────────────────
    // Each entry: Pair<workspace-id, LinearLayout(tab view)>
    private val tabViews = mutableListOf<Pair<String, LinearLayout>>()
    private var tabIsolationEnabled = true

    // ── Current project tracking (for auto-save / restore) ────────────────────
    /** Stable project record ID used as the auto-save file key. */
    private var currentProjectId: String? = null
    private val workspaceProjectIds = mutableMapOf<String, String>()
    private val workspaceTextStates = mutableMapOf<String, MutableList<TextElement>>()
    private val workspaceImageStates = mutableMapOf<String, MutableList<ImageElement>>()
    private val workspaceMaskStates = mutableMapOf<String, MutableList<PaddleDbNetDetector.DetectedRegion>>()
    private var autoSaveJob: kotlinx.coroutines.Job? = null
    private var periodicAutoSaveJob: kotlinx.coroutines.Job? = null
    private val autoSaveMutex = Mutex()
    @Volatile private var batchAutoSaveSuspended = false

    private data class WorkspaceSnapshot(
        val workspaceJson: String,
        val textJson: String,
        val imageJson: String,
        val projectId: String
    )
    private val workspaceUndoStack = ArrayDeque<WorkspaceSnapshot>()
    private val workspaceRedoStack = ArrayDeque<WorkspaceSnapshot>()
    private var restoringWorkspaceSnapshot = false

    // ── Floating popup dialogs (persistent while working) ─────────────────────
    private var layersDialog: AlertDialog? = null
    private val composeLayersOpen = mutableStateOf(false)
    private val composeLayerItems = mutableStateOf<List<ComposeLayerItem>>(emptyList())
    private val composeBrushState = mutableStateOf(BrushUiState())
    // v5.3 — ItemTouchHelper attached to the layers panel for Ibis-style drag-reorder.
    // Held as a field so the LayerAdapter's onStartDrag lambda can call startDrag() on it.
    private var layersTouchHelper: ItemTouchHelper? = null
    private var scriptAdapter: ScriptLineAdapter? = null
    private var scriptLines  = mutableListOf<com.vasiliastyper.engine.ScriptLine>()
    private var scriptFileName = "No script loaded"
    private var scriptSourceLanguage = "Source (terdeteksi)"
    private var scriptSourceLanguageCode = MlKitOcrEngine.LANG_AUTO
    private var scriptHasOcrPairs = false

    /** Script biasa dan TipeR OCR sengaja memakai dua jalur impor yang terpisah. */
    private enum class ScriptImportMode { SCRIPT_ONLY, TIPER_AUTO_MATCH }
    private enum class PlaceAllDirection {
        TOP_TO_BOTTOM_LEFT_TO_RIGHT,
        TOP_TO_BOTTOM_RIGHT_TO_LEFT
    }
    private var pendingScriptImportMode = ScriptImportMode.SCRIPT_ONLY
    private var placeAllDirection = PlaceAllDirection.TOP_TO_BOTTOM_LEFT_TO_RIGHT
    private var scriptOcrMatchJob: Job? = null

    // Pending text from script that should be pre-filled in next text editor open
    private var pendingScriptLine: String? = null

    // Auto-style override: set when a style rule matches the pending script/typeR text
    private var pendingAutoStyle: com.vasiliastyper.model.TextStyle? = null

    // v11.2: guards autoPlaceTextWithStyle() while it awaits the (now off-main-thread)
    // font lookup, so a rapid double-tap on "Use" can't start a second placement
    // before the first one has finished.
    private var autoPlaceInFlight = false

    // Type-R: queue of texts to place one-by-one as user draws each selection
    private val typeRQueue = ArrayDeque<String>()

    // VasType: bottom-sheet importer for structured OCR/translation scripts.
    private enum class VasTypeMode { INPAINT_ONLY, INPAINT_AND_PLACE }
    private data class VasTypeProcessResult(val changed: Boolean, val message: String)
    private var vasTypeDialog: BottomSheetDialog? = null
    private var vasTypeJob: Job? = null
    private var vasTypeMode: VasTypeMode = VasTypeMode.INPAINT_AND_PLACE
    private var vasTypeLoadedLabel: String = "Belum ada file"
    private var vasTypeLoadedScript: StructuredScript? = null
    private val vasTypeSelectedWorkspaceIds = linkedSetOf<String>()
    private var vasTypeScriptInputView: EditText? = null
    private var vasTypeStatusView: TextView? = null
    private var vasTypePreviewView: TextView? = null
    private var vasTypeSourcePreviewView: TextView? = null
    private var vasTypeTranslationPreviewView: TextView? = null
    private var vasTypeTabContainer: LinearLayout? = null
    private var vasTypeProgressView: ProgressBar? = null
    private var vasTypeLoadedLabelView: TextView? = null
    private var vasTypeTabCountView: TextView? = null
    private var vasTypeModeGroupView: RadioGroup? = null
    private var vasTypeModeInpaintOnlyView: RadioButton? = null
    private var vasTypeModeInpaintPlaceView: RadioButton? = null
    private var vasTypeDetectButtonView: View? = null
    private var vasTypePreviewWorkspaceId: String? = null
    private var vasTypePreviewReady = false

    // Manual/legacy TipeR may still strip a configured PREFIX_CODE. VasType uses
    // its own resolver and always keeps the imported prefix visible on canvas.
    private var pendingDisplayText: String? = null
    private var textShaperDialogOpen = false
    private var textShaperSelectionHandled = false

    // Type-R batch prefix / numbering customisation
    private var typeRPrefix: String = ""
    private var typeRStartIndex: Int = 1

    // Batch placement guard: suppress repeated history/autosave/toast work
    // when a whole script batch is being placed on low-end devices.
    private var placementBatchDepth = 0
    private inline fun <T> withPlacementBatch(block: () -> T): T {
        placementBatchDepth++
        return try {
            block()
        } finally {
            placementBatchDepth--
        }
    }

    private suspend fun <T> withPlacementBatchSuspend(block: suspend () -> T): T {
        placementBatchDepth++
        return try {
            block()
        } finally {
            placementBatchDepth--
        }
    }

    private val isPlacementBatchActive: Boolean
        get() = placementBatchDepth > 0

    // Sequential script placement job: lets multi-area script work run one-by-one
    // without blocking the UI with repeated heavy history/autosave work.
    private var scriptPlacementJob: Job? = null

    // TipeR OCR never writes immediately after import/detection. Matches wait here
    // until the user explicitly presses Place; shaping is a separate later action.
    private val pendingTipeRMatches = ArrayDeque<ScriptOcrMatcher.Match>()

    // ── Text Detect state ───────────────────────────────────────────────────
    private val textDetectedRegions = mutableListOf<com.vasiliastyper.engine.PaddleDbNetDetector.DetectedRegion>()
    // Daftar region tertutup secara default supaya panel tidak menutupi tombol aksi.
    private var maskRegionListExpanded = false

    // ── OCR Panel state (integrated with editor selection tools) ─────────────
    private var useGeminiOcr = false
    private var bubbleTranslateArmed = false
    private val ocrResults  = mutableListOf<OcrResult>()
    private val workspaceOcrStates = mutableMapOf<String, MutableList<OcrResult>>()
    private var ocrAdapter: OcrResultAdapter? = null
    private var leftSidebarVisible = true
    private var rightSidebarVisible = true
    private val ocrSrcCodes = arrayOf("auto", "en", "zh", "ko")
    private val ocrTgtCodes = arrayOf("id", "en")
      // v2.0.3 Fix #4: when "Use" is tapped in the script panel but no selection
    // exists, the panel must be dismissed for the user to draw a box.  After
    // the text is placed we automatically reopen the script panel so the user
    // can continue working through their script without navigating back to it.
    private var reopenScriptAfterPlace = false

    // ── Activity result launchers ─────────────────────────────────────────────

    private var pendingFontMatchApply: ((FontItem) -> Unit)? = null
    private val fontMatchImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input ->
                    android.graphics.BitmapFactory.decodeStream(input)
                }
            }
            if (bitmap == null) {
                Toast.makeText(this@MainActivity, "Gambar referensi tidak dapat dibaca", Toast.LENGTH_LONG).show()
                return@launch
            }
            runFontMatch(bitmap, pendingFontMatchApply ?: {})
        }
    }

    private val batchPagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        val names = uris.mapIndexed { index, uri -> getFileDisplayName(uri) ?: "page_${index + 1}" }
        batchQueueState = BatchRecoveryStore.replaceQueue(
            this,
            uris,
            names,
            pendingBatchOperation
        )
        showVasTypeDialog()
        Toast.makeText(this, "${uris.size} halaman masuk antrean. Tekan Mulai Manual.", Toast.LENGTH_LONG).show()
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            snapshotActiveWorkspaceElements()
            snapshotActiveOcrResults()
            snapshotActiveMaskRegions()

            // Decode several images concurrently. The small semaphore avoids both the
            // old one-by-one delay and an OOM spike when a user selects many pages.
            val names = uris.mapIndexed { index, uri ->
                getFileDisplayName(uri) ?: "image_${index + 1}"
            }
            uris.forEach { uri ->
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            updateStatus("Membuka ${uris.size} gambar secara paralel…")
            val decodeSlots = Semaphore(permits = minOf(4, uris.size))
            val decoded = uris.mapIndexed { index, uri ->
                async(Dispatchers.IO) {
                    decodeSlots.withPermit {
                        Triple(index, uri, FileManager.loadBitmap(this@MainActivity, uri))
                    }
                }
            }.awaitAll().sortedBy { it.first }

            val openedWorkspaces = mutableListOf<Pair<Workspace, Uri>>()
            var failed = 0
            decoded.forEach { (index, uri, bitmap) ->
                if (bitmap == null) {
                    failed++
                    return@forEach
                }
                val ws = vm.openImage(bitmap, names[index])
                rememberWorkspaceProjectId(ws, ws.id)
                workspaceTextStates[ws.id] = mutableListOf()
                workspaceImageStates[ws.id] = mutableListOf()
                addTab(ws)
                openedWorkspaces += ws to uri
            }

            openedWorkspaces.lastOrNull()?.first?.let {
                bindWorkspace(it.id)
                highlightTab(it.id)
            }
            val opened = openedWorkspaces.size
            val summary = "$opened gambar dibuka sebagai $opened tab" +
                if (failed > 0) " • $failed gagal" else ""
            updateStatus(summary)
            Toast.makeText(this@MainActivity, summary, Toast.LENGTH_LONG).show()

            // History thumbnails and recovery files are deliberately deferred until
            // every tab is already visible, so persistence never delays opening.
            openedWorkspaces.forEach { (ws, uri) ->
                saveToHistory(ws, "image", ws.id, sourceUri = uri.toString())
            }
            lifecycleScope.launch(Dispatchers.IO) {
                openedWorkspaces.forEach { (ws, _) ->
                    autoSaveMutex.withLock {
                        WorkspaceSerializer.autoSave(
                            context = this@MainActivity,
                            ws = ws,
                            textElements = emptyList(),
                            imageElements = emptyList(),
                            projectId = ws.id
                        )
                    }
                }
            }
        }
    }

    /**
     * Stamps an image from storage as a free-floating ImageElement on the canvas.
     * The element can be moved and resized (aspect-ratio locked) using the ADD_IMAGE tool.
     * Used by the Add Image tool (watermark / overlay workflow).
     */
    private val stampImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val ws = vm.activeWorkspace ?: return@registerForActivityResult
        lifecycleScope.launch {
            val rawBm = FileManager.loadBitmap(this@MainActivity, uri) ?: return@launch

            // v6.1: Downscale bitmap ke ukuran aman untuk mencegah OOM di device low-end.
            // Bitmap besar (contoh: 3000×4000) di-downscale ke max ~2MP agar tetap tajam
            // tapi tidak memakan RAM berlebihan saat banyak ImageElement.
            val MAX_IMAGE_PIXELS = 2_500_000L  // ~2.5 MP, cukup untuk kualitas bagus
            val rawPixels = rawBm.width.toLong() * rawBm.height.toLong()
            val bm = if (rawPixels > MAX_IMAGE_PIXELS) {
                val scale = kotlin.math.sqrt(MAX_IMAGE_PIXELS.toDouble() / rawPixels.toDouble())
                val nW = (rawBm.width * scale).toInt().coerceAtLeast(1)
                val nH = (rawBm.height * scale).toInt().coerceAtLeast(1)
                android.graphics.Bitmap.createScaledBitmap(rawBm, nW, nH, true).also {
                    if (it !== rawBm) rawBm.recycle()
                }
            } else rawBm

            val maxW   = ws.width  * 0.8f
            val maxH   = ws.height * 0.8f
            val scaleW = maxW / bm.width
            val scaleH = maxH / bm.height
            val scale  = minOf(scaleW, scaleH, 1f)
            val dispW  = (bm.width  * scale).coerceAtLeast(1f)
            val dispH  = (bm.height * scale).coerceAtLeast(1f)
            val offX   = (ws.width  - dispW) / 2f
            val offY   = (ws.height - dispH) / 2f

            val opacitySeek = SeekBar(this@MainActivity).apply {
                max = 100
                progress = 100
                setPadding(32, 16, 32, 0)
            }
            val opacityLabel = TextView(this@MainActivity).apply {
                text = "Opacity: 100%"
                setPadding(32, 16, 32, 0)
            }
            opacitySeek.setOnSeekBarChangeListener(seekListener { p ->
                opacityLabel.text = "Opacity: ${p}%"
            })

            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = "Atur opacity gambar sebelum ditambahkan:"
                    setPadding(32, 24, 32, 8)
                })
                addView(opacityLabel)
                addView(opacitySeek)
            }

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Add Image")
                .setView(container)
                .setNegativeButton("Cancel") { _, _ ->
                    // v6.1: recycle bitmap jika user cancel
                    if (!bm.isRecycled) bm.recycle()
                }
                .setPositiveButton("Add") { _, _ ->
                    binding.canvasView.pushImageHistory()
                    val imgEl = com.vasiliastyper.model.ImageElement(
                        bitmap  = bm,
                        x       = offX,
                        y       = offY,
                        width   = dispW,
                        height  = dispH,
                        opacity = opacitySeek.progress.coerceIn(0, 100)
                    )
                    binding.canvasView.imageElements.add(imgEl)
                    binding.canvasView.activeImageId = imgEl.id
                    binding.canvasView.invalidate()

                    val name = getFileDisplayName(uri) ?: "Image"
                    Toast.makeText(
                        this@MainActivity,
                        "\"$name\" ditambahkan — seret untuk pindahkan, seret sudut untuk resize",
                        Toast.LENGTH_LONG
                    ).show()
                    binding.toolMove.performClick()
                }
                .show()
        }
    }

    private val referenceImagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri>? ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        referenceImages.addAll(uris)
        showReferenceWindow()
    }

    private val watermarkImagePickerLauncher = registerForActivityResult(

        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val loaded = FileManager.loadBitmap(this@MainActivity, uri)
            if (loaded == null) {
                Toast.makeText(this@MainActivity, "Gagal memuat gambar watermark", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val normalized = normalizeWatermarkBitmap(loaded)
            watermarkBitmap?.takeIf { it !== normalized }?.recycle()
            watermarkBitmap = normalized
            watermarkImageName = getFileDisplayName(uri) ?: "Watermark"
            binding.tvWmImageName.text = watermarkImageName
            updateStatus("Watermark image siap: $watermarkImageName")
            Toast.makeText(this@MainActivity, "Watermark image dipilih", Toast.LENGTH_SHORT).show()
        }
    }

    private val unwatermarkSamplePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val displayName = getFileDisplayName(uri) ?: "Sampel watermark"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val isPreset = WatermarkPresetLoader.isLikelyPreset(
                        this@MainActivity,
                        uri,
                        displayName
                    )
                    if (isPreset) {
                        val presets = WatermarkPresetLoader.load(this@MainActivity, uri)
                        Pair(presets, null)
                    } else {
                        val loaded = FileManager.loadBitmap(this@MainActivity, uri)
                            ?: throw IllegalArgumentException("Gambar tidak dapat didekode")
                        require(loaded.width > 0 && loaded.height > 0) { "Dimensi sampel tidak valid" }
                        Pair(emptyList(), normalizeWatermarkBitmap(loaded, maxDimension = 2048))
                    }
                }
            }
            result.onSuccess { (presets, sample) ->
                clearUnwatermarkPreview()
                clearUnwatermarkSamples()
                if (presets.isNotEmpty()) {
                    unwatermarkPresets += presets
                    activeUnwatermarkPreset = presets.first()
                    unwatermarkSample = presets.first().bitmap
                    unwatermarkSampleName = "$displayName • ${presets.size} preset"
                    binding.tvUnwmHint.text =
                        "${presets.size} preset siap. Auto-fit akan memilih jenis, skala, dan posisi terbaik lalu memakai filter JPEG anti-halo."
                } else {
                    unwatermarkSample = sample
                    unwatermarkSampleName = displayName
                    binding.tvUnwmHint.text =
                        "Sampel PNG siap. Auto-fit aktif dan filter JPEG anti-halo akan digunakan saat Remove."
                }
                binding.tvUnwmSampleName.text = unwatermarkSampleName
                refreshUnwatermarkPresetSpinner()
                scheduleUnwatermarkPreview()
                updateStatus("UNWM siap: $unwatermarkSampleName")
            }.onFailure { error ->
                val message = "Gagal memuat sampel/preset: ${error.message ?: "format tidak didukung"}"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                updateStatus(message)
            }
        }
    }

    private val scriptPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val mode = pendingScriptImportMode
            pendingScriptImportMode = ScriptImportMode.SCRIPT_ONLY
            binding.tvScriptProgress.text = "Membaca file script…"

            // SCRIPT membaca file mentah dan tetap memakai alur seleksi biasa.
            // TipeR OCR membaca pasangan SOURCE/OCR + TRANSLATE untuk pencocokan otomatis.
            val structuredResult = if (mode == ScriptImportMode.SCRIPT_ONLY) null else {
                runCatching { ScriptImporter.importStructured(this@MainActivity, uri) }
            }
            val plainResult = if (mode == ScriptImportMode.SCRIPT_ONLY) {
                runCatching { ScriptImporter.importPlain(this@MainActivity, uri) }
            } else null
            val loadError = structuredResult?.exceptionOrNull() ?: plainResult?.exceptionOrNull()
            if (loadError != null) {
                val message = "Gagal memuat TXT/DOCX: ${loadError.message ?: "format atau encoding tidak didukung"}"
                binding.tvScriptProgress.text = message
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                return@launch
            }

            val imported = structuredResult?.getOrNull()
            val loadedLines = imported?.lines ?: plainResult?.getOrNull().orEmpty()
            if (loadedLines.isEmpty()) {
                binding.tvScriptProgress.text = "File kosong atau tidak memiliki baris yang dapat dibaca"
                Toast.makeText(this@MainActivity, binding.tvScriptProgress.text, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (mode == ScriptImportMode.TIPER_AUTO_MATCH && imported?.hasOcrPairs != true) {
                binding.tvScriptProgress.text = "Format TipeR tidak valid: header OCR/SOURCE dan TRANSLATE wajib ada"
                Toast.makeText(
                    this@MainActivity,
                    "Format OCR/TRANSLATE tidak ditemukan; gunakan header OCR lalu TRANSLATE",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            pendingTipeRMatches.clear()
            binding.btnPlaceAll.text = "Place All ▶"
            scriptLines.clear()
            scriptLines.addAll(loadedLines)
            scriptFileName = getFileDisplayName(uri)
                ?: uri.lastPathSegment?.substringAfterLast('/')
                ?: "script"
            scriptHasOcrPairs = imported?.hasOcrPairs == true
            scriptSourceLanguageCode = if (scriptHasOcrPairs) detectImportedSourceLanguageCode(loadedLines)
                else MlKitOcrEngine.LANG_AUTO
            scriptSourceLanguage = if (scriptHasOcrPairs) sourceLanguageLabel(scriptSourceLanguageCode) else "Source"
            runCatching { binding.editScriptStartLine.setText("1") }
            refreshScriptPanel()
            binding.btnStartScriptOcr.isEnabled = scriptHasOcrPairs
            binding.tvScriptProgress.text = if (scriptHasOcrPairs && imported != null) {
                "Kategori: ${imported.sourceLines.size} OCR/source • ${imported.translationLines.size} translation • ${loadedLines.size} pasangan siap • tekan Mulai Script OCR"
            } else {
                "${loadedLines.size} baris script + Style Rules siap"
            }

            // File hanya dimuat dan disimpan di memori. OCR tidak dijalankan otomatis;
            // pengguna dapat menekan tombol Mulai berulang kali dengan file yang sama.
        }
    }

    // v2.0: Multi-script launcher — picks multiple files at once
    private val multiScriptPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val lines = ScriptImporter.importMultiple(this@MainActivity, uris)
            pendingTipeRMatches.clear()
            binding.btnPlaceAll.text = "Place All ▶"
            scriptLines.clear()
            scriptLines.addAll(lines)
            val count = uris.size
            val lineCount = lines.size
            scriptFileName = "$count scripts loaded"
            scriptHasOcrPairs = false
            scriptSourceLanguage = "Source"
            scriptSourceLanguageCode = MlKitOcrEngine.LANG_AUTO
            runCatching { binding.editScriptStartLine.setText("1") }
            refreshScriptPanel()
            Toast.makeText(this@MainActivity, "$lineCount dialog lines dari $count file", Toast.LENGTH_SHORT).show()
        }
    }

    private val vasTypeScriptPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = runCatching { ScriptImporter.importPlacement(this@MainActivity, uri) }
            result.onSuccess { structured ->
                if (structured.lines.isEmpty()) {
                    val message = "File script kosong atau tidak memiliki teks yang dapat dibaca"
                    vasTypeStatusView?.text = "Script kosong"
                    vasTypePreviewView?.text = message
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    return@onSuccess
                }
                val canonical = structured.lines.joinToString("\n") { it.text }
                vasTypeLoadedLabel = getFileDisplayName(uri)
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "vasType_script"
                vasTypeLoadedScript = structured
                vasTypeScriptInputView?.setText(canonical)
                refreshVasTypeSummary()
                vasTypePreviewReady = false
                Toast.makeText(this@MainActivity, "${structured.lines.size} baris script siap: $vasTypeLoadedLabel", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                val message = "Gagal memuat script VasType: ${error.message ?: "format tidak didukung"}"
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                vasTypeStatusView?.text = message
            }
        }
    }

    private val fontPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        // Satu file = alur lama (dialog rename bila duplikat).
        // Banyak file = batch: duplikat otomatis disimpan dengan nama bernomor.
        if (uris.size == 1) {
            val uri = uris.first()
            lifecycleScope.launch {
                val valid = CustomFontManager.isFontFile(this@MainActivity, uri)
                if (!valid) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Format Font Tidak Dikenali")
                        .setMessage("Signature file bukan TTF, OTF, atau TTC standar. Tetap coba import? File yang tidak dapat dibaca Android akan ditolak dengan aman.")
                        .setPositiveButton("Import Anyway") { _, _ ->
                            lifecycleScope.launch { doImportFont(uri) }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                } else {
                    doImportFont(uri)
                }
            }
        } else {
            lifecycleScope.launch { doImportFonts(uris) }
        }
    }

    /**
     * Import banyak font sekaligus: tiap file divalidasi signature, duplikat
     * otomatis diberi nama bernomor ("Nama (2)", "Nama (3)", ...), lalu satu
     * ringkasan Toast di akhir (tanpa dialog per file).
     */
    private suspend fun doImportFonts(uris: List<Uri>) {
        var ok = 0
        var renamed = 0
        var failed = 0
        for (uri in uris) {
            var result = CustomFontManager.importFont(this, uri)
            var autoRenamed = false
            if (result is CustomFontManager.ImportResult.Duplicate) {
                var n = 2
                while (n < 100) {
                    val dupName = (result as? CustomFontManager.ImportResult.Duplicate)
                        ?.existingDisplayName ?: break
                    result = CustomFontManager.importFont(
                        this, uri,
                        desiredDisplayName = "$dupName ($n)",
                        overrideExisting = false
                    )
                    if (result !is CustomFontManager.ImportResult.Duplicate) break
                    n++
                }
                autoRenamed = result is CustomFontManager.ImportResult.Success
            }
            when {
                result is CustomFontManager.ImportResult.Success && autoRenamed -> renamed++
                result is CustomFontManager.ImportResult.Success -> ok++
                else -> failed++
            }
        }
        invalidateFontCache()
        val parts = mutableListOf<String>()
        if (ok > 0) parts += "$ok font diimport"
        if (renamed > 0) parts += "$renamed duplikat disimpan dengan nama baru"
        if (failed > 0) parts += "$failed gagal"
        Toast.makeText(
            this,
            if (parts.isEmpty()) "Tidak ada font diimport" else parts.joinToString(", "),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * v5.0 — imports a font picked from storage, checking for duplicates in
     * the merged font bank (built-in + assets + previously-imported).
     * If a duplicate is found, prompts the user to rename or replace.
     */
    private suspend fun doImportFont(uri: Uri, forcedName: String? = null, override: Boolean = false) {
        when (val result = CustomFontManager.importFont(this, uri, forcedName, override)) {
            is CustomFontManager.ImportResult.Success -> {
                invalidateFontCache()
                Toast.makeText(
                    this,
                    "Font '${result.item.displayName}' imported!" + if (result.renamed) " (renamed)" else "",
                    Toast.LENGTH_SHORT
                ).show()
            }
            is CustomFontManager.ImportResult.Duplicate -> {
                showFontDuplicateDialog(uri, result.existingDisplayName)
            }
            is CustomFontManager.ImportResult.Failed -> {
                Toast.makeText(this, "Gagal import font: ${result.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * v5.0 — shown when the chosen font name clashes with one already in the
     * merged Font Bank. User can rename the new font, replace the existing
     * one, or cancel the import.
     */
    private fun showFontDuplicateDialog(uri: Uri, existingName: String) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val msg = TextView(this).apply {
            text = "A font named '\u2018$existingName\u2019' is already in the Font Bank.\n\nRename it, or import anyway (will save with a numbered suffix)."
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
        }
        val etName = EditText(this).apply {
            hint = "New display name"
            setText(existingName)
        }
        container.addView(msg)
        container.addView(etName)
        AlertDialog.Builder(this)
            .setTitle("Duplicate font name")
            .setView(container)
            .setPositiveButton("Use this name") { _, _ ->
                val newName = etName.text.toString().trim()
                if (newName.isBlank() || newName == existingName) {
                    Toast.makeText(this, "Please pick a different name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch { doImportFont(uri, forcedName = newName, override = false) }
            }
            .setNeutralButton("Import anyway") { _, _ ->
                lifecycleScope.launch { doImportFont(uri, forcedName = existingName + "_new", override = true) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // v5.0 — PSD export launcher (separate MIME from raster images)
    private val psdExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/vnd.adobe.photoshop")
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val ws = vm.activeWorkspace ?: return@registerForActivityResult
        lifecycleScope.launch {
            // Flatten text + image elements into a new raster layer so they're
            // visible in Photoshop / GIMP / Krita / Affinity / Clip Studio.
            val flatLayers = withContext(Dispatchers.IO) {
                val list = ws.layers.toMutableList()
                val overlay = renderOverlayLayer(ws.width, ws.height)
                if (overlay != null) {
                    list.add(Layer(name = "Text + Images", bitmap = overlay))
                }
                list
            }
            val ok = try {
                // Build a temporary workspace clone so PsdSerializer.export sees the overlay layer.
                val tmpWs = Workspace(
                    id = ws.id, name = ws.name,
                    width = ws.width, height = ws.height
                )
                tmpWs.layers.clear(); tmpWs.layers.addAll(flatLayers)
                PsdSerializer.export(this@MainActivity, tmpWs, uri)
            } catch (e: Exception) { e.printStackTrace(); false }
            Toast.makeText(
                this@MainActivity,
                if (ok) "Exported as PSD!" else "PSD export failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // v5.0 — PSD import launcher
    private val psdImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = getFileDisplayName(uri) ?: "Imported.psd"
            val imported = withContext(Dispatchers.IO) {
                PsdSerializer.import(this@MainActivity, uri, name)
            }
            if (imported == null) {
                Toast.makeText(this@MainActivity,
                    "PSD tidak dapat dibuka. Gunakan PSD v1 8-bit RGB/Grayscale/CMYK; raw, RLE, ZIP, dan ZIP Prediction didukung.",
                    Toast.LENGTH_LONG).show()
                return@launch
            }
            val ws = vm.addExistingWorkspace(imported.workspace)
            currentProjectId = ws.id
            rememberWorkspaceProjectId(ws, ws.id)
            workspaceTextStates[ws.id] = mutableListOf()
            workspaceImageStates[ws.id] = mutableListOf()
            addTab(ws)
            bindWorkspace(ws.id)
            saveToHistory(ws, "psd", ws.id, sourceUri = uri.toString())
            triggerAutoSave()
            Toast.makeText(this@MainActivity,
                "Imported PSD \u2014 ${imported.workspace.layers.size} layer(s)",
                Toast.LENGTH_SHORT).show()
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("image/*")
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val ws = vm.activeWorkspace ?: return@registerForActivityResult
        lifecycleScope.launch {
            // v5.1 FIX: Build a flat composite that includes BOTH the bitmap layers
            // AND every TextElement / ImageElement overlay so PNG/JPG/WebP exports
            // are visually identical to what the user sees on the canvas.
            val composite = withContext(Dispatchers.IO) {
                buildFlatExportBitmap(ws)
            }
            val ok = FileManager.exportBitmap(
                this@MainActivity, composite,
                pendingExportFormat, pendingExportQuality, uri
            )
            composite.recycle()
            val label = when (pendingExportFormat) {
                Bitmap.CompressFormat.JPEG -> "JPG"
                else -> if (Build.VERSION.SDK_INT >= 30 &&
                    pendingExportFormat == Bitmap.CompressFormat.WEBP_LOSSLESS) "WebP" else "WebP"
            }.let { if (pendingExportFormat == Bitmap.CompressFormat.PNG) "PNG" else it }
            Toast.makeText(
                this@MainActivity,
                if (ok) "Exported as $label!" else "Export failed",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * v5.1 FIX — Produce a single flat ARGB_8888 bitmap that contains:
     *   1. All visible bitmap layers, composited bottom-up with their
     *      opacity / blend-mode (delegated to LayerCompositor).
     *   2. All ImageElement overlays (with rotation, scale, blur).
     *   3. All TextElement overlays (rendered through TextRenderer so
     *      every effect — outline, shadow, gradient, texture, perspective,
     *      and per-character spans — is preserved).
     *
     * This is what PNG / JPG / WebP export now uses. PSD export keeps its
     * own "Text + Images" overlay layer for separate visibility in editors.
     */
    private fun buildFlatExportBitmap(ws: Workspace): Bitmap {
        binding.canvasView.syncCanvasStack()
        val output = Bitmap.createBitmap(ws.width, ws.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val pixelPaint = android.graphics.Paint(
            android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG
        )

        binding.canvasView.canvasStack.forEach { item ->
            when (item.type) {
                CanvasStackType.PIXEL -> ws.layers.firstOrNull { it.id == item.id }?.let { layer ->
                    if (!layer.isVisible || layer.bitmap.isRecycled) return@let
                    pixelPaint.alpha = (layer.opacity * 2.55f).roundToInt().coerceIn(0, 255)
                    pixelPaint.xfermode = android.graphics.PorterDuffXfermode(layer.blendMode)
                    canvas.drawBitmap(layer.bitmap, 0f, 0f, pixelPaint)
                    pixelPaint.xfermode = null
                }
                CanvasStackType.IMAGE -> binding.canvasView.imageElements
                    .firstOrNull { it.id == item.id }
                    ?.let { image ->
                        if (image.bitmap.isRecycled) return@let
                        runCatching {
                            val matrix = android.graphics.Matrix()
                            matrix.postScale(
                                image.width / image.bitmap.width.toFloat(),
                                image.height / image.bitmap.height.toFloat()
                            )
                            matrix.postRotate(image.rotation, image.width / 2f, image.height / 2f)
                            matrix.postTranslate(image.x, image.y)
                            canvas.drawBitmap(image.bitmap, matrix, image.createDrawPaint())
                        }
                    }
                CanvasStackType.TEXT -> binding.canvasView.textElements
                    .firstOrNull { it.id == item.id }
                    ?.let { text -> runCatching { TextRenderer.renderToCanvas(canvas, text) } }
            }
        }
        return output
    }

    /** Render a non-active tab without switching the UI or touching CanvasView. */
    private fun buildFlatExportBitmap(
        ws: Workspace,
        texts: List<TextElement>,
        images: List<ImageElement>
    ): Bitmap {
        val output = LayerCompositor.composite(ws.layers, ws.width, ws.height)
        val canvas = android.graphics.Canvas(output)
        images.forEach { image ->
            if (image.bitmap.isRecycled) return@forEach
            runCatching {
                val matrix = android.graphics.Matrix()
                matrix.postScale(
                    image.width / image.bitmap.width.toFloat(),
                    image.height / image.bitmap.height.toFloat()
                )
                matrix.postRotate(image.rotation, image.width / 2f, image.height / 2f)
                matrix.postTranslate(image.x, image.y)
                canvas.drawBitmap(image.bitmap, matrix, image.createDrawPaint())
            }
        }
        texts.forEach { text -> runCatching { TextRenderer.renderToCanvas(canvas, text) } }
        return output
    }

    // ── PDF export launcher ───────────────────────────────────────────────────
    private val pdfExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            exportCurrentPageToPdf(uri)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Compose is intentionally attached after the first Android view frame. Initializing
        // the whole Compose runtime here used to delay the very first editor presentation.
        // The legacy brush controls remain immediately available and are more robust on
        // devices whose Compose renderer fails while the brush panel is first measured.
        binding.root.post {
            if (!isFinishing && !isDestroyed) setupComposeEditorOverlay()
        }
        // Font discovery is demand-driven. The former delayed warm-up decoded every
        // imported font shortly after launch and could overlap Style/Preset preview
        // rendering, producing a large native-memory spike on low-end devices.

        // v5.0 — wire up TextRenderer to decode user-picked texture URIs.
        // Bundled presets are intentionally not installed at startup: they now live
        // in a separate, on-demand browser and cannot delay/OOM the editor launch.
        TextRenderer.appContext = applicationContext

        setupMenuBar()
        setupRightShortcutBar()
        setupSidebarVisibilityControls()
        setupToolBar()
        setupOptionsBar()
        setupBubbleTranslateOptionsBar()
        setupWatermarkOptionsBar()
        setupCanvas()
        setupColorPatches()
        setupBottomScriptPanel()
        setupBottomOcrPanel()
        setupMaskPanel()
        setupUnwatermarkPanel()
        startAutoSave()
        setupCrashAutoSave()
        batchQueueState = BatchRecoveryStore.load(this)

        // Handle intent from HomeActivity
        val openUri  = intent.getStringExtra(HomeActivity.EXTRA_OPEN_URI)
        val newW     = intent.getIntExtra(HomeActivity.EXTRA_NEW_W, -1)
        val newH     = intent.getIntExtra(HomeActivity.EXTRA_NEW_H, -1)
        val newName  = intent.getStringExtra(HomeActivity.EXTRA_NEW_NAME)
        val recId    = intent.getStringExtra(HomeActivity.EXTRA_REC_ID)

        when {
            openUri != null -> {
                lifecycleScope.launch(workspaceLoadErrorHandler) {
                    val pid = recId ?: UUID.randomUUID().toString()
                    currentProjectId = pid

                    // v2.0.2 Fix #1: Try full restore first (edited layers + overlays).
                    // This means a user who paints on layer 0, saves, then reopens the recent
                    // project will see their painted pixels restored — not just text/image overlays.
                    if (recId != null) {
                        val full = withContext(Dispatchers.IO) {
                            WorkspaceSerializer.tryRestoreFull(this@MainActivity, recId)
                        }
                        if (full != null) {
                            // v5.0 — hydrate typefaces from the merged font bank
                            FontResolver.hydrateTextElements(this@MainActivity, full.textElements)
                            val ws = vm.addExistingWorkspace(full.workspace)
                            rememberWorkspaceProjectId(ws, pid)
                            workspaceTextStates[ws.id] = full.textElements.map { it.copy() }.toMutableList()
                            workspaceImageStates[ws.id] = full.imageElements.map { it.copy() }.toMutableList()
                            addTab(ws)
                            saveToHistory(ws, "image", pid, sourceUri = openUri)
                            bindWorkspace(pid)
                            val tCount = full.textElements.size
                            val iCount = full.imageElements.size
                            if (tCount > 0 || iCount > 0)
                                updateStatus("Project dipulihkan — $tCount teks, $iCount gambar")
                            return@launch
                        }
                    }

                    // Fall back to the original source (first open). PSD must use the
                    // dedicated parser; BitmapFactory commonly returns null and previously
                    // caused a misleading blank canvas.
                    val sourceUri = android.net.Uri.parse(openUri)
                    val sourceName = getFileDisplayName(sourceUri)
                        ?: openUri.substringAfterLast('/').substringBeforeLast('.').ifBlank { "image" }
                    if (isPsdUri(sourceUri, sourceName)) {
                        val imported = withContext(Dispatchers.IO) {
                            PsdSerializer.import(this@MainActivity, sourceUri, sourceName)
                        }
                        if (imported == null) {
                            Toast.makeText(
                                this@MainActivity,
                                "PSD tidak dapat dibaca; kanvas kosong tidak dibuat.",
                                Toast.LENGTH_LONG
                            ).show()
                            startActivity(Intent(this@MainActivity, HomeActivity::class.java))
                            finish()
                            return@launch
                        }
                        val ws = vm.addExistingWorkspace(imported.workspace)
                        rememberWorkspaceProjectId(ws, pid)
                        workspaceTextStates[ws.id] = mutableListOf()
                        workspaceImageStates[ws.id] = mutableListOf()
                        addTab(ws)
                        saveToHistory(ws, "psd", pid, sourceUri = openUri)
                        bindWorkspace(pid)
                        updateStatus("PSD dibuka — ${ws.layers.size} layer")
                    } else {
                        val bm = FileManager.loadBitmap(this@MainActivity, sourceUri)
                        if (bm != null) {
                            val ws = vm.openImage(bm, sourceName)
                            rememberWorkspaceProjectId(ws, pid)
                            addTab(ws)
                            saveToHistory(ws, "image", pid, sourceUri = openUri)
                            bindWorkspace(pid)
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "File tidak dapat dibaca; kanvas kosong tidak dibuat.",
                                Toast.LENGTH_LONG
                            ).show()
                            startActivity(Intent(this@MainActivity, HomeActivity::class.java))
                            finish()
                            return@launch
                        }
                    }
                    // Persist permission so Recent Projects can reopen the source file.
                    runCatching {
                        contentResolver.takePersistableUriPermission(
                            sourceUri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
            }
            newW > 0 && newH > 0 -> {
                val pid = recId ?: UUID.randomUUID().toString()
                currentProjectId = pid
                if (recId != null) {
                    lifecycleScope.launch(workspaceLoadErrorHandler) {
                        val full = withContext(Dispatchers.IO) {
                            WorkspaceSerializer.tryRestoreFull(this@MainActivity, recId)
                        }
                        if (full != null) {
                            // v5.0 — hydrate typefaces from the merged font bank
                            FontResolver.hydrateTextElements(this@MainActivity, full.textElements)
                            val ws = vm.addExistingWorkspace(full.workspace)
                            rememberWorkspaceProjectId(ws, pid)
                            workspaceTextStates[ws.id] = full.textElements.map { it.copy() }.toMutableList()
                            workspaceImageStates[ws.id] = full.imageElements.map { it.copy() }.toMutableList()
                            addTab(ws)
                            saveToHistory(ws, "blank", pid)
                            bindWorkspace(pid)
                            return@launch
                        }
                        val ws = vm.createWorkspace(newName ?: "Untitled", newW, newH)
                        rememberWorkspaceProjectId(ws, pid)
                        addTab(ws)
                        saveToHistory(ws, "blank", pid)
                        bindWorkspace(pid, captureCompareBaseline = false)
                    }
                } else {
                    val ws = vm.createWorkspace(newName ?: "Untitled", newW, newH)
                    rememberWorkspaceProjectId(ws, pid)
                    addTab(ws)
                    saveToHistory(ws, "blank", pid)
                    bindWorkspace(pid, captureCompareBaseline = false)
                }
            }
            else -> {
                // Default blank workspace — still needs a project ID for auto-save
                val ws = vm.createWorkspace()
                rememberWorkspaceProjectId(ws, ws.id)
                currentProjectId = ws.id
                addTab(ws)
                saveToHistory(ws, "blank", ws.id)
                bindWorkspace(ws.id, captureCompareBaseline = false)
            }
        }

            binding.root.post { offerBatchRecoveryIfNeeded() }
        } catch (error: Throwable) {
            returnToHome(error)
        }
    }

    private fun returnToHome(error: Throwable) {
        Log.e("VasiliasTyper", "Editor gagal dimulai", error)
        runCatching {
            Toast.makeText(
                applicationContext,
                "Editor gagal dimulai. Kembali ke halaman utama.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(this, HomeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
            )
        }
        finish()
    }

    override fun onPause() {
        super.onPause()
        // Save immediately when the user switches apps or the screen turns off
        triggerAutoSave()
    }

    override fun onStop() {
        super.onStop()
        triggerAutoSave()
    }

    override fun onDestroy() {
          // onPause/onStop already queue autosave on Dispatchers.IO. Never encode
          // layer bitmaps synchronously here: onDestroy runs on the main thread and
          // a large project could otherwise trigger an ANR while the app is closing.
          periodicAutoSaveJob?.cancel()
          autoSaveJob?.cancel()
          super.onDestroy()
          layersDialog?.dismiss()
          watermarkBitmap?.recycle()
          watermarkBitmap = null
          unwatermarkPreviewJob?.cancel()
          if (this::binding.isInitialized) {
              binding.canvasView.clearUnwatermarkPreview()
          }
          clearUnwatermarkSamples()
      }

    // ══════════════════════════════════════════════════════════════════════════
    // BATCH PAGE PROCESSOR + RECOVERY
    // ══════════════════════════════════════════════════════════════════════════

    private fun offerBatchRecoveryIfNeeded() {
        // Batch processing has been replaced by VasType.
        // Keep any recovered queue state dormant so the old flow does not reappear.
        return
    }

    private fun showBatchProcessorDialog() {
        batchDialog?.takeIf { it.isShowing }?.dismiss()
        val state = batchQueueState ?: BatchRecoveryStore.QueueState()
        batchQueueState = state
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val description = TextView(this).apply {
            text = "Impor banyak halaman, lalu jalankan manual: Auto Mask → Navier-Stokes → aksi script. Upload tidak pernah memulai proses otomatis."
            setTextColor(Color.DKGRAY)
        }
        val operation = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Bersihkan saja", "Script Place All", "Script OCR")
            )
            setSelection(
                when (state.operation) {
                    BatchRecoveryStore.OP_PLACE_ALL -> 1
                    BatchRecoveryStore.OP_SCRIPT_OCR -> 2
                    else -> 0
                }
            )
            isEnabled = batchProcessorJob?.isActive != true
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = batchOverallProgress(state)
        }
        val status = TextView(this).apply {
            setPadding(0, dp(8), 0, dp(8))
            text = batchStatusText(state)
            setTextColor(Color.DKGRAY)
        }
        val addPages = Button(this).apply {
            text = "Impor / Ganti Halaman"
            isEnabled = batchProcessorJob?.isActive != true
            setOnClickListener {
                pendingBatchOperation = when (operation.selectedItemPosition) {
                    1 -> BatchRecoveryStore.OP_PLACE_ALL
                    2 -> BatchRecoveryStore.OP_SCRIPT_OCR
                    else -> BatchRecoveryStore.OP_CLEAN_ONLY
                }
                batchPagePickerLauncher.launch(arrayOf("image/*"))
                batchDialog?.dismiss()
            }
        }
        root.addView(description)
        root.addView(TextView(this).apply { text = "Aksi sesudah inpaint" })
        root.addView(operation)
        root.addView(addPages)
        root.addView(progress)
        root.addView(status)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Batch Page Processor")
            .setView(root)
            .setPositiveButton(if (batchProcessorJob?.isActive == true) "Sedang Berjalan" else "Mulai Manual", null)
            .setNeutralButton(if (batchProcessorJob?.isActive == true) "Jeda Aman" else "Hapus Recovery", null)
            .setNegativeButton("Tutup", null)
            .create()
        batchDialog = dialog
        batchStatusView = status
        batchProgressView = progress
        dialog.setOnDismissListener {
            if (batchDialog === dialog) batchDialog = null
            batchStatusView = null
            batchProgressView = null
        }
        dialog.setOnShowListener {
            val start = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            start.isEnabled = batchProcessorJob?.isActive != true && state.pages.isNotEmpty()
            start.setOnClickListener {
                state.operation = when (operation.selectedItemPosition) {
                    1 -> BatchRecoveryStore.OP_PLACE_ALL
                    2 -> BatchRecoveryStore.OP_SCRIPT_OCR
                    else -> BatchRecoveryStore.OP_CLEAN_ONLY
                }
                if (state.operation != BatchRecoveryStore.OP_CLEAN_ONLY && scriptLines.isEmpty()) {
                    Toast.makeText(this, "Muat script terlebih dahulu dari panel Script", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                BatchRecoveryStore.save(this, state)
                startBatchProcessor()
                start.isEnabled = false
                operation.isEnabled = false
                addPages.isEnabled = false
            }
            val neutral = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            neutral.setOnClickListener {
                if (batchProcessorJob?.isActive == true) {
                    batchProcessorJob?.cancel()
                    batchStatusView?.text = "Menjeda setelah checkpoint aman…"
                } else {
                    BatchRecoveryStore.clear(this)
                    batchQueueState = null
                    dialog.dismiss()
                    Toast.makeText(this, "Data recovery batch dihapus", Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
    }

    private fun batchOverallProgress(state: BatchRecoveryStore.QueueState): Int {
        if (state.pages.isEmpty()) return 0
        val completed = state.pages.count { it.status == BatchRecoveryStore.STATUS_COMPLETED }
        return (completed * 100 / state.pages.size).coerceIn(0, 100)
    }

    private fun batchStatusText(state: BatchRecoveryStore.QueueState): String {
        val completed = state.pages.count { it.status == BatchRecoveryStore.STATUS_COMPLETED }
        val failed = state.pages.count { it.status == BatchRecoveryStore.STATUS_FAILED }
        val current = state.pages.firstOrNull {
            it.status != BatchRecoveryStore.STATUS_COMPLETED && it.status != BatchRecoveryStore.STATUS_FAILED
        }
        return buildString {
            append("$completed/${state.pages.size} halaman selesai")
            if (failed > 0) append(" • $failed perlu dicoba ulang")
            if (current != null) append("\nBerikutnya: ${current.displayName} • ${current.status}")
            append("\nCheckpoint tersimpan di penyimpanan internal aplikasi.")
        }
    }

    private fun updateBatchUi(message: String, state: BatchRecoveryStore.QueueState) {
        batchStatusView?.text = "$message\n${batchStatusText(state)}"
        batchProgressView?.progress = batchOverallProgress(state)
        updateStatus(message)
    }

    private fun startBatchProcessor() {
        val state = batchQueueState ?: return
        if (batchProcessorJob?.isActive == true || state.pages.isEmpty()) return
        batchProcessorJob = lifecycleScope.launch {
            state.running = true
            batchAutoSaveSuspended = true
            autoSaveJob?.cancel()
            autoSaveJob = null
            BatchRecoveryStore.save(this@MainActivity, state)
            try {
                val pendingPages = state.pages.filter { it.status != BatchRecoveryStore.STATUS_COMPLETED }
                for ((pendingIndex, page) in pendingPages.withIndex()) {
                    updateBatchUi("Batch ${pendingIndex + 1}/${pendingPages.size}: ${page.displayName}", state)
                    try {
                        processBatchPage(page, state)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        page.status = if (page.checkpointPath != null) {
                            BatchRecoveryStore.STATUS_READY_SCRIPT
                        } else {
                            BatchRecoveryStore.STATUS_FAILED
                        }
                        page.error = error.message ?: error.javaClass.simpleName
                        BatchRecoveryStore.save(this@MainActivity, state)
                        updateBatchUi("Gagal aman: ${page.displayName} • ${page.error}", state)
                    }
                    yield()
                }
            } finally {
                state.running = false
                batchAutoSaveSuspended = false
                activeBatchWorkspaceId = null
                BatchRecoveryStore.save(this@MainActivity, state)
                batchProcessorJob = null
                updateBatchUi(
                    if (state.hasRecoverableWork) "Batch dijeda; recovery siap" else "Batch selesai seluruhnya",
                    state
                )
                batchDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = state.hasRecoverableWork
                batchDialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.text = "Lanjut Manual"
                batchDialog?.getButton(AlertDialog.BUTTON_NEUTRAL)?.text =
                    if (state.hasRecoverableWork) "Hapus Recovery" else "Bersihkan Data"
            }
        }
    }

    private suspend fun processBatchPage(
        page: BatchRecoveryStore.Page,
        state: BatchRecoveryStore.QueueState
    ) {
        val sourceUri = Uri.parse(page.sourceUri)
        val source = FileManager.loadBitmap(this, sourceUri)
            ?: throw IllegalStateException("Gambar sumber tidak dapat dibaca")
        var working: Bitmap? = null
        try {
            val engine = maskEngineCodes.getOrNull(binding.spinnerMaskEngine.selectedItemPosition) ?: "ppocr_small"
            val language = maskLangCodes.getOrNull(binding.spinnerMaskLang.selectedItemPosition) ?: "auto"
            page.status = BatchRecoveryStore.STATUS_MASKING
            page.error = null
            BatchRecoveryStore.save(this, state)
            updateBatchUi("Auto Mask: ${page.displayName}", state)

            val detected = detectMaskRegions(engine, source, language)
            if (detected.error != null && detected.regions.isEmpty()) {
                throw IllegalStateException(detected.error)
            }
            val regions = postProcessMaskRegions(source, detected.regions, engine, emptyList())
                .sortedWith(compareBy<PaddleDbNetDetector.DetectedRegion> { it.rect.top }.thenBy { it.rect.left })
            page.regionCount = regions.size
            if (regions.isEmpty()) throw IllegalStateException("Auto Mask tidak menemukan teks")

            val ocrMatches = if (state.operation == BatchRecoveryStore.OP_SCRIPT_OCR) {
                val ocrRegions = detectScriptRegionsWithTipeR(source)
                ScriptOcrMatcher.match(scriptLines, ocrRegions, minimumScore = 0.68f)
            } else {
                emptyList()
            }

            val activeBitmap = if (page.status == BatchRecoveryStore.STATUS_INPAINTING || page.checkpointPath != null) {
                BatchRecoveryStore.loadCheckpoint(page) ?: source
            } else {
                source
            }
            working = activeBitmap
            page.status = BatchRecoveryStore.STATUS_INPAINTING
            BatchRecoveryStore.save(this, state)

            val checkpointInterval = when {
                regions.size >= 40 -> 4
                regions.size >= 16 -> 3
                else -> 1
            }
            for (index in page.regionCursor until regions.size) {
                val detectedRegion = regions[index]
                val rect = Rect(
                    kotlin.math.floor(detectedRegion.rect.left.toDouble()).toInt().coerceIn(0, activeBitmap.width),
                    kotlin.math.floor(detectedRegion.rect.top.toDouble()).toInt().coerceIn(0, activeBitmap.height),
                    kotlin.math.ceil(detectedRegion.rect.right.toDouble()).toInt().coerceIn(0, activeBitmap.width),
                    kotlin.math.ceil(detectedRegion.rect.bottom.toDouble()).toInt().coerceIn(0, activeBitmap.height)
                )
                if (!rect.isEmpty) {
                    updateBatchUi("RemovR ${index + 1}/${regions.size}: ${page.displayName}", state)
                    val maskRegion = Region(rect)
                    val result = withContext(Dispatchers.Default) {
                        var removRResult = ResynthesizerEngine.healSelection(
                            activeBitmap,
                            maskRegion,
                            ResynthesizerEngine.Params(
                                searchRadius = 220,
                                maxNeighbors = 24,
                                maxRandomCandidates = 80
                            )
                        )
                        if (!removRResult.success) {
                            val fallback = CriminisiEngine.inpaint(activeBitmap, maskRegion)
                            if (fallback.success) {
                                removRResult = ResynthesizerEngine.Result(
                                    true,
                                    "Criminisi fallback",
                                    fallback.processedPixels
                                )
                            }
                        }
                        removRResult
                    }
                    if (!result.success) {
                        throw IllegalStateException("RemovR gagal pada kotak baris ${index + 1}: ${result.message}")
                    }
                }
                val processedCount = index + 1
                val shouldCheckpoint = processedCount == regions.size ||
                    processedCount % checkpointInterval == 0
                if (shouldCheckpoint) {
                    page.regionCursor = processedCount
                    withContext(Dispatchers.IO) {
                        BatchRecoveryStore.saveCheckpoint(this@MainActivity, page, activeBitmap)
                        BatchRecoveryStore.save(this@MainActivity, state)
                    }
                }
                yield()
            }

            page.status = BatchRecoveryStore.STATUS_READY_SCRIPT
            BatchRecoveryStore.save(this, state)
            updateBatchUi("Checkpoint inpaint selesai: ${page.displayName}", state)

            if (activeBitmap === source) working = null
            val cleaned = BatchRecoveryStore.loadCheckpoint(page)
                ?: throw IllegalStateException("Checkpoint hasil inpaint tidak dapat dibuka")
            val projectId = page.projectId ?: "batch_${page.id}"
            val ws = vm.openImage(cleaned, page.displayName.substringBeforeLast('.'))
            page.projectId = projectId
            activeBatchWorkspaceId = ws.id
            rememberWorkspaceProjectId(ws, projectId)
            addTab(ws)
            bindWorkspace(projectId)
            textDetectedRegions.clear()
            textDetectedRegions.addAll(regions)
            binding.canvasView.geminiDetectOverlay = regions
            binding.canvasView.invalidate()

            page.status = BatchRecoveryStore.STATUS_SCRIPTING
            BatchRecoveryStore.save(this, state)
            when (state.operation) {
                BatchRecoveryStore.OP_PLACE_ALL -> {
                    val areas = regions.map { RectF(it.rect) }
                    val count = minOf(areas.size, (scriptLines.size - state.scriptCursor).coerceAtLeast(0))
                    if (count <= 0) throw IllegalStateException("Baris script habis atau belum dimuat")
                    val pairs = (0 until count).map { offset ->
                        val scriptIndex = state.scriptCursor + offset
                        scriptIndex to scriptLines[scriptIndex].text
                    }
                    executeAutoTypeset(
                        areas.take(count),
                        pairs,
                        BubbleTextShaper.Profile.AUTO,
                        1f,
                        clearAdapterSelection = false
                    )
                    scriptPlacementJob?.join()
                    state.scriptCursor += count
                }
                BatchRecoveryStore.OP_SCRIPT_OCR -> {
                    if (ocrMatches.isEmpty()) throw IllegalStateException("Script OCR tidak menemukan pasangan yang cocok")
                    pendingTipeRMatches.clear()
                    pendingTipeRMatches.addAll(ocrMatches)
                    placeAllPendingTipeRMatches(BubbleTextShaper.Profile.AUTO, 1f)
                    scriptPlacementJob?.join()
                }
            }

            val batchTextElements = binding.canvasView.textElements.map { it.copy() }
            val batchImageElements = binding.canvasView.imageElements.map { it.copy() }
            withContext(Dispatchers.IO) {
                autoSaveMutex.withLock {
                    WorkspaceSerializer.autoSave(
                        this@MainActivity,
                        ws,
                        batchTextElements,
                        batchImageElements,
                        projectId
                    )
                }
            }
            saveToHistory(ws, "image", projectId, sourceUri = page.sourceUri)
            page.status = BatchRecoveryStore.STATUS_COMPLETED
            page.error = null
            BatchRecoveryStore.save(this, state)
            updateBatchUi("Selesai: ${page.displayName}", state)
            activeBatchWorkspaceId = null
        } finally {
            if (working != null && working !== source && !working.isRecycled) working.recycle()
            if (!source.isRecycled) source.recycle()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MENU BAR
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupMenuBar() {
        binding.menuFile.setOnClickListener   { showFileMenu(it) }
        binding.menuEdit.setOnClickListener   { showEditMenu(it) }
        binding.menuLayer.setOnClickListener  { showLayersPopup() }
        binding.menuImage.setOnClickListener  { showImageMenu(it) }
        binding.menuFilter.setOnClickListener { showFilterMenu(it) }
        binding.menuView.setOnClickListener   { showViewMenu(it) }
        binding.menuWindow.setOnClickListener { showWindowMenu(it) }
        binding.btnNewTab.setOnClickListener  { showNewWorkspaceDialog() }
    }

    private fun setupSidebarVisibilityControls() {
        // The reconstructed editor uses two horizontal docks instead of edge rails.
        // Old persisted rail visibility must not hide the new primary navigation.
        leftSidebarVisible = true
        rightSidebarVisible = true
        binding.leftToolSidebar.visibility = View.VISIBLE
        binding.rightFeatureSidebar.visibility = View.VISIBLE
        binding.btnToggleLeftSidebar.visibility = View.GONE
        binding.btnToggleRightSidebar.visibility = View.GONE
    }

    private fun setupRightShortcutBar() {
        binding.rightLayersShortcut.setOnClickListener {
            setLayersPanelOpen(!composeLayersOpen.value)
        }
        binding.rightMaskPreset.setOnClickListener { toggleMaskPanel() }
        binding.rightSelectionPreset.setOnClickListener {
            binding.toolRectSelect.performClick()
            updateStatus("Preset Kotak Seleksi aktif — seret pada kanvas")
        }
        binding.rightScriptPreset.setOnClickListener { showScriptPanel() }
        binding.rightStyleRulesShortcut.setOnClickListener { showStyleRulesDialog() }
        binding.rightStyleManagerShortcut.setOnClickListener { showStyleManagerStandalone() }
        binding.rightCompareShortcut.setOnClickListener {
            binding.comparePanel.visibility = View.VISIBLE
            binding.toggleCompare.isChecked = true
            binding.canvasView.compareEnabled = true
            updateStatus("Compare aktif — BEFORE kiri, AFTER kanan")
        }
        binding.toggleCompare.setOnCheckedChangeListener { _, checked ->
            binding.canvasView.compareEnabled = checked
        }
        binding.seekCompareDivider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.canvasView.compareDivider = progress.coerceIn(5, 95) / 100f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.btnCloseCompare.setOnClickListener {
            binding.toggleCompare.isChecked = false
            binding.comparePanel.visibility = View.GONE
        }
    }

    private fun showFileMenu(anchor: View) = popupMenu(anchor,
        "New"            to { showNewWorkspaceDialog() },
        "Open Image…"    to { pickImage() },
        "VasType…" to { showVasTypeDialog() },
        "Open PSD…"      to { psdImportLauncher.launch(arrayOf("image/vnd.adobe.photoshop", "application/x-photoshop", "application/octet-stream", "*/*")) },
        "Load Script…"   to { showScriptPanel(); loadScript() },
        "Export…"        to { showExportDialog() },
        "Export PDF…"    to { launchPdfExport() },
        "Close Tab"      to { confirmCloseAllTabsFromFileMenu() }
    )

    private fun showEditMenu(anchor: View) = popupMenu(anchor,
        "Undo"        to { doUndo() },
        "Redo"        to { doRedo() },
        "Select All"  to { selectAll() },
        "Deselect"    to { binding.canvasView.clearSelection() },
        "Toggle Text Perspective" to { toggleTextPerspective() },
        "Toggle Mesh Form" to { toggleTextMesh() },
        "Multi Replace…" to { showMultiReplaceDialog() }
    )

    private fun doUndo() {
        val ws = vm.activeWorkspace ?: return
        if (binding.canvasView.undoImage()) return
        val snap = workspaceUndoStack.removeLastOrNull()
        if (snap != null) {
            val current = WorkspaceSnapshot(
                workspaceJson = WorkspaceSerializer.serialize(ws).toString(),
                textJson      = WorkspaceSerializer.serializeTextElements(binding.canvasView.textElements.toList()).toString(),
                imageJson     = WorkspaceSerializer.serializeImageElements(binding.canvasView.imageElements.toList()).toString(),
                projectId     = projectIdFor(ws)
            )
            workspaceRedoStack.addLast(current)
            restoreWorkspaceSnapshot(snap)
            return
        }
        if (!binding.canvasView.undoText()) {
            vm.undo(ws)
        }
        binding.canvasView.invalidate()
    }

    private fun doRedo() {
        val ws = vm.activeWorkspace ?: return
        if (binding.canvasView.redoImage()) return
        val snap = workspaceRedoStack.removeLastOrNull()
        if (snap != null) {
            val current = WorkspaceSnapshot(
                workspaceJson = WorkspaceSerializer.serialize(ws).toString(),
                textJson      = WorkspaceSerializer.serializeTextElements(binding.canvasView.textElements.toList()).toString(),
                imageJson     = WorkspaceSerializer.serializeImageElements(binding.canvasView.imageElements.toList()).toString(),
                projectId     = projectIdFor(ws)
            )
            workspaceUndoStack.addLast(current)
            restoreWorkspaceSnapshot(snap)
            return
        }
        if (!binding.canvasView.redoText()) {
            vm.redo(ws)
        }
        binding.canvasView.invalidate()
    }

    private fun showImageMenu(anchor: View) = popupMenu(anchor,
        "Flatten Image" to { flattenImage() }
    )


    private fun toggleMeshForActiveText() {
        binding.canvasView.enableMeshForActiveText()
        binding.canvasView.currentTool = Tool.MESH_FORM
        updateStatus("Mesh form toggled for active text")
    }

    private fun showReferenceWindow() {
        referenceDialog?.takeIf { it.isShowing }?.dismiss()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
        }

        val preview = ImageView(this).apply {
            adjustViewBounds = true
            minimumHeight = 360
            scaleType = ImageView.ScaleType.MATRIX
            setBackgroundColor(Color.parseColor("#141414"))
        }
        val matrix = android.graphics.Matrix()
        var currentScale = 1f
        var previewBitmap: Bitmap? = null

        fun applyScale(scale: Float) {
            val bm = previewBitmap ?: return
            currentScale = scale.coerceIn(0.2f, 6f)
            val px = bm.width / 2f
            val py = bm.height / 2f
            matrix.reset()
            matrix.postScale(currentScale, currentScale, px, py)
            preview.imageMatrix = matrix
        }

        val zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnAdd = Button(this).apply {
            text = "＋ Add"
            setOnClickListener { referenceImagePickerLauncher.launch("image/*") }
        }
        val btnZoomOut = Button(this).apply {
            text = "－"
            setOnClickListener { applyScale(currentScale * 0.85f) }
        }
        val btnZoomIn = Button(this).apply {
            text = "+"
            setOnClickListener { applyScale(currentScale * 1.15f) }
        }
        zoomRow.addView(btnAdd)
        zoomRow.addView(btnZoomOut)
        zoomRow.addView(btnZoomIn)

        val thumbsScroll = HorizontalScrollView(this).apply {
            isFillViewport = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val thumbsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        thumbsScroll.addView(thumbsRow)

        fun reloadPreview(uri: Uri) {
            lifecycleScope.launch {
                val bm = FileManager.loadBitmap(this@MainActivity, uri) ?: return@launch
                val oldBm = previewBitmap
                previewBitmap = bm
                preview.setImageBitmap(bm)
                if (oldBm != null && oldBm !== bm && !oldBm.isRecycled) oldBm.recycle()
                applyScale(1f)
            }
        }

        fun rebuildThumbs() {
            thumbsRow.removeAllViews()
            referenceImages.forEachIndexed { index, uri ->
                val thumb = ImageView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(180, 180).apply { marginEnd = 12 }
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Color.DKGRAY)
                    setPadding(4, 4, 4, 4)
                }
                lifecycleScope.launch {
                    FileManager.loadBitmap(this@MainActivity, uri)?.let { bm -> thumb.setImageBitmap(bm) }
                }
                thumb.setOnClickListener { reloadPreview(uri) }
                thumb.setOnLongClickListener {
                    referenceImages.removeAt(index)
                    rebuildThumbs()
                    if (referenceImages.isEmpty()) preview.setImageDrawable(null) else referenceImages.lastOrNull()?.let { reloadPreview(it) }
                    true
                }
                thumbsRow.addView(thumb)
            }
            if (referenceImages.isNotEmpty() && preview.drawable == null) {
                referenceImages.lastOrNull()?.let { reloadPreview(it) }
            }
        }

        layout.addView(zoomRow)
        layout.addView(thumbsScroll)
        layout.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val dialog = AlertDialog.Builder(this)
            .setTitle("Reference Window")
            .setView(layout)
            .setNegativeButton("Close", null)
            .setNeutralButton("Add Images") { _, _ -> referenceImagePickerLauncher.launch("image/*") }
            .create()

        referenceDialog = dialog
        dialog.setOnDismissListener {
            referenceDialog = null
            previewBitmap?.takeIf { !it.isRecycled }?.recycle()
            previewBitmap = null
        }
        dialog.show()
        rebuildThumbs()
    }

    private fun showActiveImageBlurDialog() {
        val img = binding.canvasView.imageElements.firstOrNull { it.id == binding.canvasView.activeImageId }
        if (img == null) {
            Toast.makeText(this, "Pilih gambar terlebih dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }
        val spinner = Spinner(this)
        val items = listOf("Gaussian", "Motion Horizontal", "Motion Vertical", "Motion (angle)")
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, items)
        spinner.setSelection(when (img.blurType) {
            com.vasiliastyper.model.BlurType.GAUSSIAN -> 0
            com.vasiliastyper.model.BlurType.MOTION_H -> 1
            com.vasiliastyper.model.BlurType.MOTION_V -> 2
            else -> 3
        })
        val radius = SeekBar(this).apply { max = 80; progress = img.blurRadius.toInt().coerceIn(0, 80) }
        val angle = SeekBar(this).apply { max = 360; progress = img.blurMotionAngle.toInt().coerceIn(0, 360) }
        val dist = SeekBar(this).apply { max = 120; progress = img.blurMotionDistance.toInt().coerceIn(0, 120) }
        panel.addView(TextView(this).apply { text = "Type" })
        panel.addView(spinner)
        panel.addView(TextView(this).apply { text = "Radius" })
        panel.addView(radius)
        panel.addView(TextView(this).apply { text = "Angle" })
        panel.addView(angle)
        panel.addView(TextView(this).apply { text = "Distance" })
        panel.addView(dist)
        AlertDialog.Builder(this)
            .setTitle("Image Blur")
            .setView(panel)
            .setPositiveButton("Apply") { _, _ ->
                pushWorkspaceSnapshot()
                when (spinner.selectedItemPosition) {
                    0 -> img.blurType = com.vasiliastyper.model.BlurType.GAUSSIAN
                    1 -> img.blurType = com.vasiliastyper.model.BlurType.MOTION_H
                    2 -> img.blurType = com.vasiliastyper.model.BlurType.MOTION_V
                    else -> img.blurType = com.vasiliastyper.model.BlurType.MOTION
                }
                img.blurRadius = radius.progress.toFloat()
                img.blurMotionAngle = angle.progress.toFloat()
                img.blurMotionDistance = dist.progress.toFloat().coerceAtLeast(1f)
                binding.canvasView.invalidate()
                triggerAutoSave()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFilterMenu(anchor: View) = popupMenu(anchor,
        "Grayscale"     to { applyGrayscale() },
        "Invert Colors" to { applyInvert() }
    )

    private fun showViewMenu(anchor: View) = popupMenu(anchor,
        "Zoom In"       to { binding.canvasView.zoomIn() },
        "Zoom Out"      to { binding.canvasView.zoomOut() },
        "Fit to Screen" to { binding.canvasView.zoomToFit() },
        // v2.0.3: toggle X/Y/Z centre guides + selection crosshair
        (if (binding.canvasView.showGuides) "Hide Guides (X/Y/Z)" else "Show Guides (X/Y/Z)") to {
            binding.canvasView.showGuides = !binding.canvasView.showGuides
            binding.canvasView.invalidate()
            val msg = if (binding.canvasView.showGuides) "Guides ditampilkan" else "Guides disembunyikan"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    )

    private fun showWindowMenu(anchor: View) = popupMenu(anchor,
        (if (composeLayersOpen.value) "Close Layers" else "Layers Panel") to {
            setLayersPanelOpen(!composeLayersOpen.value)
        },
        (if (referenceDialog?.isShowing == true) "Close Reference" else "Reference Window") to {
            if (referenceDialog?.isShowing == true) referenceDialog?.dismiss() else showReferenceWindow()
        },
        (if (binding.bottomScriptPanel.visibility == View.VISIBLE) "Close Script" else "Script Panel") to {
            showScriptPanel()
        },
        "Style Manager"    to { showStyleManagerStandalone() },
        "Style Rules…"     to { showStyleRulesDialog() },
        "AI Chat & Canvas Vision…" to { showAiChatPanel() },
        "Agnes Image 2.1 Flash…" to { showAgnesSettingsDialog() },
        "Gemini API Key…" to { showApiKeyDialog() },
        "Zoom In"          to { binding.canvasView.zoomIn() },
        "Zoom Out"         to { binding.canvasView.zoomOut() },
        "Fit to Screen"    to { binding.canvasView.zoomToFit() }
    )

    /**
     * Opens AI Chat directly as an editor bottom panel. Chat and canvas vision stay in
     * the current workspace instead of navigating away or showing a blocking scope popup.
     */
    private fun showAiChatPanel() {
        aiChatPanelDialog?.takeIf { it.isShowing }?.let { existing ->
            existing.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            return
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun rounded(color: String, radius: Int, stroke: String? = null) = GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), Color.parseColor(it)) }
        }
        fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        fun actionButton(text: String, primary: Boolean = false) = label(
            text,
            11f,
            if (primary) "#FFFFFF" else "#D9CBFF",
            true
        ).apply {
            gravity = Gravity.CENTER
            background = if (primary) {
                GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(Color.parseColor("#6D3DE8"), Color.parseColor("#8B5CF6"))
                ).apply { cornerRadius = dp(14).toFloat() }
            } else {
                rounded("#211E2B", 14, "#413752")
            }
            isClickable = true
            isFocusable = true
            minHeight = dp(42)
        }

        var provider = AiChatProvider.GPT_35
        var attachedBitmap: Bitmap? = null
        val conversation = mutableListOf(
            AiConversationMessage(
                "system",
                "You are an assistant for a professional webtoon and manga typesetting workspace. Be concise and practical."
            )
        )
        val visibleTranscript = mutableListOf<Pair<String, String>>()

        lateinit var messagesContainer: LinearLayout
        lateinit var messagesScroll: ScrollView
        lateinit var promptInput: EditText
        lateinit var sendButton: TextView
        lateinit var visionButton: TextView
        lateinit var modelButton: TextView
        lateinit var selectionButton: TextView
        lateinit var canvasButton: TextView
        lateinit var attachmentStatus: TextView
        lateinit var progress: ProgressBar

        fun copyText(text: String, message: String) {
            if (text.isBlank()) {
                Toast.makeText(this, "Belum ada teks untuk disalin", Toast.LENGTH_SHORT).show()
                return
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("AI Chat VasiliasTyper", text))
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }

        fun copyAllMessages() {
            val text = visibleTranscript.joinToString("\n\n") { (role, content) -> "$role:\n$content" }
            copyText(text, "Semua percakapan disalin")
        }

        fun addMessage(text: String, fromUser: Boolean) {
            val role = if (fromUser) "Anda" else "AI"
            visibleTranscript += role to text
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(12))
                background = rounded(
                    if (fromUser) "#2B2040" else "#17151F",
                    18,
                    if (fromUser) "#5D4485" else "#34303F"
                )
                addView(LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        label(role, 11f, if (fromUser) "#CDBBFF" else "#A78BFA", true),
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(actionButton("▣  Salin pesan").apply {
                        contentDescription = "Salin seluruh pesan dari $role"
                        setOnClickListener { copyText(text, "Pesan disalin") }
                    }, LinearLayout.LayoutParams(dp(92), dp(32)))
                })
                addView(label(text, 14f, "#F4F1FA").apply {
                    setPadding(0, dp(9), 0, dp(4))
                    setLineSpacing(0f, 1.12f)
                    setTextIsSelectable(true)
                    contentDescription =
                        "Pesan dari $role. Tekan lama lalu geser penanda untuk menyalin teks pilihan."
                })
                addView(label("Tekan lama teks untuk memilih bagian tertentu", 9f,
                    if (fromUser) "#9F91BE" else "#797485"))
            }
            messagesContainer.addView(
                card,
                LinearLayout.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.88f).roundToInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = if (fromUser) Gravity.END else Gravity.START
                    topMargin = dp(7)
                }
            )
            messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
        }

        fun setBusy(busy: Boolean) {
            progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
            sendButton.isEnabled = !busy
            modelButton.isEnabled = !busy
            selectionButton.isEnabled = !busy
            canvasButton.isEnabled = !busy
            visionButton.isEnabled = !busy && attachedBitmap?.isRecycled == false
            promptInput.isEnabled = !busy
        }

        fun prepareAttachment(selectionOnly: Boolean) {
            val workspace = vm.activeWorkspace
            if (workspace == null) {
                addMessage("Workspace belum tersedia. Buka atau buat kanvas terlebih dahulu.", false)
                return
            }
            val bounds = binding.canvasView.selection.getBounds()
            if (selectionOnly && (!binding.canvasView.selection.hasSelection() || bounds.isEmpty)) {
                addMessage("Buat area seleksi di kanvas terlebih dahulu, lalu tekan Seleksi lagi.", false)
                return
            }
            setBusy(true)
            attachmentStatus.text = if (selectionOnly) "Menyiapkan area seleksi…" else "Menyiapkan seluruh kanvas…"
            aiChatRequestJob?.cancel()
            aiChatRequestJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val full = buildFlatExportBitmap(workspace)
                        val cropped = if (selectionOnly) {
                            val left = bounds.left.toInt().coerceIn(0, full.width - 1)
                            val top = bounds.top.toInt().coerceIn(0, full.height - 1)
                            val right = bounds.right.toInt().coerceIn(left + 1, full.width)
                            val bottom = bounds.bottom.toInt().coerceIn(top + 1, full.height)
                            Bitmap.createBitmap(full, left, top, right - left, bottom - top).also {
                                if (!full.isRecycled) full.recycle()
                            }
                        } else {
                            full
                        }
                        val maxSide = maxOf(cropped.width, cropped.height)
                        if (maxSide > 1600) {
                            val scale = 1600f / maxSide
                            Bitmap.createScaledBitmap(
                                cropped,
                                (cropped.width * scale).roundToInt().coerceAtLeast(1),
                                (cropped.height * scale).roundToInt().coerceAtLeast(1),
                                true
                            ).also { if (!cropped.isRecycled) cropped.recycle() }
                        } else {
                            cropped
                        }
                    }
                }
                if (aiChatPanelDialog?.isShowing != true) {
                    result.getOrNull()?.takeUnless { it.isRecycled }?.recycle()
                    return@launch
                }
                setBusy(false)
                result.onSuccess { bitmap ->
                    attachedBitmap?.takeUnless { it.isRecycled }?.recycle()
                    attachedBitmap = bitmap
                    attachmentStatus.text = if (selectionOnly) {
                        "Area seleksi terlampir (${bitmap.width} × ${bitmap.height})"
                    } else {
                        "Seluruh kanvas terlampir (${bitmap.width} × ${bitmap.height})"
                    }
                    visionButton.isEnabled = true
                    addMessage("Gambar siap. Tulis pertanyaan lalu tekan Vision.", false)
                }.onFailure { error ->
                    attachmentStatus.text = "Gagal menyiapkan gambar"
                    addMessage("Gagal menyiapkan kanvas: ${error.message ?: "kesalahan tidak diketahui"}", false)
                }
            }
        }

        fun sendRequest(withVision: Boolean) {
            val prompt = promptInput.text.toString().trim()
            if (!withVision && prompt.isBlank()) return
            val bitmapCopy = if (withVision) {
                val source = attachedBitmap
                if (source == null || source.isRecycled) {
                    addMessage("Lampirkan Seleksi atau Kanvas sebelum memakai Vision.", false)
                    return
                }
                source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
            } else {
                null
            }
            val effectivePrompt = prompt.ifBlank {
                "Analisis kanvas webtoon ini: identifikasi teks, bahasa, speech bubble, SFX, tata letak, dan masalah typesetting. Berikan saran praktis."
            }
            promptInput.text.clear()
            addMessage(if (withVision) "[Canvas Vision] $effectivePrompt" else effectivePrompt, true)
            if (!withVision) conversation += AiConversationMessage("user", effectivePrompt)
            setBusy(true)
            aiChatRequestJob?.cancel()
            aiChatRequestJob = lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        if (withVision) {
                            val visionBitmap = requireNotNull(bitmapCopy)
                            try {
                                AiChatClient.understandImage(this@MainActivity, effectivePrompt, visionBitmap)
                            } finally {
                                if (!visionBitmap.isRecycled) visionBitmap.recycle()
                            }
                        } else {
                            AiChatClient.chat(this@MainActivity, provider, conversation)
                        }
                    }
                }
                if (aiChatPanelDialog?.isShowing != true) return@launch
                setBusy(false)
                result.onSuccess { answer ->
                    if (!withVision) conversation += AiConversationMessage("assistant", answer)
                    addMessage(answer, false)
                }.onFailure { error ->
                    addMessage("Permintaan gagal: ${error.message ?: "kesalahan AI tidak diketahui"}", false)
                }
            }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0B12"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (resources.displayMetrics.heightPixels * 0.86f).roundToInt()
            )
        }

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(label("AI Chat &\nCanvas Vision", 21f, "#F7F3FF", true).apply {
                    setLineSpacing(0f, 0.94f)
                })
                addView(label("Chat langsung tanpa meninggalkan editor", 11f, "#9A94A6").apply {
                    setPadding(0, dp(4), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionButton("Salin").apply {
                contentDescription = "Salin seluruh percakapan AI Chat"
                setOnClickListener { copyAllMessages() }
            }, LinearLayout.LayoutParams(dp(64), dp(40)))
            addView(actionButton("Key").apply {
                setOnClickListener { showAiChatKeySettings() }
            }, LinearLayout.LayoutParams(dp(50), dp(40)).apply { marginStart = dp(6) })
            addView(actionButton("×").apply {
                textSize = 18f
                contentDescription = "Tutup AI Chat"
                setOnClickListener { aiChatPanelDialog?.dismiss() }
            }, LinearLayout.LayoutParams(dp(42), dp(40)).apply { marginStart = dp(6) })
        })

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded("#17131F", 17, "#4A3864")
            addView(label("◆  Cara cepat", 12f, "#BCA5FF", true))
            addView(label("1. Pilih model  •  2. Ketik pertanyaan  •  3. Tekan Kirim", 11f, "#F0EBF8").apply {
                setPadding(0, dp(5), 0, 0)
            })
            addView(label("Untuk gambar: lampirkan Area/Kanvas, lalu tekan Vision. Salin pesan dengan tombol; pilih sebagian teks dengan tekan lama.", 10f, "#918A9D").apply {
                setLineSpacing(0f, 1.1f)
                setPadding(0, dp(3), 0, 0)
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(7)
        })

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            modelButton = actionButton("◉  ${provider.displayName}", primary = true).apply {
                setOnClickListener {
                    val options = AiChatProvider.entries.toTypedArray()
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Model chat")
                        .setSingleChoiceItems(options.map { it.displayName }.toTypedArray(), provider.ordinal) { dialog, index ->
                            provider = options[index]
                            modelButton.text = "◉  ${provider.displayName}"
                            dialog.dismiss()
                        }
                        .show()
                }
            }
            addView(modelButton, LinearLayout.LayoutParams(0, dp(42), 1.25f))
            selectionButton = actionButton("▧  Area").apply {
                contentDescription = "Lampirkan area kanvas yang sedang dipilih"
                setOnClickListener { prepareAttachment(selectionOnly = true) }
            }
            addView(selectionButton, LinearLayout.LayoutParams(0, dp(42), 0.8f).apply { marginStart = dp(6) })
            canvasButton = actionButton("▧  Kanvas").apply {
                contentDescription = "Lampirkan seluruh kanvas"
                setOnClickListener { prepareAttachment(selectionOnly = false) }
            }
            addView(canvasButton, LinearLayout.LayoutParams(0, dp(42), 0.8f).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)).apply { topMargin = dp(6) })

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = rounded("#17151D", 14, "#322D3B")
            setPadding(dp(11), dp(5), dp(7), dp(5))
            attachmentStatus = label(
                "▧  Tidak ada gambar terlampir. Chat teks tetap dapat digunakan.",
                10f,
                "#9891A2"
            )
            addView(attachmentStatus, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(actionButton("×").apply {
                textSize = 16f
                contentDescription = "Hapus lampiran kanvas"
                setOnClickListener {
                    attachedBitmap?.takeUnless { it.isRecycled }?.recycle()
                    attachedBitmap = null
                    attachmentStatus.text = "▧  Tidak ada gambar terlampir. Chat teks tetap dapat digunakan."
                    visionButton.isEnabled = false
                }
            }, LinearLayout.LayoutParams(dp(38), dp(34)))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        messagesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(3), dp(2), dp(6))
        }
        messagesScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(messagesContainer)
        }
        root.addView(messagesScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.INVISIBLE
        }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        root.addView(LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            promptInput = EditText(this@MainActivity).apply {
                hint = "Tanya tentang terjemahan, SFX, layout, atau kanvas…"
                setHintTextColor(Color.parseColor("#777382"))
                setTextColor(Color.WHITE)
                textSize = 13f
                minLines = 1
                maxLines = 4
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                background = rounded("#17151D", 18, "#373140")
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            addView(promptInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            visionButton = actionButton("◉  Vision").apply {
                isEnabled = false
                setOnClickListener { sendRequest(withVision = true) }
            }
            addView(visionButton, LinearLayout.LayoutParams(dp(86), dp(50)).apply { marginStart = dp(7) })
            sendButton = actionButton("➤  Kirim", primary = true).apply {
                setOnClickListener { sendRequest(withVision = false) }
            }
            addView(sendButton, LinearLayout.LayoutParams(dp(88), dp(50)).apply { marginStart = dp(7) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7) })

        val dialog = showBottomSheetDialog(draggable = false) { setContentView(root) }
        aiChatPanelDialog = dialog
        dialog.setOnDismissListener {
            aiChatRequestJob?.cancel()
            aiChatRequestJob = null
            attachedBitmap?.takeUnless { it.isRecycled }?.recycle()
            attachedBitmap = null
            if (aiChatPanelDialog === dialog) aiChatPanelDialog = null
        }
        addMessage(
            "AI siap. Chat memakai model yang dipilih. Untuk memahami gambar, lampirkan Seleksi atau Kanvas lalu tekan Vision.",
            false
        )
    }

    private fun showAiChatKeySettings() {
        val keys = AiChatSettings.load(this)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun secret(hint: String, value: String) = EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        val openAi = secret("OpenAI API key", keys.openAi)
        val claude = secret("Anthropic API key", keys.claude)
        val agnes = secret("Agnes API key untuk Vision", keys.agnes)
        panel.addView(openAi)
        panel.addView(claude)
        panel.addView(agnes)
        AlertDialog.Builder(this)
            .setTitle("Koneksi AI")
            .setMessage("API key disimpan di private storage aplikasi dan tidak ditulis ke source code.")
            .setView(panel)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Simpan") { _, _ ->
                AiChatSettings.save(this, openAi.text.toString(), claude.text.toString(), agnes.text.toString())
                Toast.makeText(this, "API key AI disimpan", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun popupMenu(anchor: View, vararg items: Pair<String, () -> Unit>) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun background(color: String, radius: Int, stroke: String? = null) = GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), Color.parseColor(it)) }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            this.background = background("#1A191F", 18, "#393542")
            elevation = dp(18).toFloat()
        }
        val menuName = anchor.contentDescription?.toString()?.takeIf { it.isNotBlank() } ?: "Menu"
        panel.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(2), dp(4), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = menuName
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.parseColor("#A78BFA"))
            }, LinearLayout.LayoutParams(0, dp(30), 1f))
            addView(TextView(this@MainActivity).apply {
                text = "${items.size} aksi"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9895A4"))
                this.background = background("#24212D", 10)
                setPadding(dp(9), 0, dp(9), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)))
        })

        val popup = PopupWindow(
            ScrollView(this).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(panel)
            },
            dp(286),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(20).toFloat()
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }

        items.forEachIndexed { index, (label, action) ->
            if (index > 0 && (label.startsWith("Zoom In") || label.startsWith("Agnes"))) {
                panel.addView(View(this).apply {
                    setBackgroundColor(Color.parseColor("#302E3A"))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(5)
                    bottomMargin = dp(5)
                })
            }
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                this.background = background("#00000000", 12)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    popup.dismiss()
                    action()
                }
                addView(TextView(this@MainActivity).apply {
                    text = label.firstOrNull()?.uppercaseChar()?.toString().orEmpty()
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(Color.parseColor("#C4B5FD"))
                    this.background = background("#2A223A", 10, "#493762")
                }, LinearLayout.LayoutParams(dp(34), dp(34)))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 14f
                    setTextColor(Color.parseColor("#F2F2F4"))
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(6), 0)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, dp(42), 1f))
                addView(TextView(this@MainActivity).apply {
                    text = "›"
                    textSize = 21f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#777281"))
                }, LinearLayout.LayoutParams(dp(24), dp(40)))
            })
        }

        val statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .takeIf { it > 0 }
            ?.let { resources.getDimensionPixelSize(it) }
            ?: 0
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.END, dp(10), statusBarHeight + dp(56))
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WORKSPACE TABS — multi-project, each tab has × close button
    // ══════════════════════════════════════════════════════════════════════════

    private fun addTab(ws: Workspace) {
        val density = resources.displayMetrics.density
        fun px(value: Int) = (value * density).roundToInt()

        val tab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumWidth = px(112)
            setPadding(px(13), 0, px(5), 0)
            tag = ws.id
            isClickable = true
            isFocusable = true
            setOnClickListener { switchToWorkspace(ws.id) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                px(32)
            ).apply {
                marginStart = px(4)
                marginEnd = px(2)
            }
        }

        val label = TextView(this).apply {
            text = ws.name.ifBlank { "Untitled" }
            setTextColor(Color.parseColor("#B9B5C4"))
            textSize = 11.5f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, px(8), 0)
            layoutParams = LinearLayout.LayoutParams(px(126), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val closeBtn = TextView(this).apply {
            text = "×"
            setTextColor(Color.parseColor("#8E8999"))
            textSize = 16f
            gravity = Gravity.CENTER
            contentDescription = "Tutup ${ws.name.ifBlank { "tab" }}"
            setOnClickListener { closeSingleTabById(ws.id) }
            layoutParams = LinearLayout.LayoutParams(px(28), px(28))
        }

        tab.addView(label)
        tab.addView(closeBtn)
        binding.tabsContainer.addView(tab)
        tabViews.add(ws.id to tab)
        highlightTab(ws.id)
    }

    private fun switchToWorkspace(wsId: String) {
        val current = vm.activeWorkspace
        if (current?.id == wsId) return
        snapshotActiveWorkspaceElements()
        snapshotActiveOcrResults()
        snapshotActiveMaskRegions()
        // Detection results are workspace-bound. Cancel scans before switching so
        // a late callback cannot paint one tab's boxes onto another tab.
        bubbleDetectionJob?.cancel()
        maskDetectionJob?.cancel()
        val idx = vm.workspaces.value?.indexOfFirst { it.id == wsId } ?: return
        if (idx < 0) return
        vm.activeWorkspaceIndex.value = idx
        bindWorkspace()
        highlightTab(wsId)
    }

    private fun highlightTab(activeId: String) {
        tabViews.forEach { (id, tab) ->
            val active = id == activeId
            tab.background = workspaceTabBackground(active)
            (tab.getChildAt(0) as? TextView)?.apply {
                setTextColor(Color.parseColor(if (active) "#FFFFFF" else "#AAA6B5"))
                setTypeface(typeface, if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
            (tab.getChildAt(1) as? TextView)?.setTextColor(
                Color.parseColor(if (active) "#E7DEFF" else "#777281")
            )
            tab.alpha = if (active) 1f else 0.9f
        }
    }

    private fun workspaceTabBackground(active: Boolean): GradientDrawable {
        val density = resources.displayMetrics.density
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            if (active) intArrayOf(Color.parseColor("#6646D9"), Color.parseColor("#8B5CF6"))
            else intArrayOf(Color.parseColor("#1C1B22"), Color.parseColor("#24222B"))
        ).apply {
            cornerRadius = 10f * density
            setStroke(
                (if (active) 1.2f else 1f).times(density).roundToInt().coerceAtLeast(1),
                Color.parseColor(if (active) "#B9A6FF" else "#34313D")
            )
        }
    }

    /**
     * Tombol × hanya menutup tab yang ditekan. Perintah menutup seluruh tab
     * sengaja hanya tersedia dari File > Close Tab.
     */
    private fun closeSingleTabById(wsId: String) {
        val workspaces = vm.workspaces.value.orEmpty()
        val closeIndex = workspaces.indexOfFirst { it.id == wsId }
        if (closeIndex < 0) return
        if (workspaces.size == 1) {
            closeAllTabsAndReturnHome()
            return
        }

        val activeIdBeforeClose = vm.activeWorkspace?.id
        if (activeIdBeforeClose == wsId) {
            snapshotActiveWorkspaceElements()
            snapshotActiveOcrResults()
            snapshotActiveMaskRegions()
        }

        tabViews.firstOrNull { it.first == wsId }?.second?.let(binding.tabsContainer::removeView)
        tabViews.removeAll { it.first == wsId }
        workspaceProjectIds.remove(wsId)
        workspaceTextStates.remove(wsId)
        workspaceImageStates.remove(wsId)
        workspaceMaskStates.remove(wsId)
        workspaceOcrStates.remove(wsId)
        vm.closeWorkspace(closeIndex)

        val remaining = vm.workspaces.value.orEmpty()
        val desiredId = activeIdBeforeClose
            ?.takeIf { activeId -> activeId != wsId && remaining.any { it.id == activeId } }
            ?: remaining.getOrNull(closeIndex.coerceAtMost(remaining.lastIndex))?.id
            ?: return
        val desiredIndex = remaining.indexOfFirst { it.id == desiredId }
        if (desiredIndex >= 0) vm.activeWorkspaceIndex.value = desiredIndex

        if (activeIdBeforeClose == wsId) bindWorkspace()
        highlightTab(desiredId)
        triggerAutoSave()
    }

    private fun confirmCloseAllTabsFromFileMenu() {
        if (vm.workspaces.value.orEmpty().isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Tutup semua tab?")
            .setMessage("File > Close Tab akan menutup seluruh tab editor dan kembali ke homepage. Auto-save terakhir tetap tersimpan.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Tutup Semua") { _, _ -> closeAllTabsAndReturnHome() }
            .show()
    }

    private fun closeAllTabsAndReturnHome() {
        bubbleDetectionJob?.cancel()
        maskDetectionJob?.cancel()
        scriptPlacementJob?.cancel()
        snapshotActiveWorkspaceElements()
        snapshotActiveOcrResults()
        snapshotActiveMaskRegions()

        tabViews.forEach { (_, tab) -> binding.tabsContainer.removeView(tab) }
        tabViews.clear()
        workspaceProjectIds.clear()
        workspaceTextStates.clear()
        workspaceImageStates.clear()
        workspaceMaskStates.clear()
        workspaceOcrStates.clear()
        vm.closeAllWorkspaces()

        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    /**
     * Bind the active workspace to the canvas.
     * [restoreProjectId] — if provided, attempt to restore text/image elements
     * from the auto-save file for that project ID.
     */
    private fun bindWorkspace(
        restoreProjectId: String? = null,
        captureCompareBaseline: Boolean = true
    ) {
        val ws = vm.activeWorkspace ?: return
        val pid = restoreProjectId ?: projectIdFor(ws)
        rememberWorkspaceProjectId(ws, pid)
        binding.canvasView.layers           = ws.layers
        binding.canvasView.activeLayerIndex = ws.activeLayerIndex
        binding.canvasView.setCanvasSize(ws.width, ws.height)
        // Imported/restored pages need a BEFORE snapshot. A brand-new blank canvas does
        // not: copying several megapixels of plain white pixels here only delays first open.
        // Its baseline is captured lazily before the first destructive brush operation.
        binding.canvasView.setCompareBaseline(
            if (captureCompareBaseline) ws.layers.firstOrNull()?.bitmap else null
        )
        binding.toggleCompare.isChecked = false
        binding.comparePanel.visibility = View.GONE
        binding.canvasView.textElements.clear()
        binding.canvasView.imageElements.clear()
        binding.canvasView.clearSelection()
        binding.canvasView.activeTextId = null
        binding.canvasView.activeImageId = null
        workspaceTextStates.putIfAbsent(ws.id, mutableListOf())
        workspaceImageStates.putIfAbsent(ws.id, mutableListOf())
        binding.canvasView.post { binding.canvasView.zoomToFit() }
        restoreWorkspaceElements(ws)
        restoreOcrResultsFor(ws)
        restoreMaskRegionsFor(ws)
        binding.canvasView.invalidate()
        updateStatus("Studio siap • ${ws.width}×${ws.height}")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // SCRIPT PANEL
    // ══════════════════════════════════════════════════════════════════════════

    // Fix #10: toggle bottom script panel instead of launching a dialog
    private fun showScriptPanel() {
        val panel = binding.bottomScriptPanel
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
        } else {
            binding.bottomOcrPanel.visibility = View.GONE
            binding.bottomMaskPanel.visibility = View.GONE
            binding.bottomUnwatermarkPanel.visibility = View.GONE
            panel.visibility = View.VISIBLE
        }
    }

    private fun setupBottomScriptPanel() {
        fun refreshMultiSelectionBar() {
            val count = scriptAdapter?.selected?.size ?: 0
            binding.multiSelectBar.visibility = if (count > 0) View.VISIBLE else View.GONE
            binding.tvSelectedCount.text = "$count dipilih"
            binding.btnUseSelected.isEnabled = count > 0
        }

        scriptAdapter = ScriptLineAdapter(
            lines = scriptLines,
            onUse = { line, index ->
                pendingScriptLine = line.text
                val (stripped, autoStyle) = resolveTypeRLine(line.text)
                pendingDisplayText = stripped
                pendingAutoStyle   = autoStyle
                scriptAdapter?.markUsed(index)
                updateScriptProgress()

                val sel = binding.canvasView.selection
                if (sel.isActive) {
                    val area = sel.getAreas().firstOrNull() ?: sel.getBounds()
                    if (pendingAutoStyle != null) {
                        autoPlaceTextWithStyle(area.left, area.top, area.width(), area.height(), padding = 8f)
                    } else {
                        safeShowTextEditorDialogInBounds(area.left, area.top, area.width(), area.height(), padding = 8f)
                    }
                } else {
                    reopenScriptAfterPlace = false
                    val selTool = Tool.RECT_SELECT
                    binding.canvasView.currentTool = selTool
                    vm.currentTool.value = selTool
                    toolButtons.forEach { it.isSelected = false }
                    binding.toolRectSelect.isSelected = true
                    updateOptionsBar(selTool)
                    Toast.makeText(
                        this,
                        "Draw a selection box to place \"${line.text.take(24)}\"",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onEdit = { line, index -> showEditScriptLineDialog(line, index) },
            onToggleSelect = { refreshMultiSelectionBar() }
        )

        binding.scriptLinesList.layoutManager = LinearLayoutManager(this)
        binding.scriptLinesList.adapter       = scriptAdapter

        setupPlaceAllDirectionSpinner()
        binding.tvScriptFileName.text = scriptFileName
        binding.tvScriptSourceHeader.text = scriptSourceLanguage
        binding.scriptColumnsHeader.visibility = if (scriptHasOcrPairs) View.VISIBLE else View.GONE

        // Dua alur terpisah: SCRIPT tetap manual seperti sebelumnya, sedangkan
        // TipeR OCR langsung mendeteksi teks SOURCE yang sama/mirip pada kanvas.
        binding.btnLoadScript.setOnClickListener { chooseScriptInput(ScriptImportMode.SCRIPT_ONLY) }
        binding.btnLoadMultiScript.setOnClickListener { chooseScriptInput(ScriptImportMode.TIPER_AUTO_MATCH) }
        binding.btnStartScriptOcr.setOnClickListener { startScriptOcrMatching() }
        binding.btnStartScriptOcr.isEnabled = scriptHasOcrPairs
        binding.btnSelectAllScript.setOnClickListener {
            scriptAdapter?.clearSelection()
            scriptLines.forEachIndexed { i, line -> if (!line.used) scriptAdapter?.selected?.add(i) }
            scriptAdapter?.multiSelectMode = true
            scriptAdapter?.notifyDataSetChanged()
            refreshMultiSelectionBar()
        }
        binding.btnUseSelected.setOnClickListener {
            if (scriptAdapter?.selected.isNullOrEmpty()) {
                Toast.makeText(this, "Pilih minimal satu baris script", Toast.LENGTH_SHORT).show()
            } else {
                autoTypesetScript()
            }
        }
        binding.btnCancelMulti.setOnClickListener {
            scriptAdapter?.clearSelection()
            refreshMultiSelectionBar()
        }
        binding.btnResetUsed.setOnClickListener {
            scriptAdapter?.resetUsed()
            updateScriptProgress()
        }
        findViewById<Button>(R.id.btnTypeR)?.setOnClickListener { showVasTypeDialog() }
        binding.btnPlaceAll.setOnClickListener {
            if (pendingTipeRMatches.isNotEmpty()) showNextTipeRMatchPreview() else autoTypesetScript()
        }
        binding.btnPlaceAll.visibility = View.VISIBLE
        refreshMultiSelectionBar()
        binding.btnCloseScript.setOnClickListener {
            binding.bottomScriptPanel.visibility = View.GONE
        }

        // Bottom quick buttons are removed: left toolbar buttons are now the single source.
        binding.btnToggleScript.visibility = View.GONE
        binding.btnOcrPanel.visibility = View.GONE
        binding.btnMaskPanel.visibility = View.GONE
    }

    /**
     * Text Shaper selector: profile tabs and a two-column word-wrap preview grid.
     * It is opened only for one already-placed TextElement selected on the canvas.
     */
    private fun showTextShaperPreviewDialog(
        title: String,
        primaryLabel: String,
        applyImmediately: Boolean = false,
        candidateProvider: (BubbleTextShaper.Profile) -> List<BubbleTextShaper.Candidate>,
        onApply: (BubbleTextShaper.Candidate) -> Unit
    ) {
        if (textShaperDialogOpen) return
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).roundToInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.rgb(72, 72, 72))
        }
        val tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tabScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabRow, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        }
        root.addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)))

        val grid = GridLayout(this).apply {
            columnCount = 2
            setPadding(0, dp(4), 0, dp(4))
        }
        val gridScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(grid)
        }
        root.addView(gridScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(440)))

        val hint = TextView(this).apply {
            text = if (applyImmediately) {
                "Tap satu shape untuk langsung menerapkannya ke semua teks."
            } else {
                "Tap shape untuk memilih. Psacs-kp otomatis menyusun jeda baris alami sesuai bentuk bubble."
            }
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }
        root.addView(hint)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val applyButton = Button(this).apply { text = "Apply"; isEnabled = false }
        val primaryButton = Button(this).apply { text = primaryLabel; isEnabled = false }
        val closeButton = Button(this).apply { text = "Close" }
        actionRow.addView(applyButton)
        actionRow.addView(primaryButton)
        actionRow.addView(closeButton)
        root.addView(actionRow)

        val profiles = listOf(
            BubbleTextShaper.Profile.BALANCED,
            BubbleTextShaper.Profile.ROUND,
            BubbleTextShaper.Profile.TALL,
            BubbleTextShaper.Profile.WIDE,
            BubbleTextShaper.Profile.HYPHENATION
        )
        val tabButtons = mutableMapOf<BubbleTextShaper.Profile, Button>()
        var activeProfile = BubbleTextShaper.Profile.BALANCED
        var selectedCandidate: BubbleTextShaper.Candidate? = null
        var selectedCard: TextView? = null
        lateinit var dialog: AlertDialog

        fun cardBackground(selected: Boolean) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(5).toFloat()
            setColor(if (selected) Color.rgb(58, 74, 86) else Color.rgb(55, 55, 55))
            setStroke(dp(if (selected) 2 else 1), if (selected) Color.rgb(42, 145, 220) else Color.rgb(78, 78, 78))
        }

        fun refreshTabs() {
            tabButtons.forEach { (profile, button) ->
                button.setBackgroundColor(
                    if (profile == activeProfile) Color.rgb(54, 116, 154) else Color.rgb(66, 66, 66)
                )
                button.setTextColor(Color.WHITE)
            }
        }

        fun render(profile: BubbleTextShaper.Profile) {
            activeProfile = profile
            selectedCandidate = null
            selectedCard = null
            applyButton.isEnabled = false
            primaryButton.isEnabled = false
            grid.removeAllViews()
            val candidates = candidateProvider(profile).take(10)
            candidates.forEachIndexed { index, candidate ->
                val card = TextView(this).apply {
                    text = "${index + 1} • Area ${(candidate.widthScale * 100f).roundToInt()}%\n${candidate.text}"
                    setTextColor(Color.WHITE)
                    textSize = 12f
                    gravity = Gravity.CENTER
                    val shapeInset = dp(((1f - candidate.widthScale) * 42f).roundToInt())
                    setPadding(dp(8) + shapeInset, dp(4), dp(8) + shapeInset, dp(4))
                    background = cardBackground(false)
                    setOnClickListener {
                        selectedCard?.background = cardBackground(false)
                        selectedCard = this
                        selectedCandidate = candidate
                        background = cardBackground(true)
                        applyButton.isEnabled = true
                        primaryButton.isEnabled = true
                        if (applyImmediately) {
                            post {
                                dialog.dismiss()
                                onApply(candidate)
                            }
                        }
                    }
                }
                val params = GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(84)
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                }
                grid.addView(card, params)
            }
            hint.text = if (candidates.isEmpty()) {
                "Tidak ada shape untuk profil ${profile.label}."
            } else {
                "${candidates.size} preview ${profile.label} • tap shape untuk menguji hasil."
            }
            refreshTabs()
        }

        profiles.forEach { profile ->
            val button = Button(this).apply {
                text = profile.label
                textSize = 10f
                minWidth = dp(76)
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { render(profile) }
            }
            tabButtons[profile] = button
            tabRow.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        }

        textShaperDialogOpen = true
        dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(root)
            .create()
        fun applySelection() {
            selectedCandidate?.let {
                dialog.dismiss()
                onApply(it)
            }
        }
        applyButton.setOnClickListener { applySelection() }
        primaryButton.setOnClickListener { applySelection() }
        closeButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            textShaperDialogOpen = false
            if (binding.canvasView.currentTool == Tool.TEXT_SHAPER) {
                binding.canvasView.clearSelection()
                textShaperSelectionHandled = false
            }
        }
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        render(activeProfile)
        dialog.show()
    }

    /**
     * Resolves a rectangle against every already-placed TextElement on the canvas.
     * The Text Shaper is intentionally post-placement: it never creates text and
     * never applies one choice to a batch. Only the explicitly chosen element is
     * updated, while its content and styling remain intact.
     */
    private fun showTextShaperForPlacedSelection(selectionArea: RectF) {
        val canvas = binding.canvasView
        val hits = canvas.textElements.withIndex()
            .filter { (_, element) ->
                RectF.intersects(
                    selectionArea,
                    RectF(element.x, element.y, element.x + element.width, element.y + element.height)
                )
            }
            .sortedByDescending { it.index }
            .map { it.value }

        if (hits.isEmpty()) {
            Toast.makeText(this, "Kotak seleksi tidak mengenai text layer", Toast.LENGTH_SHORT).show()
            canvas.clearSelection()
            textShaperSelectionHandled = false
            return
        }

        fun layerLabel(element: TextElement): String {
            val layer = canvas.layers.firstOrNull { it.id == element.layerId }
            return layer?.name ?: "Text layer"
        }

        fun openFor(element: TextElement) {
            val layer = canvas.layers.firstOrNull { it.id == element.layerId }
            if (layer?.isLocked == true) {
                Toast.makeText(this, "${layer.name} terkunci — buka lock sebelum shaping", Toast.LENGTH_LONG).show()
                canvas.clearSelection()
                textShaperSelectionHandled = false
                return
            }
            if (element.meshPoints != null || element.perspCorners != null) {
                Toast.makeText(
                    this,
                    "Text dengan Mesh/Perspective harus dikembalikan ke bentuk normal sebelum word wrap",
                    Toast.LENGTH_LONG
                ).show()
                canvas.clearSelection()
                textShaperSelectionHandled = false
                return
            }

            val localArea = RectF(0f, 0f, element.width.coerceAtLeast(10f), element.height.coerceAtLeast(10f))
            showTextShaperPreviewDialog(
                title = "Text Shaper • ${layerLabel(element)}",
                primaryLabel = "Apply",
                candidateProvider = { profile ->
                    BubbleTextShaper.generate(
                        element.text,
                        localArea,
                        element.typeface,
                        element.fontSize.coerceAtLeast(8f),
                        profile,
                        limit = 10
                    )
                }
            ) { chosen ->
                pushWorkspaceSnapshot()
                canvas.pushTextHistory()
                val oldCenterX = element.x + element.width / 2f
                val newWidth = (localArea.width() * chosen.widthScale).coerceAtLeast(10f)
                element.x = (oldCenterX - newWidth / 2f).coerceIn(
                    0f,
                    ((vm.activeWorkspace?.width ?: (element.x + element.width).roundToInt()).toFloat() - newWidth)
                        .coerceAtLeast(0f)
                )
                element.width = newWidth
                // Candidate text contains silhouette-aware explicit line breaks.
                // No words or punctuation are removed; only visual wrapping changes.
                element.text = chosen.text
                element.fontSize = chosen.fittedFontSize.coerceAtLeast(8f)
                canvas.activeTextId = element.id
                updateTextQuickToolbar(element)
                canvas.invalidate()
                triggerAutoSave()
                updateStatus(
                    "Text Shaper diterapkan ke 1 layer • ${chosen.profile.label} • " +
                        "wrap ${chosen.lineCount} baris • area ${(chosen.widthScale * 100f).roundToInt()}%"
                )
            }
        }

        if (hits.size == 1) {
            openFor(hits.first())
            return
        }

        val labels = hits.mapIndexed { index, element ->
            val preview = element.text.replace(Regex("\\s+"), " ").trim().take(42)
            "${index + 1}. ${layerLabel(element)} — $preview"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Pilih satu text layer")
            .setItems(labels) { _, index -> openFor(hits[index]) }
            .setNegativeButton("Batal", null)
            .setOnDismissListener {
                if (!textShaperDialogOpen) {
                    canvas.clearSelection()
                    textShaperSelectionHandled = false
                }
            }
            .show()
    }

    private fun loadScript(mode: ScriptImportMode = ScriptImportMode.SCRIPT_ONLY) {
        pendingScriptImportMode = mode
        scriptPickerLauncher.launch("*/*")
    }

    private fun chooseScriptInput(mode: ScriptImportMode) {
        val title = if (mode == ScriptImportMode.SCRIPT_ONLY) "Input Script" else "Input TipeR / Script OCR"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(arrayOf("Ketik / edit / paste manual", "Load file TXT / DOCX")) { _, which ->
                if (which == 0) showManualScriptDialog(mode) else loadScript(mode)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun createScriptEditor(hintText: String, initialText: String): EditText = EditText(this).apply {
        hint = hintText
        setText(initialText)
        minLines = 7
        gravity = Gravity.TOP or Gravity.START
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setTextColor(Color.parseColor("#EEEEEE"))
        setHintTextColor(Color.parseColor("#777777"))
        setBackgroundColor(Color.parseColor("#242424"))
        setPadding(20, 16, 20, 16)
    }

    private fun manualLines(value: CharSequence): List<String> = value.toString()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toList()

    private fun showManualScriptDialog(mode: ScriptImportMode) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
        }
        val sourceEditor: EditText?
        val translationEditor: EditText
        if (mode == ScriptImportMode.TIPER_AUTO_MATCH) {
            container.addView(TextView(this).apply {
                text = "SOURCE / OCR — satu bubble per baris"
                setTextColor(Color.parseColor("#AFC8E8"))
            })
            sourceEditor = createScriptEditor(
                "Teks source hasil OCR atau ketik/paste manual…",
                scriptLines.mapNotNull { it.sourceText }.joinToString("\n")
            )
            container.addView(sourceEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val importOcrButton = Button(this).apply { text = "Ambil hasil dari panel OCR" }
            container.addView(importOcrButton)
            container.addView(TextView(this).apply {
                text = "TRANSLATION — dapat diketik, diedit, atau paste manual"
                setTextColor(Color.WHITE)
            })
            translationEditor = createScriptEditor(
                "Terjemahan baris 1\nTerjemahan baris 2…",
                scriptLines.joinToString("\n") { it.text }
            )
            container.addView(translationEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            importOcrButton.setOnClickListener {
                if (ocrResults.isEmpty()) {
                    Toast.makeText(this, "Panel OCR belum memiliki hasil", Toast.LENGTH_SHORT).show()
                } else {
                    sourceEditor.setText(ocrResults.joinToString("\n") { it.originalText })
                    translationEditor.setText(ocrResults.joinToString("\n") {
                        it.translatedText?.takeIf(String::isNotBlank).orEmpty()
                    })
                }
            }
        } else {
            sourceEditor = null
            translationEditor = createScriptEditor(
                "Satu dialog per baris. Ketik, edit, atau paste script di sini…",
                scriptLines.joinToString("\n") { it.text }
            )
            container.addView(translationEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 480))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (mode == ScriptImportMode.SCRIPT_ONLY) "Script Manual" else "TipeR / Script OCR Manual")
            .setView(container)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val translations = manualLines(translationEditor.text)
                val loaded = if (mode == ScriptImportMode.SCRIPT_ONLY) {
                    translations.map { ScriptLine(it) }
                } else {
                    val sources = manualLines(sourceEditor?.text?.toString().orEmpty())
                    if (sources.isEmpty()) {
                        Toast.makeText(this, "SOURCE / OCR tidak boleh kosong", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    sources.mapIndexed { index, source ->
                        ScriptLine(
                            text = translations.getOrNull(index)?.takeIf { it.isNotBlank() } ?: source,
                            sourceText = source
                        )
                    }
                }
                if (loaded.isEmpty()) {
                    Toast.makeText(this, "Script masih kosong", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                applyScriptLines(
                    loaded,
                    if (mode == ScriptImportMode.SCRIPT_ONLY) "Script manual" else "TipeR OCR manual",
                    mode == ScriptImportMode.TIPER_AUTO_MATCH
                )
                dialog.dismiss()
                if (mode == ScriptImportMode.TIPER_AUTO_MATCH) {
                    binding.tvScriptProgress.text =
                        "${loaded.size} pasangan siap • tekan Mulai Script OCR"
                }
            }
        }
        dialog.show()
    }

    private fun applyScriptLines(lines: List<ScriptLine>, label: String, hasOcrPairs: Boolean) {
        pendingTipeRMatches.clear()
        binding.btnPlaceAll.text = "Place All ▶"
        scriptLines.clear()
        scriptLines.addAll(lines)
        scriptFileName = label
        scriptHasOcrPairs = hasOcrPairs
        scriptSourceLanguageCode = if (hasOcrPairs) detectImportedSourceLanguageCode(lines) else MlKitOcrEngine.LANG_AUTO
        scriptSourceLanguage = if (hasOcrPairs) sourceLanguageLabel(scriptSourceLanguageCode) else "Source"
        binding.editScriptStartLine.setText("1")
        refreshScriptPanel()
        binding.btnStartScriptOcr.isEnabled = hasOcrPairs
    }

    private fun showEditScriptLineDialog(line: ScriptLine, index: Int) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 20, 36, 8)
        }
        val sourceEditor = if (scriptHasOcrPairs || line.sourceText != null) {
            createScriptEditor("Source / OCR", line.sourceText.orEmpty()).also {
                it.minLines = 3
                container.addView(TextView(this).apply { text = "SOURCE / OCR" })
                container.addView(it)
            }
        } else null
        container.addView(TextView(this).apply { text = "SCRIPT / TRANSLATION" })
        val translationEditor = createScriptEditor("Script / translation", line.text).apply { minLines = 3 }
        container.addView(translationEditor)
        val dialog = AlertDialog.Builder(this)
            .setTitle("Edit baris ${index + 1}")
            .setView(container)
            .setPositiveButton("Simpan", null)
            .setNegativeButton("Batal", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val translation = translationEditor.text.toString().trim()
                if (translation.isEmpty()) {
                    Toast.makeText(this, "Teks translation/script tidak boleh kosong", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                line.text = translation
                line.sourceText = sourceEditor?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                line.used = false
                scriptHasOcrPairs = scriptLines.any { !it.sourceText.isNullOrBlank() }
                refreshScriptPanel()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    // v2.0: Load multiple scripts at once
    private fun loadMultiScript() {
        multiScriptPickerLauncher.launch("*/*")
    }

    private fun refreshScriptPanel() {
        binding.tvScriptFileName.text = scriptFileName
        binding.tvScriptSourceHeader.text = scriptSourceLanguage
        binding.scriptColumnsHeader.visibility = if (scriptHasOcrPairs) View.VISIBLE else View.GONE
        binding.btnStartScriptOcr.isEnabled = scriptHasOcrPairs && scriptOcrMatchJob?.isActive != true
        scriptAdapter?.notifyDataSetChanged()
        updateScriptProgress()
    }

    /**
     * Explicit start point for Script OCR. Imported lines stay available in memory,
     * and their used flags are reset so the same file can be matched repeatedly.
     */
    private fun startScriptOcrMatching() {
        if (!scriptHasOcrPairs || scriptLines.none { !it.sourceText.isNullOrBlank() }) {
            Toast.makeText(this, "Muat file TipeR dengan bagian OCR dan TRANSLATE terlebih dahulu", Toast.LENGTH_LONG).show()
            return
        }
        if (vm.activeWorkspace == null) {
            Toast.makeText(this, "Buka gambar kanvas sebelum memulai Script OCR", Toast.LENGTH_LONG).show()
            return
        }
        pendingTipeRMatches.clear()
        scriptLines.forEach { it.used = false }
        scriptAdapter?.clearSelection()
        scriptAdapter?.notifyDataSetChanged()
        binding.btnPlaceAll.text = "Place All ▶"
        binding.btnStartScriptOcr.isEnabled = false
        detectMatchAndPrepareStructuredScript()
    }

    private fun detectImportedSourceLanguageCode(lines: List<ScriptLine>): String {
        val sample = lines.mapNotNull { it.sourceText }.joinToString("").take(500)
        if (sample.isBlank()) return MlKitOcrEngine.LANG_AUTO
        return when {
            sample.any { it.code in 0xAC00..0xD7AF } -> MlKitOcrEngine.LANG_KO
            sample.any { it.code in 0x3040..0x30FF } ||
                sample.any { it.code in 0x4E00..0x9FFF } -> MlKitOcrEngine.LANG_ZH
            sample.count { it.isLetter() && it.code < 0x0250 } >= sample.length / 3 -> MlKitOcrEngine.LANG_EN
            else -> MlKitOcrEngine.LANG_AUTO
        }
    }

    private fun sourceLanguageLabel(code: String): String = when (code) {
        MlKitOcrEngine.LANG_ZH -> "Chinese/Japanese (source)"
        MlKitOcrEngine.LANG_KO -> "Korean (source)"
        MlKitOcrEngine.LANG_EN -> "Latin (source)"
        else -> "Auto (source)"
    }

    private fun detectMatchAndPrepareStructuredScript() {
        val ws = vm.activeWorkspace
        if (ws == null) {
            Toast.makeText(this, "Buka gambar kanvas sebelum menjalankan TipeR", Toast.LENGTH_LONG).show()
            return
        }

        scriptOcrMatchJob?.cancel()
        scriptOcrMatchJob = lifecycleScope.launch {
            binding.tvScriptProgress.text = "TipeR OCR: mendeteksi teks source secara otomatis…"

            // Mengakses CanvasView harus tetap di main thread. Fallback compositor yang
            // berat saja yang dijalankan di dispatcher IO.
            val canvasComposite = binding.canvasView.compositeVisibleLayers()
            val composite = canvasComposite ?: withContext(Dispatchers.IO) {
                LayerCompositor.composite(ws.layers, ws.width, ws.height)
            }
            try {
                val detected = detectScriptRegionsWithTipeR(composite)
                if (detected.isEmpty()) {
                    binding.tvScriptProgress.text = "OCR tidak menemukan region teks"
                    Toast.makeText(this@MainActivity, "OCR tidak menemukan region teks pada kanvas", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val matches = ScriptOcrMatcher.match(scriptLines, detected, minimumScore = 0.68f)
                if (matches.isEmpty()) {
                    binding.tvScriptProgress.text = "Tidak ada teks OCR yang cukup mirip dengan script"
                    Toast.makeText(this@MainActivity, "Tidak ada OCR yang cocok dengan bagian OCR pada file", Toast.LENGTH_LONG).show()
                    return@launch
                }

                showMatchedScriptRegions(matches)
                prepareMatchedScriptTranslations(matches)
            } catch (error: Exception) {
                val message = error.message ?: "OCR gagal"
                binding.tvScriptProgress.text = message
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                if (!composite.isRecycled) composite.recycle()
                binding.btnStartScriptOcr.isEnabled = scriptHasOcrPairs
            }
        }
    }

    private suspend fun detectScriptRegionsWithMlKit(bitmap: Bitmap): List<ScriptOcrMatcher.OcrRegion> =
        suspendCoroutine { continuation ->
            MlKitMaskDetector.detect(bitmap, scriptSourceLanguageCode, object : MlKitMaskDetector.DetectCallback {
                override fun onSuccess(regions: List<MlKitMaskDetector.DetectedRegion>) {
                    continuation.resume(regions.map { ScriptOcrMatcher.OcrRegion(RectF(it.rect), it.text) })
                }

                override fun onFailure(error: String) {
                    continuation.resume(emptyList())
                }
            })
        }

    /**
     * Pipeline TipeR ringan: satu kali OCR ML Kit menjadi sumber teks utama.
     * Bubble detector hanya dipakai sebagai koreksi geometri terhadap block OCR,
     * sehingga tidak ada OCR/model berat kedua dan hasil tetap mengikuti bubble.
     */
    private suspend fun detectScriptRegionsWithTipeR(
        bitmap: Bitmap,
        fallbackSourceTexts: List<String> = emptyList()
    ): List<ScriptOcrMatcher.OcrRegion> {
        val ocr = detectScriptRegionsWithMlKit(bitmap)
        // Bubble utama: YOLOv8m (Drive user, dibundle saat build). Grid tiling
        // 1200/300 membuat 720x16000+ hanya menambah jumlah tile (±18 tile
        // untuk 16000px) sehingga timeout 120 dtk aman untuk HP low-end.
        // Bila model tidak ada/gagal/timeout, fallback ke OpenCV lokal.
        val bubbles: List<RectF> = withTimeoutOrNull(120_000L) {
            withContext(Dispatchers.Default) {
                runCatching {
                    YoloV8mBubbleDetector.detect(this@MainActivity, bitmap).map { it.rect }
                }.getOrNull()?.takeIf { it.isNotEmpty() }
                    ?: BubbleDetector.detect(bitmap).take(160)
            }
        } ?: emptyList()

        // Stylised or vertical CJK can yield valid bubble geometry while ML Kit
        // returns no characters at all. For VasType only, preserve progress by
        // assigning imported source lines to bubbles in page-reading order. The
        // normal script OCR path leaves [fallbackSourceTexts] empty and therefore
        // never makes this positional assumption.
        if (ocr.isEmpty() && fallbackSourceTexts.isNotEmpty() && bubbles.isNotEmpty()) {
            val orderedBubbles = bubbles.sortedWith(compareBy<RectF> { it.top }.thenBy { it.left })
            return orderedBubbles.take(fallbackSourceTexts.size).mapIndexed { index, bubble ->
                ScriptOcrMatcher.OcrRegion(
                    rect = RectF(bubble),
                    text = fallbackSourceTexts[index],
                    placementRect = RectF(bubble)
                )
            }
        }
        if (ocr.isEmpty() || bubbles.isEmpty()) return ocr

        return ocr.map { region ->
            val centerX = region.rect.centerX()
            val centerY = region.rect.centerY()
            val containing = bubbles
                .asSequence()
                .filter { it.contains(centerX, centerY) }
                .filter { bubble ->
                    bubble.width() <= region.rect.width().coerceAtLeast(1f) * 8f &&
                        bubble.height() <= region.rect.height().coerceAtLeast(1f) * 8f
                }
                .minByOrNull { it.width() * it.height() }
            if (containing == null) region else region.copy(placementRect = RectF(containing))
        }
    }

    private fun showMatchedScriptRegions(matches: List<ScriptOcrMatcher.Match>) {
        textDetectedRegions.clear()
        textDetectedRegions.addAll(matches.map { match ->
            PaddleDbNetDetector.DetectedRegion(
                rect = expandScriptMatchRegion(match.rect),
                type = "script_match",
                text = "${match.detectedText} → ${match.translationText}"
            )
        })
        binding.canvasView.geminiDetectOverlay = textDetectedRegions.toList()
        binding.canvasView.invalidate()
        setMaskActionsEnabled(textDetectedRegions.isNotEmpty())
    }

    private fun expandScriptMatchRegion(rect: RectF): RectF {
        val ws = vm.activeWorkspace ?: return RectF(rect)
        val padX = maxOf(8f, rect.width() * 0.18f)
        val padY = maxOf(8f, rect.height() * 0.35f)
        return RectF(
            (rect.left - padX).coerceAtLeast(0f),
            (rect.top - padY).coerceAtLeast(0f),
            (rect.right + padX).coerceAtMost(ws.width.toFloat()),
            (rect.bottom + padY).coerceAtMost(ws.height.toFloat())
        )
    }

    /**
     * Queue OCR matches for explicit placement. Detection/import only prepares the
     * queue; no TextElement is created until the user explicitly presses Place.
     */
    private fun prepareMatchedScriptTranslations(matches: List<ScriptOcrMatcher.Match>) {
        pendingTipeRMatches.clear()
        pendingTipeRMatches.addAll(
            matches.sortedWith(compareBy<ScriptOcrMatcher.Match> { it.rect.top }.thenBy { it.rect.left })
        )
        binding.btnPlaceAll.text = "Place (${pendingTipeRMatches.size})"
        binding.tvScriptProgress.text =
            "${pendingTipeRMatches.size} hasil OCR siap • tekan Place untuk menempatkan tanpa shaping"
    }

    private fun showNextTipeRMatchPreview() {
        if (pendingTipeRMatches.isEmpty()) {
            binding.btnPlaceAll.text = "Place All ▶"
            refreshScriptPanel()
            return
        }
        placeAllPendingTipeRMatches(BubbleTextShaper.Profile.AUTO, 1f)
    }

    /**
     * History, redraw, autosave, style loading and font fitting are batched so
     * placement remains responsive even when a page contains many bubbles. No
     * shaping is performed here; users shape one placed text layer at a time.
     */
    private fun placeAllPendingTipeRMatches(
        profile: BubbleTextShaper.Profile,
        widthScale: Float
    ) {
        if (pendingTipeRMatches.isEmpty() || vm.activeWorkspace == null) return
        val matches = pendingTipeRMatches.toList()
        scriptPlacementJob?.cancel()
        scriptPlacementJob = lifecycleScope.launch {
            binding.btnPlaceAll.isEnabled = false
            binding.btnStartScriptOcr.isEnabled = false
            val rules = StyleManager.loadStyleRules(this@MainActivity)
            val styles = StyleManager.loadStyles(this@MainActivity)
            // v11.2 fix: buildFontList() on a cold cache recursively scans assets/fonts
            // (+font) and every user-imported custom font, decoding each Typeface
            // synchronously. Running it on lifecycleScope's default (Main) dispatcher
            // blocked the UI here exactly like the Quick Style bug fixed in v11.1 —
            // this "Place All" path had the same gap. Moved off the main thread.
            val fontLookup = withContext(Dispatchers.Default) {
                buildFontList()
            }.associate { it.displayName.lowercase() to it.typeface }
            pushWorkspaceSnapshot()
            binding.canvasView.pushTextHistory()

            var placed = 0
            var failure: String? = null
            val contrastSource = binding.canvasView.compositeVisibleLayers()
            try {
                withPlacementBatchSuspend {
                    for (match in matches) {
                        val matchArea = expandScriptMatchRegion(match.rect)
                        val (displayText, matchedStyle) = resolveTypeRLine(match.translationText, rules, styles)
                        prepareAndAddTextElement(
                            area = matchArea,
                            text = displayText,
                            style = matchedStyle,
                            padding = 8f,
                            shapeProfile = profile,
                            explicitWidthScale = widthScale,
                            fontLookup = fontLookup,
                            sourceBitmap = contrastSource
                        )
                        scriptLines.getOrNull(match.scriptIndex)?.used = true
                        pendingTipeRMatches.removeFirstOrNull()
                        placed++

                        // Beri kesempatan main looper memproses sentuhan/drawing di
                        // antara setiap teks agar aplikasi tidak terlihat hang.
                        binding.tvScriptProgress.text = "Menempatkan OCR $placed / ${matches.size}…"
                        binding.btnPlaceAll.text = "$placed / ${matches.size}"
                        if (placed % 3 == 0) {
                            binding.canvasView.invalidate()
                            delay(8)
                        } else {
                            yield()
                        }
                    }
                }
            } catch (error: Throwable) {
                failure = if (error is CancellationException) "dibatalkan" else error.message ?: "penempatan dihentikan"
            } finally {
                contrastSource?.takeUnless { it.isRecycled }?.recycle()
                binding.canvasView.clearSelection()
                binding.canvasView.invalidate()
                if (placed > 0) triggerAutoSave()
                refreshScriptPanel()
                binding.btnPlaceAll.isEnabled = true
                binding.btnStartScriptOcr.isEnabled = scriptHasOcrPairs
                binding.btnPlaceAll.text = if (pendingTipeRMatches.isEmpty()) {
                    "Place All ▶"
                } else {
                    "Place (${pendingTipeRMatches.size})"
                }
            }

            binding.tvScriptProgress.text = if (failure == null) {
                "Semua $placed hasil OCR sudah ditempatkan"
            } else {
                "$placed teks ditempatkan; gagal melanjutkan: $failure"
            }
            Toast.makeText(
                this@MainActivity,
                if (failure == null) "$placed teks OCR ditempatkan dengan shape ${profile.label}"
                else "Penempatan berhenti aman setelah $placed teks: $failure",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateScriptProgress() {
        val total = scriptLines.size
        val used  = scriptLines.count { it.used }
        binding.tvScriptProgress.text = if (total > 0) "$used / $total lines used" else ""
    }

    private fun getScriptStartIndex(): Int {
        val total = scriptLines.size
        if (total <= 0) return 0
        val raw = binding.editScriptStartLine.text?.toString()?.trim()?.toIntOrNull() ?: 1
        return (raw.coerceAtLeast(1) - 1).coerceIn(0, total - 1)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TYPE-R — batch text input: type all bubble texts at once, placed in order
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Resolves a Type-R queue line using one cached rules/styles pass.
     * This avoids reloading the same SharedPreferences data more than once.
     */
    private fun resolveTypeRLine(line: String): Pair<String, com.vasiliastyper.model.TextStyle?> {
        val rules = com.vasiliastyper.engine.StyleManager.loadStyleRules(this)
        val styles = com.vasiliastyper.engine.StyleManager.loadStyles(this)
        return resolveTypeRLine(line, rules, styles)
    }

    private fun resolveTypeRLine(
        line: String,
        rules: List<com.vasiliastyper.engine.StyleRule>,
        styles: List<com.vasiliastyper.model.TextStyle>
    ): Pair<String, com.vasiliastyper.model.TextStyle?> {
        val trimmed = line.trim()

        var display = line
        var matchedStyle: com.vasiliastyper.model.TextStyle? = null

        for (rule in rules) {
            val matches = when (rule.patternType) {
                "WRAP_PAREN"   -> trimmed.startsWith("(") && trimmed.endsWith(")")
                "WRAP_QUOTE"   -> (trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                                  (trimmed.startsWith("\u201C") && trimmed.endsWith("\u201D"))
                "WRAP_SQUARE"  -> trimmed.startsWith("[") && trimmed.endsWith("]")
                "CONTAINS"     -> rule.pattern.isNotEmpty() && trimmed.contains(rule.pattern)
                "STARTS_WITH"  -> rule.pattern.isNotEmpty() && trimmed.startsWith(rule.pattern)
                "PREFIX_CODE"  -> rule.pattern.isNotEmpty() && trimmed.startsWith(rule.pattern)
                else           -> false
            }

            if (matches) {
                matchedStyle = styles.firstOrNull {
                    it.name.equals(rule.styleName, ignoreCase = true) &&
                    it.folder.equals(rule.folder, ignoreCase = true)
                }
                if (rule.patternType == "PREFIX_CODE" && rule.pattern.isNotEmpty() && trimmed.startsWith(rule.pattern)) {
                    display = trimmed.removePrefix(rule.pattern).trimStart()
                }
                break
            }
        }

        return display to matchedStyle
    }

    /**
     * VasType uses the prefix to select a style, but the prefix itself is part of
     * the translated text. Unlike legacy TipeR, `-`, `/`, `[`, and custom prefix
     * codes are therefore never removed from the text placed on canvas.
     */
    private fun resolveVasTypeLine(
        line: String,
        rules: List<com.vasiliastyper.engine.StyleRule>,
        styles: List<com.vasiliastyper.model.TextStyle>
    ): Pair<String, com.vasiliastyper.model.TextStyle?> {
        val matchedStyle = resolveTypeRLine(line, rules, styles).second
        return line to matchedStyle
    }

    data class PlacementTarget(
        val area: RectF,
        val readingY: Float,
        val readingX: Float
    )

    /**
     * Determines the placement order for Place All / auto typeset.
     *
     * Default: reading order (top → bottom, left → right).
     * If the current canvas already has detected text regions, those regions are
     * used as a lightweight guide so placement follows actual text blocks.
     */
    private fun setupPlaceAllDirectionSpinner() {
        val labels = listOf(
            "Atas ke bawah • kiri ke kanan",
            "Atas ke bawah • kanan ke kiri"
        )
        val preferences = getSharedPreferences("editor_preferences", MODE_PRIVATE)
        placeAllDirection = runCatching {
            PlaceAllDirection.valueOf(
                preferences.getString(
                    "place_all_direction",
                    PlaceAllDirection.TOP_TO_BOTTOM_LEFT_TO_RIGHT.name
                ) ?: PlaceAllDirection.TOP_TO_BOTTOM_LEFT_TO_RIGHT.name
            )
        }.getOrDefault(PlaceAllDirection.TOP_TO_BOTTOM_LEFT_TO_RIGHT)

        binding.spinnerPlaceAllDirection.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerPlaceAllDirection.setSelection(placeAllDirection.ordinal, false)
        binding.spinnerPlaceAllDirection.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                placeAllDirection = PlaceAllDirection.entries.getOrElse(position) {
                    PlaceAllDirection.TOP_TO_BOTTOM_LEFT_TO_RIGHT
                }
                preferences.edit().putString("place_all_direction", placeAllDirection.name).apply()
                binding.spinnerPlaceAllDirection.contentDescription = labels[placeAllDirection.ordinal]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun resolvePlacementTargetsForPlaceAll(): List<RectF> {
        val selectionAreas = if (binding.canvasView.selection.isActive) {
            binding.canvasView.selection.getAreas()
        } else emptyList()
        val regionAreas = textDetectedRegions.map { RectF(it.rect) }
        val candidates = (selectionAreas + regionAreas)
            .mapNotNull { area -> RectF(area).takeIf { it.width() > 0f && it.height() > 0f } }

        // Selection parts and detected regions may describe the same bubble. Keep
        // only one target when most of the smaller rectangle overlaps.
        val unique = mutableListOf<RectF>()
        for (candidate in candidates) {
            val duplicateIndex = unique.indexOfFirst { existing ->
                val left = maxOf(existing.left, candidate.left)
                val top = maxOf(existing.top, candidate.top)
                val right = minOf(existing.right, candidate.right)
                val bottom = minOf(existing.bottom, candidate.bottom)
                val intersection = maxOf(0f, right - left) * maxOf(0f, bottom - top)
                val smaller = minOf(existing.width() * existing.height(), candidate.width() * candidate.height())
                    .coerceAtLeast(1f)
                intersection / smaller >= 0.82f
            }
            if (duplicateIndex < 0) {
                unique += candidate
            } else {
                val existing = unique[duplicateIndex]
                val existingArea = existing.width() * existing.height()
                val candidateArea = candidate.width() * candidate.height()
                // Prefer the tighter detected/selection region over a broad union box.
                if (candidateArea < existingArea * 0.82f) unique[duplicateIndex] = candidate
            }
        }

        val horizontalComparator = when (placeAllDirection) {
            PlaceAllDirection.TOP_TO_BOTTOM_LEFT_TO_RIGHT ->
                compareBy<PlacementTarget> { it.readingY }.thenBy { it.readingX }
            PlaceAllDirection.TOP_TO_BOTTOM_RIGHT_TO_LEFT ->
                compareBy<PlacementTarget> { it.readingY }.thenByDescending { it.readingX }
        }
        return unique
            .map { area -> PlacementTarget(area, area.top, area.left) }
            .sortedWith(horizontalComparator)
            .map { it.area }
    }

    /**
     * Batch-place all Type-R texts from queue onto multiple selected areas in reading order.
     * Called from "Place All" button when addToSelection mode has multiple bubbles selected.
     */
    private fun batchPlaceAllAreas() {
        val areas = resolvePlacementTargetsForPlaceAll()
        if (areas.isEmpty()) {
            Toast.makeText(this, "Tidak ada area terseleksi", Toast.LENGTH_SHORT).show()
            return
        }

        scriptPlacementJob?.cancel()
        scriptPlacementJob = lifecycleScope.launch {
            val rules = com.vasiliastyper.engine.StyleManager.loadStyleRules(this@MainActivity)
            val styles = com.vasiliastyper.engine.StyleManager.loadStyles(this@MainActivity)

            var placed = 0
            pushWorkspaceSnapshot()
            binding.canvasView.pushTextHistory()

            withPlacementBatchSuspend {
                for (area in areas) {
                    if (pendingScriptLine == null && typeRQueue.isEmpty()) break
                    if (pendingScriptLine == null && typeRQueue.isNotEmpty()) {
                        val nextLine = typeRQueue.removeFirst()
                        pendingScriptLine = nextLine
                        val (stripped, autoStyle) = resolveTypeRLine(nextLine, rules, styles)
                        pendingDisplayText = stripped
                        pendingAutoStyle = autoStyle
                    }

                    if (pendingAutoStyle != null) {
                        autoPlaceTextWithStyle(area.left, area.top, area.width(), area.height(), padding = 8f)
                    } else {
                        safeShowTextEditorDialogInBounds(area.left, area.top, area.width(), area.height(), padding = 8f)
                        break
                    }

                    placed++
                    if (placed < areas.size) {
                        delay(1)
                    }
                }
            }

            if (placed > 0) {
                triggerAutoSave()
                Toast.makeText(this@MainActivity, "$placed teks ditempatkan secara otomatis", Toast.LENGTH_SHORT).show()
            }
            binding.canvasView.clearSelection()
            binding.canvasView.invalidate()
        }
    }

    private fun autoTypesetScript() {
        val areas = resolvePlacementTargetsForPlaceAll()
        if (areas.isEmpty()) {
            Toast.makeText(this, "Tidak ada area terseleksi", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedIndices = scriptAdapter?.selected?.sorted().orEmpty()
        val startIndex = getScriptStartIndex()
        val sourceIndices = if (selectedIndices.isNotEmpty()) {
            selectedIndices
        } else {
            scriptLines.indices.filter { it >= startIndex && !scriptLines[it].used }
        }

        if (sourceIndices.isEmpty()) {
            Toast.makeText(this, "Tidak ada baris script yang bisa dipakai", Toast.LENGTH_SHORT).show()
            return
        }

        val pairs = sourceIndices.mapNotNull { idx ->
            scriptLines.getOrNull(idx)?.let { idx to it.text }
        }
        if (pairs.isEmpty()) {
            Toast.makeText(this, "Tidak ada teks script yang valid", Toast.LENGTH_SHORT).show()
            return
        }

        executeAutoTypeset(
            areas,
            pairs,
            BubbleTextShaper.Profile.AUTO,
            1f,
            selectedIndices.isNotEmpty()
        )
    }

    private fun executeAutoTypeset(
        areas: List<RectF>,
        pairs: List<Pair<Int, String>>,
        profile: BubbleTextShaper.Profile,
        widthScale: Float,
        clearAdapterSelection: Boolean
    ) {
        scriptPlacementJob?.cancel()
        scriptPlacementJob = lifecycleScope.launch {
            binding.btnPlaceAll.isEnabled = false
            val rules = StyleManager.loadStyleRules(this@MainActivity)
            val styles = StyleManager.loadStyles(this@MainActivity)
            // v11.2 fix: see placeAllPendingTipeRMatches() above — same cold-cache
            // Typeface-decode freeze, same fix (off the Main dispatcher).
            val fontLookup = withContext(Dispatchers.Default) {
                buildFontList()
            }.associate { it.displayName.lowercase() to it.typeface }
            pushWorkspaceSnapshot()
            binding.canvasView.pushTextHistory()

            var placed = 0
            var failure: String? = null
            val count = minOf(areas.size, pairs.size)
            val contrastSource = binding.canvasView.compositeVisibleLayers()
            try {
                withPlacementBatchSuspend {
                    for (i in 0 until count) {
                        val (index, rawText) = pairs[i]
                        val (displayText, matchedStyle) = resolveTypeRLine(rawText, rules, styles)
                        prepareAndAddTextElement(
                            area = areas[i],
                            text = displayText,
                            style = matchedStyle,
                            padding = 8f,
                            shapeProfile = profile,
                            explicitWidthScale = widthScale,
                            fontLookup = fontLookup,
                            sourceBitmap = contrastSource
                        )
                        scriptLines.getOrNull(index)?.used = true
                        placed++
                        binding.tvScriptProgress.text = "Menempatkan teks $placed / $count…"
                        binding.btnPlaceAll.text = "$placed / $count"
                        if (placed % 3 == 0) {
                            binding.canvasView.invalidate()
                            delay(8)
                        } else {
                            yield()
                        }
                    }
                }
            } catch (error: Throwable) {
                failure = if (error is CancellationException) "dibatalkan" else error.message ?: "penempatan dihentikan"
            } finally {
                contrastSource?.takeUnless { it.isRecycled }?.recycle()
                if (clearAdapterSelection && placed > 0) {
                    scriptAdapter?.selected?.clear()
                    scriptAdapter?.multiSelectMode = false
                }
                binding.canvasView.clearSelection()
                binding.canvasView.invalidate()
                if (placed > 0) triggerAutoSave()
                refreshScriptPanel()
                binding.btnPlaceAll.isEnabled = true
                binding.btnPlaceAll.text = "Place All ▶"
            }

            if (placed > 0 || failure != null) {
                val remain = pairs.size - placed
                val profileLabel = if (profile == BubbleTextShaper.Profile.AUTO) "Auto" else profile.label
                binding.tvScriptProgress.text = if (failure == null) {
                    "$placed teks selesai ditempatkan"
                } else {
                    "$placed teks ditempatkan; gagal melanjutkan: $failure"
                }
                Toast.makeText(
                    this@MainActivity,
                    if (failure != null) "Penempatan berhenti aman setelah $placed teks: $failure"
                    else if (remain > 0) "$placed teks dibentuk ($profileLabel) — $remain tersisa"
                    else "$placed teks dibentuk ($profileLabel)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun prepareAndAddTextElement(
        area: RectF,
        text: String,
        style: com.vasiliastyper.model.TextStyle?,
        padding: Float,
        shapeProfile: BubbleTextShaper.Profile,
        explicitWidthScale: Float?,
        fontLookup: Map<String, android.graphics.Typeface>,
        sourceBitmap: Bitmap? = null
    ) {
        val ws = vm.activeWorkspace ?: return
        val layerId = ws.layers.getOrNull(ws.activeLayerIndex)?.id
        val element = withContext(Dispatchers.Default) {
            buildTextElementForArea(
                area, text, style, layerId, padding, shapeProfile,
                explicitWidthScale, fontLookup, sourceBitmap
            )
        }
        binding.canvasView.textElements.add(element)
        binding.canvasView.activeTextId = element.id
    }

    private fun buildTextElementForArea(
        area: RectF,
        text: String,
        style: com.vasiliastyper.model.TextStyle?,
        layerId: String?,
        padding: Float,
        shapeProfile: BubbleTextShaper.Profile,
        explicitWidthScale: Float?,
        fontLookup: Map<String, android.graphics.Typeface>,
        sourceBitmap: Bitmap? = null
    ): TextElement {
        val safePadding = padding.coerceIn(0f, minOf(area.width(), area.height()) * 0.20f)
        val px = area.left + safePadding
        val py = area.top + safePadding
        val pw = (area.width() - 2f * safePadding).coerceAtLeast(10f)
        val ph = (area.height() - 2f * safePadding).coerceAtLeast(10f)

        val fontName = style?.fontName ?: "Default"
        val resolvedTypeface = fontLookup[fontName.lowercase()] ?: android.graphics.Typeface.DEFAULT

        val baseSize = style?.fontSize ?: 36f
        // Placement keeps the requested text box untouched. Shaping and word wrap
        // are applied later, explicitly, through the dedicated Text Shaper tool.
        val shapedText = text
        val shapedWidth = pw
        val shapedX = px
        val rawFittedSize = com.vasiliastyper.engine.TextRenderer.autoFitFontSize(
            text            = shapedText,
            boxWidth        = shapedWidth,
            boxHeight       = ph,
            typeface        = resolvedTypeface,
            maxFontSize     = baseSize,
            roundBubbleMode = false
        )
        val fittedSize = rawFittedSize * if (safePadding > 0f) 0.82f else 1f
        val fallbackColor = style?.color ?: android.graphics.Color.BLACK
        val automaticTextColor = sourceBitmap?.let {
            BubbleTextColorDetector.bestTextColor(it, area, fallbackColor)
        } ?: fallbackColor

        return TextElement(
            text               = shapedText,
            x                  = shapedX,
            y                  = py,
            width              = shapedWidth,
            height             = ph,
            fontSize           = fittedSize,
            typeface           = resolvedTypeface,
            fontName           = fontName,
            color              = automaticTextColor,
            opacity            = style?.opacity?.coerceIn(0, 100) ?: 100,
            isBold             = style?.isBold ?: false,
            isItalic           = style?.isItalic ?: false,
            effect             = style?.effect?.let {
                runCatching { TextEffect.valueOf(it) }.getOrDefault(TextEffect.NONE)
            } ?: TextEffect.NONE,
            outlineColor       = style?.outlineColor ?: android.graphics.Color.TRANSPARENT,
            outlineWidth       = style?.outlineWidth?.coerceAtLeast(1f) ?: 1f,
            outlineOpacity     = style?.outlineOpacity ?: 0,
            shadowDx           = style?.shadowDx ?: 0f,
            shadowDy           = style?.shadowDy ?: 0f,
            shadowRadius       = style?.shadowRadius ?: 0f,
            shadowSpread       = style?.shadowSpread ?: 0f,
            shadowColor        = style?.shadowColor ?: android.graphics.Color.BLACK,
            shadowOpacity      = style?.shadowOpacity ?: 0,
            gradientStartColor = style?.gradientStartColor ?: android.graphics.Color.WHITE,
            gradientEndColor   = style?.gradientEndColor ?: android.graphics.Color.GRAY,
            gradientColors     = style?.gradientColors?.toMutableList() ?: mutableListOf(),
            gradientAngle      = style?.gradientAngle ?: 90f,
            align              = style?.align?.let { runCatching { TextAlign.valueOf(it) }.getOrDefault(TextAlign.CENTER) } ?: TextAlign.CENTER,
            layerId            = layerId,
            leading            = style?.leading ?: 120f,
            tracking           = style?.tracking ?: 0f,
            justify            = style?.justify ?: false,
            enableOutline      = style?.enableOutline ?: false,
            enableShadow       = style?.enableShadow ?: false,
            enableGradient     = style?.enableGradient ?: (style?.effect == "GRADIENT"),
            enableTexture      = style?.enableTexture ?: (style?.effect == "TEXTURE"),
            textTransform      = style?.textTransform ?: "NONE",
            enableOutlineGradient = style?.enableOutlineGradient ?: false,
            outlineGradStartColor = style?.outlineGradStartColor ?: android.graphics.Color.BLACK,
            outlineGradEndColor   = style?.outlineGradEndColor ?: android.graphics.Color.GRAY
        )
    }

    // v11.2 fix: fontLookup is now a required parameter instead of being built
    // synchronously inside this function. This is the function reached from the
    // Script Panel's "Use" button (see setupBottomScriptPanel's onUse), which is
    // the most direct "apply style/preset" action in the app and previously called
    // buildFontList() — a cold-cache asset scan + per-font Typeface decode — right
    // on the main thread with no coroutine involved at all. Every caller must now
    // resolve the lookup off the main thread first (see autoPlaceTextWithStyle).
    private fun placeTextElementOnArea(
        area: RectF,
        text: String,
        style: com.vasiliastyper.model.TextStyle?,
        fontLookup: Map<String, android.graphics.Typeface>,
        padding: Float = 8f,
        shapeProfile: BubbleTextShaper.Profile = BubbleTextShaper.Profile.AUTO,
        explicitWidthScale: Float? = null
    ) {
        val ws = vm.activeWorkspace ?: return
        val layerId = ws.layers.getOrNull(ws.activeLayerIndex)?.id
        val contrastSource = binding.canvasView.compositeVisibleLayers()
        val el = try {
            buildTextElementForArea(
                area, text, style, layerId, padding, shapeProfile, explicitWidthScale,
                fontLookup, contrastSource
            )
        } finally {
            contrastSource?.takeUnless { it.isRecycled }?.recycle()
        }

        if (!isPlacementBatchActive) {
            binding.canvasView.pushTextHistory()
        }
        binding.canvasView.textElements.add(el)
        binding.canvasView.activeTextId = el.id
        if (!isPlacementBatchActive) {
            updateTextQuickToolbar(el)
            binding.canvasView.invalidate()
            triggerAutoSave()
        }
    }

    private fun autoPlaceTextWithStyle(x: Float, y: Float, width: Float, height: Float, padding: Float = 8f) {
        val text  = pendingDisplayText ?: pendingScriptLine ?: return
        val style = pendingAutoStyle ?: return
        // A previous tap is still resolving its font lookup in the background;
        // ignore this one instead of racing pendingAutoStyle/pendingScriptLine.
        if (autoPlaceInFlight) return

        if (!isPlacementBatchActive) {
            pushWorkspaceSnapshot()
            binding.canvasView.pushTextHistory()
        }

        pendingAutoStyle   = null
        pendingDisplayText = null
        pendingScriptLine  = null

        val area = RectF(x, y, x + width, y + height)
        autoPlaceInFlight = true
        lifecycleScope.launch {
            // v11.2 fix: buildFontList() moved off the Main dispatcher — this is
            // the freeze/crash reported when using "Use" on a script line with a
            // matched Style/Preset. See the comment on placeTextElementOnArea().
            val fontLookup = withContext(Dispatchers.Default) {
                mapOf(style.fontName.lowercase() to FontResolver.resolveStyleTypeface(
                    applicationContext,
                    style.fontName
                ))
            }

            if (isFinishing || isDestroyed) {
                autoPlaceInFlight = false
                return@launch
            }

            runCatching {
                placeTextElementOnArea(
                    area,
                    text,
                    style,
                    fontLookup = fontLookup,
                    padding = padding,
                    explicitWidthScale = null
                )
            }.onFailure { error ->
                Log.e("VasiliasTyper", "Auto-place style gagal diterapkan", error)
                Toast.makeText(this@MainActivity, "Style gagal diterapkan; teks tidak ditempatkan", Toast.LENGTH_LONG).show()
            }

            if (!isPlacementBatchActive) {
                binding.canvasView.clearSelection()
                binding.canvasView.invalidate()
            }

            if (typeRQueue.isNotEmpty()) {
                val nextLine = typeRQueue.removeFirst()
                pendingScriptLine = nextLine
                val (stripped, nextStyle) = resolveTypeRLine(nextLine)
                pendingDisplayText = stripped
                pendingAutoStyle   = nextStyle
            }

            if (!isPlacementBatchActive) {
                val remaining = typeRQueue.size + (if (pendingScriptLine != null) 1 else 0)
                val msg = if (remaining > 0) "\"$text\" ditempatkan — $remaining teks tersisa" else "\"$text\" ditempatkan"
                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
            }
            autoPlaceInFlight = false
        }
    }

    private fun showTypeRDialog() = showVasTypeDialog()

    private fun showVasTypeDialog() {
        vasTypeDialog?.takeIf { it.isShowing }?.dismiss()

        val sheet = layoutInflater.inflate(R.layout.dialog_vastype, null, false)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheet)

        val input = sheet.findViewById<EditText>(R.id.etVasTypeScript)
        val upload = sheet.findViewById<View>(R.id.btnVasTypeUpload)
        val example = sheet.findViewById<View>(R.id.btnVasTypeExample)
        val help = sheet.findViewById<View>(R.id.btnVasTypeHelp)
        val detect = sheet.findViewById<View>(R.id.btnDetectVasType)
        val run = sheet.findViewById<View>(R.id.btnRunVasType)
        val cancel = sheet.findViewById<View>(R.id.btnCancelVasType)
        val tabsContainer = sheet.findViewById<LinearLayout>(R.id.vasTypeTabsContainer)
        val selectAll = sheet.findViewById<View>(R.id.btnVasTypeSelectAllTabs)
        val statusChip = sheet.findViewById<TextView>(R.id.tvVasTypeStatusChip)
        val loadedLabel = sheet.findViewById<TextView>(R.id.tvVasTypeLoadedLabel)
        val tabCount = sheet.findViewById<TextView>(R.id.tvVasTypeTabCount)
        val preview = sheet.findViewById<TextView>(R.id.tvVasTypePreview)
        val sourcePreview = sheet.findViewById<TextView>(R.id.tvVasTypeSourcePreview)
        val translationPreview = sheet.findViewById<TextView>(R.id.tvVasTypeTranslationPreview)
        val progress = sheet.findViewById<ProgressBar>(R.id.progressVasType)
        val modeGroup = sheet.findViewById<RadioGroup>(R.id.rgVasTypeMode)
        val modeInpaintOnly = sheet.findViewById<RadioButton>(R.id.rbVasTypeInpaintOnly)
        val modeInpaintPlace = sheet.findViewById<RadioButton>(R.id.rbVasTypeInpaintPlace)

        vasTypeDialog = dialog
        vasTypeScriptInputView = input
        vasTypeStatusView = statusChip
        vasTypePreviewView = preview
        vasTypeSourcePreviewView = sourcePreview
        vasTypeTranslationPreviewView = translationPreview
        vasTypeTabContainer = tabsContainer
        vasTypeProgressView = progress
        vasTypeLoadedLabelView = loadedLabel
        vasTypeTabCountView = tabCount
        vasTypeModeGroupView = modeGroup
        vasTypeModeInpaintOnlyView = modeInpaintOnly
        vasTypeModeInpaintPlaceView = modeInpaintPlace
        vasTypeDetectButtonView = detect
        vasTypeMode = VasTypeMode.INPAINT_AND_PLACE

        fun canonicalize(script: StructuredScript): String =
            script.lines.joinToString("\n") { it.text }

        fun selectedWorkspaces(): List<Workspace> {
            val workspaces = vm.workspaces.value.orEmpty().take(5)
            return workspaces.filter { it.id in vasTypeSelectedWorkspaceIds }
        }

        fun refreshTabs() {
            tabsContainer.removeAllViews()
            val workspaces = vm.workspaces.value.orEmpty().take(5)
            if (vasTypeSelectedWorkspaceIds.isEmpty()) {
                workspaces.forEach { vasTypeSelectedWorkspaceIds += it.id }
            } else {
                vasTypeSelectedWorkspaceIds.retainAll(workspaces.mapTo(linkedSetOf()) { it.id })
            }
            workspaces.forEachIndexed { index, ws ->
                val row = CheckBox(this@MainActivity).apply {
                    text = "${index + 1}. ${ws.name}"
                    setTextColor(android.graphics.Color.parseColor("#E5E7EB"))
                    isChecked = vasTypeSelectedWorkspaceIds.contains(ws.id)
                    setOnCheckedChangeListener { button, checked ->
                        if (checked) {
                            if (vasTypeSelectedWorkspaceIds.size >= 5 && ws.id !in vasTypeSelectedWorkspaceIds) {
                                button.isChecked = false
                                Toast.makeText(this@MainActivity, "Maksimal 5 tab aktif", Toast.LENGTH_SHORT).show()
                            } else {
                                vasTypeSelectedWorkspaceIds += ws.id
                            }
                        } else {
                            vasTypeSelectedWorkspaceIds -= ws.id
                        }
                        refreshVasTypeSummary()
                    }
                }
                tabsContainer.addView(row)
            }
            tabCount.text = "${selectedWorkspaces().size}/5"
        }

        fun refreshPreview() = refreshVasTypeSummary()

        refreshTabs()
        refreshPreview()

        input.setText(vasTypeLoadedScript?.let(::canonicalize).orEmpty())
        input.setSelection(input.text?.length ?: 0)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = refreshVasTypeSummary()
        })

        upload.setOnClickListener {
            vasTypeScriptPickerLauncher.launch(
                arrayOf(
                    "text/plain",
                    "text/csv",
                    "text/tab-separated-values",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    "application/octet-stream"
                )
            )
        }
        example.setOnClickListener {
            val sample = """
                - Tahun ini kita tetap menanam gandum musim semi.
                / Orang yang keras kepala harus dihadapi dengan tegas.
                (): Moore, atur para petani mengaduk lumpur bergantian.
            """.trimIndent()
            input.setText(sample)
            input.setSelection(input.text?.length ?: 0)
            vasTypeLoadedLabel = "Contoh bawaan"
            vasTypeLoadedScript = ScriptImporter.parsePlacementText(sample)
            vasTypePreviewReady = false
            refreshVasTypeSummary()
        }
        help.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Cara memakai VasType")
                .setMessage(
                    "1. Buka gambar yang masih memuat teks asli.\n" +
                        "2. Tempel script atau upload TXT/CSV/TSV/DOCX biasa; satu teks per baris.\n" +
                        "3. Tekan Deteksi & Preview. OCR membentuk satu mask kotak untuk setiap baris teks, bukan satu mask untuk seluruh bubble.\n" +
                        "4. Periksa kotak mask per baris di kanvas. Tekan × di kanan atas mask yang tidak diinginkan.\n" +
                        "5. Tekan Terapkan. Teks diurutkan dari atas ke bawah lalu kiri ke kanan.\n" +
                        "6. Prefix/kode tetap dibaca oleh Style Rules sebelum teks ditempatkan.\n\n" +
                        "Format OCR/TRANSLATE lama tetap dapat dimuat; VasType baru memakai bagian TRANSLATE sebagai script placement."
                )
                .setPositiveButton("Mengerti", null)
                .show()
        }
        selectAll.setOnClickListener {
            vasTypeSelectedWorkspaceIds.clear()
            vm.workspaces.value.orEmpty().take(5).forEach { vasTypeSelectedWorkspaceIds += it.id }
            refreshTabs()
            refreshPreview()
        }
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            vasTypeMode = if (checkedId == R.id.rbVasTypeInpaintOnly) VasTypeMode.INPAINT_ONLY else VasTypeMode.INPAINT_AND_PLACE
            refreshPreview()
        }
        modeInpaintPlace.isChecked = vasTypeMode == VasTypeMode.INPAINT_AND_PLACE
        modeInpaintOnly.isChecked = vasTypeMode == VasTypeMode.INPAINT_ONLY

        detect.setOnClickListener {
            val raw = input.text?.toString().orEmpty().trim()
            val structured = if (raw.isNotBlank()) {
                ScriptImporter.parsePlacementText(raw)
            } else {
                vasTypeLoadedScript
            }
            if (structured == null || structured.lines.isEmpty()) {
                Toast.makeText(this, "Masukkan minimal satu baris script", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            vasTypeLoadedScript = structured
            vasTypeLoadedLabel = if (raw.isNotBlank()) "Script manual" else vasTypeLoadedLabel
            detectVasTypeMasks(structured)
        }

        run.setOnClickListener {
            val raw = input.text?.toString().orEmpty().trim()
            val structured = if (raw.isNotBlank()) {
                ScriptImporter.parsePlacementText(raw)
            } else {
                vasTypeLoadedScript
            }
            if (structured == null || structured.lines.isEmpty()) {
                Toast.makeText(this, "Masukkan minimal satu baris script", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val workspace = vm.activeWorkspace
            if (!vasTypePreviewReady || workspace == null || vasTypePreviewWorkspaceId != workspace.id || textDetectedRegions.isEmpty()) {
                Toast.makeText(this, "Tekan Deteksi & Preview dan periksa mask terlebih dahulu", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            vasTypeLoadedScript = structured
            vasTypeJob?.cancel()
            run.isEnabled = false
            vasTypeJob = lifecycleScope.launch {
                try {
                    applyVasTypePreview(structured)
                } catch (cancelled: CancellationException) {
                    vasTypeStatusView?.text = "Dibatalkan"
                    throw cancelled
                } catch (error: Throwable) {
                    Log.e("VasType", "Workflow gagal", error)
                    val message = error.message?.takeIf { it.isNotBlank() } ?: "kesalahan pemrosesan"
                    vasTypeStatusView?.text = "Gagal"
                    vasTypePreviewView?.text = "VasType gagal: $message"
                    Toast.makeText(this@MainActivity, "VasType gagal: $message", Toast.LENGTH_LONG).show()
                } finally {
                    run.isEnabled = true
                }
            }
        }

        cancel.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            if (vasTypeDialog === dialog) {
                vasTypeDialog = null
                vasTypeScriptInputView = null
                vasTypeStatusView = null
                vasTypePreviewView = null
                vasTypeSourcePreviewView = null
                vasTypeTranslationPreviewView = null
                vasTypeTabContainer = null
                vasTypeProgressView = null
                vasTypeDetectButtonView = null
            }
        }

        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
        dialog.behavior.apply {
            isHideable = false
            skipCollapsed = false
            peekHeight = (resources.displayMetrics.heightPixels * 0.56f).toInt()
            state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED
        }
        refreshVasTypeSummary()
    }

    private fun refreshVasTypeSummary() {
        val inputText = vasTypeScriptInputView?.text?.toString().orEmpty()
        val parsed = runCatching { ScriptImporter.parsePlacementText(inputText) }.getOrNull()
        val script = parsed?.takeIf { it.lines.isNotEmpty() }
            ?: vasTypeLoadedScript?.takeIf { it.lines.isNotEmpty() }
        val workspace = vm.activeWorkspace
        val previewCount = if (workspace != null && vasTypePreviewWorkspaceId == workspace.id) {
            textDetectedRegions.size
        } else {
            0
        }

        vasTypeStatusView?.text = when {
            vasTypeJob?.isActive == true -> "Berjalan"
            vasTypePreviewReady && previewCount > 0 -> "Preview siap"
            else -> "Siap"
        }
        vasTypeLoadedLabelView?.text = "${vasTypeLoadedLabel} • ${script?.lines?.size ?: 0} baris • kanvas aktif"
        vasTypeTabCountView?.text = "1"
        vasTypeProgressView?.progress = if (vasTypeJob?.isActive == true) 65 else 0
        vasTypeModeInpaintOnlyView?.isChecked = false
        vasTypeModeInpaintPlaceView?.isChecked = true

        vasTypePreviewView?.text = when {
            workspace == null -> "Buka gambar terlebih dahulu."
            script == null -> "Tempel script atau upload file teks biasa untuk mulai."
            previewCount == 0 -> "${script.lines.size} baris siap • tekan Deteksi & Preview."
            else -> "$previewCount mask siap • ${script.lines.size} baris script • tekan × pada mask yang tidak diinginkan, lalu Terapkan."
        }

        vasTypeSourcePreviewView?.text = if (previewCount > 0) {
            "Urutan placement: atas → bawah, lalu kiri → kanan. Setiap kotak mengikuti bounding box satu baris OCR."
        } else {
            "Detektor: ML Kit OCR • mask kotak terpisah untuk setiap baris teks"
        }
        vasTypeTranslationPreviewView?.text = script?.lines.orEmpty()
            .take(8)
            .mapIndexed { index, line -> "${index + 1}. ${line.text}" }
            .joinToString("\n")
            .ifBlank { "Belum ada script" }
    }

    private fun detectVasTypeMasks(script: StructuredScript) {
        val workspace = vm.activeWorkspace
        if (workspace == null) {
            Toast.makeText(this, "Buka gambar sebelum mendeteksi mask", Toast.LENGTH_LONG).show()
            return
        }
        val targetLayer = workspace.layers.getOrNull(workspace.activeLayerIndex)
        if (targetLayer == null) {
            Toast.makeText(this, "Layer aktif tidak ditemukan", Toast.LENGTH_LONG).show()
            return
        }

        vasTypeJob?.cancel()
        vasTypeDetectButtonView?.isEnabled = false
        vasTypePreviewReady = false
        vasTypeProgressView?.isIndeterminate = true
        vasTypeStatusView?.text = "Mendeteksi"
        vasTypePreviewView?.text = "Mendeteksi teks dan membentuk mask kotak per baris…"
        vasTypeJob = lifecycleScope.launch {
            var composite: Bitmap? = null
            try {
                composite = binding.canvasView.compositeVisibleLayers() ?: withContext(Dispatchers.IO) {
                    LayerCompositor.composite(workspace.layers, workspace.width, workspace.height)
                }
                val detections = MlKitMaskDetector
                    .detectSuspend(composite, MlKitOcrEngine.LANG_AUTO)
                    .sortedWith(compareBy<MlKitMaskDetector.DetectedRegion> { it.rect.top }.thenBy { it.rect.left })
                    .take(200)

                textDetectedRegions.clear()
                textDetectedRegions.addAll(detections.map { item ->
                    PaddleDbNetDetector.DetectedRegion(
                        rect = buildVasTypeLineMask(item.rect, workspace.width, workspace.height),
                        type = "text_line_box",
                        text = item.text
                    )
                })
                vasTypePreviewWorkspaceId = workspace.id
                vasTypePreviewReady = textDetectedRegions.isNotEmpty()
                workspaceMaskStates[workspace.id] = textDetectedRegions.map {
                    PaddleDbNetDetector.DetectedRegion(RectF(it.rect), it.type, it.text)
                }.toMutableList()
                binding.canvasView.geminiDetectOverlay = textDetectedRegions.toList()
                binding.canvasView.geminiOverlayDeleteMode = false
                binding.canvasView.invalidate()

                if (textDetectedRegions.isEmpty()) {
                    vasTypeStatusView?.text = "Tidak ditemukan"
                    vasTypePreviewView?.text = "Tidak ada baris teks yang terdeteksi. Pastikan teks cukup jelas dan coba lagi."
                    Toast.makeText(this@MainActivity, "Tidak ada baris teks terdeteksi", Toast.LENGTH_LONG).show()
                } else {
                    val usable = minOf(script.lines.size, textDetectedRegions.size)
                    vasTypeStatusView?.text = "Preview siap"
                    vasTypePreviewView?.text = "${textDetectedRegions.size} mask ditemukan • $usable teks akan ditempatkan. Tekan × di kanan atas mask untuk menghapus."
                    Toast.makeText(this@MainActivity, "Preview ${textDetectedRegions.size} mask siap", Toast.LENGTH_SHORT).show()
                }
            } catch (error: Throwable) {
                Log.e("VasType", "Deteksi mask gagal", error)
                vasTypeStatusView?.text = "Deteksi gagal"
                vasTypePreviewView?.text = "Deteksi gagal: ${error.message ?: "kesalahan pemrosesan"}"
            } finally {
                composite?.takeUnless { it.isRecycled || it === targetLayer.bitmap }?.recycle()
                vasTypeProgressView?.isIndeterminate = false
                vasTypeProgressView?.progress = 0
                vasTypeDetectButtonView?.isEnabled = true
                refreshVasTypeSummary()
            }
        }
    }

    /**
     * Memperluas bounding box OCR secukupnya untuk menutup antialias glyph tanpa
     * menggabungkan baris yang berdekatan. Hasilnya tetap satu kotak per baris.
     */
    private fun buildVasTypeLineMask(rect: RectF, canvasWidth: Int, canvasHeight: Int): RectF {
        val padX = (rect.height() * 0.18f).coerceIn(2f, 10f)
        val padY = (rect.height() * 0.12f).coerceIn(1f, 6f)
        return RectF(
            (rect.left - padX).coerceIn(0f, canvasWidth.toFloat()),
            (rect.top - padY).coerceIn(0f, canvasHeight.toFloat()),
            (rect.right + padX).coerceIn(0f, canvasWidth.toFloat()),
            (rect.bottom + padY).coerceIn(0f, canvasHeight.toFloat())
        )
    }

    private suspend fun applyVasTypePreview(script: StructuredScript) {
        val workspace = vm.activeWorkspace
            ?: throw IllegalStateException("Kanvas aktif tidak ditemukan")
        if (vasTypePreviewWorkspaceId != workspace.id) {
            throw IllegalStateException("Preview bukan milik kanvas aktif. Jalankan deteksi ulang.")
        }
        val targetLayer = workspace.layers.getOrNull(workspace.activeLayerIndex)
            ?: throw IllegalStateException("Layer aktif tidak ditemukan")
        if (!targetLayer.bitmap.isMutable) {
            targetLayer.bitmap = targetLayer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                ?: throw IllegalStateException("Layer tidak dapat diedit")
        }

        val orderedRegions = textDetectedRegions
            .sortedWith(compareBy<PaddleDbNetDetector.DetectedRegion> { it.rect.top }.thenBy { it.rect.left })
        val pairCount = minOf(script.lines.size, orderedRegions.size)
        if (pairCount <= 0) throw IllegalStateException("Tidak ada pasangan script dan mask")

        val rules = StyleManager.loadStyleRules(this)
        val styles = StyleManager.loadStyles(this)
        // v11.2 fix: see placeAllPendingTipeRMatches() — cold-cache Typeface decode
        // must not run on the Main dispatcher.
        val fontLookup = withContext(Dispatchers.Default) {
            buildFontList()
        }.associate { it.displayName.lowercase() to it.typeface }
        val textState = workspaceTextStates.getOrPut(workspace.id) { mutableListOf() }
        val source = binding.canvasView.compositeVisibleLayers() ?: withContext(Dispatchers.IO) {
            LayerCompositor.composite(workspace.layers, workspace.width, workspace.height)
        }

        pushWorkspaceSnapshot()
        binding.canvasView.pushTextHistory()
        vasTypeProgressView?.progress = 0
        vasTypeStatusView?.text = "Menerapkan"
        var placed = 0
        var maskedLines = 0
        try {
            for (index in 0 until pairCount) {
                val region = orderedRegions[index]
                val lineBox = RectF(region.rect)
                val line = script.lines[index].text
                val background = BubbleTextColorDetector.analyze(source, lineBox)?.backgroundColor
                    ?: android.graphics.Color.WHITE
                val maskResult = withContext(Dispatchers.IO) {
                    BoxMaskWhiteFiller.fill(
                        targetBitmap = targetLayer.bitmap,
                        regions = listOf(lineBox),
                        fillColor = background,
                        paddingPx = 0,
                        dilationPx = 0
                    )
                }
                if (maskResult.validRegions > 0) maskedLines++

                val placementRect = RectF(lineBox)
                val (displayText, style) = resolveVasTypeLine(line, rules, styles)
                val element = withContext(Dispatchers.Default) {
                    buildTextElementForArea(
                        area = placementRect,
                        text = displayText,
                        style = style,
                        layerId = targetLayer.id,
                        padding = 8f,
                        shapeProfile = BubbleTextShaper.Profile.AUTO,
                        explicitWidthScale = null,
                        fontLookup = fontLookup,
                        sourceBitmap = source
                    )
                }
                textState.add(element)
                binding.canvasView.textElements.add(element)
                binding.canvasView.activeTextId = element.id
                placed++
                vasTypeProgressView?.progress = (((index + 1f) / pairCount) * 100f).toInt()
                vasTypePreviewView?.text = "Menempatkan $placed / $pairCount sesuai urutan baca…"
                if (placed % 3 == 0) {
                    binding.canvasView.invalidate()
                    yield()
                }
            }

            binding.canvasView.clearSelection()
            binding.canvasView.geminiDetectOverlay = emptyList()
            binding.canvasView.geminiOverlayDeleteMode = false
            binding.canvasView.invalidate()
            textDetectedRegions.clear()
            workspaceMaskStates.remove(workspace.id)
            vasTypePreviewReady = false
            vasTypePreviewWorkspaceId = null
            saveToHistory(workspace, "vastype_ordered", existingId = projectIdFor(workspace))
            triggerAutoSave()

            val unusedScript = script.lines.size - pairCount
            val unusedMasks = orderedRegions.size - pairCount
            vasTypeProgressView?.progress = 100
            vasTypeStatusView?.text = "Selesai"
            vasTypePreviewView?.text = "$placed teks ditempatkan • $maskedLines kotak baris diterapkan" +
                if (unusedScript > 0) " • $unusedScript baris script tersisa" else if (unusedMasks > 0) " • $unusedMasks mask tidak terisi" else ""
            Toast.makeText(this, "$placed teks berhasil ditempatkan sesuai urutan mask", Toast.LENGTH_LONG).show()
        } finally {
            source.takeUnless { it.isRecycled || it === targetLayer.bitmap }?.recycle()
        }
    }

    private suspend fun runVasTypeWorkflow(
        script: StructuredScript,
        targets: List<Workspace>,
        mode: VasTypeMode
    ) {
        val safeTargets = targets.distinctBy { it.id }.take(5)
        if (safeTargets.isEmpty()) {
            vasTypeStatusView?.text = "Tidak ada tab yang dipilih"
            vasTypePreviewView?.text = "Pilih minimal 1 tab untuk menjalankan VasType."
            return
        }

        val rules = StyleManager.loadStyleRules(this)
        val styles = StyleManager.loadStyles(this)
        // v11.2 fix: see placeAllPendingTipeRMatches() — cold-cache Typeface decode
        // must not run on the Main dispatcher.
        val fontLookup = withContext(Dispatchers.Default) {
            buildFontList()
        }.associate { it.displayName.lowercase() to it.typeface }
        val targetCount = safeTargets.size

        vasTypeProgressView?.progress = 0
        vasTypeStatusView?.text = "Berjalan"

        var changedTargets = 0
        safeTargets.forEachIndexed { index, workspace ->
            if (vasTypeJob?.isActive != true) return@forEachIndexed
            val label = workspace.name.ifBlank { "Tab ${index + 1}" }
            vasTypePreviewView?.post {
                vasTypePreviewView?.text = "Memproses $label (${index + 1}/$targetCount)…"
            }
            val result = processVasTypeWorkspace(
                workspace = workspace,
                script = script,
                mode = mode,
                rules = rules,
                styles = styles,
                fontLookup = fontLookup,
                index = index,
                total = targetCount
            )
            if (result.changed) changedTargets++
            vasTypeProgressView?.progress = (((index + 1).toFloat() / targetCount.toFloat()) * 100f).toInt().coerceIn(0, 100)
            vasTypeStatusView?.text = result.message
        }

        vasTypeProgressView?.progress = 100
        if (changedTargets == 0) {
            vasTypeStatusView?.text = "Selesai tanpa perubahan"
            vasTypePreviewView?.text = "VasType tidak menemukan pasangan OCR yang cocok. Coba periksa header OCR/TRANSLATE, kecocokan teks, atau kontras gambar."
            Toast.makeText(this, "VasType selesai tanpa perubahan", Toast.LENGTH_LONG).show()
        } else {
            vasTypeStatusView?.text = "Selesai"
            vasTypePreviewView?.text = "VasType selesai memproses $changedTargets tab dari ${safeTargets.size}."
            Toast.makeText(this, "VasType selesai memproses $changedTargets tab", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun processVasTypeWorkspace(
        workspace: Workspace,
        script: StructuredScript,
        mode: VasTypeMode,
        rules: List<com.vasiliastyper.engine.StyleRule>,
        styles: List<com.vasiliastyper.model.TextStyle>,
        fontLookup: Map<String, android.graphics.Typeface>,
        index: Int,
        total: Int
    ): VasTypeProcessResult {
        val active = vm.activeWorkspace?.id == workspace.id
        // CanvasView adalah View Android dan hanya boleh disentuh dari main thread.
        // Ambil snapshot tab aktif sebelum pindah ke worker thread.
        val activeComposite = if (active) binding.canvasView.compositeVisibleLayers() else null
        val composite = activeComposite ?: withContext(Dispatchers.IO) {
            LayerCompositor.composite(workspace.layers, workspace.width, workspace.height)
        }
        var originalComposite: Bitmap? = null
        try {
            // VasType must read real OCR from the canvas. Never synthesize OCR text
            // from the imported file, because that would turn positional guesses
            // into false matches and could inpaint unrelated artwork.
            val detected = detectScriptRegionsWithTipeR(composite)
            if (detected.isEmpty()) {
                return VasTypeProcessResult(
                    false,
                    "${workspace.name}: tidak ada teks terdeteksi (${sourceLanguageLabel(scriptSourceLanguageCode)})"
                )
            }

            // Status `used` hanya berlaku per tab. Menyalin daftar mencegah tab pertama
            // menghabiskan seluruh pasangan sehingga tab berikutnya selalu mendapat 0 match.
            val workspaceScriptLines = script.lines.map { it.copy(used = false) }
            var matches = ScriptOcrMatcher.match(workspaceScriptLines, detected, minimumScore = 0.60f)
            if (matches.isEmpty()) {
                matches = ScriptOcrMatcher.match(workspaceScriptLines, detected, minimumScore = 0.48f)
            }
            if (matches.isEmpty()) {
                matches = ScriptOcrMatcher.matchByReadingOrder(workspaceScriptLines, detected)
            }
            if (matches.isEmpty()) return VasTypeProcessResult(false, "${workspace.name}: OCR kanvas tidak cocok dengan source file")

            val targetLayer = workspace.layers.getOrNull(workspace.activeLayerIndex)
                ?: return VasTypeProcessResult(false, "${workspace.name}: layer aktif tidak ditemukan")
            if (!targetLayer.bitmap.isMutable) {
                val mutableCopy = targetLayer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                    ?: return VasTypeProcessResult(false, "${workspace.name}: layer gagal dikonversi menjadi mutable")
                targetLayer.bitmap = mutableCopy
            }

            val textState = workspaceTextStates.getOrPut(workspace.id) { mutableListOf() }
            originalComposite = composite.copy(Bitmap.Config.ARGB_8888, false)

            matches.forEach { match ->
                // Placement follows the speech-bubble geometry, while cleanup is
                // restricted to the OCR source box. This prevents VasType from
                // inpainting balloon borders or artwork around a short sentence.
                val placementPadX = maxOf(8f, match.rect.width() * 0.08f)
                val placementPadY = maxOf(8f, match.rect.height() * 0.12f)
                val placementRect = RectF(
                    (match.rect.left + placementPadX).coerceIn(0f, workspace.width.toFloat()),
                    (match.rect.top + placementPadY).coerceIn(0f, workspace.height.toFloat()),
                    (match.rect.right - placementPadX).coerceIn(0f, workspace.width.toFloat()),
                    (match.rect.bottom - placementPadY).coerceIn(0f, workspace.height.toFloat())
                ).takeIf { it.width() > 8f && it.height() > 8f } ?: RectF(match.rect)

                val sourcePadX = maxOf(3f, match.sourceRect.width() * 0.08f)
                val sourcePadY = maxOf(3f, match.sourceRect.height() * 0.15f)
                val sourceRect = RectF(
                    (match.sourceRect.left - sourcePadX).coerceIn(0f, workspace.width.toFloat()),
                    (match.sourceRect.top - sourcePadY).coerceIn(0f, workspace.height.toFloat()),
                    (match.sourceRect.right + sourcePadX).coerceIn(0f, workspace.width.toFloat()),
                    (match.sourceRect.bottom + sourcePadY).coerceIn(0f, workspace.height.toFloat())
                )
                withContext(Dispatchers.IO) {
                    val region = Region(Rect(
                        sourceRect.left.toInt().coerceIn(0, targetLayer.bitmap.width),
                        sourceRect.top.toInt().coerceIn(0, targetLayer.bitmap.height),
                        sourceRect.right.toInt().coerceIn(0, targetLayer.bitmap.width),
                        sourceRect.bottom.toInt().coerceIn(0, targetLayer.bitmap.height)
                    ))
                    val fillResult = ContentAwareFillEngine.fill(targetLayer.bitmap, region)
                    if (!fillResult.success) {
                        BoxMaskWhiteFiller.fill(targetLayer.bitmap, listOf(sourceRect))
                    }
                }

                if (mode == VasTypeMode.INPAINT_AND_PLACE) {
                    val translation = match.translationText.trim()
                    if (translation.isNotEmpty()) {
                        val (displayText, style) = resolveVasTypeLine(translation, rules, styles)
                        val element = withContext(Dispatchers.Default) {
                            buildTextElementForArea(
                                area = placementRect,
                                text = displayText,
                                style = style,
                                layerId = workspace.layers.getOrNull(workspace.activeLayerIndex)?.id,
                                padding = 8f,
                                shapeProfile = BubbleTextShaper.Profile.AUTO,
                                explicitWidthScale = null,
                                fontLookup = fontLookup,
                                sourceBitmap = originalComposite ?: composite
                            )
                        }
                        textState.add(element)
                        if (active) {
                            binding.canvasView.textElements.add(element)
                            binding.canvasView.activeTextId = element.id
                        }
                    }
                }
                workspaceScriptLines.getOrNull(match.scriptIndex)?.used = true
            }

            if (active) {
                binding.canvasView.invalidate()
                binding.canvasView.clearSelection()
            }
            saveToHistory(workspace, "vastype", existingId = projectIdFor(workspace))
            if (active) triggerAutoSave()
            return VasTypeProcessResult(true, "${workspace.name}: ${matches.size} pasangan • OCR/source cocok")
        } finally {
            originalComposite?.takeUnless { it.isRecycled }?.recycle()
            if (!composite.isRecycled) composite.recycle()
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // LAYERS POPUP
    // ══════════════════════════════════════════════════════════════════════════

    private fun saveCanvasStackOrder(workspaceId: String) {
        binding.canvasView.syncCanvasStack()
        val encoded = binding.canvasView.canvasStack.joinToString("|") { "${it.type.name}:${it.id}" }
        getSharedPreferences("canvas_stack_order", MODE_PRIVATE)
            .edit()
            .putString(workspaceId, encoded)
            .apply()
    }

    private fun setupComposeEditorOverlay() {
        binding.editorComposeOverlay.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        val engine = binding.canvasView.brushEngine
        composeBrushState.value = BrushUiState(
            expanded = brushControlsExpanded,
            size = engine.brushSize,
            opacity = engine.opacity.toFloat(),
            hardness = engine.hardness * 100f,
            stabilizer = engine.stabilizer * 100f,
            forceFade = engine.forceFade * 100f,
            spacing = engine.spacing * 100f,
            flow = engine.flow * 100f
        )
        binding.editorComposeOverlay.setContent {
            EditorComposeOverlay(
                layersOpen = composeLayersOpen.value,
                layerItems = composeLayerItems.value,
                brush = composeBrushState.value,
                onToggleLayers = { showLayersPopup() },
                onAddLayer = {
                    vm.activeWorkspace?.let { workspace ->
                        vm.addLayer(workspace)
                        binding.canvasView.syncCanvasStack()
                        saveCanvasStackOrder(workspace.id)
                        binding.canvasView.invalidate()
                        refreshComposeLayers()
                        triggerAutoSave()
                    }
                },
                onAddFolder = { createComposeLayerFolder() },
                onSelectLayer = { selectComposeLayer(it) },
                onRenameLayer = { item, name -> renameComposeLayerItem(item, name) },
                onToggleClippingMask = { toggleComposeClippingMask(it) },
                onToggleAlphaMask = { toggleComposeAlphaMask(it) },
                onRemoveAlphaMask = { removeComposeAlphaMask(it) },
                onToggleFolderExpanded = { toggleComposeFolderExpanded(it) },
                onToggleLayerFolder = { toggleComposeLayerFolder(it) },
                onToggleLayerVisibility = { toggleComposeLayerVisibility(it) },
                onToggleLayerLock = { toggleComposeLayerLock(it) },
                onLayerOpacity = { item, opacity -> updateComposeLayerOpacity(item, opacity) },
                onLayerBlendMode = { item, mode -> updateComposeLayerBlend(item, mode) },
                onMoveLayer = { up -> moveComposeSelectedLayer(up) },
                onDuplicateLayer = { duplicateComposeSelectedLayer() },
                onMergeLayerDown = { mergeComposeSelectedLayerDown() },
                onFlattenLayers = { flattenComposeVisibleLayers() },
                onDeleteLayer = { deleteComposeSelectedLayer() },
                onToggleBrushExpanded = { setBrushControlsExpanded(!composeBrushState.value.expanded) },
                onBrushSize = { value ->
                    if (value.isFinite()) {
                        engine.brushSize = value
                        vm.brushSize.value = engine.brushSize
                        composeBrushState.value = composeBrushState.value.copy(size = engine.brushSize)
                    }
                },
                onBrushOpacity = { value ->
                    if (value.isFinite()) {
                        val opacity = value.roundToInt().coerceIn(0, 100)
                        engine.opacity = opacity
                        vm.brushOpacity.value = engine.opacity
                        composeBrushState.value = composeBrushState.value.copy(opacity = engine.opacity.toFloat())
                    }
                },
                onBrushHardness = { value ->
                    if (value.isFinite()) {
                        engine.hardness = value / 100f
                        composeBrushState.value = composeBrushState.value.copy(hardness = engine.hardness * 100f)
                    }
                },
                onBrushStabilizer = { value ->
                    if (value.isFinite()) {
                        engine.stabilizer = value / 100f
                        composeBrushState.value = composeBrushState.value.copy(stabilizer = engine.stabilizer * 100f)
                    }
                },
                onBrushForceFade = { value ->
                    if (value.isFinite()) {
                        engine.forceFade = value / 100f
                        composeBrushState.value = composeBrushState.value.copy(forceFade = engine.forceFade * 100f)
                    }
                },
                onBrushSpacing = { value ->
                    if (value.isFinite()) {
                        engine.spacing = value / 100f
                        composeBrushState.value = composeBrushState.value.copy(spacing = engine.spacing * 100f)
                    }
                },
                onBrushFlow = { value ->
                    if (value.isFinite()) {
                        engine.flow = value / 100f
                        composeBrushState.value = composeBrushState.value.copy(flow = engine.flow * 100f)
                    }
                }
            )
        }
    }

    private fun refreshComposeLayers() {
        val workspace = vm.activeWorkspace ?: run {
            composeLayerItems.value = emptyList()
            return
        }
        workspace.syncFolderVisibility()
        binding.canvasView.syncCanvasStack()
        val drawableItems = binding.canvasView.canvasStack.asReversed().mapNotNull { stackItem ->
            when (stackItem.type) {
                CanvasStackType.PIXEL -> workspace.layers.firstOrNull { it.id == stackItem.id }?.let { layer ->
                    ComposeLayerItem(
                        id = layer.id,
                        name = layer.name,
                        kind = ComposeLayerKind.PIXEL,
                        thumbnail = layer.bitmap,
                        visible = layer.isVisible,
                        locked = layer.isLocked,
                        opacity = layer.opacity,
                        blendMode = composeBlendModeName(layer.blendMode),
                        selected = workspace.layers.getOrNull(workspace.activeLayerIndex)?.id == layer.id,
                        clippingMask = layer.isClippingMask,
                        alphaMaskEnabled = layer.isAlphaMaskEnabled,
                        hasAlphaMask = layer.hasAlphaMask,
                        folderId = layer.folderId,
                        canMoveToFolder = workspace.layerFolders.isNotEmpty()
                    )
                }
                CanvasStackType.IMAGE -> binding.canvasView.imageElements.firstOrNull { it.id == stackItem.id }?.let { image ->
                    ComposeLayerItem(
                        id = image.id,
                        name = image.name,
                        kind = ComposeLayerKind.IMAGE,
                        thumbnail = image.bitmap,
                        visible = image.isVisible,
                        locked = image.isLocked,
                        opacity = image.opacity,
                        blendMode = image.blendMode,
                        selected = binding.canvasView.activeImageId == image.id
                    )
                }
                CanvasStackType.TEXT -> binding.canvasView.textElements.firstOrNull { it.id == stackItem.id }?.let { text ->
                    ComposeLayerItem(
                        id = text.id,
                        name = text.name.ifBlank {
                            text.text.lineSequence().firstOrNull()?.take(24)?.ifBlank { "Text Layer" } ?: "Text Layer"
                        },
                        kind = ComposeLayerKind.TEXT,
                        visible = text.isVisible,
                        locked = text.isLocked,
                        opacity = text.opacity,
                        blendMode = text.blendMode,
                        selected = binding.canvasView.activeTextId == text.id
                    )
                }
            }
        }
        val foldersById = workspace.layerFolders.associateBy { it.id }
        val emittedFolders = mutableSetOf<String>()
        composeLayerItems.value = buildList {
            drawableItems.forEach { item ->
                val folder = item.folderId?.let(foldersById::get)
                if (folder != null && emittedFolders.add(folder.id)) {
                    add(ComposeLayerItem(
                        id = "folder:${folder.id}",
                        name = folder.name,
                        kind = ComposeLayerKind.FOLDER,
                        visible = folder.isVisible,
                        expanded = folder.isExpanded,
                        folderId = folder.id
                    ))
                }
                if (folder == null || folder.isExpanded) add(item)
            }
        }
    }

    private fun createComposeLayerFolder() {
        val workspace = vm.activeWorkspace ?: return
        if (binding.canvasView.activeTextId != null || binding.canvasView.activeImageId != null ||
            workspace.activeLayerIndex == 0
        ) {
            Toast.makeText(this, "Pilih pixel layer non-background untuk dimasukkan ke folder", Toast.LENGTH_SHORT).show()
            return
        }
        vm.createFolderForActiveLayer(workspace, "Folder ${workspace.layerFolders.size + 1}")
        refreshComposeLayers()
        binding.canvasView.invalidate()
        triggerAutoSave()
    }

    private fun renameComposeLayerItem(item: ComposeLayerItem, name: String) {
        val workspace = vm.activeWorkspace ?: return
        when (item.kind) {
            ComposeLayerKind.PIXEL -> {
                val index = workspace.layers.indexOfFirst { it.id == item.id }
                if (index >= 0) vm.renameLayer(workspace, index, name)
            }
            ComposeLayerKind.FOLDER -> item.folderId?.let { vm.renameFolder(workspace, it, name) }
            ComposeLayerKind.IMAGE -> binding.canvasView.imageElements
                .firstOrNull { it.id == item.id }?.name = name.trim().ifBlank { "Image Layer" }
            ComposeLayerKind.TEXT -> binding.canvasView.textElements
                .firstOrNull { it.id == item.id }?.name = name.trim().ifBlank { "Text Layer" }
        }
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun toggleComposeClippingMask(item: ComposeLayerItem) {
        if (item.kind != ComposeLayerKind.PIXEL) return
        val workspace = vm.activeWorkspace ?: return
        val index = workspace.layers.indexOfFirst { it.id == item.id }
        if (!vm.toggleClippingMask(workspace, index)) {
            Toast.makeText(this, "Background tidak dapat menjadi clipping mask", Toast.LENGTH_SHORT).show()
            return
        }
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun toggleComposeAlphaMask(item: ComposeLayerItem) {
        if (item.kind != ComposeLayerKind.PIXEL) return
        val workspace = vm.activeWorkspace ?: return
        val index = workspace.layers.indexOfFirst { it.id == item.id }
        if (!vm.toggleAlphaMask(workspace, index)) return
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun removeComposeAlphaMask(item: ComposeLayerItem) {
        if (item.kind != ComposeLayerKind.PIXEL) return
        val workspace = vm.activeWorkspace ?: return
        val index = workspace.layers.indexOfFirst { it.id == item.id }
        vm.removeAlphaMask(workspace, index)
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun toggleComposeFolderExpanded(item: ComposeLayerItem) {
        if (item.kind != ComposeLayerKind.FOLDER) return
        val workspace = vm.activeWorkspace ?: return
        item.folderId?.let { vm.toggleFolderExpanded(workspace, it) }
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun toggleComposeLayerFolder(item: ComposeLayerItem) {
        if (item.kind != ComposeLayerKind.PIXEL) return
        val workspace = vm.activeWorkspace ?: return
        val index = workspace.layers.indexOfFirst { it.id == item.id }
        if (index <= 0) return
        workspace.activeLayerIndex = index
        val layer = workspace.layers[index]
        val destinationId = if (layer.folderId != null) null else workspace.layerFolders.lastOrNull()?.id
        vm.moveActiveLayerToFolder(workspace, destinationId)
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun selectComposeLayer(item: ComposeLayerItem) {
        val workspace = vm.activeWorkspace ?: return
        when (item.kind) {
            ComposeLayerKind.FOLDER -> return
            ComposeLayerKind.PIXEL -> {
                val index = workspace.layers.indexOfFirst { it.id == item.id }
                if (index >= 0) {
                    workspace.activeLayerIndex = index
                    binding.canvasView.activeLayerIndex = index
                    binding.canvasView.activeTextId = null
                    binding.canvasView.activeImageId = null
                }
            }
            ComposeLayerKind.TEXT -> {
                binding.canvasView.activeTextId = item.id
                binding.canvasView.activeImageId = null
                binding.canvasView.currentTool = Tool.MOVE
                vm.currentTool.value = Tool.MOVE
            }
            ComposeLayerKind.IMAGE -> {
                binding.canvasView.activeImageId = item.id
                binding.canvasView.activeTextId = null
                binding.canvasView.currentTool = Tool.MOVE
                vm.currentTool.value = Tool.MOVE
            }
        }
        binding.canvasView.invalidate()
        refreshComposeLayers()
    }

    private fun toggleComposeLayerVisibility(item: ComposeLayerItem) {
        val workspace = vm.activeWorkspace ?: return
        when (item.kind) {
            ComposeLayerKind.FOLDER -> item.folderId?.let { vm.toggleFolderVisibility(workspace, it) }
            ComposeLayerKind.PIXEL -> workspace.layers.firstOrNull { it.id == item.id }?.let {
                it.isVisible = !it.isVisible
            }
            ComposeLayerKind.TEXT -> binding.canvasView.textElements.firstOrNull { it.id == item.id }?.let {
                it.isVisible = !it.isVisible
            }
            ComposeLayerKind.IMAGE -> binding.canvasView.imageElements.firstOrNull { it.id == item.id }?.let {
                it.isVisible = !it.isVisible
            }
        }
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun toggleComposeLayerLock(item: ComposeLayerItem) {
        when (item.kind) {
            ComposeLayerKind.FOLDER -> return
            ComposeLayerKind.PIXEL -> vm.activeWorkspace?.layers
                ?.firstOrNull { it.id == item.id }?.let { it.isLocked = !it.isLocked }
            ComposeLayerKind.TEXT -> binding.canvasView.textElements
                .firstOrNull { it.id == item.id }?.let { it.isLocked = !it.isLocked }
            ComposeLayerKind.IMAGE -> binding.canvasView.imageElements
                .firstOrNull { it.id == item.id }?.let { it.isLocked = !it.isLocked }
        }
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun updateComposeLayerOpacity(item: ComposeLayerItem, opacity: Int) {
        val safeOpacity = opacity.coerceIn(0, 100)
        when (item.kind) {
            ComposeLayerKind.FOLDER -> return
            ComposeLayerKind.PIXEL -> vm.activeWorkspace?.layers?.firstOrNull { it.id == item.id }?.opacity = safeOpacity
            ComposeLayerKind.TEXT -> binding.canvasView.textElements.firstOrNull { it.id == item.id }?.opacity = safeOpacity
            ComposeLayerKind.IMAGE -> binding.canvasView.imageElements.firstOrNull { it.id == item.id }?.opacity = safeOpacity
        }
        binding.canvasView.invalidate()
        // Slider events arrive every frame. Updating only the changed row avoids
        // rebuilding thumbnails and canvas-stack data while the panel animates.
        composeLayerItems.value = composeLayerItems.value.map { current ->
            if (current.id == item.id && current.kind == item.kind) {
                current.copy(opacity = safeOpacity)
            } else {
                current
            }
        }
    }

    private fun updateComposeLayerBlend(item: ComposeLayerItem, mode: String) {
        when (item.kind) {
            ComposeLayerKind.FOLDER -> return
            ComposeLayerKind.PIXEL -> {
                val modeIndex = LayerCompositor.blendModeNames.indexOf(mode).coerceAtLeast(0)
                vm.activeWorkspace?.layers?.firstOrNull { it.id == item.id }?.blendMode =
                    LayerCompositor.blendModeFromIndex(modeIndex)
            }
            ComposeLayerKind.TEXT -> binding.canvasView.textElements
                .firstOrNull { it.id == item.id }?.blendMode = mode
            ComposeLayerKind.IMAGE -> binding.canvasView.imageElements
                .firstOrNull { it.id == item.id }?.blendMode = mode
        }
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun composeBlendModeName(mode: android.graphics.PorterDuff.Mode): String = when (mode) {
        android.graphics.PorterDuff.Mode.MULTIPLY -> "Multiply"
        android.graphics.PorterDuff.Mode.SCREEN -> "Screen"
        android.graphics.PorterDuff.Mode.OVERLAY -> "Overlay"
        else -> "Normal"
    }

    private fun moveComposeSelectedLayer(up: Boolean) {
        val stack = binding.canvasView.canvasStack
        val activeId = binding.canvasView.activeTextId
            ?: binding.canvasView.activeImageId
            ?: vm.activeWorkspace?.layers?.getOrNull(vm.activeWorkspace?.activeLayerIndex ?: -1)?.id
            ?: return
        val from = stack.indexOfFirst { it.id == activeId }
        val to = if (up) from + 1 else from - 1
        if (from < 0 || to !in stack.indices) return
        val target = stack[to]
        if (target.type == CanvasStackType.PIXEL && vm.activeWorkspace?.layers?.firstOrNull()?.id == target.id) return
        java.util.Collections.swap(stack, from, to)
        vm.activeWorkspace?.let { saveCanvasStackOrder(it.id) }
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun duplicateComposeSelectedLayer() {
        val workspace = vm.activeWorkspace ?: return
        when {
            binding.canvasView.activeTextId != null -> {
                val source = binding.canvasView.textElements
                    .firstOrNull { it.id == binding.canvasView.activeTextId } ?: return
                val duplicate = source.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "${source.name} Copy",
                    x = source.x + 24f,
                    y = source.y + 24f,
                    spans = source.spans?.map { it.copy() }?.toMutableList(),
                    perspCorners = source.perspCorners?.clone(),
                    meshPoints = source.meshPoints?.clone()
                )
                binding.canvasView.textElements.add(duplicate)
                binding.canvasView.activeTextId = duplicate.id
                binding.canvasView.canvasStack.add(
                    CanvasStackItem(CanvasStackType.TEXT, duplicate.id)
                )
            }
            binding.canvasView.activeImageId != null -> {
                val source = binding.canvasView.imageElements
                    .firstOrNull { it.id == binding.canvasView.activeImageId } ?: return
                val bitmapCopy = runCatching {
                    source.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                }.getOrNull() ?: source.bitmap
                val duplicate = source.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    name = "${source.name} Copy",
                    bitmap = bitmapCopy,
                    x = source.x + 24f,
                    y = source.y + 24f
                )
                binding.canvasView.imageElements.add(duplicate)
                binding.canvasView.activeImageId = duplicate.id
                binding.canvasView.canvasStack.add(
                    CanvasStackItem(CanvasStackType.IMAGE, duplicate.id)
                )
            }
            else -> vm.duplicateLayer(workspace)
        }
        binding.canvasView.syncCanvasStack()
        saveCanvasStackOrder(workspace.id)
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun mergeComposeSelectedLayerDown() {
        val workspace = vm.activeWorkspace ?: return
        if (binding.canvasView.activeTextId != null || binding.canvasView.activeImageId != null) {
            Toast.makeText(this, "Gabung ke bawah hanya tersedia untuk pixel layer", Toast.LENGTH_SHORT).show()
            return
        }
        if (workspace.activeLayerIndex <= 0 || workspace.layers.size < 2) {
            Toast.makeText(this, "Tidak ada pixel layer di bawahnya", Toast.LENGTH_SHORT).show()
            return
        }
        pushWorkspaceSnapshot()
        vm.mergeDown(workspace)
        binding.canvasView.layers = workspace.layers
        binding.canvasView.activeLayerIndex = workspace.activeLayerIndex
        binding.canvasView.syncCanvasStack()
        saveCanvasStackOrder(workspace.id)
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun flattenComposeVisibleLayers() {
        val workspace = vm.activeWorkspace ?: return
        if (workspace.layers.count { it.isVisible } < 2) {
            Toast.makeText(this, "Minimal dua pixel layer terlihat untuk disatukan", Toast.LENGTH_SHORT).show()
            return
        }
        pushWorkspaceSnapshot()
        vm.mergeVisible(workspace)
        binding.canvasView.layers = workspace.layers
        binding.canvasView.activeLayerIndex = workspace.activeLayerIndex
        binding.canvasView.activeTextId = null
        binding.canvasView.activeImageId = null
        binding.canvasView.syncCanvasStack()
        saveCanvasStackOrder(workspace.id)
        binding.canvasView.invalidate()
        refreshComposeLayers()
        triggerAutoSave()
    }

    private fun deleteComposeSelectedLayer() {
        val workspace = vm.activeWorkspace ?: return
        val selectedName = when {
            binding.canvasView.activeTextId != null -> binding.canvasView.textElements
                .firstOrNull { it.id == binding.canvasView.activeTextId }?.name ?: "Text Layer"
            binding.canvasView.activeImageId != null -> binding.canvasView.imageElements
                .firstOrNull { it.id == binding.canvasView.activeImageId }?.name ?: "Image Layer"
            else -> workspace.layers.getOrNull(workspace.activeLayerIndex)?.name ?: "Layer"
        }
        if (binding.canvasView.activeTextId == null &&
            binding.canvasView.activeImageId == null &&
            workspace.layers.size <= 1
        ) {
            Toast.makeText(this, "Background utama tidak dapat dihapus", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Hapus layer?")
            .setMessage("'$selectedName' akan dihapus dari kanvas. Tindakan dapat dibatalkan dengan Undo.")
            .setNegativeButton("Batal", null)
            .setPositiveButton("Hapus") { _, _ ->
                when {
                    binding.canvasView.activeTextId != null -> {
                        binding.canvasView.pushTextHistory()
                        val id = binding.canvasView.activeTextId
                        binding.canvasView.textElements.removeAll { it.id == id }
                        binding.canvasView.activeTextId = null
                        updateTextQuickToolbar(null)
                    }
                    binding.canvasView.activeImageId != null -> {
                        binding.canvasView.pushImageHistory()
                        val id = binding.canvasView.activeImageId
                        binding.canvasView.imageElements.removeAll { it.id == id }
                        binding.canvasView.activeImageId = null
                    }
                    else -> {
                        pushWorkspaceSnapshot()
                        vm.deleteLayer(workspace) { removedLayerId ->
                            binding.canvasView.textElements
                                .filter { it.layerId == removedLayerId }
                                .forEach { it.layerId = null }
                        }
                    }
                }
                binding.canvasView.syncCanvasStack()
                saveCanvasStackOrder(workspace.id)
                binding.canvasView.invalidate()
                refreshComposeLayers()
                triggerAutoSave()
                updateStatus("Layer '$selectedName' dihapus")
            }
            .show()
    }

    private fun restoreCanvasStackOrder(workspaceId: String) {
        val encoded = getSharedPreferences("canvas_stack_order", MODE_PRIVATE)
            .getString(workspaceId, null)
            ?: return
        val restored = encoded.split('|').mapNotNull { token ->
            val separator = token.indexOf(':')
            if (separator <= 0 || separator >= token.lastIndex) return@mapNotNull null
            val type = runCatching { CanvasStackType.valueOf(token.substring(0, separator)) }.getOrNull()
                ?: return@mapNotNull null
            CanvasStackItem(type, token.substring(separator + 1))
        }
        if (restored.isNotEmpty()) {
            binding.canvasView.canvasStack.clear()
            binding.canvasView.canvasStack.addAll(restored)
        }
    }

    private fun showLayersPopup() {
        setLayersPanelOpen(!composeLayersOpen.value)
    }

    private fun setLayersPanelOpen(open: Boolean) {
        if (composeLayersOpen.value == open) return
        if (open) refreshComposeLayers()
        composeLayersOpen.value = open
    }

    @Suppress("unused")
    private fun showLegacyLayersPopup() {
        if (layersDialog?.isShowing == true) { layersDialog?.dismiss(); return }
        val ws = vm.activeWorkspace ?: return
        val db = com.vasiliastyper.databinding.DialogLayersBinding.inflate(layoutInflater)

        // Forward-declare so lambdas can reference it
        var popupAdapter: LayerAdapter? = null
        restoreCanvasStackOrder(ws.id)
        binding.canvasView.syncCanvasStack()

        popupAdapter = LayerAdapter(
            layers                       = ws.layers,
            // Fix #6: pass text and image element lists for section display
            textElements                 = binding.canvasView.textElements,
            imageElements                = binding.canvasView.imageElements,
            stackOrder                   = binding.canvasView.canvasStack,
            textLayerVisible             = binding.canvasView.textLayerVisible,
            onTextLayerVisibilityToggle  = { visible ->
                binding.canvasView.textLayerVisible = visible
                binding.canvasView.invalidate()
            },
            imageLayerVisible            = true,
            onImageLayerVisibilityToggle = { visible ->
                binding.canvasView.imageElements.forEach { /* visibility managed via adapter */ }
                binding.canvasView.invalidate()
            },
            onLayerClick = { idx ->
                ws.activeLayerIndex = idx
                binding.canvasView.activeLayerIndex = idx
                val layer = ws.layers.getOrNull(idx)
                db.spinnerBlendMode.setSelection(
                    LayerCompositor.blendModeNames.indexOf(layer?.blendMode?.name).coerceAtLeast(0)
                )
                db.seekLayerOpacity.progress = layer?.opacity ?: 100
                binding.canvasView.invalidate()
            },
            onVisibilityToggle = { idx ->
                ws.layers[idx].isVisible = !ws.layers[idx].isVisible
                binding.canvasView.invalidate()
            },
            onLockToggle = { idx -> ws.layers[idx].isLocked = !ws.layers[idx].isLocked },
            onRename = { idx, name ->
                vm.renameLayer(ws, idx, name)
                triggerAutoSave()
            },
            onMergeDown  = { idx ->
                // Make the tapped layer the active one, then merge it down
                pushWorkspaceSnapshot()
                ws.activeLayerIndex = idx
                vm.mergeDown(ws)
                binding.canvasView.syncCanvasStack()
                saveCanvasStackOrder(ws.id)
                popupAdapter?.selectedIndex = ws.activeLayerIndex
                popupAdapter?.notifyDataSetChanged()
                binding.canvasView.layers           = ws.layers
                binding.canvasView.activeLayerIndex = ws.activeLayerIndex
                binding.canvasView.invalidate()
                triggerAutoSave()
            },
            onTextElementClick = { idx ->
                val el = binding.canvasView.textElements.getOrNull(idx) ?: return@LayerAdapter

                // Ensure text row click truly activates its backing pixel layer.
                val linkedLayerIndex = ws.layers.indexOfFirst { it.id == el.layerId }
                if (linkedLayerIndex >= 0) {
                    ws.activeLayerIndex = linkedLayerIndex
                    binding.canvasView.activeLayerIndex = linkedLayerIndex
                    popupAdapter?.selectedIndex = linkedLayerIndex
                    val layer = ws.layers.getOrNull(linkedLayerIndex)
                    db.spinnerBlendMode.setSelection(
                        LayerCompositor.blendModeNames.indexOf(layer?.blendMode?.name).coerceAtLeast(0)
                    )
                    db.seekLayerOpacity.progress = layer?.opacity ?: 100
                }

                binding.canvasView.activeTextId = el.id
                binding.canvasView.activeImageId = null
                binding.canvasView.currentTool = Tool.MOVE
                vm.currentTool.value = Tool.MOVE
                popupAdapter?.selectedTextIndex = idx
                popupAdapter?.notifyDataSetChanged()
                binding.canvasView.invalidate()
                updateTextQuickToolbar(el)
            },
            onImageElementClick = { idx ->
                val img = binding.canvasView.imageElements.getOrNull(idx) ?: return@LayerAdapter
                popupAdapter?.selectedIndex = ws.activeLayerIndex
                binding.canvasView.activeImageId = img.id
                binding.canvasView.activeTextId = null
                binding.canvasView.currentTool = Tool.MOVE
                vm.currentTool.value = Tool.MOVE
                popupAdapter?.selectedImageIndex = idx
                popupAdapter?.notifyDataSetChanged()
                binding.canvasView.invalidate()
            },
            onStackChanged = {
                saveCanvasStackOrder(ws.id)
                binding.canvasView.invalidate()
                triggerAutoSave()
            },
            // All non-background entries use the same unrestricted drag handle.
            onStartDrag = { vh -> layersTouchHelper?.startDrag(vh) }
        )
        popupAdapter?.selectedIndex = ws.activeLayerIndex
        db.layersList.layoutManager = LinearLayoutManager(this)
        db.layersList.adapter       = popupAdapter

        // ── v5.3: Ibis / Clip Studio-style drag-to-reorder for pixel layers ──────────
        // Drag is initiated by touching the ≡ handle on a pixel-layer row.
        // Text / image / background / section rows are not draggable.
        run {
            val cb = object : ItemTouchHelper.Callback() {
                override fun isLongPressDragEnabled() = false   // start only via drag handle
                override fun isItemViewSwipeEnabled() = false

                override fun getMovementFlags(
                    rv: RecyclerView, vh: RecyclerView.ViewHolder
                ): Int {
                    val stackIndex = popupAdapter?.stackIndexAt(vh.bindingAdapterPosition) ?: -1
                    val isBackground = popupAdapter?.isBackgroundAt(vh.bindingAdapterPosition) ?: true
                    return if (stackIndex >= 0 && !isBackground) makeMovementFlags(
                        ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
                    ) else 0
                }

                override fun canDropOver(
                    rv: RecyclerView,
                    cur: RecyclerView.ViewHolder,
                    tgt: RecyclerView.ViewHolder
                ): Boolean {
                    val targetStack = popupAdapter?.stackIndexAt(tgt.bindingAdapterPosition) ?: -1
                    val targetIsBackground = popupAdapter?.isBackgroundAt(tgt.bindingAdapterPosition) ?: true
                    return targetStack >= 0 && !targetIsBackground
                }

                override fun onMove(
                    rv: RecyclerView,
                    cur: RecyclerView.ViewHolder,
                    tgt: RecyclerView.ViewHolder
                ): Boolean {
                    val fromStack = popupAdapter?.stackIndexAt(cur.bindingAdapterPosition) ?: -1
                    val toStack = popupAdapter?.stackIndexAt(tgt.bindingAdapterPosition) ?: -1
                    if (fromStack < 0 || toStack < 0) return false
                    return popupAdapter?.moveCanvasItem(fromStack, toStack) == true
                }

                override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}

                override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                    super.clearView(rv, vh)
                    triggerAutoSave()
                }
            }
            layersTouchHelper = ItemTouchHelper(cb)
            layersTouchHelper?.attachToRecyclerView(db.layersList)
        }

        db.spinnerBlendMode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, LayerCompositor.blendModeNames
        )
        db.spinnerBlendMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                ws.layers.getOrNull(ws.activeLayerIndex)?.blendMode = LayerCompositor.blendModeFromIndex(pos)
                binding.canvasView.invalidate()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Initial opacity readout ("100 %" style label, Ibis-like)
        val initOp = ws.layers.getOrNull(ws.activeLayerIndex)?.opacity ?: 100
        db.seekLayerOpacity.progress = initOp
        db.tvLayerOpacity.text = "$initOp %"
        db.seekLayerOpacity.setOnSeekBarChangeListener(seekListener { p ->
            ws.layers.getOrNull(ws.activeLayerIndex)?.opacity = p
            db.tvLayerOpacity.text = "$p %"
            popupAdapter?.notifyDataSetChanged()    // refresh sublabel "px · 80% …"
            binding.canvasView.invalidate()
        })
        db.btnAddLayer.setOnClickListener {
            vm.addLayer(ws)
            binding.canvasView.syncCanvasStack()
            saveCanvasStackOrder(ws.id)
            popupAdapter?.selectedIndex = ws.activeLayerIndex
            popupAdapter?.notifyDataSetChanged()
            binding.canvasView.invalidate()
        }
        db.btnDuplicateLayer.setOnClickListener {
            vm.duplicateLayer(ws)
            binding.canvasView.syncCanvasStack()
            saveCanvasStackOrder(ws.id)
            popupAdapter?.selectedIndex = ws.activeLayerIndex
            popupAdapter?.notifyDataSetChanged()
            binding.canvasView.invalidate()
        }
        db.btnDeleteLayer.setOnClickListener {
            val selected = popupAdapter?.selectedStackItem()
            when (selected?.type) {
                CanvasStackType.TEXT -> {
                    val removed = binding.canvasView.textElements.firstOrNull { it.id == selected.id }
                        ?: return@setOnClickListener
                    binding.canvasView.pushTextHistory()
                    binding.canvasView.textElements.remove(removed)
                    if (binding.canvasView.activeTextId == removed.id) binding.canvasView.activeTextId = null
                    popupAdapter?.clearElementSelection()
                    updateTextQuickToolbar(null)
                    updateStatus("Elemen teks dihapus dari layer panel")
                }
                CanvasStackType.IMAGE -> {
                    val removed = binding.canvasView.imageElements.firstOrNull { it.id == selected.id }
                        ?: return@setOnClickListener
                    binding.canvasView.pushImageHistory()
                    binding.canvasView.imageElements.remove(removed)
                    if (binding.canvasView.activeImageId == removed.id) binding.canvasView.activeImageId = null
                    popupAdapter?.clearElementSelection()
                    updateStatus("Elemen gambar dihapus dari layer panel")
                }
                CanvasStackType.PIXEL -> {
                    if (ws.layers.size <= 1) {
                        Toast.makeText(this, "Background utama tidak dapat dihapus", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    vm.deleteLayer(ws) { removedLayerId ->
                        binding.canvasView.textElements
                            .filter { it.layerId == removedLayerId }
                            .forEach { it.layerId = null }
                    }
                    updateStatus("Pixel layer dihapus")
                }
                null -> return@setOnClickListener
            }
            binding.canvasView.syncCanvasStack()
            saveCanvasStackOrder(ws.id)
            popupAdapter?.selectedIndex = ws.activeLayerIndex
            popupAdapter?.notifyDataSetChanged()
            binding.canvasView.invalidate()
            triggerAutoSave()
        }
        db.btnMergeVisible.setOnClickListener {
            val idx = ws.activeLayerIndex
            if (idx <= 0 || idx >= ws.layers.size) {
                Toast.makeText(this, "Pilih layer non-background untuk merge ke layer bawah", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.mergeDown(ws)
            binding.canvasView.syncCanvasStack()
            saveCanvasStackOrder(ws.id)
            popupAdapter?.selectedIndex = ws.activeLayerIndex
            popupAdapter?.notifyDataSetChanged()
            binding.canvasView.layers           = ws.layers
            binding.canvasView.activeLayerIndex = ws.activeLayerIndex
            binding.canvasView.invalidate()
            triggerAutoSave()
            updateStatus("Layer aktif berhasil digabung ke layer bawah")
        }
        db.btnFlatten.setOnClickListener {
            if (ws.layers.size < 2) {
                Toast.makeText(this, "Need at least 2 layers to flatten", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Make all layers visible, then merge them all
            ws.layers.forEach { it.isVisible = true }
            vm.mergeVisible(ws)
            binding.canvasView.syncCanvasStack()
            saveCanvasStackOrder(ws.id)
            popupAdapter?.selectedIndex = ws.activeLayerIndex
            popupAdapter?.notifyDataSetChanged()
            binding.canvasView.layers           = ws.layers
            binding.canvasView.activeLayerIndex = ws.activeLayerIndex
            binding.canvasView.invalidate()
            triggerAutoSave()
            updateStatus("All layers flattened")
        }

        // ── Shared stack order: works for pixel, image and text selections ──────
        db.btnLayerUp.setOnClickListener {
            if (popupAdapter?.moveSelected(up = true) != true) {
                Toast.makeText(this, "Sudah berada di posisi paling atas", Toast.LENGTH_SHORT).show()
            }
        }
        db.btnLayerDown.setOnClickListener {
            if (popupAdapter?.moveSelected(up = false) != true) {
                Toast.makeText(this, "Background adalah batas paling bawah", Toast.LENGTH_SHORT).show()
            }
        }

        // v5.3 — No .setTitle(): the new dialog_layers.xml has its own Ibis-style header bar.
        layersDialog = AlertDialog.Builder(this)
            .setView(db.root)
            .setOnDismissListener {
                layersDialog      = null
                layersTouchHelper = null   // detach to avoid leaking the old RecyclerView
            }
            .create()
        layersDialog?.show()
        layersDialog?.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
            window.setDimAmount(0.38f)
            window.attributes = window.attributes.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                gravity = Gravity.BOTTOM
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TOOLBAR
    // ══════════════════════════════════════════════════════════════════════════

    // v2.0.3 Fix #6: toolMoveElement added so its blue highlight is cleared
    // when any other tool is selected.
    private val toolButtons get() = listOf(
        binding.toolMove, binding.toolMoveElement, binding.toolRectSelect,
        binding.toolFreeSelect, binding.toolMagicWand,
        binding.toolBubbleClean, binding.toolBubbleTranslate,
        binding.toolBrush, binding.toolRemovR, binding.toolCloneStamp,
        binding.toolBrushPointer, binding.toolText, binding.toolTextShaper, binding.toolAddImage,
        binding.toolReferenceWindow, binding.toolWatermark, binding.toolZoom
    )

    private fun setupToolBar() {
        fun select(tool: Tool, btn: View) {
            // Cancel delayed long-press/path state before any tool transition.
            // A stale brush Runnable used to fire after the user had already
            // switched tools and could crash/reopen controls before first use.
            binding.canvasView.resetBrushGestureState()
            toolButtons.forEach { it.isSelected = false }
            btn.isSelected = true
            vm.currentTool.value           = tool
            binding.canvasView.currentTool = tool
            updateOptionsBar(tool)
            // v5.1: hide floating text quick-toolbar when leaving text tool
            if (tool != Tool.TEXT && tool != Tool.MESH_FORM) updateTextQuickToolbar(null)
        }
        binding.toolBubbleClean.visibility = View.VISIBLE
        binding.toolBubbleClean.isEnabled = true
        binding.toolMove.setOnClickListener        { select(Tool.PAN,  it) }     // v2.0: Pan
        binding.toolZoom.setOnClickListener        { select(Tool.ZOOM, it) }
        binding.toolMoveElement.setOnClickListener { select(Tool.MOVE, it) }     // v2.0: Move element
        binding.toolRectSelect.setOnClickListener { select(Tool.RECT_SELECT, it) }
        binding.toolFreeSelect.setOnClickListener { select(Tool.FREE_SELECT, it) }
        binding.toolMagicWand.setOnClickListener   { select(Tool.MAGIC_WAND, it) }
        binding.toolBubbleClean.setOnClickListener {
            select(Tool.BUBBLE_CLEAN, it)
            updateStatus("Bubble Clean aktif — tap balon di canvas untuk menghapus isi bubble")
        }
        // Long-press: toggle batch mode on the canvas.
        //   First long press  → enable batch mode (taps queue bubbles with numbered markers)
        //   Second long press → execute all queued bubbles / or cancel if queue empty
        binding.toolBubbleClean.setOnLongClickListener {
            showToolTip(
                "🫧 Bubble Cleaner\n\n" +
                "Tap balon untuk menghapus isi bubble.\n" +
                "Gunakan tolerance di toolbar atas untuk menyesuaikan area."
            )
            true
        }
        binding.toolBubbleDetect.setOnClickListener { runBubbleDetect() }
        binding.toolBubbleDetect.setOnLongClickListener {
            showToolTip(
                "🔎 Bubble Detector Hybrid\n\n" +
                "Sekali tap: kontur bubble + ML Kit Text diproses bersama.\n" +
                "Bubble yang menyatu dipisahkan dan teks di luar bubble disaring."
            )
            true
        }
        binding.toolBrush.setOnClickListener {
            select(Tool.BRUSH, it)
            updateStatus("Brush aktif — gambar warna pada layer aktif")
        }
        // Brush Inpaint dan Inpainting Tekstur Lokal telah dihapus dari UI.
        // Semua penghapusan berbasis mask sekarang memakai RemovR.
        binding.toolContentAware.visibility = View.GONE
        binding.toolContentAware.isEnabled = false
        binding.toolBrushInpaint.visibility = View.GONE
        binding.toolBrushInpaint.isEnabled = false
        binding.toolRemovR.setOnClickListener {
            select(Tool.REMOVR, it)
            updateStatus("RemovR aktif — sapukan objek, Resynthesizer menumbuhkan tekstur pengganti")
        }
        binding.toolRemovR.setOnLongClickListener {
            showToolTip(
                "RemovR (Resynthesizer Heal-Selection)\n\n" +
                    "Port dari light-and-ray/resynthesizer-python-lib (GIMP Resynthesizer).\n\n" +
                    "• Brush lembut (hardness < 95%): goresan menghapus piksel menjadi " +
                    "transparan, lalu engine menumbuhkan ulang tekstur di lubang tersebut — " +
                    "persis seperti resynthesize(source, mask) di library Python.\n" +
                    "• Brush keras: goresan hanya membentuk mask; objek di dalamnya dihapus " +
                    "dan diganti tekstur sintesis (object removal).\n\n" +
                    "Hasil dapat di-Undo."
            )
            true
        }
        binding.toolCloneStamp.setOnClickListener {
            select(Tool.CLONE_STAMP, it)
            binding.canvasView.clearCloneStampSource()
            updateStatus("Clone Stamp: tap canvas untuk memilih sumber, lalu drag untuk melukis")
        }
        binding.toolCloneStamp.setOnLongClickListener {
            showToolTip(
                "Clone Stamp\n\n" +
                    "1. Tap canvas untuk mengambil snapshot sumber.\n" +
                    "2. Drag pada area tujuan untuk menyalin tekstur.\n" +
                    "3. Tap ikon Clone lagi untuk memilih sumber baru.\n\n" +
                    "Sumber dibekukan saat sampling agar stroke tidak menyalin hasil lukisnya sendiri."
            )
            true
        }
        binding.toolBrushPointer.setOnClickListener {
            select(Tool.BLEMISH_REMOVAL, it)
            binding.canvasView.resetBlemishSelection()
            updateStatus("Blemish Removal — tap noda, lalu tap area bersih")
        }
        binding.toolBrushPointer.setOnLongClickListener {
            showToolTip(
                "Blemish Removal (Healing Brush)\n\n" +
                    "1. Tap noda/objek kecil yang ingin dihilangkan.\n" +
                    "2. Tap area bersih dengan tekstur serupa.\n" +
                    "3. OpenCV seamlessClone membaurkan tekstur sumber dengan pencahayaan target.\n\n" +
                    "Ukuran healing mengikuti Brush Size dan hasil dapat di-Undo."
            )
            true
        }
        binding.toolText.setOnClickListener {
            // If we were in TEXT_ERASE mode, switch back to TEXT on tap
            it.isActivated = false
            binding.toolText.setImageResource(R.drawable.ic_text)
            select(Tool.TEXT, it)
        }
        // Long-press on Text tool = activate TEXT_ERASE
        binding.toolTextShaper.setOnClickListener {
            textShaperSelectionHandled = false
            binding.canvasView.clearSelection()
            select(Tool.TEXT_SHAPER, it)
            updateStatus("Text Shaper aktif — buat kotak seleksi pada satu text layer yang sudah ditempatkan")
        }
        binding.toolTextShaper.setOnLongClickListener {
            showToolTip(
                "Text Shaper\n\n" +
                    "1. Buat kotak seleksi di atas text layer yang sudah ada.\n" +
                    "2. Jika beberapa teks terkena seleksi, pilih satu layer.\n" +
                    "3. Pilih profil dan variasi word wrap.\n" +
                    "4. Tekan Apply. Teks lain tidak ikut berubah."
            )
            true
        }
        binding.toolText.setOnLongClickListener {
            toolButtons.forEach { b -> b.isSelected = false }
            it.isSelected  = true
            it.isActivated = true
            binding.toolText.setImageResource(R.drawable.ic_erase)
            vm.currentTool.value           = Tool.TEXT_ERASE
            binding.canvasView.currentTool = Tool.TEXT_ERASE
            updateOptionsBar(Tool.TEXT_ERASE)
            updateTextQuickToolbar(null)
            Toast.makeText(this,
                "Text Erase aktif — brush di atas teks untuk hapus. Tap tombol Text untuk kembali.",
                Toast.LENGTH_LONG).show()
            true
        }
        binding.toolAddImage.setOnClickListener   { select(Tool.ADD_IMAGE, it); pickStampImage() }
        binding.toolPolyline.setOnClickListener   { select(Tool.MESH_FORM, it); toggleMeshForActiveText() }
        binding.toolReferenceWindow.setOnClickListener { select(Tool.REFERENCE_WINDOW, it); showReferenceWindow() }

        // ── Bubble Translation tool (BARU — terpisah dari Cleaner dan OCR) ──────
        binding.toolBubbleTranslate.setOnClickListener {
            select(Tool.BUBBLE_TRANSLATE, it)
            // Otomatis masuk RECT_SELECT agar user menyeret area, bukan tap
            binding.canvasView.currentTool = Tool.RECT_SELECT
            updateStatus("Bubble Translate: seret untuk buat seleksi area, lalu tekan ▶ Translate di atas")
        }
        binding.toolBubbleTranslate.setOnLongClickListener {
            showToolTip(
                "🌐 Bubble Translation\n\n" +
                "Cara pakai:\n" +
                "1. Tap icon ini untuk aktifkan\n" +
                "2. Seret di canvas untuk membuat area seleksi (bukan tap — agar tidak meluber)\n" +
                "3. Pilih Fill White / SmartFill dan Style di toolbar atas\n" +
                "4. Tekan ▶ Translate\n\n" +
                "• Fill White: hapus teks asli dengan warna solid\n" +
                "• SmartFill: rekonstruksi latar belakang secara otomatis"
            )
            true
        }

        // ── Watermark tool (BARU) ─────────────────────────────────────────────
        binding.toolWatermark.setOnClickListener { select(Tool.WATERMARK, it) }
        binding.toolWatermark.setOnLongClickListener {
            showToolTip(
                "🔒 Auto Watermark\n\n" +
                "Cara pakai:\n" +
                "1. Tap icon ini untuk aktifkan\n" +
                "2. Pilih gambar watermark di toolbar atas\n" +
                "3. Atur: Posisi (Kiri/Tengah/Kanan), Ukuran, Opacity, Jarak antar baris\n" +
                "4. Tekan ✓ Terapkan\n\n" +
                "Watermark gambar akan diulang dari atas ke bawah canvas\n" +
                "pada layer aktif."
            )
            true
        }

        // ── Mask Panel ────────────────────────────────────────────────────────
        binding.toolUnwatermark.setOnClickListener { toggleUnwatermarkPanel() }
        binding.toolUnwatermark.setOnLongClickListener {
            showToolTip(
                "UNWM — Watermark Remover (offline)\n\n" +
                    "1. Pilih PNG sampel watermark transparan.\n" +
                    "2. Buat seleksi tepat pada watermark di kanvas.\n" +
                    "3. Buka panel UNWM, atur Strength, lalu tekan Remove.\n\n" +
                    "Gunakan Auto-align jika posisi sampel meleset beberapa piksel. " +
                    "Hasil diterapkan hanya pada area seleksi dan bisa di-Undo."
            )
            true
        }

        binding.toolMask.setOnClickListener { toggleMaskPanel() }
        binding.toolMask.setOnLongClickListener {
            showToolTip(
                "🎭 Auto Mask\n\n" +
                "Deteksi teks di canvas secara otomatis menggunakan OpenCV, PP-OCR Lite, atau Gemini.\n\n" +
                "Cara pakai:\n" +
                "1. Tap 'Mask' untuk buka panel\n" +
                "2. Tekan 🔍 Detect untuk deteksi teks\n" +
                "3. Pilih aksi: Fill White atau SmartFill\n\n" +
                "Hasil deteksi muncul sebagai daftar region yang bisa dihapus satu per satu."
            )
            true
        }

        // ── Perspective & Mesh toggle buttons (text sub-tools) ───────────
        binding.toolPerspective.setOnClickListener { toggleTextPerspective() }
        binding.toolPerspective.setOnLongClickListener {
            showToolTip(
                "透视 Perspective\n\n" +
                "Cara pakai:\n" +
                "1. Tap teks yang sudah ditempatkan untuk memilih\n" +
                "2. Tap icon ini untuk aktifkan/nonaktifkan perspective\n" +
                "3. Drag sudut ungu untuk mengubah bentuk trapesium\n" +
                "4. Tap lagi untuk kembali ke layout normal"
            )
            true
        }
        binding.toolPolyline.setOnClickListener { toggleTextMesh() }
        binding.toolPolyline.setOnLongClickListener {
            showToolTip(
                "Mesh / Free-form Warp\n\n" +
                "Cara pakai:\n" +
                "1. Tap teks yang sudah ditempatkan untuk memilih\n" +
                "2. Tap icon ini untuk aktifkan mesh\n" +
                "3. Tap canvas untuk menambah titik baru\n" +
                "4. Drag titik teal untuk membentuk bentuk bebas\n" +
                "5. Tap icon ini lagi untuk nonaktifkan"
            )
            true
        }

        // ── Script Panel ──────────────────────────────────────────────────────
        binding.toolScript.setOnClickListener { showScriptPanel() }
        binding.toolAiChat.setOnClickListener { showAiChatPanel() }
        binding.toolAiChat.setOnLongClickListener {
            showToolTip(
                "AI Chat & Canvas Vision\n\n" +
                    "Panel bawah non-blocking untuk chat, analisis area seleksi, atau seluruh kanvas. " +
                    "Atur API key dari tombol Keys di dalam panel."
            )
            true
        }
        binding.toolVasType.setOnClickListener { showVasTypeDialog() }
        binding.toolVasType.setOnLongClickListener {
            showToolTip(
                "🧬 VasType\n\nImpor skrip OCR/TRANSLATE lalu cocokkan source text ke semua tab aktif (maks. 5).\nMode tersedia: inpainting saja, atau inpainting + place text."
            )
            true
        }

        // ── OCR Panel — hanya OCR, translate pindah ke Bubble Translate ───────
        runCatching {
            (binding.toolOcrPanel as View).setOnClickListener { toggleOcrPanel() }
            (binding.toolOcrPanel as View).setOnLongClickListener {
                showToolTip(
                    "🔍 OCR Panel\n\n" +
                    "Ekstrak dan terjemahkan teks dari area gambar.\n\n" +
                    "Cara pakai:\n" +
                    "1. Buat seleksi area dengan Rectangle Select atau Lasso\n" +
                    "2. Buka panel OCR (tap icon ini)\n" +
                    "3. Tekan ▶ OCR Seleksi\n\n" +
                    "Mendukung teks Latin, Chinese, Korean.\n" +
                    "Aktifkan Gemini AI untuk akurasi lebih tinggi."
                )
                true
            }
        }

        // ── Long-press tooltips untuk tool utama ──────────────────────────────
        binding.toolMove.setOnLongClickListener {
            showToolTip("✋ Pan Canvas\n\nGeser canvas untuk navigasi tanpa memindahkan elemen.\nPinch untuk zoom in/out.")
            true
        }
        binding.toolMoveElement.setOnLongClickListener {
            showToolTip("↕ Move Element\n\nTap elemen teks atau gambar untuk pilih, lalu seret untuk memindahkan.\nSeret sudut untuk resize.")
            true
        }
        binding.toolRectSelect.setOnLongClickListener {
            showToolTip("⬜ Rectangle Select\n\nSeret untuk membuat seleksi persegi panjang.\nGunakan tombol +Sel di atas untuk mode tambah seleksi.\nSetelah seleksi: bisa Fill White atau SmartFill.")
            true
        }
        binding.toolFreeSelect.setOnLongClickListener {
            showToolTip("🪢 Lasso Select\n\nGambar seleksi bebas mengikuti bentuk yang diinginkan.\nCocok untuk seleksi area berbentuk tidak teratur.")
            true
        }
        binding.toolMagicWand.setOnLongClickListener {
            showToolTip("🪄 Magic Wand\n\nTap area dengan warna serupa untuk membuat seleksi otomatis.\nAtur Tolerance di toolbar atas — semakin tinggi, semakin luas seleksi.")
            true
        }
        binding.toolBubbleClean.setOnLongClickListener {
            showToolTip(
                "Bubble Cleaner\n\n" +
                    "Tap balon untuk menghapus teks di dalamnya.\n" +
                    "Gunakan tombol Bubble Detect untuk memindai seluruh kanvas " +
                    "dan membuat seleksi multi-area otomatis."
            )
            true
        }
        binding.toolBrush.setOnLongClickListener {
            showToolTip("Brush biasa\n\nMenggambar warna solid pada layer aktif. Content-Aware sekarang memiliki tombol C-Aware terpisah.")
            true
        }

        binding.toolMove.isSelected = true
        vm.currentTool.value = Tool.PAN
        binding.canvasView.currentTool = Tool.PAN
    }

    /** Tampilkan tooltip sederhana via AlertDialog. */
    private fun showToolTip(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun setBrushControlsExpanded(expanded: Boolean) {
        brushControlsExpanded = expanded
        composeBrushState.value = composeBrushState.value.copy(expanded = expanded)
        val visibility = if (expanded) View.VISIBLE else View.GONE
        listOf(
            binding.optBrushSize,
            binding.seekBrushSize,
            binding.optOpacity,
            binding.seekOpacity,
            binding.optHardness,
            binding.seekBrushHard,
            binding.optStabilizer,
            binding.seekBrushStabilizer,
            binding.optForceFade,
            binding.seekBrushFade,
            binding.optBrushMode,
            binding.spinnerBrushMode
        ).forEach { it.visibility = visibility }
        binding.btnToggleBrushPanel.text = if (expanded) "−" else "+"
        binding.btnToggleBrushPanel.contentDescription = if (expanded) {
            "Tutup pengaturan brush"
        } else {
            "Buka pengaturan brush"
        }
    }

    private fun updateOptionsBar(tool: Tool) {
        binding.optionsLabel.visibility   = View.GONE
        binding.seekTolerance.visibility  = View.GONE
        binding.tvToleranceVal.visibility = View.GONE
        binding.brushControlPanel.visibility = View.GONE
        binding.optBrushSize.visibility   = View.GONE
        binding.seekBrushSize.visibility  = View.GONE
        binding.optOpacity.visibility     = View.GONE
        binding.seekOpacity.visibility    = View.GONE
        binding.optHardness.visibility    = View.GONE
        binding.seekBrushHard.visibility  = View.GONE
        binding.optStabilizer.visibility  = View.GONE
        binding.seekBrushStabilizer.visibility = View.GONE
        binding.optForceFade.visibility   = View.GONE
        binding.seekBrushFade.visibility  = View.GONE
        binding.optBrushMode.visibility   = View.GONE
        binding.spinnerBrushMode.visibility = View.GONE

        // Hide Bubble Translate controls
        binding.divBubbleTranslate.visibility = View.GONE
        binding.optBtFill.visibility          = View.GONE
        binding.spinnerBtFill.visibility      = View.GONE
        binding.optBtStyle.visibility         = View.GONE
        binding.spinnerBtStyle.visibility     = View.GONE
        binding.btnBtTranslate.visibility     = View.GONE

        // Hide Watermark controls
        binding.divWatermark.visibility    = View.GONE
        binding.optWmImage.visibility      = View.GONE
        binding.btnWmPickImage.visibility  = View.GONE
        binding.tvWmImageName.visibility   = View.GONE
        binding.optWmPos.visibility        = View.GONE
        binding.spinnerWmPosition.visibility = View.GONE
        binding.optWmSize.visibility       = View.GONE
        binding.seekWmSize.visibility      = View.GONE
        binding.tvWmSizeVal.visibility     = View.GONE
        binding.optWmOpacity.visibility    = View.GONE
        binding.seekWmOpacity.visibility   = View.GONE
        binding.tvWmOpacityVal.visibility  = View.GONE
        binding.optWmSpacing.visibility    = View.GONE
        binding.seekWmSpacing.visibility   = View.GONE
        binding.tvWmSpacingVal.visibility  = View.GONE
        binding.btnWmApply.visibility      = View.GONE

        // Selection mode and actions now live in one contextual panel below the options bar.
        // Keeping the panel hidden for unrelated tools preserves maximum canvas height.
        val isSelTool = tool in listOf(Tool.RECT_SELECT, Tool.FREE_SELECT, Tool.MAGIC_WAND)
        binding.selectionActionPanel.visibility = if (isSelTool) View.VISIBLE else View.GONE
        binding.btnAddToSel.visibility = if (isSelTool) View.VISIBLE else View.GONE
        binding.btnSubtractSel.visibility = if (isSelTool) View.VISIBLE else View.GONE

        when (tool) {
            Tool.RECT_SELECT -> {
                binding.optionsLabel.text = "RECT SELECT · drag untuk memilih"
                binding.optionsLabel.visibility = View.VISIBLE
            }
            Tool.TEXT_SHAPER -> {
                binding.optionsLabel.text = "TEXT SHAPER · kotakkan satu text layer yang sudah ditempatkan"
                binding.optionsLabel.visibility = View.VISIBLE
            }
            Tool.FREE_SELECT -> {
                binding.optionsLabel.text = "LASSO · gambar area tertutup"
                binding.optionsLabel.visibility = View.VISIBLE
            }
            Tool.MAGIC_WAND -> {
                binding.optionsLabel.text         = "MAGIC WAND · Tolerance:"
                binding.optionsLabel.visibility   = View.VISIBLE
                binding.seekTolerance.visibility  = View.VISIBLE
                binding.tvToleranceVal.visibility = View.VISIBLE
            }
            Tool.BUBBLE_CLEAN -> {
                // Label = tombol toggle mode (CEPAT/PRESISI). Selalu reset threshold ke 15.
                refreshBubbleCleanOptionsBar()
                binding.seekTolerance.progress = 15
                binding.tvToleranceVal.text    = "15"
                binding.canvasView.tolerance   = 15
            }
            Tool.BUBBLE_TRANSLATE -> {
                // Show Bubble Translate controls
                binding.divBubbleTranslate.visibility = View.VISIBLE
                binding.optBtFill.visibility          = View.VISIBLE
                binding.spinnerBtFill.visibility      = View.VISIBLE
                binding.optBtStyle.visibility         = View.VISIBLE
                binding.spinnerBtStyle.visibility     = View.VISIBLE
                binding.btnBtTranslate.visibility     = View.VISIBLE
                refreshBubbleTranslateStyleSpinner()
            }
            Tool.MESH_FORM -> Unit
            Tool.WATERMARK -> {
                // Compact quick bar; all advanced options live in the phone-friendly panel.
                binding.divWatermark.visibility   = View.VISIBLE
                binding.optWmImage.visibility     = View.VISIBLE
                binding.btnWmPickImage.visibility = View.VISIBLE
                binding.tvWmImageName.visibility  = View.VISIBLE
                binding.btnWmApply.visibility     = View.VISIBLE
            }
            Tool.BRUSH -> {
                binding.optionsLabel.text        = "Brush  (gambar pada layer aktif)"
                binding.optionsLabel.visibility  = View.VISIBLE
                binding.brushControlPanel.visibility = View.VISIBLE
                binding.optBrushSize.visibility  = View.VISIBLE
                binding.seekBrushSize.visibility = View.VISIBLE
                binding.optOpacity.visibility    = View.VISIBLE
                binding.seekOpacity.visibility   = View.VISIBLE
                binding.optHardness.visibility   = View.VISIBLE
                binding.seekBrushHard.visibility = View.VISIBLE
                binding.optStabilizer.visibility = View.VISIBLE
                binding.seekBrushStabilizer.visibility = View.VISIBLE
                binding.optForceFade.visibility = View.VISIBLE
                binding.seekBrushFade.visibility = View.VISIBLE
            }
            Tool.CONTENT_AWARE_BRUSH -> {
                binding.optionsLabel.text        = "Content-Aware  (OpenCV Patch + Criminisi + Resynthesizer)"
                binding.optionsLabel.visibility  = View.VISIBLE
                binding.brushControlPanel.visibility = View.VISIBLE
                binding.optBrushSize.visibility  = View.VISIBLE
                binding.seekBrushSize.visibility = View.VISIBLE
                binding.optOpacity.visibility    = View.VISIBLE
                binding.seekOpacity.visibility   = View.VISIBLE
                binding.optHardness.visibility   = View.VISIBLE
                binding.seekBrushHard.visibility = View.VISIBLE
                binding.optStabilizer.visibility = View.VISIBLE
                binding.seekBrushStabilizer.visibility = View.VISIBLE
                binding.optForceFade.visibility = View.VISIBLE
                binding.seekBrushFade.visibility = View.VISIBLE
            }
            Tool.BRUSH_INPAINT -> {
                binding.optionsLabel.text        = "Brush Inpaint  (OpenCV Patch • bukan Telea/NS)"
                binding.optionsLabel.visibility  = View.VISIBLE
                binding.brushControlPanel.visibility = View.VISIBLE
                binding.optBrushSize.visibility  = View.VISIBLE
                binding.seekBrushSize.visibility = View.VISIBLE
                binding.optOpacity.visibility    = View.VISIBLE
                binding.seekOpacity.visibility   = View.VISIBLE
                binding.optHardness.visibility   = View.VISIBLE
                binding.seekBrushHard.visibility = View.VISIBLE
                binding.optStabilizer.visibility = View.VISIBLE
                binding.seekBrushStabilizer.visibility = View.VISIBLE
                binding.optForceFade.visibility = View.VISIBLE
                binding.seekBrushFade.visibility = View.VISIBLE
            }
            Tool.REMOVR -> {
                binding.optionsLabel.text        = "RemovR  (Resynthesizer • brush lembut = hapus + tumbuhkan tekstur)"
                binding.optionsLabel.visibility  = View.VISIBLE
                binding.brushControlPanel.visibility = View.VISIBLE
                binding.optBrushSize.visibility  = View.VISIBLE
                binding.seekBrushSize.visibility = View.VISIBLE
                binding.optOpacity.visibility    = View.VISIBLE
                binding.seekOpacity.visibility   = View.VISIBLE
                binding.optHardness.visibility   = View.VISIBLE
                binding.seekBrushHard.visibility = View.VISIBLE
                binding.optStabilizer.visibility = View.VISIBLE
                binding.seekBrushStabilizer.visibility = View.VISIBLE
                binding.optForceFade.visibility = View.VISIBLE
                binding.seekBrushFade.visibility = View.VISIBLE
            }
            Tool.CLONE_STAMP -> {
                binding.optionsLabel.text        = "Clone Stamp  (tap sumber, lalu drag)"
                binding.optionsLabel.visibility  = View.VISIBLE
                binding.brushControlPanel.visibility = View.VISIBLE
                binding.optBrushSize.visibility  = View.VISIBLE
                binding.seekBrushSize.visibility = View.VISIBLE
                binding.optOpacity.visibility    = View.VISIBLE
                binding.seekOpacity.visibility   = View.VISIBLE
                binding.optHardness.visibility   = View.VISIBLE
                binding.seekBrushHard.visibility = View.VISIBLE
                binding.optStabilizer.visibility = View.VISIBLE
                binding.seekBrushStabilizer.visibility = View.VISIBLE
                binding.optForceFade.visibility = View.VISIBLE
                binding.seekBrushFade.visibility = View.VISIBLE
            }
            Tool.BLEMISH_REMOVAL -> {
                binding.optionsLabel.text        = "Blemish Removal  (tap noda → tap sumber bersih)"
                binding.optionsLabel.visibility  = View.VISIBLE
                binding.brushControlPanel.visibility = View.VISIBLE
                binding.optBrushSize.visibility  = View.VISIBLE
                binding.seekBrushSize.visibility = View.VISIBLE
                binding.optOpacity.visibility    = View.VISIBLE
                binding.seekOpacity.visibility   = View.VISIBLE
                binding.optHardness.visibility   = View.VISIBLE
                binding.seekBrushHard.visibility = View.VISIBLE
                binding.optStabilizer.visibility = View.VISIBLE
                binding.seekBrushStabilizer.visibility = View.VISIBLE
                binding.optForceFade.visibility = View.VISIBLE
                binding.seekBrushFade.visibility = View.VISIBLE
            }
            Tool.TEXT_ERASE -> {
                binding.optionsLabel.text        = "Text Erase  (tahan lama tool Text)"
                binding.optionsLabel.visibility  = View.VISIBLE
                binding.brushControlPanel.visibility = View.VISIBLE
                binding.optBrushSize.visibility  = View.VISIBLE
                binding.seekBrushSize.visibility = View.VISIBLE
                binding.optOpacity.visibility    = View.VISIBLE
                binding.seekOpacity.visibility   = View.VISIBLE
                binding.optHardness.visibility   = View.VISIBLE
                binding.seekBrushHard.visibility = View.VISIBLE
                binding.optStabilizer.visibility = View.VISIBLE
                binding.seekBrushStabilizer.visibility = View.VISIBLE
                binding.optForceFade.visibility = View.VISIBLE
                binding.seekBrushFade.visibility = View.VISIBLE
            }
            else -> {
                binding.optionsLabel.text       = tool.name.replace('_', ' ')
                binding.optionsLabel.visibility = View.VISIBLE
            }
        }
        val showBrushControls = binding.brushControlPanel.visibility == View.VISIBLE
        if (showBrushControls) {
            setBrushControlsExpanded(brushControlsExpanded)
        }
        // Keep the proven Android View brush panel as the active control surface. It now
        // occupies a dedicated bottom panel, so controls remain reachable without covering
        // the artwork. Other Compose chrome (notably Layers) remains enabled.
        binding.brushControlPanel.visibility = if (showBrushControls) View.VISIBLE else View.GONE
        composeBrushState.value = composeBrushState.value.copy(
            visible = false,
            toolName = tool.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
        )
        // Show "Center in Bubble" when any selection is active
        refreshCenterInBubbleBtn()
    }

    private fun setupBrushModeSpinner() {
        val engine = binding.canvasView.brushEngine
        val labels = listOf("Brush Patch", "Vector Line")
        val preferences = getSharedPreferences("editor_preferences", MODE_PRIVATE)
        engine.mode = runCatching {
            BrushEngine.Mode.valueOf(
                preferences.getString("brush_mode", BrushEngine.Mode.PATCH.name)
                    ?: BrushEngine.Mode.PATCH.name
            )
        }.getOrDefault(BrushEngine.Mode.PATCH)

        binding.spinnerBrushMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.spinnerBrushMode.setSelection(engine.mode.ordinal, false)
        binding.spinnerBrushMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                engine.mode = BrushEngine.Mode.entries.getOrElse(position) { BrushEngine.Mode.PATCH }
                preferences.edit().putString("brush_mode", engine.mode.name).apply()
                binding.optBrushMode.text = "Mode • ${labels[engine.mode.ordinal]}"
                binding.spinnerBrushMode.contentDescription = labels[engine.mode.ordinal]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.optBrushMode.text = "Mode • ${labels[engine.mode.ordinal]}"
    }

    private fun setupOptionsBar() {
        var syncingSelectionMode = false

        fun applySelectionMode(mode: SelectionCombineMode) {
            syncingSelectionMode = true
            binding.canvasView.selectionCombineMode = mode
            binding.btnAddToSel.isChecked = mode == SelectionCombineMode.ADD
            binding.btnSubtractSel.isChecked = mode == SelectionCombineMode.SUBTRACT
            binding.btnAddToSel.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (mode == SelectionCombineMode.ADD) "#147D72" else "#394555")
            )
            binding.btnSubtractSel.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (mode == SelectionCombineMode.SUBTRACT) "#B84A4A" else "#5A2A2A")
            )
            updateStatus(
                when (mode) {
                    SelectionCombineMode.ADD -> "Selection: tambah area"
                    SelectionCombineMode.SUBTRACT -> "Selection: kurangi area"
                    SelectionCombineMode.REPLACE -> "Selection: ganti area"
                }
            )
            binding.canvasView.invalidate()
            syncingSelectionMode = false
        }

        applySelectionMode(SelectionCombineMode.ADD)
        binding.btnAddToSel.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSelectionMode) return@setOnCheckedChangeListener
            if (isChecked) {
                applySelectionMode(SelectionCombineMode.ADD)
            } else if (!binding.btnSubtractSel.isChecked) {
                applySelectionMode(SelectionCombineMode.REPLACE)
            }
        }
        binding.btnSubtractSel.setOnCheckedChangeListener { _, isChecked ->
            if (syncingSelectionMode) return@setOnCheckedChangeListener
            if (isChecked) {
                applySelectionMode(SelectionCombineMode.SUBTRACT)
            } else if (!binding.btnAddToSel.isChecked) {
                applySelectionMode(SelectionCombineMode.REPLACE)
            }
        }



        binding.btnToggleBrushPanel.setOnClickListener {
            setBrushControlsExpanded(!brushControlsExpanded)
        }

        setupBrushModeSpinner()

        binding.seekTolerance.setOnSeekBarChangeListener(seekListener { p ->
            vm.tolerance.value           = p
            binding.canvasView.tolerance = p
            binding.tvToleranceVal.text  = p.toString()
        })
        binding.seekBrushSize.setOnSeekBarChangeListener(seekListener { p ->
            val s = (p + 1).toFloat()
            vm.brushSize.value                       = s
            binding.canvasView.brushEngine.brushSize = s
            binding.optBrushSize.text = "Ukuran • ${s.roundToInt()} px"
        })
        binding.seekOpacity.setOnSeekBarChangeListener(seekListener { p ->
            vm.brushOpacity.value                    = p
            binding.canvasView.brushEngine.opacity   = p
            binding.optOpacity.text = "Opasitas • $p%"
        })
        binding.seekBrushHard.setOnSeekBarChangeListener(seekListener { p ->
            // progress 0..100 → hardness 0.0..1.0
            binding.canvasView.brushEngine.hardness = p / 100f
            binding.optHardness.text = "Hardness • $p%"
        })
        binding.seekBrushStabilizer.setOnSeekBarChangeListener(seekListener { p ->
            binding.canvasView.brushEngine.stabilizer = p / 100f
            binding.optStabilizer.text = "Stabilizer • $p%"
        })
        binding.seekBrushFade.setOnSeekBarChangeListener(seekListener { p ->
            binding.canvasView.brushEngine.forceFade = p / 100f
            binding.optForceFade.text = "Force Fade • $p%"
        })

        binding.seekBrushSize.progress = binding.canvasView.brushEngine.brushSize.roundToInt().coerceAtLeast(1) - 1
        binding.seekOpacity.progress = binding.canvasView.brushEngine.opacity
        binding.seekBrushHard.progress = (binding.canvasView.brushEngine.hardness * 100f).roundToInt()
        binding.seekBrushStabilizer.progress = (binding.canvasView.brushEngine.stabilizer * 100f).roundToInt()
        binding.seekBrushFade.progress = (binding.canvasView.brushEngine.forceFade * 100f).roundToInt()
        binding.btnFillWhite.setOnClickListener {
            val ws2 = vm.activeWorkspace ?: return@setOnClickListener
            vm.pushHistory(ws2)
            showMaskProgress("Fill putih…", indeterminate = true)
            lifecycleScope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) { binding.canvasView.fillSelection(Color.WHITE) }
                }.getOrDefault(false)
                hideMaskProgress()
                updateStatus(if (ok) "Fill putih selesai ✓" else "Fill putih gagal")
            }
        }
        binding.btnFillBlack.setOnClickListener {
            val ws2 = vm.activeWorkspace ?: return@setOnClickListener
            vm.pushHistory(ws2)
            showMaskProgress("Fill hitam…", indeterminate = true)
            lifecycleScope.launch {
                val ok = runCatching {
                    withContext(Dispatchers.IO) { binding.canvasView.fillSelection(Color.BLACK) }
                }.getOrDefault(false)
                hideMaskProgress()
                updateStatus(if (ok) "Fill hitam selesai ✓" else "Fill hitam gagal")
            }
        }
        binding.btnCopySelection.setOnClickListener {
            if (vm.activeWorkspace == null) return@setOnClickListener
            val copied = binding.canvasView.copySelectionAsImageElement()
            if (copied == null) {
                Toast.makeText(this, "Area seleksi tidak dapat dicopy", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.canvasView.pushImageHistory()
            binding.canvasView.imageElements.add(copied)
            binding.canvasView.activeImageId = copied.id
            binding.canvasView.currentTool = Tool.MOVE
            vm.currentTool.value = Tool.MOVE
            binding.canvasView.invalidate()
            triggerAutoSave()
            updateStatus("Area seleksi dicopy tanpa mengubah gambar asli")
        }
        binding.btnInpaintTelea.setOnClickListener {
            val ws = vm.activeWorkspace ?: return@setOnClickListener
            if (!binding.canvasView.selection.isActive) return@setOnClickListener
            showSmartFillChooser { backend ->
                vm.pushHistory(ws)
                binding.btnInpaintTelea.isEnabled = false
                val label = smartFillBackendLabel(backend)
                showMaskProgress("SmartFill $label…", indeterminate = false)
                updateStatus("SmartFill $label…")
                lifecycleScope.launch {
                    val applied = withContext(Dispatchers.IO) {
                        binding.canvasView.applySmartFill(backend) { p ->
                            updateMaskProgress(p, "SmartFill $label…")
                        }
                    }
                    binding.btnInpaintTelea.isEnabled = true
                    hideMaskProgress()
                    updateStatus(if (applied) "SmartFill done ✓" else "Nothing to fill")
                }
            }
        }
        // v10: Inpaint seleksi langsung (Rect/Lasso/Magic Wand) dengan engine
        // patch OpenCV native (bukan Telea/Navier-Stokes) — rekomendasi untuk
        // object removal: seleksi objek → satu ketukan → tekstur direkonstruksi.
        binding.btnInpaintSelection.setOnClickListener {
            val ws = vm.activeWorkspace ?: return@setOnClickListener
            if (!binding.canvasView.selection.isActive) return@setOnClickListener
            vm.pushHistory(ws)
            binding.btnInpaintSelection.isEnabled = false
            showMaskProgress("Inpaint OpenCV patch…", indeterminate = false)
            updateStatus("Inpaint seleksi: OpenCV patch synthesis…")
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    binding.canvasView.inpaintSelectionWithOpenCv { p ->
                        updateMaskProgress(p, "Inpaint OpenCV…")
                    }
                }
                binding.btnInpaintSelection.isEnabled = true
                hideMaskProgress()
                if (result.success) {
                    updateStatus("Inpaint selesai ✓ • ${result.processedPixels} px")
                    triggerAutoSave()
                } else {
                    updateStatus("Inpaint gagal: ${result.message}")
                    Toast.makeText(this@MainActivity, "Inpaint gagal: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        // Center in Bubble — opens text editor centered on the active selection
        binding.btnCenterInBubble.setOnClickListener {
            val sel    = binding.canvasView.selection
            if (!sel.isActive) return@setOnClickListener
            val bounds = sel.getBounds()
            safeShowTextEditorDialogInBounds(bounds.left, bounds.top, bounds.width(), bounds.height(), padding = 8f)
        }

        // Fix #8: Crop active layer to selection bounds
        binding.btnCropToSel.setOnClickListener {
            val ws = vm.activeWorkspace ?: return@setOnClickListener
            val sel = binding.canvasView.selection
            if (!sel.isActive) return@setOnClickListener
            val bounds = sel.getBounds()
            val rect = android.graphics.Rect(
                bounds.left.toInt().coerceIn(0, ws.width),
                bounds.top.toInt().coerceIn(0, ws.height),
                bounds.right.toInt().coerceIn(0, ws.width),
                bounds.bottom.toInt().coerceIn(0, ws.height)
            )
            if (rect.isEmpty || rect.width() < 1 || rect.height() < 1) {
                Toast.makeText(this, "Selection too small to crop", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.pushHistory(ws)
            for (layer in ws.layers) {
                val cropped = android.graphics.Bitmap.createBitmap(
                    layer.bitmap, rect.left, rect.top, rect.width(), rect.height()
                )
                val newBmp = android.graphics.Bitmap.createBitmap(
                    rect.width(), rect.height(), android.graphics.Bitmap.Config.ARGB_8888
                )
                android.graphics.Canvas(newBmp).drawBitmap(cropped, 0f, 0f, null)
                cropped.recycle()
                layer.bitmap = newBmp
            }
            binding.canvasView.textElements.forEach  { el  -> el.x  -= rect.left; el.y  -= rect.top }
            binding.canvasView.imageElements.forEach { img -> img.x -= rect.left; img.y -= rect.top }
            ws.width  = rect.width()
            ws.height = rect.height()
            binding.canvasView.setCanvasSize(ws.width, ws.height)
            binding.canvasView.clearSelection()
            binding.canvasView.zoomToFit()
            triggerAutoSave()
            updateStatus("Cropped to ${rect.width()}×${rect.height()}")
        }
    }

    /** Show / hide the "Center in Bubble" button based on whether a selection exists */
    private fun refreshCenterInBubbleBtn() {
        val hasSelection = binding.canvasView.selection.isActive
        binding.btnCenterInBubble.visibility = if (hasSelection) View.VISIBLE else View.GONE
    }

    /**
     * Update options bar untuk tool BUBBLE_CLEAN.
     * optionsLabel menampilkan mode aktif dan bisa di-tap untuk toggle CEPAT/PRESISI.
     * Dipanggil saat tool dipilih dan setiap kali mode berubah.
     */
    private fun refreshBubbleCleanOptionsBar() {
        val isFast = bubbleCleanMode == BubbleCleaner.CleanMode.FAST
        binding.optionsLabel.text       = if (isFast) "▶ CEPAT  |  Presisi" else "Cepat  |  ▶ PRESISI"
        binding.optionsLabel.visibility = View.VISIBLE
        binding.optionsLabel.isClickable = true
        binding.optionsLabel.setOnClickListener {
            bubbleCleanMode = if (bubbleCleanMode == BubbleCleaner.CleanMode.FAST)
                BubbleCleaner.CleanMode.PRECISE else BubbleCleaner.CleanMode.FAST
            refreshBubbleCleanOptionsBar()
            val name = if (bubbleCleanMode == BubbleCleaner.CleanMode.FAST)
                "CEPAT — isi seluruh balon" else "PRESISI — hapus teks saja"
            updateStatus("Mode: $name")
        }
        binding.seekTolerance.visibility  = View.VISIBLE
        binding.tvToleranceVal.visibility = View.VISIBLE
    }

    // ══════════════════════════════════════════════════════════════════════════
    // BUBBLE TRANSLATE OPTIONS BAR SETUP
    // ══════════════════════════════════════════════════════════════════════════

    fun setupBubbleTranslateOptionsBar() {
        // Fill type spinner
        val fillItems = arrayOf("Fill White", "Fill Black", "Inpainting")
        binding.spinnerBtFill.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, fillItems
        )
        binding.spinnerBtFill.setSelection(0)
        binding.spinnerBtFill.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                btFillMode = pos
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // Style spinner — refreshed lazily on show (see refreshBubbleTranslateStyleSpinner)
        refreshBubbleTranslateStyleSpinner()

        // Translate button
        binding.btnBtTranslate.setOnClickListener {
            val sel = binding.canvasView.selection
            if (!sel.isActive) {
                Toast.makeText(this, "Buat seleksi area bubble dulu dengan cara menyeret", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            runBubbleTranslateForSelection()
        }
    }

    /** Refresh style spinner dengan daftar style yang tersimpan. */
    private fun refreshBubbleTranslateStyleSpinner() {
        val styles = com.vasiliastyper.engine.StyleManager.loadStyles(this)
        val styleNames = mutableListOf("Auto (match font)")
        styleNames.addAll(styles.map { "${it.name} [${it.folder}]" })
        binding.spinnerBtStyle.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, styleNames
        )
        binding.spinnerBtStyle.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                btSelectedStyleName = if (pos == 0) null else styles.getOrNull(pos - 1)?.name
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    /**
     * Jalankan pipeline Bubble Translation dari seleksi aktif.
     * Sebelum translate: hapus teks asli sesuai fill mode (White / Black / Inpaint).
     * Font diambil dari style yang dipilih user, atau auto-match jika tidak ada.
     */
    private fun runBubbleTranslateForSelection() {
        val ws    = vm.activeWorkspace ?: return
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return
        if (!layer.bitmap.isMutable) {
            Toast.makeText(this, "Layer aktif tidak bisa diedit", Toast.LENGTH_SHORT).show()
            return
        }
        val sel   = binding.canvasView.selection
        if (!sel.isActive) return
        val areas = sel.getAreas().ifEmpty { listOf(sel.getBounds()) }
            .map { RectF(it) }
            .filter { it.width() > 6f && it.height() > 6f }
        if (areas.isEmpty()) {
            Toast.makeText(this, "Seleksi terlalu kecil", Toast.LENGTH_SHORT).show()
            return
        }

        vm.pushHistory(ws)
        updateStatus("Bubble Translate: memproses ${areas.size} area…")

        lifecycleScope.launch {
            val srcLang = ocrSrcCodes.getOrNull(binding.spinnerOcrSrc.selectedItemPosition) ?: "auto"
            val tgtLang = ocrTgtCodes.getOrNull(binding.spinnerOcrTgt.selectedItemPosition) ?: "id"
            // v11.2 fix: see placeAllPendingTipeRMatches() — cold-cache Typeface decode
            // must not run on the Main dispatcher.
            val fonts = withContext(Dispatchers.Default) { buildFontList() }

            // Pilih style jika user memilih dari spinner
            val chosenStyle: com.vasiliastyper.model.TextStyle? = if (btSelectedStyleName != null) {
                com.vasiliastyper.engine.StyleManager.loadStyles(this@MainActivity)
                    .firstOrNull { it.name == btSelectedStyleName }
            } else null

            val composite = withContext(Dispatchers.IO) {
                binding.canvasView.compositeVisibleLayers()
                    ?: LayerCompositor.composite(ws.layers, ws.width, ws.height)
            } ?: run {
                updateStatus("Bubble Translate gagal: komposit kosong")
                return@launch
            }

            var translatedCount = 0
            for ((idx, rawArea) in areas.withIndex()) {
                val area = RectF(
                    rawArea.left.coerceIn(0f, (composite.width - 1).toFloat()),
                    rawArea.top.coerceIn(0f, (composite.height - 1).toFloat()),
                    rawArea.right.coerceIn(1f, composite.width.toFloat()),
                    rawArea.bottom.coerceIn(1f, composite.height.toFloat())
                )
                if (area.width() < 6f || area.height() < 6f) continue

                val x  = area.left.toInt().coerceIn(0, composite.width - 1)
                val y  = area.top.toInt().coerceIn(0, composite.height - 1)
                val w  = area.width().toInt().coerceIn(1, composite.width - x)
                val h  = area.height().toInt().coerceIn(1, composite.height - y)
                val crop = Bitmap.createBitmap(composite, x, y, w, h)

                val pair       = detectAndTranslateAreaText(crop, srcLang, tgtLang)
                val translated = pair?.second?.trim().orEmpty()

                if (translated.isNotEmpty()) {
                    val strictRect = Rect(x, y, x + w, y + h)

                    // 1. Hapus teks asli sesuai pilihan fill
                    withContext(Dispatchers.IO) {
                        when (btFillMode) {
                            0 -> applyTextMaskOnly(composite, layer.bitmap, strictRect, Color.WHITE, bleedPx = 1)
                            1 -> applyTextMaskOnly(composite, layer.bitmap, strictRect, Color.BLACK, bleedPx = 1)
                            2 -> {
                                // Inpainting menggunakan region persegi area terpilih
                                try {
                                    val inpaintRegion = android.graphics.Region(strictRect)
                                    val ok = com.vasiliastyper.engine.Inpainter.inpaintInPlace(layer.bitmap, inpaintRegion)
                                    if (!ok) {
                                        applyTextMaskOnly(composite, layer.bitmap, strictRect, Color.WHITE, bleedPx = 1)
                                    } else Unit
                                } catch (_: Exception) {
                                    applyTextMaskOnly(composite, layer.bitmap, strictRect, Color.WHITE, bleedPx = 1)
                                }
                            }
                            else -> applyTextMaskOnly(composite, layer.bitmap, strictRect, Color.WHITE, bleedPx = 1)
                        }
                    }

                    // 2. Pilih font: dari style yang dipilih, lalu font linked text, lalu auto
                    val finalTypeface: android.graphics.Typeface?
                    val finalFontName: String
                    val finalColor:    Int
                    val finalBold:     Boolean
                    val finalItalic:   Boolean
                    val finalAlign:    com.vasiliastyper.model.TextAlign
                    val finalEffect:   com.vasiliastyper.model.TextEffect

                    if (chosenStyle != null) {
                        val resolvedFont = fonts.firstOrNull {
                            it.displayName.equals(chosenStyle.fontName, ignoreCase = true)
                        }
                        finalTypeface  = resolvedFont?.typeface
                        finalFontName  = resolvedFont?.displayName ?: chosenStyle.fontName
                        finalColor     = chosenStyle.color
                        finalBold      = chosenStyle.isBold
                        finalItalic    = chosenStyle.isItalic
                        finalAlign     = com.vasiliastyper.model.TextAlign.valueOf(chosenStyle.align)
                        finalEffect    = com.vasiliastyper.model.TextEffect.valueOf(chosenStyle.effect)
                    } else {
                        // Auto: cari font dari elemen teks yang beririsan dengan area ini
                        val linkedText = binding.canvasView.textElements.lastOrNull { el ->
                            RectF.intersects(area, RectF(el.x, el.y, el.x + el.width, el.y + el.height))
                        }
                        val selFont = linkedText?.fontName
                            ?.let { name -> fonts.firstOrNull { it.displayName.equals(name, ignoreCase = true) } }
                            ?: chooseFontForTranslation(pair?.first.orEmpty(), fonts)
                        finalTypeface  = selFont?.typeface
                        finalFontName  = selFont?.displayName ?: "Default"
                        finalColor     = linkedText?.color ?: estimateTextColor(crop)
                        finalBold      = linkedText?.isBold ?: false
                        finalItalic    = linkedText?.isItalic ?: false
                        finalAlign     = linkedText?.align ?: com.vasiliastyper.model.TextAlign.CENTER
                        finalEffect    = linkedText?.effect ?: com.vasiliastyper.model.TextEffect.NONE
                    }

                    // 3. Auto-fit font size
                    val padding = (minOf(area.width(), area.height()) * 0.08f).coerceIn(4f, 24f)
                    val tx = area.left + padding
                    val ty = area.top  + padding
                    val tw = (area.width()  - padding * 2f).coerceAtLeast(10f)
                    val th = (area.height() - padding * 2f).coerceAtLeast(10f)
                    val maxTextSize = if (chosenStyle != null) chosenStyle.fontSize.coerceAtLeast(10f) else 84f
                    val size = com.vasiliastyper.engine.TextRenderer.autoFitFontSize(
                        text            = translated,
                        boxWidth        = tw,
                        boxHeight       = th,
                        typeface        = finalTypeface,
                        maxFontSize     = maxTextSize,
                        roundBubbleMode = area.width() <= area.height() * 1.25f
                    )

                    val activeLayerId = ws.layers.getOrNull(ws.activeLayerIndex)?.id
                    binding.canvasView.textElements.add(
                        com.vasiliastyper.model.TextElement(
                            text       = translated,
                            x          = tx,
                            y          = ty,
                            width      = tw,
                            height     = th,
                            fontSize   = size,
                            typeface   = finalTypeface,
                            fontName   = finalFontName,
                            color      = finalColor,
                            isBold     = finalBold,
                            isItalic   = finalItalic,
                            align      = finalAlign,
                            effect     = finalEffect,
                            layerId    = activeLayerId
                        )
                    )
                    translatedCount++
                }
                crop.recycle()
                updateStatus("Bubble Translate ${idx + 1}/${areas.size}…")
            }
            composite.recycle()
            binding.canvasView.invalidate()
            binding.canvasView.clearSelection()
            updateStatus("✓ Bubble Translate selesai: $translatedCount/${areas.size} area")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WATERMARK OPTIONS BAR SETUP
    // ══════════════════════════════════════════════════════════════════════════

    fun setupWatermarkOptionsBar() {
        // The old one-line toolbar could not expose count/random/specific position
        // without becoming unusable on phones. Keep image picking in the quick bar
        // and open the complete watermark panel from one button.
        binding.tvWmImageName.text = watermarkImageName
        binding.btnWmPickImage.setOnClickListener { watermarkImagePickerLauncher.launch("image/*") }
        binding.btnWmApply.text = "Panel Watermark…"
        binding.btnWmApply.setOnClickListener { showWatermarkPanel() }
    }

    private data class WatermarkConfig(
        val count: Int,
        val sizePercent: Int,
        val opacityPercent: Int,
        val positionIndex: Int,
        val specificX: Int,
        val specificY: Int
    )

    private data class WatermarkUserPreset(
        val name: String,
        val config: WatermarkConfig
    )

    private fun loadWatermarkUserPresets(): MutableList<WatermarkUserPreset> {
        val raw = getSharedPreferences("watermark_user_presets", MODE_PRIVATE)
            .getString("items", "[]") ?: "[]"
        return runCatching {
            val array = org.json.JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                WatermarkUserPreset(
                    name = item.optString("name", "Preset ${index + 1}"),
                    config = WatermarkConfig(
                        count = item.optInt("count", 4).coerceIn(1, 100),
                        sizePercent = item.optInt("sizePercent", 50).coerceIn(10, 200),
                        opacityPercent = item.optInt("opacityPercent", 30).coerceIn(5, 100),
                        positionIndex = item.optInt("positionIndex", 0).coerceIn(0, 11),
                        specificX = item.optInt("specificX", 0).coerceAtLeast(0),
                        specificY = item.optInt("specificY", 0).coerceAtLeast(0)
                    )
                )
            }
        }.getOrElse { mutableListOf() }
    }

    private fun saveWatermarkUserPresets(items: List<WatermarkUserPreset>) {
        val array = org.json.JSONArray()
        items.take(30).forEach { preset ->
            array.put(org.json.JSONObject().apply {
                put("name", preset.name)
                put("count", preset.config.count)
                put("sizePercent", preset.config.sizePercent)
                put("opacityPercent", preset.config.opacityPercent)
                put("positionIndex", preset.config.positionIndex)
                put("specificX", preset.config.specificX)
                put("specificY", preset.config.specificY)
            })
        }
        getSharedPreferences("watermark_user_presets", MODE_PRIVATE)
            .edit().putString("items", array.toString()).apply()
    }

    private fun showWatermarkPanel() {
        if (watermarkBitmap == null) {
            Toast.makeText(this, "Pilih gambar watermark terlebih dahulu", Toast.LENGTH_SHORT).show()
            watermarkImagePickerLauncher.launch("image/*")
            return
        }

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        fun label(textValue: String) = TextView(this).apply {
            text = textValue
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, (8 * density).toInt(), 0, (2 * density).toInt())
        }

        val userPresets = loadWatermarkUserPresets()
        val quickLabels = mutableListOf("Custom", "Halus", "Standar", "Kuat")
        quickLabels.addAll(userPresets.map { "Preset: ${it.name}" })
        val quickAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            quickLabels
        )
        val quickSpinner = Spinner(this).apply { adapter = quickAdapter }
        val countInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("4")
            hint = "1–100"
        }
        val sizeValue = TextView(this).apply { setTextColor(Color.WHITE) }
        val sizeSeek = SeekBar(this).apply { max = 190; progress = 40 }
        val opacityValue = TextView(this).apply { setTextColor(Color.WHITE) }
        val opacitySeek = SeekBar(this).apply { max = 95; progress = 25 }
        val positionSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                arrayOf(
                    "Acak", "Kiri (atas ke bawah)",
                    "Kiri Atas", "Tengah Atas", "Kanan Atas",
                    "Kiri Tengah", "Tengah", "Kanan Tengah",
                    "Kiri Bawah", "Tengah Bawah", "Kanan Bawah", "Koordinat X/Y"
                )
            )
            setSelection(0)
        }
        val coordinateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = View.GONE
        }
        val xInput = EditText(this).apply {
            hint = "X px"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val yInput = EditText(this).apply {
            hint = "Y px"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coordinateRow.addView(xInput)
        coordinateRow.addView(yInput)

        val presetNameInput = EditText(this).apply {
            hint = "Nama preset buatan sendiri"
            setSingleLine(true)
        }
        val presetActionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val savePresetButton = Button(this).apply {
            text = "Simpan Preset"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val deletePresetButton = Button(this).apply {
            text = "Hapus Preset"
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        presetActionRow.addView(savePresetButton)
        presetActionRow.addView(deletePresetButton)

        fun currentWatermarkConfig() = WatermarkConfig(
            count = (countInput.text?.toString()?.toIntOrNull() ?: 1).coerceIn(1, 100),
            sizePercent = sizeSeek.progress + 10,
            opacityPercent = opacitySeek.progress + 5,
            positionIndex = positionSpinner.selectedItemPosition,
            specificX = xInput.text?.toString()?.toIntOrNull() ?: 0,
            specificY = yInput.text?.toString()?.toIntOrNull() ?: 0
        )

        fun applyPreset(config: WatermarkConfig) {
            countInput.setText(config.count.toString())
            sizeSeek.progress = (config.sizePercent - 10).coerceIn(0, sizeSeek.max)
            opacitySeek.progress = (config.opacityPercent - 5).coerceIn(0, opacitySeek.max)
            positionSpinner.setSelection(config.positionIndex.coerceIn(0, 11))
            xInput.setText(config.specificX.toString())
            yInput.setText(config.specificY.toString())
        }

        fun refreshPresetSpinner(selectIndex: Int = 0) {
            quickLabels.clear()
            quickLabels.addAll(listOf("Custom", "Halus", "Standar", "Kuat"))
            quickLabels.addAll(userPresets.map { "Preset: ${it.name}" })
            quickAdapter.notifyDataSetChanged()
            quickSpinner.setSelection(selectIndex.coerceIn(0, quickLabels.lastIndex))
        }

        fun refreshValues() {
            sizeValue.text = "${sizeSeek.progress + 10}%"
            opacityValue.text = "${opacitySeek.progress + 5}%"
        }
        sizeSeek.setOnSeekBarChangeListener(seekListener { refreshValues() })
        opacitySeek.setOnSeekBarChangeListener(seekListener { refreshValues() })
        refreshValues()

        quickSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (position) {
                    1 -> { countInput.setText("2"); sizeSeek.progress = 30; opacitySeek.progress = 13 }
                    2 -> { countInput.setText("4"); sizeSeek.progress = 40; opacitySeek.progress = 25 }
                    3 -> { countInput.setText("8"); sizeSeek.progress = 55; opacitySeek.progress = 45 }
                    else -> userPresets.getOrNull(position - 4)?.let { preset ->
                        presetNameInput.setText(preset.name)
                        applyPreset(preset.config)
                    }
                }
                deletePresetButton.isEnabled = position >= 4
                refreshValues()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        savePresetButton.setOnClickListener {
            val name = presetNameInput.text?.toString()?.trim().orEmpty()
            if (name.isBlank()) {
                Toast.makeText(this, "Isi nama preset terlebih dahulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val preset = WatermarkUserPreset(name.take(40), currentWatermarkConfig())
            val existing = userPresets.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            if (existing >= 0) userPresets[existing] = preset else userPresets.add(preset)
            saveWatermarkUserPresets(userPresets)
            val selected = 4 + userPresets.indexOfFirst { it.name.equals(name, ignoreCase = true) }
            refreshPresetSpinner(selected)
            Toast.makeText(this, "Preset '$name' disimpan", Toast.LENGTH_SHORT).show()
        }

        deletePresetButton.setOnClickListener {
            val index = quickSpinner.selectedItemPosition - 4
            val removed = userPresets.getOrNull(index) ?: return@setOnClickListener
            userPresets.removeAt(index)
            saveWatermarkUserPresets(userPresets)
            presetNameInput.text?.clear()
            refreshPresetSpinner()
            Toast.makeText(this, "Preset '${removed.name}' dihapus", Toast.LENGTH_SHORT).show()
        }
        positionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                coordinateRow.visibility = if (position == 11) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        content.addView(label("Quick setting / preset pengguna")); content.addView(quickSpinner)
        content.addView(presetNameInput)
        content.addView(presetActionRow)
        content.addView(label("Watermark count")); content.addView(countInput)
        content.addView(label("Size")); content.addView(sizeValue); content.addView(sizeSeek)
        content.addView(label("Opacity")); content.addView(opacityValue); content.addView(opacitySeek)
        content.addView(label("Position")); content.addView(positionSpinner); content.addView(coordinateRow)

        AlertDialog.Builder(this)
            .setTitle("Watermark")
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("Batal", null)
            .setPositiveButton("Terapkan") { _, _ ->
                applyAutoWatermark(currentWatermarkConfig())
            }
            .show()
    }

    private fun normalizeWatermarkBitmap(source: Bitmap, maxDimension: Int = 1024): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= maxDimension) return source
        val scale = maxDimension.toFloat() / maxSide.toFloat()
        val outW = (source.width * scale).toInt().coerceAtLeast(1)
        val outH = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, outW, outH, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    /** Applies an exact number of watermark instances using fixed or random positions. */
    private fun applyAutoWatermark(config: WatermarkConfig) {
        val ws = vm.activeWorkspace ?: run {
            Toast.makeText(this, "Buka gambar terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return
        if (!layer.bitmap.isMutable) {
            Toast.makeText(this, "Layer aktif tidak bisa diedit", Toast.LENGTH_SHORT).show()
            return
        }
        val wmSource = watermarkBitmap ?: return
        if (wmSource.isRecycled || wmSource.width < 1 || wmSource.height < 1) {
            Toast.makeText(this, "Gambar watermark tidak valid", Toast.LENGTH_SHORT).show()
            return
        }

        vm.pushHistory(ws)
        updateStatus("Menerapkan ${config.count} watermark…")

        lifecycleScope.launch {
            val applied = withContext(Dispatchers.IO) {
                runCatching {
                    val bmp = layer.bitmap
                    val canvasW = bmp.width.toFloat()
                    val canvasH = bmp.height.toFloat()
                    val sizeScale = (config.sizePercent / 100f).coerceIn(0.1f, 2f)
                    val rawW = (wmSource.width * sizeScale).toInt().coerceAtLeast(1)
                    val rawH = (wmSource.height * sizeScale).toInt().coerceAtLeast(1)
                    val fitScale = minOf(
                        (canvasW * 0.90f) / rawW.toFloat(),
                        (canvasH * 0.90f) / rawH.toFloat(),
                        1f
                    )
                    val drawW = (rawW * fitScale).toInt().coerceAtLeast(1)
                    val drawH = (rawH * fitScale).toInt().coerceAtLeast(1)
                    val wmDraw = if (drawW == wmSource.width && drawH == wmSource.height) {
                        wmSource
                    } else {
                        Bitmap.createScaledBitmap(wmSource, drawW, drawH, true)
                    }

                    try {
                        val canvas = android.graphics.Canvas(bmp)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            alpha = (config.opacityPercent / 100f * 255f).toInt().coerceIn(0, 255)
                            isFilterBitmap = true
                            isDither = true
                        }
                        val maxX = (canvasW - wmDraw.width).coerceAtLeast(0f)
                        val maxY = (canvasH - wmDraw.height).coerceAtLeast(0f)
                        val margin = minOf(16f, maxX / 2f, maxY / 2f).coerceAtLeast(0f)
                        val seededRandom = java.util.Random(
                            31L * bmp.width + 17L * bmp.height + config.count
                        )

                        fun anchor(index: Int): Pair<Float, Float> {
                            if (config.positionIndex == 0) {
                                val x = if (maxX <= 0f) 0f else seededRandom.nextFloat() * maxX
                                val bandHeight = canvasH / config.count.coerceAtLeast(1)
                                val bandTop = index * bandHeight
                                val available = (bandHeight - wmDraw.height).coerceAtLeast(0f)
                                val y = (bandTop + seededRandom.nextFloat() * available).coerceIn(0f, maxY)
                                return x to y
                            }
                            if (config.positionIndex == 1) {
                                // Dedicated left-column mode: preserve the requested
                                // count and distribute every instance from top to bottom.
                                // For count=4 this produces four distinct rows instead
                                // of cycling through anchors and piling up in one spot.
                                val x = margin.coerceIn(0f, maxX)
                                val y = if (config.count <= 1 || maxY <= 0f) {
                                    margin.coerceIn(0f, maxY)
                                } else {
                                    (maxY * index.toFloat() / (config.count - 1).toFloat())
                                        .coerceIn(0f, maxY)
                                }
                                return x to y
                            }
                            if (config.positionIndex == 11) {
                                val step = maxOf(wmDraw.height * 0.35f, canvasH / config.count.coerceAtLeast(1))
                                return config.specificX.toFloat().coerceIn(0f, maxX) to
                                    (config.specificY + index * step).coerceIn(0f, maxY)
                            }

                            val gridIndex = (config.positionIndex - 2 + index) % 9
                            val column = gridIndex % 3
                            val row = gridIndex / 3
                            val x = when (column) {
                                0 -> margin
                                1 -> maxX / 2f
                                else -> (maxX - margin).coerceAtLeast(0f)
                            }
                            val y = when (row) {
                                0 -> margin
                                1 -> maxY / 2f
                                else -> (maxY - margin).coerceAtLeast(0f)
                            }
                            return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
                        }

                        repeat(config.count) { index ->
                            val (x, y) = anchor(index)
                            canvas.drawBitmap(wmDraw, x, y, paint)
                        }
                    } finally {
                        if (wmDraw !== wmSource) wmDraw.recycle()
                    }
                    true
                }.getOrDefault(false)
            }

            if (applied) {
                binding.canvasView.invalidate()
                triggerAutoSave()
                updateStatus("✓ ${config.count} watermark diterapkan — $watermarkImageName")
            } else {
                updateStatus("Watermark gagal diterapkan")
                Toast.makeText(this@MainActivity, "Gagal menerapkan watermark image", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // CANVAS
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupCanvas() {
        binding.canvasView.onSelectionChanged = selectionChanged@{ sel ->
            val has = sel.hasSelection()
            binding.btnFillWhite.visibility      = if (has) View.VISIBLE else View.GONE
            binding.btnFillBlack.visibility      = if (has) View.VISIBLE else View.GONE
            binding.btnInpaintTelea.visibility   = if (has) View.VISIBLE else View.GONE
            binding.btnInpaintSelection.visibility = if (has) View.VISIBLE else View.GONE
            binding.btnCenterInBubble.visibility = if (has) View.VISIBLE else View.GONE
            binding.btnCropToSel.visibility = if (has) View.VISIBLE else View.GONE
            binding.btnCopySelection.visibility = if (has) View.VISIBLE else View.GONE

            if (binding.canvasView.currentTool == Tool.TEXT_SHAPER) {
                binding.btnFillWhite.visibility = View.GONE
                binding.btnFillBlack.visibility = View.GONE
                binding.btnInpaintTelea.visibility = View.GONE
                binding.btnInpaintSelection.visibility = View.GONE
                binding.btnCenterInBubble.visibility = View.GONE
                binding.btnCropToSel.visibility = View.GONE
                binding.btnCopySelection.visibility = View.GONE
                if (has && !textShaperSelectionHandled && !textShaperDialogOpen) {
                    textShaperSelectionHandled = true
                    showTextShaperForPlacedSelection(sel.getBounds())
                } else if (!has) {
                    textShaperSelectionHandled = false
                }
                return@selectionChanged
            }

            // Script-flow / Type-R places text normally. Text shaping is now a
            // separate post-placement tool and never interrupts the Place flow.
            val isAddMode = binding.btnAddToSel.isChecked
            if (has && pendingScriptLine != null && !isAddMode) {
                val area = sel.getBounds()
                if (pendingAutoStyle != null) {
                    autoPlaceTextWithStyle(area.left, area.top, area.width(), area.height(), padding = 8f)
                } else {
                    safeShowTextEditorDialogInBounds(area.left, area.top, area.width(), area.height(), padding = 8f)
                }
            }
            // Show "Place All" hint if queue is loaded and multiple areas selected
            if (has && isAddMode && (pendingScriptLine != null || typeRQueue.isNotEmpty())) {
                val count = sel.getAreas().size
                if (count > 1) updateStatus("$count area dipilih — tekan Place All ▶ untuk menempatkan semua teks")
            }

            // OCR Panel: update hint when selection is ready
            if (has && binding.bottomOcrPanel.visibility == View.VISIBLE) {
                binding.tvOcrHint.text = "Seleksi siap — tekan ▶ OCR Seleksi"
            }
            if (binding.bottomUnwatermarkPanel.visibility == View.VISIBLE) {
                scheduleUnwatermarkPreview()
            }
        }
        binding.canvasView.onUnwatermarkSelectionTransform = { rect ->
            if (binding.bottomUnwatermarkPanel.visibility == View.VISIBLE) {
                unwatermarkSelectionManuallyAdjusted = true
                binding.tvUnwmHint.text =
                    "Hybrid manual • posisi ${rect.left.roundToInt()},${rect.top.roundToInt()} • ${rect.width().roundToInt()}×${rect.height().roundToInt()} px"
                scheduleUnwatermarkPreview(delayMs = 80L)
            }
        }
        binding.canvasView.onTextTap      = { cx, cy -> showTextEditorDialog(cx, cy) }
        binding.canvasView.onTextEdit     = { el -> showTextEditorDialogForEdit(el) }
        // v5.1 — floating quick-edit toolbar wiring
        binding.canvasView.onTextSelected = { el -> updateTextQuickToolbar(el) }
        binding.canvasView.onImageSelected = { el -> updateImageQuickToolbar(el) }
        setupTextQuickToolbar()
        setupImageQuickToolbar()
        binding.canvasView.onStatusUpdate = { zoom, x, y ->
            binding.statusZoom.text   = zoom
            binding.statusCursor.text = "%.0f, %.0f".format(x, y)
        }
        binding.canvasView.onBrushSizeChanged = { size ->
            val progress = (size.roundToInt() - 1).coerceIn(0, binding.seekBrushSize.max)
            if (binding.seekBrushSize.progress != progress) {
                binding.seekBrushSize.progress = progress
            }
        }
        binding.canvasView.onGeminiOverlayDeleteRequest = deleteOverlay@{ index ->
            if (index !in textDetectedRegions.indices) return@deleteOverlay
            textDetectedRegions.removeAt(index)
            binding.canvasView.geminiDetectOverlay = textDetectedRegions.toList()
            syncDetectedBubbleSelection()
            binding.canvasView.invalidate()
            refreshMaskRegionList()
            setMaskActionsEnabled(textDetectedRegions.isNotEmpty())
            binding.tvMaskStatus.text = if (textDetectedRegions.isNotEmpty()) {
                "✅ ${textDetectedRegions.size} region tersisa — tekan Hapus Area atau pilih aksi di bawah:"
            } else {
                "Semua region dihapus. Tekan Deteksi lagi untuk memulai ulang."
            }
            if (vasTypeDialog?.isShowing == true) {
                vasTypePreviewReady = textDetectedRegions.isNotEmpty()
                snapshotActiveMaskRegions()
                refreshVasTypeSummary()
            }
        }
        // Text history is self-managed inside CanvasView (pushTextHistory on ACTION_DOWN,
        // onTextHistoryPush signals to MainActivity that the action is committed on ACTION_UP)
        binding.canvasView.onTextHistoryPush = { /* text history handled separately */ }
        binding.canvasView.onWorkspaceHistoryPush = {
            vm.activeWorkspace?.let { workspace ->
                // Capture the blank comparison baseline only when the first real pixel edit
                // starts; this removes a large copy from project startup without losing Undo.
                binding.canvasView.ensureCompareBaseline(workspace.layers.firstOrNull()?.bitmap)
                vm.pushHistory(workspace)
            }
        }

        // ── Auto Clean Bubble (single-tap) ──────────────────────────────────────
        // OpenCV-based text detection (adaptive threshold + contour filter).
        // No model files required. Runs on IO thread (~30-150 ms).
        binding.canvasView.onBubbleCleanRequest = cleanReq@{ bx, by ->
            val ws    = vm.activeWorkspace    ?: return@cleanReq
            val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return@cleanReq
            if (!layer.bitmap.isMutable) {
                Toast.makeText(this, "Layer ini tidak bisa diedit", Toast.LENGTH_SHORT).show()
                return@cleanReq
            }
            vm.pushHistory(ws)
            val modeLabel = if (bubbleCleanMode == BubbleCleaner.CleanMode.FAST) "Cepat" else "Presisi"
            updateStatus("Membersihkan balon [$modeLabel]…")
            lifecycleScope.launch {
                val threshold = binding.canvasView.tolerance.coerceIn(8, 90).coerceAtLeast(28)
                val mode      = bubbleCleanMode
                val ok = withContext(Dispatchers.IO) {
                    val layerPx = layer.bitmap.width.toLong() * layer.bitmap.height
                    val composite = if (layerPx > 3_000_000L) null
                        else try { binding.canvasView.compositeVisibleLayers() }
                        catch (_: OutOfMemoryError) { System.gc(); null }
                    val primary = if (composite != null) {
                        val r = BubbleCleaner.clean(composite, layer.bitmap, bx, by, threshold, mode = mode)
                        composite.recycle(); r
                    } else {
                        BubbleCleaner.clean(layer.bitmap, bx, by, threshold, mode = mode)
                    }

                    if (primary) {
                        true
                    } else if (mode == BubbleCleaner.CleanMode.PRECISE) {
                        BubbleCleaner.clean(layer.bitmap, bx, by, (threshold + 4).coerceAtMost(90), mode = BubbleCleaner.CleanMode.PRECISE)
                    } else {
                        BubbleCleaner.clean(layer.bitmap, bx, by, (threshold - 4).coerceAtLeast(8), mode = BubbleCleaner.CleanMode.FAST)
                    }
                }
                binding.canvasView.invalidate()
                updateStatus(
                    if (ok) "✓ Balon dibersihkan [$modeLabel]"
                    else    "Tap di area terang dalam balon (bukan di garis tepi/teks)"
                )
            }
        }

        // ── Batch Bubble Clean ────────────────────────────────────────────────
        // Triggered when user executes the batch via second long-press on the tool button.
        binding.canvasView.onBubbleCleanBatchReady = batchReq@{ tapPoints ->
            val ws    = vm.activeWorkspace    ?: return@batchReq
            val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return@batchReq
            if (!layer.bitmap.isMutable) {
                Toast.makeText(this, "Layer ini tidak bisa diedit", Toast.LENGTH_SHORT).show()
                return@batchReq
            }
            vm.pushHistory(ws)
            val modeLabel = if (bubbleCleanMode == BubbleCleaner.CleanMode.FAST) "Cepat" else "Presisi"
            updateStatus("Membersihkan ${tapPoints.size} balon [$modeLabel]…")
            lifecycleScope.launch {
                val threshold = binding.canvasView.tolerance.coerceIn(8, 90).coerceAtLeast(28)
                val mode      = bubbleCleanMode
                val result = withContext(Dispatchers.IO) {
                    // Gambar >3MP: lewati composite untuk hindari OOM (700×15000 = 10.5MP)
                    val layerPx = layer.bitmap.width.toLong() * layer.bitmap.height
                    val composite = if (layerPx > 3_000_000L) null
                        else try { binding.canvasView.compositeVisibleLayers() }
                        catch (_: OutOfMemoryError) { System.gc(); null }
                    val src = composite ?: layer.bitmap
                    val r = BubbleCleaner.cleanBatch(
                        sourceBitmap     = src,
                        targetBitmap     = layer.bitmap,
                        tapPoints        = tapPoints,
                        outlineThreshold = threshold,
                        mode             = mode,
                        onProgress       = { cur, tot ->
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                updateStatus("Membersihkan balon $cur/$tot [$modeLabel]…")
                            }
                        }
                    )
                    if (composite != null && composite !== layer.bitmap) composite.recycle()
                    r
                }
                binding.canvasView.invalidate()
                updateStatus(
                    "✓ Batch selesai: ${result.cleaned}/${result.total} balon dibersihkan [$modeLabel]" +
                    if (result.skipped > 0) " (${result.skipped} dilewati)" else ""
                )
            }
        }

        // Eyedropper callback: set the picked colour as the foreground colour and,
        // if there is an active selection, auto-fill it with that colour.
        binding.canvasView.onEyedropperCancel = {
            pendingPickerEyedropper = null
            activeTextEditorDialog?.let { editor ->
                if (!isFinishing && !isDestroyed) editor.show()
            }
            updateStatus("Eyedropper dibatalkan")
        }
        binding.canvasView.onEyedropperPick = pick@{ picked ->
            pendingPickerEyedropper?.let { callback ->
                pendingPickerEyedropper = null
                callback(picked)
                updateStatus("Warna kanvas disalin: #%08X".format(picked))
                return@pick
            }
            vm.foregroundColor.value             = picked
            binding.colorFg.setBackgroundColor(picked)
            binding.canvasView.brushEngine.color = picked
            val ws = vm.activeWorkspace ?: return@pick
            if (binding.canvasView.selection.hasSelection()) {
                vm.pushHistory(ws)
                binding.canvasView.fillSelection(picked)
                updateStatus("Diisi dengan warna eyedropper")
            } else {
                updateStatus("Warna diambil: #%08X".format(picked))
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // COLOR PATCHES
    // ══════════════════════════════════════════════════════════════════════════

    private fun setupColorPatches() {
        binding.colorFg.setBackgroundColor(Color.BLACK)
        binding.colorBg.setBackgroundColor(Color.WHITE)
        binding.colorFg.setOnClickListener { showColorPickerDialog(true) }
        binding.colorBg.setOnClickListener { showColorPickerDialog(false) }

        // Long-press foreground color patch → activate eyedropper mode.
        // Next canvas tap will pick the pixel color and auto-fill the active selection.
        binding.colorFg.setOnLongClickListener {
            binding.canvasView.eyedropperMode = true
            Toast.makeText(this, "Eyedropper aktif — tap warna di kanvas", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun showColorPickerDialog(isForeground: Boolean) {
        val initial = if (isForeground) {
            vm.foregroundColor.value ?: Color.BLACK
        } else {
            vm.backgroundColor.value ?: Color.WHITE
        }
        showColorPickerFull(if (isForeground) "Foreground" else "Background", initial) { color ->
            if (isForeground) {
                vm.foregroundColor.value             = color
                binding.colorFg.setBackgroundColor(color)
                binding.canvasView.brushEngine.color = color
            } else {
                vm.backgroundColor.value             = color
                binding.colorBg.setBackgroundColor(color)
            }
        }
    }

    // ── Responsive color wheel + editable user palette + canvas eyedropper ─────
    private fun showColorPicker(title: String, onPicked: (Int) -> Unit) {
        showColorPickerFull(title, Color.BLACK, onPicked)
    }

    private fun showColorPickerFull(title: String, initialColor: Int, onPicked: (Int) -> Unit) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()

        val prefs = getSharedPreferences("color_palette", MODE_PRIVATE)
        val savedColors = prefs.getString("user_colors", "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.toIntOrNull() }
            .toMutableList()
        if (savedColors.isEmpty()) {
            savedColors += listOf(
                Color.BLACK,
                Color.WHITE,
                Color.parseColor("#7C4DFF"),
                Color.parseColor("#A855F7"),
                Color.parseColor("#34D399"),
                Color.parseColor("#FBBF24"),
                Color.parseColor("#F87171")
            )
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#FF20242B"))
                setStroke(dp(1), Color.parseColor("#FF424852"))
            }
        }
        root.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.parseColor("#FFF2F2F4"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Geser pada roda warna atau masukkan kode HEX"
            textSize = 11f
            setTextColor(Color.parseColor("#FF9895A4"))
            setPadding(0, dp(2), 0, dp(10))
        })

        val availableHeight = resources.displayMetrics.heightPixels
        val wheelHeight = (availableHeight * 0.31f).roundToInt().coerceIn(dp(190), dp(286))
        val wheel = com.vasiliastyper.view.ColorWheelView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                wheelHeight
            ).apply { gravity = Gravity.CENTER_HORIZONTAL }
            setColor(initialColor)
            contentDescription = "Roda hue dan saturasi"
        }
        root.addView(wheel)

        val previewRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(8), 0, dp(8)) }
        }
        val preview = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(40)).apply {
                marginEnd = dp(10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(initialColor)
                setStroke(dp(1), Color.parseColor("#FF707780"))
            }
        }
        val hexInput = EditText(this).apply {
            setSingleLine(true)
            textSize = 14f
            hint = "AARRGGBB"
            setText("%08X".format(initialColor))
            setTextColor(Color.parseColor("#FFF2F2F4"))
            setHintTextColor(Color.parseColor("#FF777281"))
            background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(Color.parseColor("#FF17171E"))
                setStroke(dp(1), Color.parseColor("#FF424852"))
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setPadding(dp(12), 0, dp(12), 0)
        }
        previewRow.addView(preview)
        previewRow.addView(TextView(this).apply {
            text = "#"
            textSize = 16f
            setTextColor(Color.parseColor("#FFF2F2F4"))
            setPadding(0, 0, dp(4), 0)
        })
        previewRow.addView(hexInput)
        root.addView(previewRow)

        var updatingHex = false
        var selectedColor = initialColor
        fun updatePreview(color: Int, updateHex: Boolean) {
            selectedColor = color
            preview.background = GradientDrawable().apply {
                cornerRadius = dp(9).toFloat()
                setColor(color)
                setStroke(dp(1), Color.parseColor("#FF707780"))
            }
            if (updateHex) {
                updatingHex = true
                hexInput.setText("%08X".format(color))
                hexInput.setSelection(hexInput.text.length)
                updatingHex = false
            }
        }
        wheel.onColorChanged = { updatePreview(it, true) }
        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(editable: Editable?) {
                if (updatingHex) return
                val raw = editable?.toString()?.trim()?.trimStart('#').orEmpty()
                val normalized = if (raw.length == 6) "FF$raw" else raw
                if (normalized.length != 8) return
                runCatching { normalized.toLong(16).toInt() }.getOrNull()?.let {
                    wheel.setColor(it)
                    updatePreview(it, false)
                }
            }
        })

        val alphaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val alphaLabel = TextView(this).apply {
            text = "Opasitas ${Color.alpha(initialColor) * 100 / 255}%"
            textSize = 11f
            setTextColor(Color.parseColor("#FFB9B5C4"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        alphaRow.addView(alphaLabel)
        listOf(25, 50, 75, 100).forEach { percent ->
            alphaRow.addView(Button(this).apply {
                text = "$percent%"
                textSize = 10f
                minWidth = 0
                setPadding(dp(5), 0, dp(5), 0)
                layoutParams = LinearLayout.LayoutParams(dp(55), dp(38))
                setOnClickListener {
                    wheel.setAlphaValue(percent * 255 / 100)
                    alphaLabel.text = "Opasitas $percent%"
                }
            })
        }
        root.addView(alphaRow)

        root.addView(TextView(this).apply {
            text = "PALET · ketuk untuk pakai, tahan untuk menghapus"
            textSize = 10f
            setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#FF9895A4"))
            setPadding(0, dp(10), 0, dp(4))
        })
        val paletteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, dp(4), 0)
        }
        val paletteScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(paletteRow)
        }
        root.addView(paletteScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ))

        fun persistPalette() {
            prefs.edit().putString("user_colors", savedColors.joinToString(",")).apply()
        }
        fun rebuildPalette() {
            paletteRow.removeAllViews()
            savedColors.forEach { paletteColor ->
                paletteRow.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply {
                        marginEnd = dp(7)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(paletteColor)
                        setStroke(dp(2), Color.WHITE)
                    }
                    setOnClickListener { wheel.setColor(paletteColor, notify = true) }
                    setOnLongClickListener {
                        savedColors.remove(paletteColor)
                        persistPalette()
                        rebuildPalette()
                        true
                    }
                })
            }
            if (savedColors.isEmpty()) {
                paletteRow.addView(TextView(this).apply {
                    text = "Belum ada warna tersimpan"
                    setPadding(dp(8), dp(10), dp(8), 0)
                })
            }
        }
        rebuildPalette()

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val addPalette = Button(this).apply {
            text = "Simpan ke palet"
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
            setOnClickListener {
                if (wheel.color !in savedColors) {
                    savedColors += wheel.color
                    persistPalette()
                    rebuildPalette()
                }
            }
        }
        val eyedropper = ImageButton(this).apply {
            setImageResource(R.drawable.ic_eyedropper)
            setColorFilter(Color.WHITE)
            contentDescription = "Ambil warna dari kanvas"
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF3B4350"))
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(44)).apply {
                marginStart = dp(8)
            }
        }
        actionRow.addView(addPalette)
        actionRow.addView(eyedropper)
        root.addView(actionRow)

        val confirmationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val cancelButton = Button(this).apply {
            text = "Batal"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f)
        }
        val selectButton = Button(this).apply {
            text = "Gunakan warna"
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(8)
            }
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF4D9BF0"))
            setTextColor(Color.WHITE)
        }
        confirmationRow.addView(cancelButton)
        confirmationRow.addView(selectButton)
        root.addView(confirmationRow)

        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            setContentView(scroll)
            window?.apply {
                setGravity(Gravity.END)
                setBackgroundDrawable(GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(Color.parseColor("#FF20242B"))
                    setStroke(dp(1), Color.parseColor("#FF424852"))
                })
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        cancelButton.setOnClickListener { dialog.dismiss() }
        selectButton.setOnClickListener {
            onPicked(selectedColor)
            dialog.dismiss()
        }
        eyedropper.setOnClickListener {
            pendingPickerEyedropper = onPicked
            binding.canvasView.eyedropperMode = true
            dialog.dismiss()
            Toast.makeText(this, "Tap atau geser di kanvas untuk menyalin warna", Toast.LENGTH_SHORT).show()
        }
        dialog.setOnCancelListener { pendingPickerEyedropper = null }
        dialog.show()
        val screenWidth = resources.displayMetrics.widthPixels
        val dialogWidth = minOf(dp(420), screenWidth - dp(24)).coerceAtLeast(dp(280))
        val dialogHeight = (resources.displayMetrics.heightPixels * 0.94f).roundToInt()
        dialog.window?.setLayout(dialogWidth, dialogHeight)
    }


    private fun showBottomSheetDialog(
        draggable: Boolean = true,
        configure: BottomSheetDialog.() -> Unit
    ): BottomSheetDialog {
        return BottomSheetDialog(this).apply {
            window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            configure()
            setOnShowListener {
                behavior.skipCollapsed = true
                behavior.isHideable = false
                behavior.isDraggable = draggable
                behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
            show()
        }
    }

    private fun showRightDockedDialog(
        content: View,
        widthDp: Int = 380,
        title: String? = null,
        onShown: ((Dialog) -> Unit)? = null
    ): Dialog {
        val density = resources.displayMetrics.density
        val widthPx = (widthDp * density).roundToInt()
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar).apply {
            setContentView(content)
            window?.apply {
                setLayout(widthPx, ViewGroup.LayoutParams.MATCH_PARENT)
                setGravity(Gravity.END)
                setBackgroundDrawable(GradientDrawable().apply {
                    cornerRadius = (18 * density)
                    setColor(Color.TRANSPARENT)
                })
                setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
        }
        onShown?.invoke(dialog)
        dialog.show()
        return dialog
    }

    // ══════════════════════════════════════════════════════════════════════════
    // TEXT EDITOR DIALOG — with Style Manager, Script pre-fill, Bubble centering
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Standard open: user taps on canvas.
     */
    private fun showTextEditorDialog(cx: Float, cy: Float) {
        if (vm.activeWorkspace == null) return
        safeShowTextEditorDialogInBounds(
            x      = cx - 150f,
            y      = cy - 30f,
            width  = 300f,
            height = 80f
        )
    }

    /**
     * Opens a lightweight editor for an existing text element. Keeping this path
     * separate from text creation prevents an edit from adding a duplicate element.
     */
    private fun showTextEditorDialogForEdit(element: TextElement) {
        if (binding.canvasView.textElements.none { it.id == element.id }) {
            Toast.makeText(this, "Elemen teks sudah tidak tersedia", Toast.LENGTH_SHORT).show()
            return
        }
        safeShowTextEditorDialogInBounds(
            x = element.x,
            y = element.y,
            width = element.width,
            height = element.height,
            existingElement = element
        )
    }

    private fun startTextEditorEyedropper(onPicked: (Int) -> Unit) {
        val editor = activeTextEditorDialog ?: return
        pendingPickerEyedropper = { color ->
            onPicked(color)
            if (!isFinishing && !isDestroyed) {
                editor.show()
                activeTextEditorDialog = editor
            }
        }
        binding.canvasView.eyedropperMode = true
        editor.window?.decorView?.clearFocus()
        (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(editor.window?.decorView?.windowToken, 0)
        editor.hide()
        binding.canvasView.isFocusableInTouchMode = true
        binding.canvasView.requestFocus()
        updateStatus("Eyedropper aktif — tap atau geser pada kanvas")
        Toast.makeText(this, "Pilih warna langsung dari kanvas", Toast.LENGTH_SHORT).show()
    }

    /**
     * Opens one editor at a time. Font discovery is warmed off the UI thread so
     * the first tap does not block rendering on devices with a large font bank.
     */
    private fun safeShowTextEditorDialogInBounds(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        padding: Float = 0f,
        existingElement: TextElement? = null
    ) {
        // v10 FIX (delay & crash): abaikan panggilan ganda saat dialog sudah
        // terbuka (double-tap cepat sebelumnya bisa membuat dua instance dialog
        // yang saling menimpa → tampak seperti delay lalu crash/ANR).
        if (textEditorDialogOpen) return
        val existing = activeTextEditorDialog
        if (existing != null && existing.isShowing) return
        if (isFinishing || isDestroyed) return

        // Inflate and show immediately on Main. Font scanning is deliberately not awaited:
        // waiting for every asset/user font caused the visible first-open delay.
        textEditorDialogOpen = true
        try {
            showTextEditorDialogInBounds(
                x = x,
                y = y,
                width = width,
                height = height,
                padding = padding,
                existingElement = existingElement
            )
        } catch (error: Throwable) {
            textEditorDialogOpen = false
            Log.e("VasiliasTyper", "Text editor gagal dibuka", error)
            val errorMessage = error.message ?: "unknown error"
            Toast.makeText(
                this,
                "Text editor gagal dibuka: $errorMessage",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Open with pre-computed bounds (from selection / Center in Bubble).
     * Text will be auto-centered inside those bounds.
     *
     * @param padding Extra inset (canvas pixels) added on all four sides so the
     *                text never touches the bubble outline.  Pass 0f for a
     *                free-form tap where no selection boundary is present.
     */
    private fun showTextEditorDialogInBounds(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        padding: Float = 0f,
        existingElement: TextElement? = null
    ) {
        val db = DialogTextEditorBinding.inflate(layoutInflater)

        if (existingElement == null && pendingScriptLine == null && typeRQueue.isNotEmpty()) {
            val nextLine = typeRQueue.removeFirst()
            pendingScriptLine = nextLine
            val (stripped, autoStyle) = resolveTypeRLine(nextLine)
            pendingDisplayText = stripped
            pendingAutoStyle = autoStyle
        }
        if (existingElement == null) pendingScriptLine?.let { line ->
            val displayText = pendingDisplayText ?: line
            db.etTextContent.setText(displayText)
            db.tvTextPreview.text = displayText
            pendingScriptLine = null
            pendingDisplayText = null
            if (typeRQueue.isNotEmpty()) {
                val nextLine = typeRQueue.removeFirst()
                pendingScriptLine = nextLine
                val (stripped, autoStyle) = resolveTypeRLine(nextLine)
                pendingDisplayText = stripped
                pendingAutoStyle = autoStyle
            }
        }

        // Use the cached list when ready; otherwise render immediately with lightweight
        // system fonts and hot-swap the complete list after background discovery.
        val systemFonts = (cachedFontList ?: defaultFontList()).toMutableList()
        val fontAdapter = FontPreviewAdapter(this, systemFonts)
        db.spinnerFont.adapter = fontAdapter
        db.spinnerFont.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                db.tvTextPreview.typeface = systemFonts.getOrNull(pos)?.typeface ?: android.graphics.Typeface.DEFAULT
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val effectNames = listOf("None", "Outline", "Shadow", "Outline + Shadow", "Gradient", "Texture", "Warp")
        db.spinnerTextEffect.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, effectNames)

        fun refreshEffectColumns() {
            val outline = db.cbEnableOutlineEffect.isChecked
            val shadow = db.cbEnableShadowEffect.isChecked
            val gradient = db.cbEnableGradientEffect.isChecked
            val texture = db.cbEnableTextureEffect.isChecked
            val warp = db.cbEnableWarpEffect.isChecked

            db.cbExtraOutline.isChecked = outline
            db.cbExtraShadow.isChecked = shadow
            db.cbExtraGradient.isChecked = gradient
            db.cbExtraTexture.isChecked = texture

            db.panelOutline.alpha = if (outline) 1f else 0.55f
            db.panelShadow.alpha = if (shadow) 1f else 0.55f
            db.panelGradient.alpha = if (gradient) 1f else 0.55f
            db.panelTexture.alpha = if (texture) 1f else 0.55f
            db.panelWarp.alpha = if (warp) 1f else 0.55f

            val legacySelection = when {
                warp -> 6
                texture -> 5
                gradient -> 4
                outline && shadow -> 3
                shadow -> 2
                outline -> 1
                else -> 0
            }
            if (db.spinnerTextEffect.selectedItemPosition != legacySelection) {
                db.spinnerTextEffect.setSelection(legacySelection)
            }
        }

        db.cbEnableOutlineEffect.setOnCheckedChangeListener { _, _ -> refreshEffectColumns() }
        db.cbEnableShadowEffect.setOnCheckedChangeListener { _, _ -> refreshEffectColumns() }
        db.cbEnableGradientEffect.setOnCheckedChangeListener { _, _ -> refreshEffectColumns() }
        db.cbEnableTextureEffect.setOnCheckedChangeListener { _, _ -> refreshEffectColumns() }
        db.cbEnableWarpEffect.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                db.cbEnableGradientEffect.isChecked = false
                db.cbEnableTextureEffect.isChecked = false
            }
            refreshEffectColumns()
        }
        db.cbExtraOutline.setOnCheckedChangeListener { _, checked ->
            if (db.cbEnableOutlineEffect.isChecked != checked) db.cbEnableOutlineEffect.isChecked = checked
        }
        db.cbExtraShadow.setOnCheckedChangeListener { _, checked ->
            if (db.cbEnableShadowEffect.isChecked != checked) db.cbEnableShadowEffect.isChecked = checked
        }
        db.cbExtraGradient.setOnCheckedChangeListener { _, checked ->
            if (db.cbEnableGradientEffect.isChecked != checked) db.cbEnableGradientEffect.isChecked = checked
        }
        db.cbExtraTexture.setOnCheckedChangeListener { _, checked ->
            if (db.cbEnableTextureEffect.isChecked != checked) db.cbEnableTextureEffect.isChecked = checked
        }
        refreshEffectColumns()

        db.btnTabText.setOnClickListener {
            db.containerText.visibility = View.VISIBLE
            db.containerEffects.visibility = View.GONE
            db.btnTabText.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4D9BF0"))
            db.btnTabEffects.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3A3A3A"))
        }
        db.btnTabEffects.setOnClickListener {
            db.containerText.visibility = View.GONE
            db.containerEffects.visibility = View.VISIBLE
            db.btnTabText.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#3A3A3A"))
            db.btnTabEffects.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#4D9BF0"))
        }

        var textColor = existingElement?.color ?: Color.WHITE
        var outlineColor = existingElement?.outlineColor ?: Color.BLACK
        var outlineWidth = existingElement?.outlineWidth?.roundToInt() ?: 1
        var shadowColor = existingElement?.shadowColor ?: Color.argb(128, 0, 0, 0)
        var shadowDx = existingElement?.shadowDx ?: 3f
        var shadowDy = existingElement?.shadowDy ?: 3f
        var shadowRadius = existingElement?.shadowRadius ?: 2f
        var shadowSpread = existingElement?.shadowSpread ?: 0f
        var opacity = existingElement?.opacity ?: 100
        var outlineGradientEnabled = existingElement?.enableOutlineGradient ?: false
        var outlineGradientStart = existingElement?.outlineGradStartColor ?: outlineColor
        var outlineGradientEnd = existingElement?.outlineGradEndColor ?: Color.GRAY
        var size = existingElement?.fontSize
            ?: getSharedPreferences("vasilia_editor", MODE_PRIVATE).getFloat("last_font_size", 44f)
        var tracking = existingElement?.tracking?.roundToInt() ?: 0
        var leading = (existingElement?.leading ?: 120f) / 100f
        // Jarak paragraph ala ibispaint: -50 … +50 px antar paragraf.
        var paragraph = existingElement?.paragraphSpacing ?: 0f

        var align = existingElement?.align ?: TextAlign.CENTER
        var gradientAngle = existingElement?.gradientAngle ?: 90f
        val gradientColors = existingElement?.gradientColors
            ?.takeIf { it.size >= 2 }
            ?.toMutableList()
            ?: mutableListOf(
                existingElement?.gradientStartColor ?: Color.WHITE,
                existingElement?.gradientEndColor ?: Color.GRAY
            )
        var pendingTextureUri: String? = existingElement?.textureUri
        val pendingSpans = existingElement?.spans?.map { it.copy() }?.toMutableList() ?: mutableListOf()
        var pendingPerspective = existingElement?.perspCorners != null
        var pendingMesh = existingElement?.meshPoints != null
        var pendingJustify = existingElement?.justify ?: false
        var pendingBlurType = runCatching {
            BlurType.valueOf(existingElement?.blurType ?: BlurType.GAUSSIAN.name)
        }.getOrDefault(BlurType.GAUSSIAN)
        var blurEnabled = existingElement?.let {
            !it.blurType.equals("NONE", ignoreCase = true) && it.blurRadius > 0f
        } ?: false
        var pendingBlurRadius = existingElement?.blurRadius ?: 0f
        var pendingBlurDistance = existingElement?.blurMotionDistance ?: 20f
        var pendingBlurAngle = existingElement?.blurMotionAngle ?: 0f
        val textPathModes = listOf("NONE", "CURVE_UP", "CURVE_DOWN", "WAVE", "ARCH", "VALLEY")
        val textPathLabels = listOf("Lurus", "Curve Up", "Curve Down", "Wavy", "Arch", "Valley")
        var pendingTextPathMode = existingElement?.textPathMode?.uppercase()
            ?.takeIf { it in textPathModes } ?: "NONE"
        var pendingTextPathAmount = existingElement?.textPathAmount
            ?.takeIf(Float::isFinite)?.coerceIn(-100f, 100f) ?: 35f
        var pendingTextPathCycles = existingElement?.textPathCycles
            ?.takeIf(Float::isFinite)?.coerceIn(0.5f, 5f) ?: 1.5f

        db.spinnerTextPathMode.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            textPathLabels
        )
        fun refreshTextPathControls() {
            val enabled = pendingTextPathMode != "NONE"
            val wave = pendingTextPathMode == "WAVE"
            db.seekTextPathAmount.isEnabled = enabled
            db.seekTextPathAmount.alpha = if (enabled) 1f else 0.45f
            db.seekTextPathCycles.isEnabled = wave
            db.seekTextPathCycles.alpha = if (wave) 1f else 0.45f
            db.tvTextPathCycles.alpha = if (wave) 1f else 0.45f
            db.tvTextPathAmount.text = "Kekuatan ${pendingTextPathAmount.roundToInt()}%"
            db.tvTextPathCycles.text = "Gelombang ${String.format(Locale.getDefault(), "%.1f", pendingTextPathCycles)}×"
        }
        db.seekTextPathAmount.progress = (pendingTextPathAmount + 100f).roundToInt()
            .coerceIn(0, db.seekTextPathAmount.max)
        db.seekTextPathCycles.progress = ((pendingTextPathCycles - 0.5f) * 10f).roundToInt()
            .coerceIn(0, db.seekTextPathCycles.max)
        db.spinnerTextPathMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                pendingTextPathMode = textPathModes.getOrElse(position) { "NONE" }
                refreshTextPathControls()
            }
        }
        db.seekTextPathAmount.setOnSeekBarChangeListener(seekListener { progress ->
            pendingTextPathAmount = (progress - 100).toFloat()
            refreshTextPathControls()
        })
        db.seekTextPathCycles.setOnSeekBarChangeListener(seekListener { progress ->
            pendingTextPathCycles = (0.5f + progress / 10f).coerceIn(0.5f, 5f)
            refreshTextPathControls()
        })
        db.spinnerTextPathMode.setSelection(textPathModes.indexOf(pendingTextPathMode).coerceAtLeast(0))
        refreshTextPathControls()

        fun updateTextColor(color: Int) {
            textColor = color
            db.textColorPatch.setCardBackgroundColor(color)
            db.tvTextPreview.setTextColor(color)
        }
        fun updateOutlineColor(color: Int) {
            outlineColor = color
            db.outlineColorPatch.setCardBackgroundColor(color)
        }
        fun updateShadowColor(color: Int) {
            shadowColor = color
            db.shadowColorPatch.setCardBackgroundColor(color)
        }
        fun updateOutlineGradientStart(color: Int) {
            outlineGradientStart = color
            db.outlineGradientStartPatch.setCardBackgroundColor(color)
        }
        fun updateOutlineGradientEnd(color: Int) {
            outlineGradientEnd = color
            db.outlineGradientEndPatch.setCardBackgroundColor(color)
        }
        fun refreshOutlineGradientControls() {
            db.rowOutlineGradientColors.alpha = if (outlineGradientEnabled) 1f else 0.45f
            db.outlineGradientStartPatch.isEnabled = outlineGradientEnabled
            db.outlineGradientEndPatch.isEnabled = outlineGradientEnabled
        }

        updateTextColor(textColor)
        updateOutlineColor(outlineColor)
        updateShadowColor(shadowColor)
        updateOutlineGradientStart(outlineGradientStart)
        updateOutlineGradientEnd(outlineGradientEnd)
        db.cbOutlineGradient.isChecked = outlineGradientEnabled
        refreshOutlineGradientControls()
        existingElement?.let { element ->
            db.etTextContent.setText(element.text)
            db.etTextContent.setSelection(db.etTextContent.text?.length ?: 0)
            db.tvTextPreview.text = element.text
            db.cbBold.isChecked = element.isBold
            db.cbItalic.isChecked = element.isItalic
            db.cbExtraOutline.isChecked = element.enableOutline
            db.cbExtraShadow.isChecked = element.enableShadow
            db.cbExtraGradient.isChecked = element.enableGradient || element.effect == TextEffect.GRADIENT
            db.cbExtraTexture.isChecked = element.enableTexture || element.effect == TextEffect.TEXTURE
            db.cbEnableOutlineEffect.isChecked = element.enableOutline ||
                element.effect == TextEffect.OUTLINE || element.effect == TextEffect.OUTLINE_SHADOW
            db.cbEnableShadowEffect.isChecked = element.enableShadow ||
                element.effect == TextEffect.SHADOW || element.effect == TextEffect.OUTLINE_SHADOW
            db.cbEnableGradientEffect.isChecked = element.enableGradient || element.effect == TextEffect.GRADIENT
            db.cbEnableTextureEffect.isChecked = element.enableTexture || element.effect == TextEffect.TEXTURE
            db.cbEnableWarpEffect.isChecked = element.effect == TextEffect.WARP
            db.spinnerTextEffect.setSelection(
                when (element.effect) {
                    TextEffect.OUTLINE -> 1
                    TextEffect.SHADOW -> 2
                    TextEffect.OUTLINE_SHADOW -> 3
                    TextEffect.GRADIENT -> 4
                    TextEffect.TEXTURE -> 5
                    TextEffect.WARP -> 6
                    else -> 0
                }
            )
            val fontIndex = systemFonts.indexOfFirst { it.displayName.equals(element.fontName, ignoreCase = true) }
            if (fontIndex >= 0) db.spinnerFont.setSelection(fontIndex)
            db.btnPerspective.text = if (pendingPerspective) "✓\nPersp" else "Persp"
            db.btnPolyline.text = if (pendingMesh) "✓\nMesh" else "Mesh"
            db.btnTextDialogApply.text = "Simpan Perubahan"
            db.btnTextDialogApplyTop.text = "Simpan"
        }
        // OpenDocument grants survive process restarts, so an existing texture can
        // be decoded again when a saved project is reopened. Show it immediately
        // instead of leaving the preview blank until the user picks another file.
        pendingTextureUri?.let { textureUri ->
            runCatching {
                contentResolver.openInputStream(Uri.parse(textureUri))?.use { stream ->
                    db.ivTexturePreview.setImageBitmap(android.graphics.BitmapFactory.decodeStream(stream))
                }
            }
        }
        db.textColorPatch.contentDescription = "Pilih warna teks"
        db.textColorPatch.isClickable = true
        db.textColorPatch.isFocusable = true
        db.textColorPatch.setOnClickListener {
            showColorPickerFull("Warna Teks", textColor, ::updateTextColor)
        }
        db.outlineColorPatch.setOnClickListener {
            showColorPickerFull("Warna Outline", outlineColor, ::updateOutlineColor)
        }
        db.shadowColorPatch.setOnClickListener {
            showColorPickerFull("Warna Bayangan", shadowColor, ::updateShadowColor)
        }
        db.cbOutlineGradient.setOnCheckedChangeListener { _, checked ->
            outlineGradientEnabled = checked
            refreshOutlineGradientControls()
        }
        db.outlineGradientStartPatch.setOnClickListener {
            if (outlineGradientEnabled) {
                showColorPickerFull("Warna Awal Gradient Outline", outlineGradientStart, ::updateOutlineGradientStart)
            }
        }
        db.outlineGradientEndPatch.setOnClickListener {
            if (outlineGradientEnabled) {
                showColorPickerFull("Warna Akhir Gradient Outline", outlineGradientEnd, ::updateOutlineGradientEnd)
            }
        }

        val refreshGradient = bindGradientControls(
            db = db,
            colors = gradientColors,
            initialAngle = gradientAngle,
            onAngleChanged = { gradientAngle = it }
        )

        fun currentEditorStyle(name: String, folder: String): com.vasiliastyper.model.TextStyle {
            val selectedFont = systemFonts.getOrNull(db.spinnerFont.selectedItemPosition)
            val effect = when (db.spinnerTextEffect.selectedItemPosition) {
                1 -> "OUTLINE"
                2 -> "SHADOW"
                3 -> "OUTLINE_SHADOW"
                4 -> "GRADIENT"
                5 -> "TEXTURE"
                6 -> "WARP"
                else -> "NONE"
            }
            return com.vasiliastyper.model.TextStyle(
                name = name,
                folder = folder,
                fontSize = size,
                fontName = selectedFont?.displayName ?: "Default",
                color = textColor,
                opacity = opacity,
                isBold = db.cbBold.isChecked,
                isItalic = db.cbItalic.isChecked,
                effect = effect,
                outlineWidth = outlineWidth.toFloat(),
                outlineOpacity = db.seekOutlineOpacity.progress,
                outlineColor = outlineColor,
                shadowDx = shadowDx,
                shadowDy = shadowDy,
                shadowRadius = shadowRadius,
                shadowSpread = shadowSpread,
                shadowColor = shadowColor,
                shadowOpacity = db.seekShadowOpacity.progress,
                gradientStartColor = gradientColors.first(),
                gradientEndColor = gradientColors.last(),
                gradientColors = gradientColors.toMutableList(),
                gradientAngle = gradientAngle,
                align = align.name,
                tracking = tracking.toFloat(),
                leading = leading * 100f,
                justify = pendingJustify,
                enableOutline = db.cbEnableOutlineEffect.isChecked,
                enableShadow = db.cbEnableShadowEffect.isChecked,
                enableGradient = db.cbEnableGradientEffect.isChecked,
                enableTexture = db.cbEnableTextureEffect.isChecked,
                textTransform = "NONE",
                enableOutlineGradient = outlineGradientEnabled,
                outlineGradStartColor = outlineGradientStart,
                outlineGradEndColor = outlineGradientEnd,
                textPathMode = pendingTextPathMode,
                textPathAmount = pendingTextPathAmount,
                textPathCycles = pendingTextPathCycles
            )
        }

        fun promptSaveCurrentStyle() {
            val visibleSaveDialog = activeStyleSaveDialog
            if (visibleSaveDialog?.isShowing == true || isFinishing || isDestroyed) return
            val folders = StyleManager.folders(this@MainActivity, StyleManager.loadStyles(this@MainActivity))
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 24, 40, 0)
            }
            val nameInput = EditText(this@MainActivity).apply {
                hint = "Nama style"
                setText(db.etTextContent.text?.toString()?.takeIf { it.isNotBlank() }?.take(24) ?: "New Style")
            }
            val folderInput = android.widget.AutoCompleteTextView(this@MainActivity).apply {
                hint = "Folder"
                setText("Default", false)
                setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, folders))
            }
            container.addView(TextView(this@MainActivity).apply { text = "Nama style" })
            container.addView(nameInput)
            container.addView(TextView(this@MainActivity).apply {
                text = "Folder"
                setPadding(0, 18, 0, 0)
            })
            container.addView(folderInput)

            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Save Style")
                .setView(container)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", null)
                .create()
            activeStyleSaveDialog = dialog
            dialog.setOnDismissListener {
                if (activeStyleSaveDialog === dialog) activeStyleSaveDialog = null
            }
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val name = nameInput.text?.toString().orEmpty().trim()
                    val folder = StyleManager.normalizeFolderName(folderInput.text?.toString().orEmpty())
                    if (name.isBlank()) {
                        Toast.makeText(this@MainActivity, "Nama style tidak boleh kosong", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    val style = currentEditorStyle(name, folder)
                    if (StyleManager.addStyle(this@MainActivity, style)) {
                        Toast.makeText(this@MainActivity, "Style '$name' disimpan di folder '$folder'", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(this@MainActivity, "Style gagal disimpan; coba kosongkan ruang penyimpanan", Toast.LENGTH_LONG).show()
                    }
                }
            }
            dialog.show()
        }

        fun promptLoadStyleIntoEditor() {
            val editorDialog = activeTextEditorDialog ?: return
            if (activeStyleManagerDialog?.isShowing == true || styleManagerDialogOpen) return
            pendingStyleLoadAction = { style ->
                applyStyleToDialog(
                    style = style,
                    db = db,
                    fonts = systemFonts,
                    setTextColor = ::updateTextColor,
                    setOutlineColor = ::updateOutlineColor,
                    setShadowColor = ::updateShadowColor,
                    setGradStart = { gradientColors[0] = it; refreshGradient() },
                    setGradEnd = { gradientColors[gradientColors.lastIndex] = it; refreshGradient() },
                    setGradientColors = {
                        gradientColors.clear()
                        gradientColors.addAll(it.takeIf(List<Int>::isNotEmpty) ?: listOf(Color.WHITE, Color.GRAY))
                        if (gradientColors.size == 1) gradientColors.add(gradientColors.first())
                        refreshGradient()
                    },
                    setGradientAngle = { gradientAngle = it },
                    setOpacity = { opacity = it },
                    setOutlineGradient = { enabled, start, end ->
                        outlineGradientEnabled = enabled
                        updateOutlineGradientStart(start)
                        updateOutlineGradientEnd(end)
                        refreshOutlineGradientControls()
                    },
                    setShadowSpread = { shadowSpread = it },
                    setAlign = { align = it },
                    setJustify = { pendingJustify = it }
                )
                pendingStyleLoadAction = null
            }
            // Two BottomSheetDialogs must not be stacked. Hiding (not dismissing)
            // preserves all editor controls while Style Manager is in front.
            editorDialog.hide()
            showStyleManagerStandalone {
                if (!isFinishing && !isDestroyed && activeTextEditorDialog === editorDialog) {
                    runCatching { editorDialog.show() }
                        .onFailure { error -> Log.e("VasiliasTyper", "Editor gagal dipulihkan", error) }
                }
            }
        }

        pendingAutoStyle?.let { style ->
            applyStyleToDialog(
                style = style,
                db = db,
                fonts = systemFonts,
                setTextColor = ::updateTextColor,
                setOutlineColor = ::updateOutlineColor,
                setShadowColor = ::updateShadowColor,
                setGradStart = { gradientColors[0] = it; refreshGradient() },
                setGradEnd = { gradientColors[gradientColors.lastIndex] = it; refreshGradient() },
                setGradientColors = {
                    gradientColors.clear()
                    gradientColors.addAll(it.takeIf(List<Int>::isNotEmpty) ?: listOf(Color.WHITE, Color.GRAY))
                    if (gradientColors.size == 1) gradientColors.add(gradientColors.first())
                    refreshGradient()
                },
                setGradientAngle = { gradientAngle = it },
                setOpacity = { opacity = it },
                setOutlineGradient = { enabled, start, end ->
                    outlineGradientEnabled = enabled
                    updateOutlineGradientStart(start)
                    updateOutlineGradientEnd(end)
                    refreshOutlineGradientControls()
                },
                setShadowSpread = { shadowSpread = it },
                setAlign = { align = it },
                setJustify = { pendingJustify = it }
            )
            pendingAutoStyle = null
        }

        db.etTextContent.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                db.tvTextPreview.text = s?.toString().orEmpty().ifBlank { "Preview" }
            }
        })
        db.seekFontSize.progress = (size - 8f).roundToInt().coerceIn(0, db.seekFontSize.max)
        db.tvFontSize.text = size.roundToInt().toString()
        db.tvTextPreview.textSize = size
        db.seekFontSize.setOnSeekBarChangeListener(seekListener { progress ->
            size = progress + 8f
            db.tvFontSize.text = size.roundToInt().toString()
            db.tvTextPreview.textSize = size
        })
        db.tvFontSize.setOnClickListener {
            askNumberInput("Ukuran font (px)", size, 4f, 512f) {
                size = it
                db.seekFontSize.progress = (size - 8f).roundToInt().coerceIn(0, db.seekFontSize.max)
                db.tvFontSize.text = size.roundToInt().toString()
                db.tvTextPreview.textSize = size
            }
        }
        db.seekTracking.progress = (tracking + 50).coerceIn(0, db.seekTracking.max)
        db.tvTracking.text = tracking.toString()
        db.seekTracking.setOnSeekBarChangeListener(seekListener { progress ->
            tracking = progress - 50
            db.tvTracking.text = tracking.toString()
            db.tvTextPreview.letterSpacing = tracking / 100f
        })
        db.tvTracking.setOnClickListener {
            askNumberInput("Tracking (px)", tracking.toFloat(), -50f, 100f) {
                tracking = it.roundToInt()
                db.seekTracking.progress = (tracking + 50).coerceIn(0, db.seekTracking.max)
                db.tvTracking.text = tracking.toString()
                db.tvTextPreview.letterSpacing = tracking / 100f
            }
        }
        db.seekLeading.progress = ((leading * 100f).roundToInt() + 50).coerceIn(0, db.seekLeading.max)
        db.tvLeading.text = "${(leading * 100f).roundToInt()}%"
        db.seekLeading.setOnSeekBarChangeListener(seekListener { progress ->
            leading = (progress - 50) / 100f
            db.tvLeading.text = "${(leading * 100).roundToInt()}%"
        })
        db.tvLeading.setOnClickListener {
            askNumberInput("Leading (%)", leading * 100f, -50f, 270f) {
                leading = it / 100f
                db.seekLeading.progress = ((leading * 100f).roundToInt() + 50).coerceIn(0, db.seekLeading.max)
                db.tvLeading.text = "${(leading * 100).roundToInt()}%"
            }
        }
        // Paragraph -50 … +50 px (ibispaint).
        db.seekParagraph.progress = (paragraph.roundToInt() + 50).coerceIn(0, db.seekParagraph.max)
        db.tvParagraph.text = paragraph.roundToInt().toString()
        db.seekParagraph.setOnSeekBarChangeListener(seekListener { progress ->
            paragraph = (progress - 50).toFloat()
            db.tvParagraph.text = paragraph.roundToInt().toString()
        })
        db.tvParagraph.setOnClickListener {
            askNumberInput("Jarak paragraph (px)", paragraph, -50f, 50f) {
                paragraph = it
                db.seekParagraph.progress = (paragraph.roundToInt() + 50).coerceIn(0, db.seekParagraph.max)
                db.tvParagraph.text = paragraph.roundToInt().toString()
            }
        }
        // Toggle force/snap — tersimpan agar tidak reset tiap buka dialog.
        val snapPrefs = getSharedPreferences("vasilia_editor", MODE_PRIVATE)
        db.cbSnapCenter.isChecked = snapPrefs.getBoolean("snap_center", true)
        db.cbForceInside.isChecked = snapPrefs.getBoolean("force_inside", true)
        db.cbSnapGrid.isChecked = snapPrefs.getBoolean("snap_grid", true)
        binding.canvasView.snapToCenterEnabled = db.cbSnapCenter.isChecked
        binding.canvasView.clampToCanvasEnabled = db.cbForceInside.isChecked
        binding.canvasView.snapGridEnabled = db.cbSnapGrid.isChecked
        db.cbSnapCenter.setOnCheckedChangeListener { _, checked ->
            binding.canvasView.snapToCenterEnabled = checked
            snapPrefs.edit().putBoolean("snap_center", checked).apply()
        }
        db.cbForceInside.setOnCheckedChangeListener { _, checked ->
            binding.canvasView.clampToCanvasEnabled = checked
            snapPrefs.edit().putBoolean("force_inside", checked).apply()
        }
        db.cbSnapGrid.setOnCheckedChangeListener { _, checked ->
            binding.canvasView.snapGridEnabled = checked
            snapPrefs.edit().putBoolean("snap_grid", checked).apply()
        }
        db.seekTextOpacity.progress = opacity.coerceIn(0, 100)
        db.tvTextOpacity.text = "${db.seekTextOpacity.progress}%"
        db.tvTextPreview.alpha = db.seekTextOpacity.progress / 100f
        db.seekTextOpacity.setOnSeekBarChangeListener(seekListener { progress ->
            opacity = progress.coerceIn(0, 100)
            db.tvTextOpacity.text = "$opacity%"
            db.tvTextPreview.alpha = opacity / 100f
        })
        db.tvTextOpacity.setOnClickListener {
            askNumberInput("Opacity teks (%)", opacity.toFloat(), 0f, 100f) {
                opacity = it.roundToInt()
                db.seekTextOpacity.progress = opacity
                db.tvTextOpacity.text = "$opacity%"
                db.tvTextPreview.alpha = opacity / 100f
            }
        }

        db.btnAlignLeft.setOnClickListener {
            align = TextAlign.LEFT
            db.tvTextPreview.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        db.btnAlignCenter.setOnClickListener {
            align = TextAlign.CENTER
            db.tvTextPreview.gravity = Gravity.CENTER
        }
        db.btnAlignRight.setOnClickListener {
            align = TextAlign.RIGHT
            db.tvTextPreview.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        db.btnJustify.setOnClickListener {
            pendingJustify = !pendingJustify
            db.btnJustify.backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor(if (pendingJustify) "#4D9BF0" else "#3A3A3A")
            )
        }

        db.btnCaseUpper.setOnClickListener {
            db.etTextContent.setText(db.etTextContent.text.toString().uppercase(Locale.getDefault()))
            db.etTextContent.setSelection(db.etTextContent.text.length)
        }
        db.btnCaseLower.setOnClickListener {
            db.etTextContent.setText(db.etTextContent.text.toString().lowercase(Locale.getDefault()))
            db.etTextContent.setSelection(db.etTextContent.text.length)
        }
        db.btnCaseTitle.setOnClickListener {
            val title = db.etTextContent.text.toString().split(Regex("(\\s+)"))
                .joinToString(" ") { word -> word.lowercase(Locale.getDefault()).replaceFirstChar(Char::titlecase) }
            db.etTextContent.setText(title)
            db.etTextContent.setSelection(db.etTextContent.text.length)
        }
        db.btnPerspective.setOnClickListener {
            pendingPerspective = true
            pendingMesh = false
            db.btnPerspective.text = "✓\nPersp"
        }
        db.btnPolyline.setOnClickListener {
            pendingMesh = true
            pendingPerspective = false
            db.btnPolyline.text = "✓\nMesh"
        }
        db.btnMultiStyle.setOnClickListener {
            showSpanEditorDialog(db.etTextContent.text.toString(), pendingSpans, systemFonts) { newText, spans ->
                db.etTextContent.setText(newText)
                pendingSpans.clear()
                pendingSpans.addAll(spans)
            }
        }
        db.btnSaveStyle.setOnClickListener { promptSaveCurrentStyle() }
        db.btnLoadStyle.setOnClickListener { promptLoadStyleIntoEditor() }
        db.btnFontBank.setOnClickListener {
            showFontBankDialog { selected ->
                val index = systemFonts.indexOfFirst { it.displayName == selected.displayName }
                if (index >= 0) db.spinnerFont.setSelection(index)
                db.tvTextPreview.typeface = selected.typeface
            }
        }
        db.btnFontAnalyze.setOnClickListener {
            val sample = binding.canvasView.compositeVisibleLayers()
            if (sample == null) {
                Toast.makeText(this, "Kanvas belum tersedia untuk dianalisis", Toast.LENGTH_SHORT).show()
            } else {
                runFontMatch(sample) { selected ->
                    val index = systemFonts.indexOfFirst { it.displayName == selected.displayName }
                    if (index >= 0) db.spinnerFont.setSelection(index)
                    db.tvTextPreview.typeface = selected.typeface
                }
            }
        }

        db.seekOutlineWidth.progress = outlineWidth.coerceIn(0, db.seekOutlineWidth.max)
        db.tvOutlineWidth.text = outlineWidth.toString()
        db.seekOutlineOpacity.progress = existingElement?.outlineOpacity?.coerceIn(0, 100) ?: 100
        db.tvOutlineOpacity.text = "${db.seekOutlineOpacity.progress}%"
        db.seekOutlineWidth.setOnSeekBarChangeListener(seekListener {
            outlineWidth = it
            db.tvOutlineWidth.text = it.toString()
        })
        db.tvOutlineWidth.setOnClickListener {
            askNumberInput("Lebar outline (px)", outlineWidth.toFloat(), 0f, db.seekOutlineWidth.max.toFloat()) {
                outlineWidth = it.roundToInt()
                db.seekOutlineWidth.progress = outlineWidth
                db.tvOutlineWidth.text = outlineWidth.toString()
            }
        }
        db.seekOutlineOpacity.setOnSeekBarChangeListener(seekListener {
            db.tvOutlineOpacity.text = "$it%"
        })
        db.tvOutlineOpacity.setOnClickListener {
            askNumberInput("Opacity outline (%)", db.seekOutlineOpacity.progress.toFloat(), 0f, 100f) {
                db.seekOutlineOpacity.progress = it.roundToInt()
                db.tvOutlineOpacity.text = "${it.roundToInt()}%"
            }
        }
        db.seekShadowRadius.progress = shadowRadius.roundToInt().coerceIn(0, db.seekShadowRadius.max)
        db.seekShadowDx.progress = (shadowDx + 24f).roundToInt().coerceIn(0, db.seekShadowDx.max)
        db.seekShadowDy.progress = (shadowDy + 24f).roundToInt().coerceIn(0, db.seekShadowDy.max)
        db.seekShadowOpacity.progress = existingElement?.shadowOpacity?.coerceIn(0, 100) ?: 50
        db.seekShadowSpread.progress = shadowSpread.roundToInt().coerceIn(0, db.seekShadowSpread.max)
        db.tvShadowSpread.text = "Ketebalan ${db.seekShadowSpread.progress} px"
        db.seekShadowRadius.setOnSeekBarChangeListener(seekListener { shadowRadius = it.toFloat(); db.tvShadowRadius.text = it.toString() })
        db.tvShadowRadius.setOnClickListener {
            askNumberInput("Radius shadow (px)", shadowRadius, 0f, db.seekShadowRadius.max.toFloat()) {
                shadowRadius = it
                db.seekShadowRadius.progress = it.roundToInt().coerceIn(0, db.seekShadowRadius.max)
                db.tvShadowRadius.text = it.roundToInt().toString()
            }
        }
        db.seekShadowSpread.setOnSeekBarChangeListener(seekListener {
            shadowSpread = it.toFloat()
            db.tvShadowSpread.text = "Ketebalan $it px"
        })
        db.tvShadowSpread.setOnClickListener {
            askNumberInput("Ketebalan shadow (px)", shadowSpread, 0f, db.seekShadowSpread.max.toFloat()) {
                shadowSpread = it
                db.seekShadowSpread.progress = it.roundToInt().coerceIn(0, db.seekShadowSpread.max)
                db.tvShadowSpread.text = "Ketebalan ${it.roundToInt()} px"
            }
        }
        db.seekShadowDx.setOnSeekBarChangeListener(seekListener { shadowDx = (it - 24).toFloat(); db.tvShadowDx.text = shadowDx.roundToInt().toString() })
        db.seekShadowDy.setOnSeekBarChangeListener(seekListener { shadowDy = (it - 24).toFloat(); db.tvShadowDy.text = shadowDy.roundToInt().toString() })
        db.tvShadowDx.setOnClickListener {
            askNumberInput("Shadow X (px)", shadowDx, -24f, 24f) {
                shadowDx = it
                db.seekShadowDx.progress = (shadowDx + 24f).roundToInt().coerceIn(0, db.seekShadowDx.max)
                db.tvShadowDx.text = shadowDx.roundToInt().toString()
            }
        }
        db.tvShadowDy.setOnClickListener {
            askNumberInput("Shadow Y (px)", shadowDy, -24f, 24f) {
                shadowDy = it
                db.seekShadowDy.progress = (shadowDy + 24f).roundToInt().coerceIn(0, db.seekShadowDy.max)
                db.tvShadowDy.text = shadowDy.roundToInt().toString()
            }
        }
        db.seekShadowOpacity.setOnSeekBarChangeListener(seekListener { db.tvShadowOpacity.text = "$it%" })
        db.tvShadowOpacity.setOnClickListener {
            askNumberInput("Opacity shadow (%)", db.seekShadowOpacity.progress.toFloat(), 0f, 100f) {
                db.seekShadowOpacity.progress = it.roundToInt()
                db.tvShadowOpacity.text = "${it.roundToInt()}%"
            }
        }

        db.spinnerBlurType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Gaussian", "Motion — semua arah", "Motion Horizontal", "Motion Vertical")
        )
        fun directionName(angle: Int): String = when (angle.mod(360)) {
            in 23..67 -> "kanan-bawah"
            in 68..112 -> "bawah"
            in 113..157 -> "kiri-bawah"
            in 158..202 -> "kiri"
            in 203..247 -> "kiri-atas"
            in 248..292 -> "atas"
            in 293..337 -> "kanan-atas"
            else -> "kanan"
        }
        fun refreshBlurControls() {
            val controlsEnabled = blurEnabled
            db.spinnerBlurType.isEnabled = controlsEnabled
            db.seekBlurRadius.isEnabled = controlsEnabled
            val isMotion = pendingBlurType != BlurType.GAUSSIAN
            db.panelMotionBlur.visibility = if (isMotion) View.VISIBLE else View.GONE
            db.seekBlurDistance.isEnabled = controlsEnabled
            db.seekBlurAngle.isEnabled = controlsEnabled && pendingBlurType == BlurType.MOTION
            db.tvBlurRadius.text = "Radius ${pendingBlurRadius.roundToInt()} px"
            db.tvBlurDistance.text = "Jarak gerak ${pendingBlurDistance.roundToInt()} px"
            val angle = when (pendingBlurType) {
                BlurType.MOTION_H -> 0
                BlurType.MOTION_V -> 90
                else -> pendingBlurAngle.roundToInt().mod(360)
            }
            db.tvBlurAngle.text = "Arah $angle° (${directionName(angle)})"
            db.tvBlurAngle.alpha = if (db.seekBlurAngle.isEnabled) 1f else 0.55f
            db.seekBlurAngle.alpha = if (db.seekBlurAngle.isEnabled) 1f else 0.55f
        }
        db.cbEnableBlur.isChecked = blurEnabled
        db.cbEnableBlur.setOnCheckedChangeListener { _, checked ->
            blurEnabled = checked
            if (checked && pendingBlurRadius <= 0f) {
                pendingBlurRadius = 8f
                db.seekBlurRadius.progress = 8
            }
            refreshBlurControls()
        }
        db.spinnerBlurType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                pendingBlurType = when (position) {
                    1 -> BlurType.MOTION
                    2 -> BlurType.MOTION_H
                    3 -> BlurType.MOTION_V
                    else -> BlurType.GAUSSIAN
                }
                refreshBlurControls()
            }
        }
        db.seekBlurRadius.progress = pendingBlurRadius.roundToInt().coerceIn(0, db.seekBlurRadius.max)
        db.seekBlurDistance.progress = pendingBlurDistance.roundToInt().coerceIn(0, db.seekBlurDistance.max)
        db.seekBlurAngle.progress = pendingBlurAngle.roundToInt().mod(360).coerceIn(0, db.seekBlurAngle.max)
        db.spinnerBlurType.setSelection(
            when (pendingBlurType) {
                BlurType.MOTION -> 1
                BlurType.MOTION_H -> 2
                BlurType.MOTION_V -> 3
                else -> 0
            }
        )
        db.seekBlurRadius.setOnSeekBarChangeListener(seekListener {
            pendingBlurRadius = it.toFloat()
            refreshBlurControls()
        })
        db.seekBlurDistance.setOnSeekBarChangeListener(seekListener {
            pendingBlurDistance = it.toFloat().coerceAtLeast(1f)
            refreshBlurControls()
        })
        db.seekBlurAngle.setOnSeekBarChangeListener(seekListener {
            pendingBlurAngle = it.toFloat()
            refreshBlurControls()
        })
        refreshBlurControls()
        db.tvBlurRadius.setOnClickListener {
            askNumberInput("Radius blur (px)", pendingBlurRadius, 0f, db.seekBlurRadius.max.toFloat()) {
                pendingBlurRadius = it
                db.seekBlurRadius.progress = it.roundToInt().coerceIn(0, db.seekBlurRadius.max)
                refreshBlurControls()
            }
        }
        db.tvBlurDistance.setOnClickListener {
            askNumberInput("Jarak motion blur (px)", pendingBlurDistance, 1f, db.seekBlurDistance.max.toFloat()) {
                pendingBlurDistance = it.coerceAtLeast(1f)
                db.seekBlurDistance.progress = it.roundToInt().coerceIn(0, db.seekBlurDistance.max)
                refreshBlurControls()
            }
        }
        db.tvBlurAngle.setOnClickListener {
            askNumberInput("Arah motion blur (°)", pendingBlurAngle, 0f, 360f) {
                pendingBlurAngle = it
                db.seekBlurAngle.progress = it.roundToInt().mod(360).coerceIn(0, db.seekBlurAngle.max)
                refreshBlurControls()
            }
        }
        db.btnTextTexturePick.setOnClickListener {
            pickTexture { uri ->
                TextRenderer.invalidateTextureCache(pendingTextureUri)
                pendingTextureUri = uri.toString()
                runCatching {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        db.ivTexturePreview.setImageBitmap(android.graphics.BitmapFactory.decodeStream(stream))
                    }
                }.onFailure {
                    Toast.makeText(this, "Texture tidak dapat dibaca", Toast.LENGTH_SHORT).show()
                }
                // Picking a texture should activate the effect. Previously the URI
                // was stored while Texture remained disabled, making the feature
                // appear broken until a second, unrelated checkbox was changed.
                db.cbEnableTextureEffect.isChecked = true
                db.cbExtraTexture.isChecked = true
            }
        }
        db.btnTextTextureClear.setOnClickListener {
            TextRenderer.invalidateTextureCache(pendingTextureUri)
            pendingTextureUri = null
            db.ivTexturePreview.setImageDrawable(null)
            db.cbEnableTextureEffect.isChecked = false
            db.cbExtraTexture.isChecked = false
        }

        // Editor tidak dapat digeser sebagai bottom-sheet. Gesture drag bawaan
        // Material sebelumnya berebut event dengan EditText/SeekBar dan dapat
        // memicu layout berulang, input delay, atau dialog tertutup tidak sengaja.
        val dialog = showBottomSheetDialog(draggable = false) {
            setContentView(db.root)
        }
        activeTextEditorDialog = dialog
        if (cachedFontList == null) {
            fontWarmupJob?.cancel()
            fontWarmupJob = lifecycleScope.launch {
                val discovered = withContext(Dispatchers.IO) { runCatching { buildFontList() }.getOrNull() }
                    ?: return@launch
                if (isFinishing || isDestroyed || activeTextEditorDialog !== dialog) return@launch
                val selectedName = existingElement?.fontName
                    ?: systemFonts.getOrNull(db.spinnerFont.selectedItemPosition)?.displayName
                systemFonts.clear()
                systemFonts.addAll(discovered)
                fontAdapter.notifyDataSetChanged()
                val selectedIndex = systemFonts.indexOfFirst {
                    it.displayName.equals(selectedName, ignoreCase = true)
                }
                if (selectedIndex >= 0) db.spinnerFont.setSelection(selectedIndex)
            }
        }
        db.btnTextDialogCancel.setOnClickListener { dialog.dismiss() }
        fun applyTextChanges() {
            val text = db.etTextContent.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Teks tidak boleh kosong", Toast.LENGTH_SHORT).show()
                return
            }
            val workspace = vm.activeWorkspace ?: run {
                Toast.makeText(this, "Workspace tidak tersedia", Toast.LENGTH_SHORT).show()
                return
            }
            val selectedFont = systemFonts.getOrNull(db.spinnerFont.selectedItemPosition)
            val effect = when (db.spinnerTextEffect.selectedItemPosition) {
                1 -> TextEffect.OUTLINE
                2 -> TextEffect.SHADOW
                3 -> TextEffect.OUTLINE_SHADOW
                4 -> TextEffect.GRADIENT
                5 -> TextEffect.TEXTURE
                6 -> TextEffect.WARP
                else -> TextEffect.NONE
            }
            val safePadding = if (existingElement == null) {
                padding.coerceIn(0f, minOf(width, height) * 0.20f)
            } else {
                0f
            }
            val boxX = existingElement?.x ?: (x + safePadding)
            val boxY = existingElement?.y ?: (y + safePadding)
            val boxWidth = existingElement?.width ?: (width - safePadding * 2f).coerceAtLeast(10f)
            val boxHeight = existingElement?.height ?: (height - safePadding * 2f).coerceAtLeast(10f)
            val fittedSize = TextRenderer.autoFitFontSize(
                text = text,
                boxWidth = boxWidth,
                boxHeight = boxHeight,
                typeface = selectedFont?.typeface,
                maxFontSize = size,
                leading = leading * 100f,
                roundBubbleMode = binding.canvasView.selection.isRoundBubble
            )
            // Text edits only need the lightweight text-history stack. Serializing
            // the complete workspace here PNG-encodes every canvas layer on the UI
            // thread and was the main source of delay after Simpan/Terapkan.
            binding.canvasView.pushTextHistory()
            val element = existingElement ?: TextElement(
                x = boxX,
                y = boxY,
                width = boxWidth,
                height = boxHeight,
                layerId = workspace.layers.getOrNull(workspace.activeLayerIndex)?.id
            ).also { binding.canvasView.textElements.add(it) }

            element.text = text
            element.fontSize = fittedSize
            element.fontName = selectedFont?.displayName ?: "Default"
            element.typeface = selectedFont?.typeface
            // Preserve the ARGB value chosen by the colour wheel. Element opacity
            // remains an independent multiplier, matching outline and shadow colours.
            element.color = textColor
            element.isBold = db.cbBold.isChecked
            element.isItalic = db.cbItalic.isChecked
            element.effect = effect
            element.outlineColor = outlineColor
            element.outlineWidth = outlineWidth.toFloat().coerceAtLeast(1f)
            element.outlineOpacity = db.seekOutlineOpacity.progress
            element.shadowDx = shadowDx
            element.shadowDy = shadowDy
            element.shadowRadius = shadowRadius
            element.shadowSpread = shadowSpread
            element.shadowColor = shadowColor
            element.shadowOpacity = db.seekShadowOpacity.progress
            element.gradientStartColor = gradientColors.first()
            element.gradientEndColor = gradientColors.last()
            element.gradientColors = gradientColors.toMutableList()
            element.gradientAngle = gradientAngle
            element.align = align
            element.textureUri = pendingTextureUri
            element.spans = pendingSpans.takeIf { it.isNotEmpty() }?.toMutableList()
            element.blurType = if (blurEnabled) pendingBlurType.name else "NONE"
            element.blurRadius = if (blurEnabled) pendingBlurRadius else 0f
            element.blurMotionAngle = pendingBlurAngle
            element.blurMotionDistance = pendingBlurDistance
            element.opacity = opacity
            element.leading = leading * 100f
            element.tracking = tracking.toFloat()
            element.paragraphSpacing = paragraph.coerceIn(-50f, 50f)
            element.justify = pendingJustify
            element.textPathMode = pendingTextPathMode
            element.textPathAmount = pendingTextPathAmount.coerceIn(-100f, 100f)
            element.textPathCycles = pendingTextPathCycles.coerceIn(0.5f, 5f)
            // Setiap kolom efek bersifat independen; spinner lama hanya disimpan
            // secara tersembunyi untuk kompatibilitas style/proyek versi sebelumnya.
            element.enableOutline = db.cbEnableOutlineEffect.isChecked
            element.enableShadow = db.cbEnableShadowEffect.isChecked
            element.enableGradient = db.cbEnableGradientEffect.isChecked
            element.enableTexture = db.cbEnableTextureEffect.isChecked
            element.enableOutlineGradient = outlineGradientEnabled
            element.outlineGradStartColor = outlineGradientStart
            element.outlineGradEndColor = outlineGradientEnd

            binding.canvasView.activeTextId = element.id
            // Ingat ukuran terakhir agar dialog baru tidak reset ke 44.
            getSharedPreferences("vasilia_editor", MODE_PRIVATE).edit()
                .putFloat("last_font_size", size.coerceIn(4f, 512f)).apply()
            if (pendingPerspective && element.perspCorners == null) binding.canvasView.enablePerspectiveForActiveText()
            if (pendingMesh && element.meshPoints == null) binding.canvasView.enableMeshForActiveText()
            binding.canvasView.clearSelection()
            binding.canvasView.invalidate()
            updateTextQuickToolbar(element)
            triggerAutoSave()
            Toast.makeText(
                this,
                if (existingElement == null) "Teks diterapkan ke kanvas" else "Perubahan teks disimpan",
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }
        db.btnTextDialogApply.setOnClickListener { applyTextChanges() }
        db.btnTextDialogApplyTop.setOnClickListener { applyTextChanges() }
        dialog.setOnDismissListener {
            if (activeTextEditorDialog === dialog) activeTextEditorDialog = null
            textEditorDialogOpen = false
            pendingStyleLoadAction = null
            if (binding.canvasView.eyedropperMode) {
                binding.canvasView.eyedropperMode = false
                pendingPickerEyedropper = null
            }
        }
    }
    // ══════════════════════════════════════════════════════════════════════════
    // STYLE MANAGER (standalone dialog)
    // ══════════════════════════════════════════════════════════════════════════

    private fun showStyleManagerStandalone(onClosed: (() -> Unit)? = null) {
        if (isFinishing || isDestroyed || styleManagerDialogOpen || activeStyleManagerDialog?.isShowing == true) {
            onClosed?.invoke()
            return
        }
        styleManagerDialogOpen = true
        val db = try {
            DialogStyleManagerBinding.inflate(layoutInflater)
        } catch (error: Throwable) {
            styleManagerDialogOpen = false
            pendingStyleLoadAction = null
            Log.e("VasiliasTyper", "Style Manager gagal di-inflate", error)
            Toast.makeText(this, "Style Manager gagal dibuka", Toast.LENGTH_SHORT).show()
            onClosed?.invoke()
            return
        }
        // Draw the manager first. User data and the one-time preset separation are
        // loaded after the bottom sheet has rendered, never on the tap/UI thread.
        var allStyles = mutableListOf<com.vasiliastyper.model.TextStyle>()
        var cachedFolders: List<String> = listOf("Default")
        var showingPresets = false
        var applyingStyle = false
        var initialLoadJob: Job? = null
        var presetLoadJob: Job? = null
        // Never parse SharedPreferences from this callback. Spinner refresh runs on the
        // main thread; folder data is prepared together with styles in Dispatchers.IO.
        val allFolders = { cachedFolders }

        fun refreshFolderSpinners() {
            val folders = allFolders()
            db.spinnerStyleFolder.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, folders)
            db.spinnerFilterFolder.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("All") + folders)
            db.etNewFolder.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, folders))
            db.tvStyleCount.text = if (allStyles.isEmpty()) "Memuat…" else "${allStyles.size} style"
        }
        refreshFolderSpinners()
        db.btnSaveStyle.isEnabled = false
        db.btnAddFolder.isEnabled = false

        val dialog = try {
            showBottomSheetDialog(draggable = false) { setContentView(db.root) }
        } catch (error: Throwable) {
            styleManagerDialogOpen = false
            pendingStyleLoadAction = null
            Log.e("VasiliasTyper", "Style Manager gagal dibuka", error)
            Toast.makeText(this, "Style Manager gagal dibuka", Toast.LENGTH_SHORT).show()
            onClosed?.invoke()
            return
        }
        activeStyleManagerDialog = dialog
        dialog.setOnDismissListener {
            // Detaching the adapter releases every generated preview bitmap immediately,
            // rather than waiting for a later GC cycle on an already memory-heavy canvas.
            initialLoadJob?.cancel()
            presetLoadJob?.cancel()
            db.stylesList.adapter = null
            if (activeStyleManagerDialog === dialog) activeStyleManagerDialog = null
            styleManagerDialogOpen = false
            pendingStyleLoadAction = null
            onClosed?.invoke()
        }

        val applySelectedStyle: (com.vasiliastyper.model.TextStyle) -> Unit = applyStyle@{ sourceStyle ->
            if (applyingStyle || isFinishing || isDestroyed || !dialog.isShowing) return@applyStyle
            val applyAction = pendingStyleLoadAction
            if (applyAction != null) {
                applyingStyle = true
                // Apply an independent, validated snapshot only after the manager is
                // dismissed and the hidden editor has been restored. Mutating controls
                // while two BottomSheet windows are transitioning caused intermittent
                // Window/RecyclerView crashes on slower devices.
                val style = sourceStyle.safeCopyForApply()
                pendingStyleLoadAction = null
                dialog.dismiss()
                binding.canvasView.post {
                    if (isFinishing || isDestroyed) return@post
                    runCatching { applyAction(style) }
                        .onSuccess {
                            Toast.makeText(this, "Style '${style.name}' diterapkan", Toast.LENGTH_SHORT).show()
                        }
                        .onFailure { error ->
                            applyingStyle = false
                            Log.e("VasiliasTyper", "Style '${style.name}' gagal diterapkan", error)
                            Toast.makeText(this, "Style gagal diterapkan; editor tetap aman", Toast.LENGTH_LONG).show()
                        }
                }
            } else {
                val safeName = sourceStyle.name.trim().ifBlank { "Style" }
                Toast.makeText(this, "Buka text editor lalu tekan Load Style untuk menerapkan '$safeName'", Toast.LENGTH_SHORT).show()
            }
        }
        val styleAdapter = StyleAdapter(
            allStyles,
            onApply = applySelectedStyle,
            onDelete = { style, _ ->
                if (StyleManager.deleteStyle(this, style)) {
                    allStyles = StyleManager.loadStyles(this)
                    cachedFolders = StyleManager.folders(this, allStyles)
                    refreshFolderSpinners()
                    filterStylesInDialog(db, allStyles)
                } else {
                    Toast.makeText(this, "Style gagal dihapus; data tetap dipertahankan", Toast.LENGTH_LONG).show()
                }
            }
        )
        db.stylesList.layoutManager = LinearLayoutManager(this)
        db.stylesList.setItemViewCacheSize(4)
        db.stylesList.recycledViewPool.setMaxRecycledViews(0, 4)
        db.stylesList.recycledViewPool.setMaxRecycledViews(1, 8)
        db.stylesList.adapter = styleAdapter
        db.btnExpandStyleFolders.setOnClickListener { (db.stylesList.adapter as? StyleAdapter)?.expandAll() }
        db.btnCollapseStyleFolders.setOnClickListener { (db.stylesList.adapter as? StyleAdapter)?.collapseAll() }
        db.btnRestoreWebtoonSfx.setOnClickListener {
            if (!showingPresets) {
                // Generating the first preset list initializes fonts, colours and dozens of
                // TextStyle objects. Never perform that work in the click callback: on slower
                // devices it froze the hidden Text Editor and rapid taps could race dismissal.
                showingPresets = true
                db.tvStyleManagerTitle.text = "Preset Browser"
                db.tvStyleManagerSubtitle.text = "Menyiapkan preset aman di background…"
                db.btnRestoreWebtoonSfx.text = "Memuat preset…"
                db.btnRestoreWebtoonSfx.isEnabled = false
                db.styleSaveCard.visibility = View.GONE
                db.spinnerFilterFolder.isEnabled = false
                db.etStyleSearch.isEnabled = false
                db.tvStyleEmpty.visibility = View.GONE
                db.stylesList.visibility = View.VISIBLE
                db.tvStyleCount.text = "Memuat…"
                db.stylesList.adapter = null

                presetLoadJob?.cancel()
                presetLoadJob = lifecycleScope.launch {
                    val presetResult = runCatching {
                        withContext(Dispatchers.Default) {
                            WebtoonSfxPresets.defaultStyles().toMutableList()
                        }
                    }
                    if (!dialog.isShowing || !showingPresets || isFinishing || isDestroyed) return@launch

                    db.btnRestoreWebtoonSfx.isEnabled = true
                    db.btnRestoreWebtoonSfx.text = "Kembali ke Style Manager"
                    presetResult.onSuccess { presets ->
                        db.tvStyleManagerSubtitle.text =
                            "Pilih Terapkan. Preset tidak disalin ke penyimpanan Style Manager."
                        db.tvStyleCount.text = "${presets.size} preset"
                        db.spinnerFilterFolder.adapter = ArrayAdapter(
                            this@MainActivity,
                            android.R.layout.simple_spinner_dropdown_item,
                            listOf(WebtoonSfxPresets.FOLDER)
                        )
                        db.stylesList.adapter = StyleAdapter(
                            presets,
                            onApply = applySelectedStyle,
                            onDelete = { _, _ -> },
                            allowDelete = false,
                            initiallyCollapsed = false
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) return@onFailure
                        Log.e("VasiliasTyper", "Preset Browser gagal menyiapkan preset", error)
                        showingPresets = false
                        db.stylesList.adapter = styleAdapter
                        db.tvStyleManagerTitle.text = "Style Manager"
                        db.tvStyleManagerSubtitle.text =
                            "Preset gagal dimuat. Style buatan Anda tetap aman."
                        db.btnRestoreWebtoonSfx.text = "Coba Buka Preset Lagi"
                        db.styleSaveCard.visibility = View.VISIBLE
                        db.spinnerFilterFolder.isEnabled = true
                        db.etStyleSearch.isEnabled = true
                        refreshFolderSpinners()
                        filterStylesInDialog(db, allStyles)
                        Toast.makeText(this@MainActivity, "Preset tidak dapat dimuat", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                showingPresets = false
                presetLoadJob?.cancel()
                presetLoadJob = null
                db.stylesList.adapter = styleAdapter
                db.tvStyleManagerTitle.text = "Style Manager"
                db.tvStyleManagerSubtitle.text =
                    "Style buatan Anda disimpan terpisah dari preset agar panel selalu ringan."
                db.btnRestoreWebtoonSfx.text = "Buka Preset SFX Webtoon"
                db.btnRestoreWebtoonSfx.isEnabled = true
                db.styleSaveCard.visibility = View.VISIBLE
                db.spinnerFilterFolder.isEnabled = true
                db.etStyleSearch.isEnabled = true
                refreshFolderSpinners()
                filterStylesInDialog(db, allStyles)
            }
        }

        db.spinnerFilterFolder.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!showingPresets) filterStylesInDialog(db, allStyles)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // v11.3 — live search. Debounced lightly via the text watcher; filtering is
        // cheap because it only scans the already-loaded in-memory list.
        db.etStyleSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (dialog.isShowing && !showingPresets) filterStylesInDialog(db, allStyles)
            }
        })

        db.btnAddFolder.setOnClickListener {
            val newFolder = db.etNewFolder.text.toString().trim()
            if (newFolder.isNotEmpty()) {
                val normalizedFolder = StyleManager.normalizeFolderName(newFolder)
                StyleManager.addFolder(this, normalizedFolder)
                if (cachedFolders.none { it.equals(normalizedFolder, ignoreCase = true) }) {
                    cachedFolders = cachedFolders + normalizedFolder
                }
                db.etNewFolder.setText("")
                refreshFolderSpinners()
                Toast.makeText(this, "Folder '$newFolder' ditambahkan", Toast.LENGTH_SHORT).show()
            }
        }

        db.btnSaveStyle.setOnClickListener {
            val name = db.etStyleName.text?.toString().orEmpty().trim()
            if (name.isBlank()) {
                Toast.makeText(this, "Masukkan nama style", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val active = binding.canvasView.textElements.firstOrNull {
                it.id == binding.canvasView.activeTextId
            }
            if (active == null) {
                Toast.makeText(this, "Pilih elemen teks yang akan disimpan sebagai style", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val folder = db.spinnerStyleFolder.selectedItem?.toString().orEmpty().ifBlank { "Default" }
            val style = com.vasiliastyper.model.TextStyle(
                name = name,
                folder = folder,
                previewText = active.text.trim().ifBlank { "Ag" }.take(18),
                fontSize = active.fontSize,
                fontName = active.fontName,
                color = active.color,
                opacity = active.opacity,
                isBold = active.isBold,
                isItalic = active.isItalic,
                effect = active.effect.name,
                outlineWidth = active.outlineWidth,
                outlineOpacity = active.outlineOpacity,
                outlineColor = active.outlineColor,
                shadowDx = active.shadowDx,
                shadowDy = active.shadowDy,
                shadowRadius = active.shadowRadius,
                shadowSpread = active.shadowSpread,
                shadowColor = active.shadowColor,
                shadowOpacity = active.shadowOpacity,
                gradientStartColor = active.gradientStartColor,
                gradientEndColor = active.gradientEndColor,
                gradientColors = active.gradientColors.toMutableList(),
                gradientAngle = active.gradientAngle,
                align = active.align.name,
                tracking = active.tracking,
                leading = active.leading,
                justify = active.justify,
                enableOutline = active.enableOutline,
                enableShadow = active.enableShadow,
                enableGradient = active.enableGradient,
                enableTexture = active.enableTexture,
                enableOutlineGradient = active.enableOutlineGradient,
                outlineGradStartColor = active.outlineGradStartColor,
                outlineGradEndColor = active.outlineGradEndColor
            )
            if (StyleManager.addStyle(this, style)) {
                allStyles = StyleManager.loadStyles(this)
                cachedFolders = StyleManager.folders(this, allStyles)
                refreshFolderSpinners()
                filterStylesInDialog(db, allStyles)
                db.etStyleName.text?.clear()
                Toast.makeText(this, "Style '$name' disimpan", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Style gagal disimpan; coba kosongkan ruang penyimpanan", Toast.LENGTH_LONG).show()
            }
        }

        // Post once so the bottom sheet receives its first frame before any JSON work.
        db.root.post {
            if (!dialog.isShowing || isFinishing || isDestroyed) return@post
            initialLoadJob = lifecycleScope.launch {
                val loadResult = runCatching {
                    withContext(Dispatchers.IO) {
                        WebtoonSfxPresets.separateLegacyPresets(applicationContext)
                        val loadedStyles = StyleManager.loadStyles(applicationContext)
                        loadedStyles to StyleManager.folders(applicationContext, loadedStyles)
                    }
                }
                if (!dialog.isShowing) return@launch
                loadResult.onSuccess { (loaded, folders) ->
                    allStyles = loaded
                    cachedFolders = folders
                    db.btnSaveStyle.isEnabled = true
                    db.btnAddFolder.isEnabled = true
                    if (!showingPresets) {
                        refreshFolderSpinners()
                        db.tvStyleCount.text = "${allStyles.size} style"
                        filterStylesInDialog(db, allStyles)
                    }
                }.onFailure { error ->
                    if (error is CancellationException) return@onFailure
                    StyleManager.invalidateCache()
                    Log.e("VasiliasTyper", "Daftar style gagal dibaca di background", error)
                    db.btnSaveStyle.isEnabled = true
                    db.btnAddFolder.isEnabled = true
                    db.tvStyleCount.text = "0 style"
                    // Show the composed empty state instead of a blank, broken-looking list.
                    db.tvStyleEmpty.visibility = View.VISIBLE
                    db.stylesList.visibility = View.GONE
                    Toast.makeText(
                        this@MainActivity,
                        "Data style bermasalah; manager tetap aman dalam keadaan kosong",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    /**
     * Wires the reusable multi-colour gradient editor. The first and last colours
     * are shown in the fixed patches; any intermediate stops are rendered as rows
     * that can be recoloured or removed. At least two stops are always retained.
     */
    private fun bindGradientControls(
        db: DialogTextEditorBinding,
        colors: MutableList<Int>,
        initialAngle: Float,
        onAngleChanged: (Float) -> Unit
    ): () -> Unit {
        if (colors.size < 2) {
            colors.clear()
            colors.add(Color.WHITE)
            colors.add(Color.GRAY)
        }

        fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
        lateinit var refresh: () -> Unit
        refresh = {
            db.gradientStartPatch.setBackgroundColor(colors.first())
            db.gradientEndPatch.setBackgroundColor(colors.last())
            db.gradientStopsContainer.removeAllViews()
            colors.indices.drop(1).dropLast(1).forEach { colorIndex ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                val label = TextView(this).apply {
                    text = "Stop $colorIndex:"
                    setTextColor(Color.parseColor("#BBBBBB"))
                    textSize = 11f
                    layoutParams = LinearLayout.LayoutParams(dp(58), LinearLayout.LayoutParams.WRAP_CONTENT)
                }
                val patch = View(this).apply {
                    setBackgroundColor(colors[colorIndex])
                    contentDescription = "Gradient colour stop $colorIndex"
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
                    setOnClickListener {
                        showColorPicker("Gradient Stop $colorIndex") { selected ->
                            colors[colorIndex] = selected
                            setBackgroundColor(selected)
                        }
                    }
                }
                val remove = Button(this).apply {
                    text = "Remove"
                    isAllCaps = false
                    textSize = 10f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)
                    ).apply { marginStart = dp(8) }
                    setOnClickListener {
                        if (colors.size > 2) {
                            colors.removeAt(colorIndex)
                            refresh()
                        }
                    }
                }
                row.addView(label)
                row.addView(patch)
                row.addView(remove)
                db.gradientStopsContainer.addView(row)
            }
        }

        db.gradientStartPatch.setOnClickListener {
            showColorPicker("Gradient Start") { selected -> colors[0] = selected; refresh() }
        }
        db.btnEyedropGradStart.setOnClickListener {
            startTextEditorEyedropper { selected -> colors[0] = selected; refresh() }
        }
        db.gradientEndPatch.setOnClickListener {
            showColorPicker("Gradient End") { selected -> colors[colors.lastIndex] = selected; refresh() }
        }
        db.btnEyedropGradEnd.setOnClickListener {
            startTextEditorEyedropper { selected -> colors[colors.lastIndex] = selected; refresh() }
        }
        db.btnAddGradientStop.setOnClickListener {
            if (colors.size >= 12) {
                Toast.makeText(this, "Maximum 12 gradient colours", Toast.LENGTH_SHORT).show()
            } else {
                val insertAt = colors.lastIndex
                val previous = colors[(insertAt - 1).coerceAtLeast(0)]
                val next = colors[insertAt]
                colors.add(insertAt, Color.rgb(
                    (Color.red(previous) + Color.red(next)) / 2,
                    (Color.green(previous) + Color.green(next)) / 2,
                    (Color.blue(previous) + Color.blue(next)) / 2
                ))
                refresh()
            }
        }
        val angle = initialAngle.roundToInt().mod(360)
        db.seekGradientAngle.progress = angle
        db.tvGradientAngle.text = "$angle°"
        onAngleChanged(angle.toFloat())
        db.seekGradientAngle.setOnSeekBarChangeListener(seekListener { progress ->
            val normalized = if (progress == 360) 0 else progress
            db.tvGradientAngle.text = "$normalized°"
            onAngleChanged(normalized.toFloat())
        })
        refresh()
        return refresh
    }

    /**
     * Apply all fields of a saved TextStyle to the open text-editor dialog controls.
     * The setters update mutable state held by the dialog that owns the controls.
     */
    private fun applyStyleToDialog(
        style: com.vasiliastyper.model.TextStyle,
        db: DialogTextEditorBinding,
        fonts: List<FontItem>,
        setTextColor:    (Int) -> Unit,
        setOutlineColor: (Int) -> Unit,
        setShadowColor:  (Int) -> Unit,
        setGradStart:    (Int) -> Unit,
        setGradEnd:      (Int) -> Unit,
        setGradientColors: (List<Int>) -> Unit,
        setGradientAngle:  (Float) -> Unit,
        setOpacity:        (Int) -> Unit,
        setOutlineGradient: (Boolean, Int, Int) -> Unit,
        setShadowSpread:   (Float) -> Unit,
        setAlign:        (TextAlign) -> Unit,
        setJustify:      (Boolean) -> Unit
    ) {
        // Style Manager and bundled presets provide a detached, sanitized snapshot.
        // Every SeekBar assignment below is additionally clamped to the actual widget.
        // Font
        val fontIdx = fonts.indexOfFirst { it.displayName.equals(style.fontName, ignoreCase = true) }
        if (fontIdx >= 0) {
            db.spinnerFont.setSelection(fontIdx)
            db.tvTextPreview.typeface = fonts[fontIdx].typeface
        }
        // Size
        db.seekFontSize.progress = (style.fontSize - 8).toInt().coerceIn(0, db.seekFontSize.max)
        db.tvFontSize.text = style.fontSize.toInt().toString()
        db.tvTextPreview.textSize = style.fontSize
        // Bold / Italic
        db.cbBold.isChecked   = style.isBold
        db.cbItalic.isChecked = style.isItalic
        // Colors
        setTextColor(style.color)
        db.textColorPatch.setBackgroundColor(style.color)
        db.tvTextPreview.setTextColor(style.color)
        val safeOpacity = style.opacity.coerceIn(0, 100)
        setOpacity(safeOpacity)
        db.seekTextOpacity.progress = safeOpacity
        db.tvTextOpacity.text = "$safeOpacity%"
        db.tvTextPreview.alpha = safeOpacity / 100f
        setOutlineColor(style.outlineColor)
        db.outlineColorPatch.setBackgroundColor(style.outlineColor)
        setShadowColor(style.shadowColor)
        db.shadowColorPatch.setBackgroundColor(style.shadowColor)
        setGradStart(style.gradientStartColor)
        db.gradientStartPatch.setBackgroundColor(style.gradientStartColor)
        setGradEnd(style.gradientEndColor)
        db.gradientEndPatch.setBackgroundColor(style.gradientEndColor)
        val savedColors = style.gradientColors.takeIf { it.size >= 2 }
            ?: listOf(style.gradientStartColor, style.gradientEndColor)
        setGradientColors(savedColors)
        setGradientAngle(style.gradientAngle)
        db.seekGradientAngle.progress = style.gradientAngle.roundToInt().mod(360).coerceIn(0, db.seekGradientAngle.max)
        db.tvGradientAngle.text = "${db.seekGradientAngle.progress}°"
        // Effect
        val effectIdx = listOf("NONE","OUTLINE","SHADOW","OUTLINE_SHADOW","GRADIENT","TEXTURE","WARP")
            .indexOf(style.effect).coerceAtLeast(0)
        db.spinnerTextEffect.setSelection(effectIdx)
        // Outline
        db.seekOutlineWidth.progress   = style.outlineWidth.toInt().coerceIn(0, db.seekOutlineWidth.max)
        db.seekOutlineOpacity.progress = style.outlineOpacity.coerceIn(0, 100)
        db.tvOutlineWidth.text  = style.outlineWidth.toInt().toString()
        db.tvOutlineOpacity.text = "${style.outlineOpacity}%"
        setOutlineGradient(
            style.enableOutlineGradient,
            style.outlineGradStartColor,
            style.outlineGradEndColor
        )
        db.cbOutlineGradient.isChecked = style.enableOutlineGradient
        db.outlineGradientStartPatch.setCardBackgroundColor(style.outlineGradStartColor)
        db.outlineGradientEndPatch.setCardBackgroundColor(style.outlineGradEndColor)
        // Shadow
        db.seekShadowDx.progress     = (style.shadowDx + 24).toInt().coerceIn(0, db.seekShadowDx.max)
        db.seekShadowDy.progress     = (style.shadowDy + 24).toInt().coerceIn(0, db.seekShadowDy.max)
        db.seekShadowRadius.progress = style.shadowRadius.toInt().coerceIn(0, db.seekShadowRadius.max)
        val safeSpread = style.shadowSpread.coerceIn(0f, 128f)
        setShadowSpread(safeSpread)
        db.seekShadowSpread.progress = safeSpread.roundToInt().coerceIn(0, db.seekShadowSpread.max)
        db.tvShadowSpread.text = "Ketebalan ${db.seekShadowSpread.progress} px"
        db.seekShadowOpacity.progress = style.shadowOpacity.coerceIn(0, 100)
        db.tvShadowDx.text     = style.shadowDx.toInt().toString()
        db.tvShadowDy.text     = style.shadowDy.toInt().toString()
        db.tvShadowRadius.text = style.shadowRadius.toInt().toString()
        db.tvShadowOpacity.text = style.shadowOpacity.toString()
        // Alignment
        val al = runCatching { TextAlign.valueOf(style.align) }.getOrDefault(TextAlign.CENTER)
        setAlign(al)
        db.tvTextPreview.gravity = when (al) {
            TextAlign.LEFT  -> android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            TextAlign.RIGHT -> android.view.Gravity.END   or android.view.Gravity.CENTER_VERTICAL
            else            -> android.view.Gravity.CENTER
        }
        // Leading & Justify (warp text layout) — range -50%..270% (progress 0..320).
        db.seekLeading.progress = (style.leading + 50).toInt().coerceIn(0, db.seekLeading.max)
        db.tvLeading.text = "${style.leading.toInt()}%"
        // The SeekBar maps 0..100 to tracking -50..50, so zero is progress 50.
        // Keep this mapping identical to the live editor initialization.
        db.seekTracking.progress = (style.tracking + 50f).roundToInt().coerceIn(0, db.seekTracking.max)
        db.tvTracking.text = style.tracking.roundToInt().toString()
        db.tvTextPreview.letterSpacing = style.tracking / 100f
        val pathModes = listOf("NONE", "CURVE_UP", "CURVE_DOWN", "WAVE", "ARCH", "VALLEY")
        db.spinnerTextPathMode.setSelection(pathModes.indexOf(style.textPathMode.uppercase()).coerceAtLeast(0))
        db.seekTextPathAmount.progress = (style.textPathAmount.coerceIn(-100f, 100f) + 100f)
            .roundToInt().coerceIn(0, db.seekTextPathAmount.max)
        db.seekTextPathCycles.progress = ((style.textPathCycles.coerceIn(0.5f, 5f) - 0.5f) * 10f)
            .roundToInt().coerceIn(0, db.seekTextPathCycles.max)
        val justifyOn = style.justify
        setJustify(justifyOn)
        db.btnJustify.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (justifyOn) android.graphics.Color.parseColor("#4D9BF0")
            else android.graphics.Color.parseColor("#3A3A3A")
        )
        db.btnJustify.setTextColor(
            if (justifyOn) android.graphics.Color.WHITE
            else android.graphics.Color.parseColor("#BBBBBB")
        )
        // v5.4 — stackable effects checkboxes
        db.cbExtraOutline.isChecked = style.enableOutline ||
            style.effect == "OUTLINE" || style.effect == "OUTLINE_SHADOW"
        db.cbExtraShadow.isChecked = style.enableShadow ||
            style.effect == "SHADOW" || style.effect == "OUTLINE_SHADOW"
        db.cbExtraGradient.isChecked = style.enableGradient || style.effect == "GRADIENT"
        db.cbExtraTexture.isChecked = style.enableTexture || style.effect == "TEXTURE"
        db.cbEnableOutlineEffect.isChecked = db.cbExtraOutline.isChecked
        db.cbEnableShadowEffect.isChecked = db.cbExtraShadow.isChecked
        db.cbEnableGradientEffect.isChecked = db.cbExtraGradient.isChecked
        db.cbEnableTextureEffect.isChecked = db.cbExtraTexture.isChecked
        db.cbEnableWarpEffect.isChecked = style.effect == "WARP"
    }

    /**
     * v11.3 — fully hardened filter.
     *
     * Crash sources removed:
     *  - spinnerFilterFolder.selectedItem was read before the adapter was attached,
     *    which returned null and (combined with the unguarded adapter cast) crashed
     *    the dialog on cold open. Both reads are now null-safe.
     *  - updateList() was fired while the RecyclerView adapter was swapped to the
     *    preset adapter (or null), so a style refresh could tear down the preset
     *    list mid-render. We now only touch the adapter when it is a StyleAdapter.
     *  - Adds a text query filter and a composed empty state instead of a blank list.
     */
    private fun filterStylesInDialog(
        db: DialogStyleManagerBinding,
        allStyles: MutableList<com.vasiliastyper.model.TextStyle>
    ) {
        val adapter = db.stylesList.adapter as? StyleAdapter ?: return
        val selectedFolder = db.spinnerFilterFolder.selectedItem?.toString() ?: "All"
        val query = db.etStyleSearch.text?.toString()?.trim().orEmpty()

        var filtered: List<com.vasiliastyper.model.TextStyle> = allStyles
        if (selectedFolder != "All") {
            filtered = filtered.filter { it.folder.equals(selectedFolder, ignoreCase = true) }
        }
        if (query.isNotEmpty()) {
            val q = query.lowercase()
            filtered = filtered.filter {
                it.name.lowercase().contains(q) ||
                    it.previewText.lowercase().contains(q) ||
                    it.presetCategory.lowercase().contains(q)
            }
        }
        val result = filtered.toMutableList()
        adapter.updateList(result)

        // Composed empty state: a designed "nothing here" view instead of a blank panel.
        val isEmpty = result.isEmpty()
        db.tvStyleEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        db.stylesList.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FONT BANK
    // ══════════════════════════════════════════════════════════════════════════

    private fun defaultFontList() = listOf(
        FontItem("Default",          android.graphics.Typeface.DEFAULT, folder = "System"),
        FontItem("Serif",            android.graphics.Typeface.SERIF, folder = "System"),
        FontItem("Monospace",        android.graphics.Typeface.MONOSPACE, folder = "System"),
        FontItem("Sans-Serif",       android.graphics.Typeface.SANS_SERIF, folder = "System"),
        FontItem("CC Bold",          android.graphics.Typeface.DEFAULT_BOLD, isComic = true, folder = "Comic"),
        FontItem("Wild Words",       android.graphics.Typeface.create("cursive",              android.graphics.Typeface.BOLD),   isComic = true, folder = "Comic"),
        FontItem("Bangers Style",    android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD),   isComic = true, folder = "Comic"),
        FontItem("Impact Style",     android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL), isComic = true, folder = "Comic"),
        FontItem("Manga Temple",     android.graphics.Typeface.MONOSPACE, isComic = true, folder = "Comic")
    )

    @Synchronized
    private fun buildFontList(): List<FontItem> {
        cachedFontList?.let { return it }
        // Use one process-wide bank. MainActivity and FontResolver previously built
        // separate Typeface collections, doubling native font memory precisely while
        // Style/Preset previews were also resolving fonts.
        return FontResolver.buildMergedBank(applicationContext).also { cachedFontList = it }
    }

    /** v6.2: Call when fonts are imported/changed to refresh the cache */
    private fun invalidateFontCache() {
        cachedFontList = null
        FontResolver.invalidateCache()
    }

    /**
     * Recursively scans both conventional asset roots: assets/fonts/ and
     * assets/font/. Fonts may live at any nesting depth, for example:
     * assets/font/Comics/Bold/MyFont.ttf. TTF, OTF, and TTC are supported.
     */
    private fun loadAssetFonts(): List<FontItem> {
        val result = mutableListOf<FontItem>()
        val seenAssetPaths = mutableSetOf<String>()

        fun scan(root: String, relativeDir: String = "") {
            val assetDir = listOf(root, relativeDir).filter(String::isNotBlank).joinToString("/")
            val entries = runCatching { assets.list(assetDir)?.sorted().orEmpty() }
                .getOrDefault(emptyList())
            for (entry in entries) {
                val relativePath = if (relativeDir.isBlank()) entry else "$relativeDir/$entry"
                val fullPath = "$root/$relativePath"
                val extension = entry.substringAfterLast('.', "").lowercase()
                if (extension in setOf("ttf", "otf", "ttc")) {
                    if (!seenAssetPaths.add(fullPath)) continue
                    runCatching {
                        FontItem(
                            displayName = entry.substringBeforeLast('.'),
                            typeface = android.graphics.Typeface.createFromAsset(assets, fullPath),
                            isComic = true,
                            fileName = fullPath,
                            folder = relativeDir.ifBlank {
                                if (root == "font") "Font" else "Uncategorized"
                            }
                        )
                    }.onSuccess(result::add)
                } else {
                    val children = runCatching { assets.list(fullPath).orEmpty() }
                        .getOrDefault(emptyArray())
                    if (children.isNotEmpty()) scan(root, relativePath)
                }
            }
        }

        scan("fonts")
        scan("font")
        return result
    }

    private fun showFontBankDialog(onSelected: (FontItem) -> Unit) {
        // v11.2 fix: buildFontList() moved off the Main dispatcher. On a cold cache
        // this used to freeze the UI for the entire scan+decode before the dialog
        // had a chance to appear. The dialog now opens once the font list (cached
        // or freshly scanned) is ready.
        lifecycleScope.launch {
            val fonts = withContext(Dispatchers.Default) { buildFontList() }
            if (isFinishing || isDestroyed) return@launch
            showFontBankDialogWithFonts(fonts, onSelected)
        }
    }

    private fun showFontBankDialogWithFonts(fonts: List<FontItem>, onSelected: (FontItem) -> Unit) {
        val view     = layoutInflater.inflate(R.layout.dialog_font_bank, null)
        val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.fontList)
        val search   = view.findViewById<EditText>(R.id.etFontSearch)
        val folderSpinner = view.findViewById<Spinner>(R.id.spinnerFontFolder)

        // Font Match uses an image crop/reference; font downloads open the curated Drive bank.
        val btnFontMatch = view.findViewById<Button>(R.id.btnFontMatch)
        val btnFontDrive = view.findViewById<Button>(R.id.btnFontDrive)
        val btnImport = view.findViewById<android.widget.Button?>(R.id.btnImportFont)

        val adapter = FontAdapter(fonts) { item ->
            onSelected(item)
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter       = adapter

        val folders = listOf(FontAdapter.ALL_FOLDERS) + fonts.map(FontItem::folder).distinct().sorted()
        folderSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            folders
        )
        folderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                adapter.filterFolder(folders.getOrElse(position) { FontAdapter.ALL_FOLDERS })
            }
        }

        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) { adapter.filter(s.toString()) }
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
        })

        val dialog = AlertDialog.Builder(this).setTitle("Font Bank")
            .setView(view)
            .setNeutralButton("Import Fonts…") { _, _ ->
                fontPickerLauncher.launch(
                    arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype", "application/octet-stream")
                )
            }
            .create()

        btnImport?.setOnClickListener {
            dialog.dismiss()
            fontPickerLauncher.launch(
                arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype", "application/octet-stream")
            )
        }
        btnFontMatch.setOnClickListener {
            pendingFontMatchApply = onSelected
            dialog.dismiss()
            fontMatchImagePickerLauncher.launch("image/*")
        }
        btnFontDrive.setOnClickListener {
            val driveUri = Uri.parse("https://drive.google.com/drive/u/0/mobile/folders/1hPV4o8fmxY2Ab9tXi84l0vVOUQEgFIbU?usp=sharing")
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, driveUri)) }
                .onFailure { Toast.makeText(this, "Browser/Google Drive tidak tersedia", Toast.LENGTH_LONG).show() }
        }

        dialog.show()
    }

    private fun runFontMatch(reference: Bitmap, onSelected: (FontItem) -> Unit) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Local Font Match")
            .setMessage("Menganalisis font, warna, gradasi, outline, shadow, dan shape secara offline…")
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            // v11.2 fix: buildFontList() moved off the Main dispatcher — it used to
            // run before the progress dialog even appeared, so a cold cache froze
            // the UI right when the user picked a reference image.
            val bank = withContext(Dispatchers.Default) { buildFontList() }
            val analysis = try {
                LocalFontMatcher.analyze(reference, bank)
            } finally {
                if (!reference.isRecycled) reference.recycle()
            }
            progress.dismiss()
            showFontMatchResult(analysis, onSelected)
        }
    }

    private fun showFontMatchResult(
        analysis: LocalFontMatcher.Analysis,
        onSelected: (FontItem) -> Unit
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 18, 28, 12)
        }
        val summary = TextView(this).apply {
            val fill = if (analysis.fillMode == "GRADIENT") "Gradasi" else "Solid"
            text = "${analysis.source}\n" +
                "Fill: $fill ${colorHex(analysis.fillStartColor)} → ${colorHex(analysis.fillEndColor)}\n" +
                "Outline: ${if (analysis.outlineEnabled) "Aktif ${colorHex(analysis.outlineColor)} / ${analysis.outlineWidth.toInt()}px" else "Nonaktif"}\n" +
                "Shadow: ${if (analysis.shadowEnabled) "Aktif ${colorHex(analysis.shadowColor)}" else "Nonaktif"}\n" +
                "Shape: ${analysis.textShape}\n${analysis.note}"
            setTextColor(Color.parseColor("#DDDDDD"))
            textSize = 12f
            setPadding(8, 0, 8, 12)
        }
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
        }
        var resultDialog: AlertDialog? = null
        recycler.adapter = FontAdapter(analysis.rankedFonts) { font ->
            onSelected(font)
            applyMatchedTextAppearance(font, analysis)
            resultDialog?.dismiss()
        }
        container.addView(summary)
        container.addView(recycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            620
        ))
        resultDialog = AlertDialog.Builder(this)
            .setTitle("Kandidat Font Match")
            .setView(container)
            .setNeutralButton("Buka Font Drive") { _, _ ->
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                    "https://drive.google.com/drive/u/0/mobile/folders/1hPV4o8fmxY2Ab9tXi84l0vVOUQEgFIbU?usp=sharing"
                )))
            }
            .setNegativeButton("Tutup", null)
            .create()
        resultDialog.show()
    }

    private fun applyMatchedTextAppearance(font: FontItem, analysis: LocalFontMatcher.Analysis) {
        val active = binding.canvasView.textElements.firstOrNull {
            it.id == binding.canvasView.activeTextId
        } ?: return
        binding.canvasView.pushTextHistory()
        active.fontName = font.displayName
        active.typeface = font.typeface
        active.color = analysis.fillStartColor
        active.gradientStartColor = analysis.fillStartColor
        active.gradientEndColor = analysis.fillEndColor
        active.effect = if (analysis.fillMode == "GRADIENT") TextEffect.GRADIENT else TextEffect.NONE
        active.enableOutline = analysis.outlineEnabled
        active.outlineColor = analysis.outlineColor
        active.outlineWidth = analysis.outlineWidth
        active.enableShadow = analysis.shadowEnabled
        active.shadowColor = analysis.shadowColor
        active.shadowDx = analysis.shadowDx
        active.shadowDy = analysis.shadowDy
        active.shadowRadius = analysis.shadowRadius
        if (analysis.textShape == "WAVE" || analysis.textShape == "ARC") {
            active.effect = TextEffect.WARP
        }
        binding.canvasView.invalidate()
        triggerAutoSave()
    }

    private fun colorHex(color: Int): String = String.format(
        Locale.US,
        "#%02X%02X%02X",
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun showFontAnalyzerDialog(sampleText: String, onSelected: (FontItem) -> Unit) {
        val assetFonts = loadAssetFonts()
        if (assetFonts.isEmpty()) {
            Toast.makeText(this, "Tidak ada font di assets/fonts atau assets/font", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 16)
        }

        val info = TextView(this).apply {
            text = "Font Analyzer menampilkan kandidat font assets yang paling cocok.\n" +
                   "Pilih font untuk menerapkannya ke teks saat ini."
            setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
            textSize = 12f
        }

        val sampleInput = EditText(this).apply {
            setText(sampleText.ifBlank { "Sample text" })
            hint = "Sample text"
            setTextColor(android.graphics.Color.parseColor("#EEEEEE"))
            setHintTextColor(android.graphics.Color.parseColor("#777777"))
            setPadding(20, 16, 20, 16)
        }

        val title = TextView(this).apply {
            text = "Hasil diurutkan dari kandidat paling mirip"
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            textSize = 11f
            setPadding(0, 10, 0, 8)
        }

        val recycler = androidx.recyclerview.widget.RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }

        fun refresh(query: String) {
            val ranked = assetFonts.sortedByDescending { fontAnalyzerScore(it, query) }
            recycler.adapter = FontAdapter(ranked) { item ->
                onSelected(item)
            }
        }

        refresh(sampleInput.text.toString())
        sampleInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) { refresh(s.toString()) }
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
        })

        dialogView.addView(info)
        dialogView.addView(sampleInput)
        dialogView.addView(title)
        dialogView.addView(recycler, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            720
        ))

        AlertDialog.Builder(this)
            .setTitle("Font Analyzer")
            .setView(dialogView)
            .setNegativeButton("Tutup", null)
            .show()
    }

    private fun fontAnalyzerScore(font: FontItem, sample: String): Int {
        val name = font.displayName.lowercase()
        val text = sample.trim()
        var score = 0

        if (text.isBlank()) return if (name.contains("default")) 1 else 0
        val len = text.length

        if (len <= 12 && (name.contains("bold") || name.contains("impact") || name.contains("bangers") || name.contains("comic"))) score += 40
        if (len >= 18 && (name.contains("condensed") || name.contains("narrow") || name.contains("manga") || name.contains("temple"))) score += 35
        if (text.any { it.isUpperCase() } && (name.contains("bold") || name.contains("impact") || name.contains("sans") || name.contains("condensed"))) score += 20
        if (text.any { it.isLowerCase() } && name.contains("serif")) score += 8
        if (text.any { it.isDigit() } && (name.contains("mono") || name.contains("sans"))) score += 8
        if (text.any { "!?,.;:".contains(it) } && (name.contains("comic") || name.contains("wild") || name.contains("bangers"))) score += 10
        if (name.contains("default")) score += 4
        if (name.contains("serif")) score += 3
        if (name.contains("monospace") || name.contains("mono")) score += 2
        if (font.isComic) score += 1

        return score
    }

    // ══════════════════════════════════════════════════════════════════════════
    // NEW WORKSPACE
    // ══════════════════════════════════════════════════════════════════════════

    private fun showNewWorkspaceDialog() {
        AlertDialog.Builder(this)
            .setTitle("New Workspace")
            .setItems(arrayOf(
                "Manga Page (800×1200)",
                "HD Page (1080×1520)",
                "Square (1000×1000)",
                "Custom…"
            )) { _, which ->
                when (which) {
                    0 -> createAndOpenWorkspace("Untitled", 800,  1200)
                    1 -> createAndOpenWorkspace("Untitled", 1080, 1520)
                    2 -> createAndOpenWorkspace("Untitled", 1000, 1000)
                    3 -> showCustomSizeDialog()
                }
            }.show()
    }

    private fun showCustomSizeDialog() {
        val layout  = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48,32,48,0) }
        val etName  = EditText(this).apply { hint = "Name"; setText("Untitled") }
        val etW     = EditText(this).apply { hint = "Width px"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("800") }
        val etH     = EditText(this).apply { hint = "Height px"; inputType = android.text.InputType.TYPE_CLASS_NUMBER; setText("1200") }
        layout.addView(etName); layout.addView(etW); layout.addView(etH)
        AlertDialog.Builder(this).setTitle("Custom Size").setView(layout)
            .setPositiveButton("Create") { _, _ ->
                val name = etName.text.toString().ifBlank { "Untitled" }
                val w    = etW.text.toString().toIntOrNull()?.coerceIn(100, 4000) ?: 800
                val h    = etH.text.toString().toIntOrNull()?.coerceIn(100, 4000) ?: 1200
                createAndOpenWorkspace(name, w, h)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun createAndOpenWorkspace(name: String, w: Int, h: Int) {
        val ws = vm.createWorkspace(name, w, h)
        addTab(ws)
        bindWorkspace()
        highlightTab(ws.id)
        saveToHistory(ws, "blank")
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FILTERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun applyGrayscale() {
        val ws = vm.activeWorkspace ?: return
        val bm = ws.layers.getOrNull(ws.activeLayerIndex)?.bitmap ?: return
        vm.pushHistory(ws)
        val px = IntArray(bm.width * bm.height)
        bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        for (i in px.indices) {
            val p = px[i]
            val g = (Color.red(p)*0.3 + Color.green(p)*0.59 + Color.blue(p)*0.11).toInt()
            px[i] = Color.argb(Color.alpha(p), g, g, g)
        }
        bm.setPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        binding.canvasView.invalidate()
    }

    private fun applyInvert() {
        val ws = vm.activeWorkspace ?: return
        val bm = ws.layers.getOrNull(ws.activeLayerIndex)?.bitmap ?: return
        vm.pushHistory(ws)
        val px = IntArray(bm.width * bm.height)
        bm.getPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        for (i in px.indices) {
            val p = px[i]
            px[i] = Color.argb(Color.alpha(p), 255-Color.red(p), 255-Color.green(p), 255-Color.blue(p))
        }
        bm.setPixels(px, 0, bm.width, 0, 0, bm.width, bm.height)
        binding.canvasView.invalidate()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MISC HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private fun flattenImage() {
        val ws        = vm.activeWorkspace ?: return
        val composite = LayerCompositor.composite(ws.layers, ws.width, ws.height)
        ws.layers.forEach { it.bitmap.recycle() }
        ws.layers.clear()
        ws.layers.add(Layer(name = "Flattened", bitmap = composite))
        ws.activeLayerIndex = 0
        bindWorkspace()
        updateStatus("Flattened")
    }

    private fun selectAll() {
        val ws = vm.activeWorkspace ?: return
        binding.canvasView.selection.apply {
            type     = SelectionType.RECT
            rect.set(0f, 0f, ws.width.toFloat(), ws.height.toFloat())
            isActive = true
        }
        binding.canvasView.onSelectionChanged?.invoke(binding.canvasView.selection)
        binding.canvasView.invalidate()
        refreshCenterInBubbleBtn()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXPORT — PNG / JPG / WebP
    // ══════════════════════════════════════════════════════════════════════════

    private fun loadUserExportPresets(): MutableList<ExportPreset> {
        val raw = getSharedPreferences("export_presets", MODE_PRIVATE)
            .getString("items", "[]") ?: "[]"
        return runCatching {
            val array = org.json.JSONArray(raw)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                ExportPreset(
                    item.optString("name", "Preset ${index + 1}"),
                    item.optString("format", "PNG"),
                    item.optInt("quality", 100).coerceIn(1, 100)
                )
            }
        }.getOrElse { mutableListOf() }
    }

    private fun saveUserExportPresets(items: List<ExportPreset>) {
        val array = org.json.JSONArray()
        items.take(30).forEach { preset ->
            array.put(org.json.JSONObject().apply {
                put("name", preset.name)
                put("format", preset.formatName)
                put("quality", preset.quality)
            })
        }
        getSharedPreferences("export_presets", MODE_PRIVATE)
            .edit().putString("items", array.toString()).apply()
    }

    @Suppress("DEPRECATION")
    private fun showExportDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_export, null)
        val rg       = view.findViewById<RadioGroup>(R.id.rgExportFormat)
        val seek     = view.findViewById<SeekBar>(R.id.seekExportQuality)
        val tvQty    = view.findViewById<TextView>(R.id.tvExportQuality)
        val tvHint   = view.findViewById<TextView>(R.id.tvExportQualityHint)
        val qtyRow   = view.findViewById<View>(R.id.qualityRow)
        val presetSpinner = view.findViewById<Spinner>(R.id.spinnerExportPreset)
        val savePresetButton = view.findViewById<Button>(R.id.btnSaveExportPreset)
        val exportAllTabs = view.findViewById<CheckBox>(R.id.cbExportAllTabs)
        val userPresets = loadUserExportPresets()
        val allPresets = mutableListOf<ExportPreset>().apply {
            addAll(builtInExportPresets)
            addAll(userPresets)
        }
        val presetNames = allPresets.map { it.name }.toMutableList()
        val presetAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            presetNames
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        presetSpinner.adapter = presetAdapter
        exportAllTabs.isEnabled = (vm.workspaces.value?.size ?: 0) > 1
        exportAllTabs.text = "Export semua ${(vm.workspaces.value?.size ?: 0)} tab sekaligus ke Pictures/VasiliasTyper"

        // Read live quality value (1–100)
        fun currentQuality(): Int = (seek.progress + 1).coerceIn(1, 100)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fu: Boolean) { tvQty.text = "${currentQuality()}%" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        tvQty.text = "${currentQuality()}%"

        // Toggle quality visibility per format — PNG & PSD are lossless
        fun updateQualityVisibility() {
            when (rg.checkedRadioButtonId) {
                R.id.rbExportPng -> {
                    qtyRow.visibility = View.GONE
                }
                R.id.rbExportPsd -> {
                    qtyRow.visibility = View.GONE
                }
                R.id.rbExportJpg -> {
                    qtyRow.visibility = View.VISIBLE
                    tvHint.text = "JPG: higher = sharper, larger file. 90% is a good default."
                }
                R.id.rbExportWebp -> {
                    qtyRow.visibility = View.VISIBLE
                    tvHint.text = "WebP: any value < 100 enables lossy mode; 100 = lossless."
                }
            }
        }
        rg.setOnCheckedChangeListener { _, _ -> updateQualityVisibility() }
        updateQualityVisibility()

        fun applyPreset(preset: ExportPreset) {
            when {
                preset.formatName.contains("JPEG") -> rg.check(R.id.rbExportJpg)
                preset.formatName.contains("WEBP") -> rg.check(R.id.rbExportWebp)
                else -> rg.check(R.id.rbExportPng)
            }
            seek.progress = (preset.quality - 1).coerceIn(0, 99)
            tvQty.text = "${preset.quality}%"
            updateQualityVisibility()
        }
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, selected: View?, position: Int, id: Long) {
                allPresets.getOrNull(position)?.let(::applyPreset)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        savePresetButton.setOnClickListener {
            val nameInput = EditText(this).apply { hint = "Nama preset" }
            AlertDialog.Builder(this)
                .setTitle("Simpan Export Preset")
                .setView(nameInput)
                .setPositiveButton("Simpan") { _, _ ->
                    val name = nameInput.text.toString().trim()
                    if (name.isNotEmpty()) {
                        val formatName = when (rg.checkedRadioButtonId) {
                            R.id.rbExportJpg -> "JPEG"
                            R.id.rbExportWebp -> if (currentQuality() >= 100) "WEBP_LOSSLESS" else "WEBP_LOSSY"
                            else -> "PNG"
                        }
                        userPresets += ExportPreset(name, formatName, currentQuality())
                        saveUserExportPresets(userPresets)
                        allPresets += userPresets.last()
                        presetNames += name
                        presetAdapter.notifyDataSetChanged()
                        presetSpinner.setSelection(allPresets.lastIndex)
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        AlertDialog.Builder(this)
              .setTitle("Export Image")
              .setView(view)
              .setNeutralButton("→ Pictures") { _, _ ->
                  // Direct save to Pictures/VasiliasTyper — no file picker needed
                  val q = currentQuality()
                  when (rg.checkedRadioButtonId) {
                      R.id.rbExportJpg -> { pendingExportFormat = Bitmap.CompressFormat.JPEG; pendingExportQuality = q }
                      R.id.rbExportWebp -> {
                          pendingExportFormat = if (q >= 100 && Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS
                                               else if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
                                               else Bitmap.CompressFormat.WEBP
                          pendingExportQuality = q
                      }
                      R.id.rbExportPsd -> { Toast.makeText(this, "PSD: gunakan Export biasa", Toast.LENGTH_SHORT).show(); return@setNeutralButton }
                      else -> { pendingExportFormat = Bitmap.CompressFormat.PNG; pendingExportQuality = 100 }
                  }
                  pendingExportAllTabs = exportAllTabs.isChecked
                  if (pendingExportAllTabs) directExportAllTabs() else directExport()
              }
              .setPositiveButton("Export…") { _, _ ->
                  val q = currentQuality()
                  val wsName = (vm.activeWorkspace?.name ?: "export").replace(Regex("[^\\w\\-. ]"), "_")
                  pendingExportAllTabs = exportAllTabs.isChecked
                  if (pendingExportAllTabs) {
                      when (rg.checkedRadioButtonId) {
                          R.id.rbExportJpg -> pendingExportFormat = Bitmap.CompressFormat.JPEG
                          R.id.rbExportWebp -> pendingExportFormat = if (q >= 100 && Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
                          R.id.rbExportPsd -> {
                              Toast.makeText(this, "Multi-tab mendukung PNG/JPG/WebP; pilih format raster", Toast.LENGTH_LONG).show()
                              return@setPositiveButton
                          }
                          else -> pendingExportFormat = Bitmap.CompressFormat.PNG
                      }
                      pendingExportQuality = if (pendingExportFormat == Bitmap.CompressFormat.PNG) 100 else q
                      directExportAllTabs()
                      return@setPositiveButton
                  }
                  when (rg.checkedRadioButtonId) {
                      R.id.rbExportPng -> {
                          pendingExportFormat  = Bitmap.CompressFormat.PNG
                          pendingExportQuality = 100
                          exportLauncher.launch("${wsName}.png")
                      }
                      R.id.rbExportJpg -> {
                          pendingExportFormat  = Bitmap.CompressFormat.JPEG
                          pendingExportQuality = q
                          exportLauncher.launch("${wsName}.jpg")
                      }
                      R.id.rbExportWebp -> {
                          pendingExportFormat = if (q >= 100 && Build.VERSION.SDK_INT >= 30)
                              Bitmap.CompressFormat.WEBP_LOSSLESS
                          else if (Build.VERSION.SDK_INT >= 30)
                              Bitmap.CompressFormat.WEBP_LOSSY
                          else
                              Bitmap.CompressFormat.WEBP
                          pendingExportQuality = q
                          exportLauncher.launch("${wsName}.webp")
                      }
                      R.id.rbExportPsd -> {
                          psdExportLauncher.launch("${vm.activeWorkspace?.name ?: "project"}.psd")
                      }
                      else -> {
                          pendingExportFormat  = Bitmap.CompressFormat.PNG
                          pendingExportQuality = 100
                          exportLauncher.launch("${wsName}.png")
                      }
                  }
              }
              .setNegativeButton("Cancel", null)
              .show()
      }

    // ── PDF Export ────────────────────────────────────────────────────────────

    /**
     * Prompts the user to choose a save location for the PDF, then calls
     * [exportCurrentPageToPdf] on an IO thread.
     *
     * Exports the **active** workspace as a single-page PDF.  All layers are
     * composited first; then every [ImageElement] and [TextElement] currently
     * in [CanvasView] is drawn on top, so the PDF matches exactly what is
     * visible on screen.
     */
    private fun launchPdfExport() {
        val ws = vm.activeWorkspace
        if (ws == null) {
            Toast.makeText(this, "Tidak ada workspace aktif", Toast.LENGTH_SHORT).show()
            return
        }
        val name = ws.name.ifBlank { "export" }.replace(Regex("[^\\w\\- ]"), "_")
        pdfExportLauncher.launch("$name.pdf")
    }

    /**
     * Renders the current workspace — pixel layers + image elements + text
     * elements — into a one-page [android.graphics.pdf.PdfDocument] and writes
     * it to [uri].  Must be called from a background (IO) thread.
     */
    private fun exportCurrentPageToPdf(uri: android.net.Uri) {
        val ws = vm.activeWorkspace ?: run {
            runOnUiThread { Toast.makeText(this, "Tidak ada workspace aktif", Toast.LENGTH_SHORT).show() }
            return
        }

        val pdfDoc = android.graphics.pdf.PdfDocument()
        try {
            // 1. Flatten all pixel layers into one bitmap
            val flat = com.vasiliastyper.engine.LayerCompositor.composite(ws.layers, ws.width, ws.height)
            val mutable = flat.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
            flat.recycle()

            // 2. Draw image elements on top (composited into the same bitmap)
            val overlayCanvas = android.graphics.Canvas(mutable)
            for (img in binding.canvasView.imageElements) {
                val bm = img.bitmap ?: continue
                overlayCanvas.save()
                overlayCanvas.translate(img.x + img.width / 2f, img.y + img.height / 2f)
                overlayCanvas.rotate(img.rotation)
                overlayCanvas.drawBitmap(
                    bm, null,
                    android.graphics.RectF(-img.width / 2f, -img.height / 2f, img.width / 2f, img.height / 2f),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                )
                overlayCanvas.restore()
            }

            // 3. Draw text elements on top
            for (el in binding.canvasView.textElements) {
                com.vasiliastyper.engine.TextRenderer.renderToCanvas(overlayCanvas, el)
            }

            // 4. Write the bitmap as one PDF page at the native canvas resolution
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                ws.width, ws.height, 1
            ).create()
            val page = pdfDoc.startPage(pageInfo)
            page.canvas.drawBitmap(mutable, 0f, 0f, null)
            pdfDoc.finishPage(page)
            mutable.recycle()

            // 5. Save to the URI chosen by the user
            contentResolver.openOutputStream(uri)?.use { out -> pdfDoc.writeTo(out) }

            runOnUiThread {
                Toast.makeText(
                    this,
                    "PDF berhasil diekspor — ${ws.width}×${ws.height} px",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Export PDF gagal: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } finally {
            pdfDoc.close()
        }
    }

    private fun pickImage() {
        // Storage Access Framework memberi akses URI langsung, tanpa izin storage global.
        // OpenMultipleDocuments mengaktifkan pemilihan banyak gambar dalam satu kali buka.
        imagePickerLauncher.launch(arrayOf("image/*"))
    }

    /** Stamp / overlay an image onto the current workspace (not a new workspace). */
    private fun pickStampImage() {
        if (vm.activeWorkspace == null) {
            Toast.makeText(this, "Buka atau buat workspace terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        stampImagePickerLauncher.launch("image/*")
    }

    private fun rememberWorkspaceProjectId(ws: Workspace, projectId: String) {
        workspaceProjectIds[ws.id] = projectId
        currentProjectId = projectId
    }

    private fun projectIdFor(ws: Workspace): String = workspaceProjectIds[ws.id] ?: ws.id

    private fun pushWorkspaceSnapshot() {
        if (restoringWorkspaceSnapshot) return
        val ws = vm.activeWorkspace ?: return
        try {
            val snap = WorkspaceSnapshot(
                workspaceJson = WorkspaceSerializer.serialize(ws).toString(),
                textJson      = WorkspaceSerializer.serializeTextElements(binding.canvasView.textElements.toList()).toString(),
                imageJson     = WorkspaceSerializer.serializeImageElements(binding.canvasView.imageElements.toList()).toString(),
                projectId     = projectIdFor(ws)
            )
            workspaceUndoStack.addLast(snap)
            while (workspaceUndoStack.size > 20) workspaceUndoStack.removeFirst()
            workspaceRedoStack.clear()
        } catch (_: Exception) {}
    }

    private fun restoreWorkspaceSnapshot(snapshot: WorkspaceSnapshot) {
        val ws = vm.activeWorkspace ?: return
        try {
            restoringWorkspaceSnapshot = true
            val restoredWs = WorkspaceSerializer.deserialize(org.json.JSONObject(snapshot.workspaceJson), this)

            // Recycle current layer bitmaps before replacing them.
            ws.layers.forEach { layer ->
                if (!layer.bitmap.isRecycled) layer.bitmap.recycle()
            }

            ws.name = restoredWs.name
            ws.width = restoredWs.width
            ws.height = restoredWs.height
            ws.activeLayerIndex = restoredWs.activeLayerIndex.coerceIn(0, restoredWs.layers.lastIndex.coerceAtLeast(0))
            ws.filePath = restoredWs.filePath
            ws.isDirty = restoredWs.isDirty

            ws.layers.clear()
            ws.layers.addAll(restoredWs.layers)

            binding.canvasView.layers = ws.layers
            binding.canvasView.activeLayerIndex = ws.activeLayerIndex
            binding.canvasView.setCanvasSize(ws.width, ws.height)

            val texts  = WorkspaceSerializer.deserializeTextElements(org.json.JSONArray(snapshot.textJson))
            val images = WorkspaceSerializer.deserializeImageElements(org.json.JSONArray(snapshot.imageJson))
            workspaceTextStates[ws.id]  = texts.map { it.copy() }.toMutableList()
            workspaceImageStates[ws.id] = images.map { it.copy() }.toMutableList()
            binding.canvasView.textElements.clear()
            binding.canvasView.textElements.addAll(texts.map { it.copy() })
            binding.canvasView.imageElements.clear()
            binding.canvasView.imageElements.addAll(images.map { it.copy() })
            binding.canvasView.activeTextId = null
            binding.canvasView.activeImageId = null
            binding.canvasView.clearSelection()
            binding.canvasView.invalidate()
            rememberWorkspaceProjectId(ws, snapshot.projectId)
            updateStatus("Undo/redo applied")
            triggerAutoSave()
        } catch (_: Exception) {
        } finally {
            restoringWorkspaceSnapshot = false
        }
    }

    private fun snapshotActiveWorkspaceElements() {
        val ws = vm.activeWorkspace ?: return
        workspaceTextStates[ws.id] = binding.canvasView.textElements.map { it.copy() }.toMutableList()
        workspaceImageStates[ws.id] = binding.canvasView.imageElements.map { it.copy() }.toMutableList()
    }

    private fun snapshotActiveOcrResults() {
        val ws = vm.activeWorkspace ?: return
        workspaceOcrStates[ws.id] = ocrResults.map {
            it.copy(regionRect = it.regionRect?.let(::RectF))
        }.toMutableList()
    }

    private fun restoreOcrResultsFor(ws: Workspace) {
        ocrResults.clear()
        ocrResults.addAll(workspaceOcrStates[ws.id]?.map { it.copy(regionRect = it.regionRect?.let(::RectF)) }?.toMutableList() ?: mutableListOf())
        ocrAdapter?.notifyDataSetChanged()
        refreshOcrOverlay()
    }

    private fun restoreWorkspaceElements(ws: Workspace) {
        val textState = workspaceTextStates[ws.id]
        val imageState = workspaceImageStates[ws.id]
        if (textState != null || imageState != null) {
            binding.canvasView.textElements.clear()
            binding.canvasView.textElements.addAll(textState?.map { it.copy() }?.toMutableList() ?: mutableListOf())
            binding.canvasView.imageElements.clear()
            binding.canvasView.imageElements.addAll(imageState?.map { it.copy() }?.toMutableList() ?: mutableListOf())
            binding.canvasView.invalidate()
            return
        }

        val pid = projectIdFor(ws)
        lifecycleScope.launch(Dispatchers.IO) {
            val restored = WorkspaceSerializer.tryRestore(this@MainActivity, pid) ?: return@launch
            FontResolver.hydrateTextElements(this@MainActivity, restored.textElements)
            withContext(Dispatchers.Main) {
                workspaceTextStates[ws.id] = restored.textElements.map { it.copy() }.toMutableList()
                workspaceImageStates[ws.id] = restored.imageElements.map { it.copy() }.toMutableList()
                if (vm.activeWorkspace?.id == ws.id) {
                    binding.canvasView.textElements.clear()
                    binding.canvasView.textElements.addAll(restored.textElements.map { it.copy() })
                    binding.canvasView.imageElements.clear()
                    binding.canvasView.imageElements.addAll(restored.imageElements.map { it.copy() })
                    binding.canvasView.invalidate()
                }
                val tCount = restored.textElements.size
                val iCount = restored.imageElements.size
                if (tCount > 0 || iCount > 0) updateStatus("Project restored — $tCount text, $iCount image elements")
            }
        }
    }

    /**
     * Return the human-readable display name of a content:// URI.
     * Falls back to parsing the last path segment.
     */
    private fun getFileDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.substringBeforeLast('.')?.ifBlank { null }
                } else null
            }
        } catch (_: Exception) { null }
            ?: uri.lastPathSegment?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.ifBlank { null }
    }

    private fun isPsdUri(uri: Uri, displayNameWithoutExtension: String? = null): Boolean {
        val mime = runCatching { contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT) }
            .getOrDefault("")
        if (mime.contains("photoshop") || mime == "image/vnd.adobe.photoshop") return true
        val rawName = runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull().orEmpty()
        return rawName.endsWith(".psd", ignoreCase = true) ||
            uri.lastPathSegment.orEmpty().endsWith(".psd", ignoreCase = true) ||
            displayNameWithoutExtension.orEmpty().endsWith(".psd", ignoreCase = true)
    }

    private fun saveToHistory(
        ws: Workspace,
        type: String,
        existingId: String? = null,
        sourceUri: String?  = null
    ) {
        val id  = existingId ?: projectIdFor(ws)
        rememberWorkspaceProjectId(ws, id)

        // Build thumbnail asynchronously so it doesn't block the main thread.
        // We snapshot the layer list here (on main thread) and do the pixel work
        // on IO so there's no race with ongoing edits.
        val layers = ws.layers.toList()
        val W = ws.width; val H = ws.height

        lifecycleScope.launch(Dispatchers.IO) {
            val thumbB64: String? = try {
                // Scale factor: composite at ≤180px on the long side for speed
                val scale = 180f / maxOf(W, H).coerceAtLeast(1)
                val tW = (W * scale).toInt().coerceAtLeast(1)
                val tH = (H * scale).toInt().coerceAtLeast(1)

                val composite = android.graphics.Bitmap.createBitmap(tW, tH, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(composite)
                for (layer in layers) {
                    if (!layer.isVisible || layer.bitmap.isRecycled) continue
                    val scaled = android.graphics.Bitmap.createScaledBitmap(layer.bitmap, tW, tH, true)
                    val paint  = android.graphics.Paint().apply {
                        alpha = (layer.opacity / 100f * 255).toInt().coerceIn(0, 255)
                    }
                    c.drawBitmap(scaled, 0f, 0f, paint)
                    if (scaled !== layer.bitmap) scaled.recycle()
                }
                val thumb = ProjectHistoryManager.buildThumb(composite, 120)
                composite.recycle()
                thumb
            } catch (_: Exception) { null }

            val currentFolder = ProjectHistoryManager.load(this@MainActivity)
                .firstOrNull { it.id == id }
                ?.folder
                ?: com.vasiliastyper.engine.DEFAULT_PROJECT_FOLDER
            val rec = ProjectRecord(
                id        = id,
                name      = ws.name,
                width     = W,
                height    = H,
                dateMs    = System.currentTimeMillis(),
                type      = type,
                sourceUri = sourceUri,
                thumbB64  = thumbB64,
                folder    = currentFolder
            )
            ProjectHistoryManager.add(this@MainActivity, rec)
        }
    }

    // Auto-save periodik diserialkan agar tidak bertabrakan dengan batch/checkpoint.
    private fun startAutoSave() {
        periodicAutoSaveJob?.cancel()
        periodicAutoSaveJob = lifecycleScope.launch {
            while (true) {
                delay(60_000L)
                if (batchAutoSaveSuspended || batchProcessorJob?.isActive == true) continue
                val ws = vm.activeWorkspace ?: continue
                snapshotActiveWorkspaceElements()
                val projId = projectIdFor(ws)
                val textEls = binding.canvasView.textElements.map { it.copy() }
                val imageEls = binding.canvasView.imageElements.map { it.copy() }
                val saved = withContext(Dispatchers.IO) {
                    runCatching {
                        autoSaveMutex.withLock {
                            WorkspaceSerializer.autoSave(
                                context = this@MainActivity,
                                ws = ws,
                                textElements = textEls,
                                imageElements = imageEls,
                                projectId = projId
                            )
                        }
                    }.isSuccess
                }
                if (saved) {
                    saveToHistory(ws, if (ws.filePath != null) "image" else "blank")
                    updateStatus("Auto-saved ✓")
                }
            }
        }
    }

    /** Save langsung dengan debounce; tidak menginterupsi batch yang sedang aktif. */
    private fun triggerAutoSave() {
        if (batchAutoSaveSuspended || batchProcessorJob?.isActive == true) return
        val ws = vm.activeWorkspace ?: return
        if (ws.id == activeBatchWorkspaceId) return
        snapshotActiveWorkspaceElements()
        val projId = projectIdFor(ws)
        val textEls = binding.canvasView.textElements.map { it.copy() }
        val imageEls = binding.canvasView.imageElements.map { it.copy() }
        autoSaveJob?.cancel()
        autoSaveJob = lifecycleScope.launch(Dispatchers.IO) {
            delay(350L)
            autoSaveMutex.withLock {
                WorkspaceSerializer.autoSave(
                    context = this@MainActivity,
                    ws = ws,
                    textElements = textEls,
                    imageElements = imageEls,
                    projectId = projId
                )
            }
        }
    }

      private fun updateStatus(msg: String) { binding.statusInfo.text = msg }

    private fun seekListener(onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) = onProgress(progress)
        override fun onStartTrackingTouch(sb: SeekBar) {}
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    /**
     * Ketuk angka (TextView nilai slider) untuk ketik manual. Dipakai semua
     * slider angka editor teks/image agar presisi tanpa geser.
     */
    private fun askNumberInput(
        title: String,
        current: Float,
        min: Float,
        max: Float,
        onSet: (Float) -> Unit
    ) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if (current % 1f == 0f) current.toInt().toString() else current.toString())
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage("Rentang $min … $max")
            .setView(input.apply { setPadding(48, 24, 48, 0) })
            .setPositiveButton("OK") { _, _ ->
                val v = input.text.toString().toFloatOrNull()
                if (v == null) {
                    Toast.makeText(this, "Angka tidak valid", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                onSet(v.coerceIn(min, max))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // v5.0 — HELPERS for PSD export, perspective, and texture
    // ══════════════════════════════════════════════════════════════════════════

    // ══ v5.1 — Floating Quick-Edit Toolbar for the selected TextElement ═════════
    //
    // When the user taps an existing text on the canvas, CanvasView fires
    // onTextSelected(el). We use that to show/hide a small toolbar (defined
    // in activity_main.xml) sitting above the canvas with one-tap quick
    // actions — no more digging through the full text editor dialog.

    private fun setupTextQuickToolbar() {
        val tb = binding.textQuickToolbar
        val qtbEdit       = tb.findViewById<Button>(R.id.qtbEdit)
        val qtbQuickStyle = tb.findViewById<Button>(R.id.qtbQuickStyle)
        val qtbColor      = tb.findViewById<Button>(R.id.qtbColor)
        val qtbSizeUp     = tb.findViewById<Button>(R.id.qtbSizeUp)
        val qtbSizeDown   = tb.findViewById<Button>(R.id.qtbSizeDown)
        val qtbBold       = tb.findViewById<Button>(R.id.qtbBold)
        val qtbItalic     = tb.findViewById<Button>(R.id.qtbItalic)
        val qtbAlign      = tb.findViewById<Button>(R.id.qtbAlign)
        val qtbRotL       = tb.findViewById<Button>(R.id.qtbRotateL)
        val qtbRotR       = tb.findViewById<Button>(R.id.qtbRotateR)
        val qtbDup        = tb.findViewById<Button>(R.id.qtbDuplicate)
        val qtbFront      = tb.findViewById<Button>(R.id.qtbBringFront)
        val qtbBack       = tb.findViewById<Button>(R.id.qtbSendBack)
        val qtbDelete     = tb.findViewById<Button>(R.id.qtbDelete)
        val qtbClose      = tb.findViewById<Button>(R.id.qtbClose)

        fun activeEl(): TextElement? {
            val id = binding.canvasView.activeTextId ?: return null
            return binding.canvasView.textElements.find { it.id == id }
        }
        fun commit() { binding.canvasView.invalidate(); triggerAutoSave() }

        qtbEdit.setOnClickListener {
            activeEl()?.let { showTextEditorDialogForEdit(it) }
        }
        qtbQuickStyle.setOnClickListener {
            val element = activeEl() ?: return@setOnClickListener
            pendingStyleLoadAction = { style ->
                applyQuickStyleToElement(element, style)
                pendingStyleLoadAction = null
            }
            showStyleManagerStandalone()
        }
        qtbColor.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            showColorPicker("Warna Teks") { c ->
                binding.canvasView.pushTextHistory()
                el.color = c; commit()
            }
        }
        qtbSizeUp.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            binding.canvasView.pushTextHistory()
            el.fontSize = (el.fontSize + 4f).coerceAtMost(300f); commit()
        }
        qtbSizeDown.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            binding.canvasView.pushTextHistory()
            el.fontSize = (el.fontSize - 4f).coerceAtLeast(8f); commit()
        }
        qtbBold.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            binding.canvasView.pushTextHistory()
            el.isBold = !el.isBold; commit()
            qtbBold.alpha = if (el.isBold) 1f else 0.55f
        }
        qtbItalic.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            binding.canvasView.pushTextHistory()
            el.isItalic = !el.isItalic; commit()
            qtbItalic.alpha = if (el.isItalic) 1f else 0.55f
        }
        qtbAlign.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            binding.canvasView.pushTextHistory()
            el.align = when (el.align) {
                TextAlign.LEFT   -> TextAlign.CENTER
                TextAlign.CENTER -> TextAlign.RIGHT
                TextAlign.RIGHT  -> TextAlign.LEFT
            }
            qtbAlign.text = when (el.align) {
                TextAlign.LEFT   -> "☰"
                TextAlign.CENTER -> "≡"
                TextAlign.RIGHT  -> "☲"
            }
            commit()
        }
        qtbRotL.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            binding.canvasView.pushTextHistory()
            el.rotation = ((el.rotation - 15f) % 360f); commit()
        }
        qtbRotR.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            binding.canvasView.pushTextHistory()
            el.rotation = ((el.rotation + 15f) % 360f); commit()
        }
        qtbDup.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            binding.canvasView.pushTextHistory()
            val copy = el.copy(
                id = java.util.UUID.randomUUID().toString(),
                x  = el.x + 20f, y = el.y + 20f,
                spans = el.spans?.map { it.copy() }?.toMutableList()
            )
            binding.canvasView.textElements.add(copy)
            binding.canvasView.syncCanvasStack()
            binding.canvasView.activeTextId = copy.id
            updateTextQuickToolbar(copy)
            commit()
        }
        qtbFront.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            val list = binding.canvasView.textElements
            binding.canvasView.pushTextHistory()
            list.remove(el); list.add(el); commit()
        }
        qtbBack.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            val list = binding.canvasView.textElements
            binding.canvasView.pushTextHistory()
            list.remove(el); list.add(0, el); commit()
        }
        qtbDelete.setOnClickListener {
            val el = activeEl() ?: return@setOnClickListener
            binding.canvasView.pushTextHistory()
            binding.canvasView.textElements.remove(el)
            binding.canvasView.activeTextId = null
            updateTextQuickToolbar(null); commit()
        }
        qtbClose.setOnClickListener {
            binding.canvasView.activeTextId = null
            updateTextQuickToolbar(null); binding.canvasView.invalidate()
        }
    }

    /**
     * Applies a saved preset directly from the floating toolbar. Geometry, text,
     * rotation, mesh and perspective remain untouched so this action is safe while
     * arranging a page; only typography and visual effects are replaced.
     */
    private fun applyQuickStyleToElement(
        element: TextElement,
        sourceStyle: com.vasiliastyper.model.TextStyle
    ) {
        val style = sourceStyle.safeCopyForApply()
        binding.canvasView.pushTextHistory()

        // v11.1 fix: buildFontList() was previously called here directly on the
        // main thread. On a cold cache it recursively scans assets/fonts(+font)
        // and every user-imported custom font, decoding each Typeface
        // synchronously — on a low-end device or with several imported fonts
        // this can block the UI thread long enough to ANR, which the user sees
        // as "freeze, then force close" right when tapping Apply. Every other
        // call site that can hit a cold cache (see Font Bank refresh above)
        // already runs this on Dispatchers.IO; this path was the one exception.
        lifecycleScope.launch {
            val resolvedTypeface = withContext(Dispatchers.Default) {
                FontResolver.resolveStyleTypeface(applicationContext, style.fontName)
            }
            if (isFinishing || isDestroyed) return@launch
            runCatching {
                element.fontName = style.fontName
                element.typeface = resolvedTypeface
                element.fontSize = style.fontSize.coerceIn(8f, 300f)
                element.color = style.color
                element.opacity = style.opacity.coerceIn(0, 100)
                element.isBold = style.isBold
                element.isItalic = style.isItalic
                element.effect = runCatching { TextEffect.valueOf(style.effect) }.getOrDefault(TextEffect.NONE)
                element.outlineColor = style.outlineColor
                // Matches the clamp StyleManager already applies when loading styles
                // from disk (0..128). The quick-style path was missing an upper
                // bound, so a stray/corrupt value could reach the renderer's
                // shadow/stroke code on the next frame — outside this try/catch —
                // and freeze or crash there instead.
                element.outlineWidth = style.outlineWidth.coerceIn(0f, 128f)
                element.outlineOpacity = style.outlineOpacity.coerceIn(0, 100)
                element.shadowDx = style.shadowDx.coerceIn(-256f, 256f)
                element.shadowDy = style.shadowDy.coerceIn(-256f, 256f)
                element.shadowRadius = style.shadowRadius.coerceIn(0f, 128f)
                element.shadowSpread = style.shadowSpread.coerceIn(0f, 128f)
                element.shadowColor = style.shadowColor
                element.shadowOpacity = style.shadowOpacity.coerceIn(0, 100)
                element.gradientStartColor = style.gradientStartColor
                element.gradientEndColor = style.gradientEndColor
                // Never hand the renderer a single-color (or empty) gradient list;
                // LinearGradient requires at least two colors.
                element.gradientColors = style.gradientColors.takeIf { it.size >= 2 }
                    ?.toMutableList()
                    ?: mutableListOf(style.gradientStartColor, style.gradientEndColor)
                element.gradientAngle = style.gradientAngle
                element.align = runCatching { TextAlign.valueOf(style.align) }.getOrDefault(TextAlign.CENTER)
                element.leading = style.leading.coerceIn(-50f, 1000f)
                element.tracking = style.tracking.coerceIn(-128f, 512f)
                element.justify = style.justify
                element.enableOutline = style.enableOutline || style.effect in setOf("OUTLINE", "OUTLINE_SHADOW")
                element.enableShadow = style.enableShadow || style.effect in setOf("SHADOW", "OUTLINE_SHADOW")
                element.enableGradient = style.enableGradient || style.effect == "GRADIENT"
                element.enableTexture = style.enableTexture || style.effect == "TEXTURE"
                element.textTransform = style.textTransform
                element.enableOutlineGradient = style.enableOutlineGradient
                element.outlineGradStartColor = style.outlineGradStartColor
                element.outlineGradEndColor = style.outlineGradEndColor
                updateTextQuickToolbar(element)
                binding.canvasView.invalidate()
                triggerAutoSave()
            }.onSuccess {
                Toast.makeText(this@MainActivity, "Quick Style '${style.name}' diterapkan", Toast.LENGTH_SHORT).show()
            }.onFailure { error ->
                Log.e("VasiliasTyper", "Quick Style '${style.name}' gagal diterapkan", error)
                Toast.makeText(this@MainActivity, "Style gagal diterapkan; elemen tetap aman", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Direct canvas actions for the selected image, without opening Layers. */
    private fun setupImageQuickToolbar() {
        fun activeImage(): ImageElement? {
            val id = binding.canvasView.activeImageId ?: return null
            return binding.canvasView.imageElements.find { it.id == id }
        }
        fun commit() {
            binding.canvasView.syncCanvasStack()
            binding.canvasView.invalidate()
            triggerAutoSave()
        }

        binding.iqtbDuplicate.setOnClickListener {
            val image = activeImage() ?: return@setOnClickListener
            binding.canvasView.pushImageHistory()
            val copy = image.copy(
                id = java.util.UUID.randomUUID().toString(),
                x = image.x + 20f,
                y = image.y + 20f
            )
            binding.canvasView.imageElements.add(copy)
            binding.canvasView.activeImageId = copy.id
            updateImageQuickToolbar(copy)
            commit()
            Toast.makeText(this, "Gambar berhasil disalin", Toast.LENGTH_SHORT).show()
        }
        binding.iqtbBringFront.setOnClickListener {
            val image = activeImage() ?: return@setOnClickListener
            binding.canvasView.pushImageHistory()
            binding.canvasView.syncCanvasStack()
            val item = binding.canvasView.canvasStack.firstOrNull {
                it.type == CanvasStackType.IMAGE && it.id == image.id
            } ?: return@setOnClickListener
            binding.canvasView.canvasStack.remove(item)
            binding.canvasView.canvasStack.add(item)
            commit()
        }
        binding.iqtbSendBack.setOnClickListener {
            val image = activeImage() ?: return@setOnClickListener
            binding.canvasView.pushImageHistory()
            binding.canvasView.syncCanvasStack()
            val item = binding.canvasView.canvasStack.firstOrNull {
                it.type == CanvasStackType.IMAGE && it.id == image.id
            } ?: return@setOnClickListener
            binding.canvasView.canvasStack.remove(item)
            val backgroundOffset = if (binding.canvasView.layers.isNotEmpty()) 1 else 0
            binding.canvasView.canvasStack.add(
                backgroundOffset.coerceAtMost(binding.canvasView.canvasStack.size),
                item
            )
            commit()
        }
        binding.iqtbDelete.setOnClickListener {
            val image = activeImage() ?: return@setOnClickListener
            binding.canvasView.pushImageHistory()
            binding.canvasView.imageElements.remove(image)
            binding.canvasView.activeImageId = null
            updateImageQuickToolbar(null)
            commit()
        }
        binding.iqtbEdit.setOnClickListener {
            val image = activeImage() ?: return@setOnClickListener
            showImageEditDialog(image) { commit() }
        }
        binding.iqtbClose.setOnClickListener {
            binding.canvasView.activeImageId = null
            updateImageQuickToolbar(null)
            binding.canvasView.invalidate()
        }
    }

    private fun updateImageQuickToolbar(image: ImageElement?) {
        if (image == null) {
            binding.imageQuickToolbar.visibility = View.GONE
            return
        }
        binding.textQuickToolbar.visibility = View.GONE
        binding.imageQuickToolbar.visibility = View.VISIBLE
    }

    /**
     * Ubah opacity + ukuran (px) image kapan saja, bukan hanya saat place.
     * Lebar/tinggi diketik manual; kunci aspek opsional.
     */
    private fun showImageEditDialog(image: ImageElement, onDone: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val opacityLabel = TextView(this).apply {
            text = "Opacity: ${image.opacity}%"
            setTextColor(Color.parseColor("#CCCCCC"))
        }
        val opacitySeek = SeekBar(this).apply {
            max = 100
            progress = image.opacity.coerceIn(0, 100)
            setOnSeekBarChangeListener(seekListener { opacityLabel.text = "Opacity: $it%" })
        }
        val lockAspect = CheckBox(this).apply {
            text = "Kunci aspek"
            isChecked = true
            setTextColor(Color.parseColor("#CCCCCC"))
        }
        val etW = EditText(this).apply {
            hint = "Lebar (px)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(image.width.roundToInt().toString())
        }
        val etH = EditText(this).apply {
            hint = "Tinggi (px)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(image.height.roundToInt().toString())
        }
        container.addView(opacityLabel)
        container.addView(opacitySeek)
        container.addView(lockAspect)
        container.addView(etW)
        container.addView(etH)
        AlertDialog.Builder(this)
            .setTitle("Edit Gambar")
            .setView(container)
            .setPositiveButton("Terapkan") { _, _ ->
                binding.canvasView.pushImageHistory()
                image.opacity = opacitySeek.progress.coerceIn(0, 100)
                var w = etW.text.toString().toIntOrNull()?.coerceIn(10, 10000)
                var h = etH.text.toString().toIntOrNull()?.coerceIn(10, 10000)
                if (lockAspect.isChecked && w != null && h == null && image.height > 0f) {
                    h = (w * image.height / image.width).roundToInt().coerceIn(10, 10000)
                } else if (lockAspect.isChecked && h != null && w == null && image.width > 0f) {
                    w = (h * image.width / image.height).roundToInt().coerceIn(10, 10000)
                }
                if (lockAspect.isChecked && w != null && h != null && image.width > 0f && image.height > 0f) {
                    // Jaga rasio dari ukuran saat ini bila hanya satu sisi diubah.
                    if (etW.text.toString().toIntOrNull() != image.width.roundToInt()) {
                        h = (w * image.height / image.width).roundToInt().coerceIn(10, 10000)
                    } else {
                        w = (h * image.width / image.height).roundToInt().coerceIn(10, 10000)
                    }
                }
                if (w != null && w > 0) image.width = w.toFloat()
                if (h != null && h > 0) image.height = h.toFloat()
                onDone()
                updateImageQuickToolbar(image)
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Show / hide / refresh the floating quick-edit toolbar for [el]. */
    private fun updateTextQuickToolbar(el: TextElement?) {
        val tb = binding.textQuickToolbar
        if (el == null) {
            tb.visibility = View.GONE
            return
        }
        binding.imageQuickToolbar.visibility = View.GONE
        tb.visibility = View.VISIBLE
        // Reflect bold/italic toggle state
        tb.findViewById<Button>(R.id.qtbBold).alpha   = if (el.isBold)   1f else 0.55f
        tb.findViewById<Button>(R.id.qtbItalic).alpha = if (el.isItalic) 1f else 0.55f
        tb.findViewById<Button>(R.id.qtbAlign).text   = when (el.align) {
            TextAlign.LEFT   -> "☰"
            TextAlign.CENTER -> "≡"
            TextAlign.RIGHT  -> "☲"
        }
    }

    /**
     * Render all text + image overlays into a fresh ARGB_8888 bitmap of size
     * [width] × [height] so PSD export can include them as a flat “Text +
     * Images” layer. Returns null when there's nothing to render.
     */
    private fun renderOverlayLayer(width: Int, height: Int): Bitmap? {
        val texts  = binding.canvasView.textElements
        val images = binding.canvasView.imageElements
        if (texts.isEmpty() && images.isEmpty()) return null
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        for (img in images) {
            try {
                val matrix = android.graphics.Matrix()
                matrix.postScale(img.width / img.bitmap.width.toFloat(),
                                 img.height / img.bitmap.height.toFloat())
                matrix.postRotate(img.rotation, img.width / 2f, img.height / 2f)
                matrix.postTranslate(img.x, img.y)
                canvas.drawBitmap(img.bitmap, matrix, img.createDrawPaint())
            } catch (_: Exception) {}
        }
        for (el in texts) {
            try { TextRenderer.renderToCanvas(canvas, el) } catch (_: Exception) {}
        }
        return bmp
    }

    /** v5.0 — Toggle mesh warp transform on the currently selected text element. */
    private fun toggleTextMesh() {
        val activeId = binding.canvasView.activeTextId
        if (activeId == null) {
            Toast.makeText(this, "Select a text element first (tap on it).", Toast.LENGTH_SHORT).show()
            return
        }
        binding.canvasView.enableMeshForActiveText()
        val el = binding.canvasView.textElements.find { it.id == activeId }
        val nowOn = el?.meshPoints != null
        Toast.makeText(
            this,
            if (nowOn) "Mesh ON \u2014 drag the teal control grid to shape the text"
            else "Mesh OFF \u2014 back to normal layout",
            Toast.LENGTH_SHORT
        ).show()
        triggerAutoSave()
    }

    /** v5.0 — Toggle perspective transform on the currently selected text element. */
    private fun toggleTextPerspective() {
        val activeId = binding.canvasView.activeTextId
        if (activeId == null) {
            Toast.makeText(this, "Select a text element first (tap on it).", Toast.LENGTH_SHORT).show()
            return
        }
        binding.canvasView.enablePerspectiveForActiveText()
        val el = binding.canvasView.textElements.find { it.id == activeId }
        val nowOn = el?.perspCorners != null
        Toast.makeText(
            this,
            if (nowOn) "Perspective ON \u2014 drag purple corners to skew"
            else "Perspective OFF \u2014 back to normal layout",
            Toast.LENGTH_SHORT
        ).show()
        triggerAutoSave()
    }

    // ── Texture picker (for TextEffect.TEXTURE) ──────────────────────────────

    private var pendingTextureCallback: ((Uri) -> Unit)? = null
    private val texturePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: run {
            pendingTextureCallback = null
            return@registerForActivityResult
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some document providers grant only the current process. The texture
            // still works for this session; standard SAF providers persist it.
        }
        pendingTextureCallback?.invoke(uri)
        pendingTextureCallback = null
    }

    /** Trigger the persistent SAF texture picker; result delivered to [callback]. */
    fun pickTexture(callback: (Uri) -> Unit) {
        pendingTextureCallback = callback
        texturePickerLauncher.launch(arrayOf("image/*"))
    }

    // ══ v5.0 — Multi-style Span Editor ══════════════════════════════════════════════
    //
    // Lets the user pick character ranges and override font / colour /
    // outline / shadow on each range. Called from the "Spans" button in the
    // main text editor dialog. The result is passed back to the caller so
    // it can be baked into the TextElement on placement.

    private fun showSpanEditorDialog(
        initialText: String,
        initialSpans: List<TextSpan>,
        fonts: List<FontItem>,
        onApply: (newText: String, spans: List<TextSpan>) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_span_editor, null)
        val etText  = view.findViewById<EditText>(R.id.etSpanText)
        val tvRange = view.findViewById<TextView>(R.id.tvSelectionRange)
        val rv      = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSpans)
        val btnAdd  = view.findViewById<Button>(R.id.btnSpanAdd)
        val btnClr  = view.findViewById<Button>(R.id.btnSpanClear)
        val btnOk   = view.findViewById<Button>(R.id.btnSpanOk)
        val btnCanc = view.findViewById<Button>(R.id.btnSpanCancel)
        // v5.1 NEW — quick auto-split buttons
        val btnPerLine     = view.findViewById<Button>(R.id.btnSpanPerLine)
        val btnPerSentence = view.findViewById<Button>(R.id.btnSpanPerSentence)
        val btnPerWord     = view.findViewById<Button>(R.id.btnSpanPerWord)

        etText.setText(initialText)
        val spans = initialSpans.map { it.copy() }.toMutableList()

        fun refreshSelectionLabel() {
            val s = etText.selectionStart.coerceAtLeast(0)
            val e = etText.selectionEnd.coerceAtLeast(0)
            tvRange.text = "Selection: $s \u2013 $e (${(e - s).coerceAtLeast(0)} chars)"
        }
        etText.setOnClickListener { refreshSelectionLabel() }
        etText.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable) { refreshSelectionLabel() }
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence, st: Int, c: Int, a: Int) { refreshSelectionLabel() }
        })

        // Simple adapter
        rv.layoutManager = LinearLayoutManager(this)
        val adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<
                androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            inner class VH(v: View): androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
                val tv: TextView = v.findViewById(android.R.id.text1)
            }
            override fun onCreateViewHolder(parent: ViewGroup, vt: Int): VH {
                val v = LayoutInflater.from(parent.context).inflate(
                    android.R.layout.simple_list_item_1, parent, false)
                return VH(v)
            }
            override fun onBindViewHolder(h: androidx.recyclerview.widget.RecyclerView.ViewHolder, pos: Int) {
                val sp = spans[pos]
                val txt = etText.text.toString()
                val sub = if (sp.start in 0..txt.length && sp.end in 0..txt.length && sp.end > sp.start)
                    txt.substring(sp.start, sp.end.coerceAtMost(txt.length)) else ""
                val parts = mutableListOf<String>()
                sp.fontName?.let { parts.add("font=$it") }
                sp.fontSize?.let { parts.add("size=${it.toInt()}px") }
                sp.color?.let    { parts.add("color=#${"%06X".format(0xFFFFFF and it)}") }
                sp.effect?.let   { parts.add("effect=$it") }
                (h as VH).tv.text = "[${sp.start}\u2013${sp.end}] \u201c$sub\u201d  " + parts.joinToString(" ")
                h.tv.setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                h.itemView.setOnClickListener {
                    showSpanItemDialog(sp, fonts, onDelete = {
                        spans.removeAt(pos); notifyDataSetChanged()
                    }, onChanged = { notifyDataSetChanged() })
                }
            }
            override fun getItemCount() = spans.size
        }
        rv.adapter = adapter

        btnAdd.setOnClickListener {
            val s = etText.selectionStart.coerceAtLeast(0)
            val e = etText.selectionEnd.coerceAtLeast(0)
            if (e <= s) {
                Toast.makeText(this, "Pilih beberapa karakter dulu di teks di atas.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            spans.add(TextSpan(start = s, end = e))
            adapter.notifyDataSetChanged()
        }
        btnClr.setOnClickListener {
            spans.clear(); adapter.notifyDataSetChanged()
        }

        // v5.1 NEW — Quick auto-split: every line becomes its own styleable span
        btnPerLine.setOnClickListener {
            val txt = etText.text.toString()
            spans.clear()
            var cursor = 0
            txt.split('\n').forEach { line ->
                val len = line.length
                if (len > 0) spans.add(TextSpan(start = cursor, end = cursor + len))
                cursor += len + 1 // +1 for the '\n' character
            }
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "✨ ${spans.size} baris siap diberi gaya berbeda — ketuk setiap baris di daftar untuk atur.", Toast.LENGTH_LONG).show()
        }

        // v5.1 NEW — Quick auto-split: every sentence (. ! ?) becomes its own span
        btnPerSentence.setOnClickListener {
            val txt = etText.text.toString()
            spans.clear()
            val sentenceRegex = Regex("[^.!?\\n]+[.!?]?")
            for (m in sentenceRegex.findAll(txt)) {
                val raw = m.range
                // trim leading whitespace from sentence start
                var s = raw.first; val e = raw.last + 1
                while (s < e && txt[s].isWhitespace()) s++
                if (e > s) spans.add(TextSpan(start = s, end = e))
            }
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "✨ ${spans.size} kalimat siap diberi gaya — ketuk setiap kalimat di daftar.", Toast.LENGTH_LONG).show()
        }

        // v5.1 NEW — Quick auto-split: every word becomes its own span
        btnPerWord.setOnClickListener {
            val txt = etText.text.toString()
            spans.clear()
            val wordRegex = Regex("\\S+")
            for (m in wordRegex.findAll(txt)) {
                val r = m.range
                spans.add(TextSpan(start = r.first, end = r.last + 1))
            }
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "✨ ${spans.size} kata siap diberi gaya — cocok untuk rainbow / efek pelangi.", Toast.LENGTH_LONG).show()
        }
        refreshSelectionLabel()

        var dialog: AlertDialog? = null
        btnCanc.setOnClickListener { dialog?.dismiss() }
        btnOk.setOnClickListener {
            onApply(etText.text.toString(), spans.toList())
            dialog?.dismiss()
        }
        dialog = AlertDialog.Builder(this).setView(view).create()
        dialog.show()
    }

    private fun showSpanItemDialog(
        sp: TextSpan,
        fonts: List<FontItem>,
        onDelete: () -> Unit,
        onChanged: () -> Unit
    ) {
        val v = layoutInflater.inflate(R.layout.dialog_span_edit_item, null)
        val tvLabel = v.findViewById<TextView>(R.id.tvSpanRangeLabel)
        val cbFont  = v.findViewById<CheckBox>(R.id.cbSpanFont)
        val sFont   = v.findViewById<Spinner>(R.id.spinnerSpanFont)
        val cbBold  = v.findViewById<CheckBox>(R.id.cbSpanBold)
        val cbIta   = v.findViewById<CheckBox>(R.id.cbSpanItalic)
        val cbSize  = v.findViewById<CheckBox>(R.id.cbSpanSize)
        val seekSize = v.findViewById<SeekBar>(R.id.seekSpanSize)
        val tvSize  = v.findViewById<TextView>(R.id.tvSpanSize)
        val cbColor = v.findViewById<CheckBox>(R.id.cbSpanColor)
        val colorP  = v.findViewById<View>(R.id.spanColorPatch)
        val colorHex = v.findViewById<TextView>(R.id.tvSpanColorHex)
        val colorWheel = v.findViewById<com.vasiliastyper.view.ColorWheelView>(R.id.spanColorWheel)
        val cbEff   = v.findViewById<CheckBox>(R.id.cbSpanEffect)
        val sEff    = v.findViewById<Spinner>(R.id.spinnerSpanEffect)
        val pOut    = v.findViewById<View>(R.id.panelSpanOutline)
        val seekOw  = v.findViewById<SeekBar>(R.id.seekSpanOutlineW)
        val tvOw    = v.findViewById<TextView>(R.id.tvSpanOutlineW)
        val patchO  = v.findViewById<View>(R.id.spanOutlineColorPatch)
        val pSh     = v.findViewById<View>(R.id.panelSpanShadow)
        val seekSr  = v.findViewById<SeekBar>(R.id.seekSpanShadowR)
        val tvSr    = v.findViewById<TextView>(R.id.tvSpanShadowR)
        val patchS  = v.findViewById<View>(R.id.spanShadowColorPatch)
        val btnDel  = v.findViewById<Button>(R.id.btnSpanItemDelete)
        val btnCnc  = v.findViewById<Button>(R.id.btnSpanItemCancel)
        val btnOk   = v.findViewById<Button>(R.id.btnSpanItemOk)

        tvLabel.text = "Span [${sp.start} \u2013 ${sp.end}]"

        sFont.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, fonts.map { it.displayName })
        sp.fontName?.let { fn -> sFont.setSelection(fonts.indexOfFirst { it.displayName == fn }.coerceAtLeast(0)) }
        cbFont.isChecked = sp.fontName != null

        cbBold.isChecked = sp.isBold == true
        cbIta.isChecked  = sp.isItalic == true

        val initialSize = (sp.fontSize ?: 44f).takeIf { it.isFinite() }?.coerceIn(4f, 200f) ?: 44f
        cbSize.isChecked = sp.fontSize != null
        seekSize.progress = (initialSize.toInt() - 4).coerceIn(0, 196)
        fun syncSizeUi() {
            val value = seekSize.progress + 4
            tvSize.text = "$value px"
            seekSize.isEnabled = cbSize.isChecked
            seekSize.alpha = if (cbSize.isChecked) 1f else 0.45f
        }
        seekSize.setOnSeekBarChangeListener(seekListener { syncSizeUi() })
        cbSize.setOnCheckedChangeListener { _, _ -> syncSizeUi() }
        tvSize.setOnClickListener {
            askNumberInput("Ukuran span (px)", (seekSize.progress + 4).toFloat(), 4f, 200f) {
                seekSize.progress = (it.roundToInt() - 4).coerceIn(0, 196)
                cbSize.isChecked = true
                syncSizeUi()
            }
        }
        syncSizeUi()

        var color = sp.color ?: android.graphics.Color.YELLOW
        fun syncSpanColorUi(newColor: Int) {
            color = newColor
            colorP.setBackgroundColor(newColor)
            colorHex.text = "#${"%06X".format(0xFFFFFF and newColor)}"
        }
        cbColor.isChecked = sp.color != null
        colorWheel.setColor(color)
        syncSpanColorUi(color)
        colorWheel.onColorChanged = { selected ->
            syncSpanColorUi(selected)
            cbColor.isChecked = true
        }
        colorP.setOnClickListener {
            showColorPickerFull("Warna Span", color) { selected ->
                colorWheel.setColor(selected)
                syncSpanColorUi(selected)
                cbColor.isChecked = true
            }
        }

        val effects = listOf("NONE","OUTLINE","SHADOW","OUTLINE_SHADOW")
        sEff.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, effects)
        cbEff.isChecked = sp.effect != null
        sp.effect?.let { e -> sEff.setSelection(effects.indexOf(e).coerceAtLeast(0)) }

        fun syncPanels() {
            val on = cbEff.isChecked
            val eff = if (on) effects[sEff.selectedItemPosition] else "NONE"
            pOut.visibility = if (on && (eff == "OUTLINE" || eff == "OUTLINE_SHADOW")) View.VISIBLE else View.GONE
            pSh.visibility  = if (on && (eff == "SHADOW"  || eff == "OUTLINE_SHADOW")) View.VISIBLE else View.GONE
        }
        cbEff.setOnCheckedChangeListener { _, _ -> syncPanels() }
        sEff.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, vv: View?, pos: Int, id: Long) = syncPanels()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        syncPanels()

        seekOw.progress = (sp.outlineWidth ?: 4f).toInt().coerceIn(0, 30)
        tvOw.text = seekOw.progress.toString()
        seekOw.setOnSeekBarChangeListener(seekListener { p -> tvOw.text = p.toString() })
        tvOw.setOnClickListener {
            askNumberInput("Lebar outline span (px)", tvOw.text.toString().toFloatOrNull() ?: 4f, 0f, 30f) {
                seekOw.progress = it.roundToInt().coerceIn(0, 30)
                tvOw.text = seekOw.progress.toString()
            }
        }
        var outColor = sp.outlineColor ?: android.graphics.Color.BLACK
        patchO.setBackgroundColor(outColor)
        patchO.setOnClickListener { showColorPickerFull("Warna Outline Span", outColor) { c -> outColor = c; patchO.setBackgroundColor(c) } }

        seekSr.progress = (sp.shadowRadius ?: 6f).toInt().coerceIn(0, 40)
        tvSr.text = seekSr.progress.toString()
        seekSr.setOnSeekBarChangeListener(seekListener { p -> tvSr.text = p.toString() })
        tvSr.setOnClickListener {
            askNumberInput("Radius shadow span (px)", tvSr.text.toString().toFloatOrNull() ?: 6f, 0f, 40f) {
                seekSr.progress = it.roundToInt().coerceIn(0, 40)
                tvSr.text = seekSr.progress.toString()
            }
        }
        var shColor = sp.shadowColor ?: android.graphics.Color.BLACK
        patchS.setBackgroundColor(shColor)
        patchS.setOnClickListener { showColorPickerFull("Warna Shadow Span", shColor) { c -> shColor = c; patchS.setBackgroundColor(c) } }

        var dialog: AlertDialog? = null
        btnDel.setOnClickListener { onDelete(); dialog?.dismiss() }
        btnCnc.setOnClickListener { dialog?.dismiss() }
        btnOk.setOnClickListener {
            sp.fontName       = if (cbFont.isChecked) fonts.getOrNull(sFont.selectedItemPosition)?.displayName else null
            sp.typeface       = if (cbFont.isChecked) fonts.getOrNull(sFont.selectedItemPosition)?.typeface else null
            sp.isBold         = if (cbBold.isChecked) true else null
            sp.isItalic       = if (cbIta.isChecked)  true else null
            sp.fontSize       = if (cbSize.isChecked) (seekSize.progress + 4).toFloat() else null
            sp.color          = if (cbColor.isChecked) color else null
            sp.effect         = if (cbEff.isChecked) effects[sEff.selectedItemPosition] else null
            sp.outlineWidth   = if (cbEff.isChecked) seekOw.progress.toFloat() else null
            sp.outlineColor   = if (cbEff.isChecked) outColor else null
            sp.outlineOpacity = if (cbEff.isChecked) 100 else null
            sp.shadowRadius   = if (cbEff.isChecked) seekSr.progress.toFloat() else null
            sp.shadowColor    = if (cbEff.isChecked) shColor else null
            sp.shadowOpacity  = if (cbEff.isChecked) 80 else null
            onChanged(); dialog?.dismiss()
        }
        dialog = AlertDialog.Builder(this).setView(v).create()
        dialog.show()
    }

    /**
     * Lightweight 8-swatch colour picker (used by the span editor).
     * For richer pickers in the main editor see the existing colorPatch hooks.
     */
    private fun showSimpleColorPicker(current: Int, onPicked: (Int) -> Unit) {
        val colors = intArrayOf(
            android.graphics.Color.WHITE, android.graphics.Color.BLACK,
            android.graphics.Color.RED,   android.graphics.Color.parseColor("#FF8800"),
            android.graphics.Color.YELLOW, android.graphics.Color.GREEN,
            android.graphics.Color.CYAN,   android.graphics.Color.parseColor("#0066FF"),
            android.graphics.Color.MAGENTA, android.graphics.Color.parseColor("#888888")
        )
        val names  = arrayOf("White","Black","Red","Orange","Yellow","Green","Cyan","Blue","Magenta","Gray")
        AlertDialog.Builder(this)
            .setTitle("Pick colour")
            .setItems(names) { _, i -> onPicked(colors[i]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ════════════════════════════════════════════════════════════════════════════
    // BUBBLE TRANSLATION (tap bubble/text or use current selection)
    // ════════════════════════════════════════════════════════════════════════════

    private fun runBubbleTranslationFromSelection() {
        val sel = binding.canvasView.selection
        if (!sel.isActive) {
            Toast.makeText(this, "Buat seleksi bubble dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val areas = sel.getAreas().ifEmpty { listOf(sel.getBounds()) }
            .map { RectF(it) }
            .filter { it.width() > 6f && it.height() > 6f }
        if (areas.isEmpty()) {
            Toast.makeText(this, "Seleksi terlalu kecil", Toast.LENGTH_SHORT).show()
            return
        }
        runBubbleTranslationForAreas(areas)
    }

    private fun runBubbleTranslationForActiveText() {
        val activeId = binding.canvasView.activeTextId ?: return
        val el = binding.canvasView.textElements.firstOrNull { it.id == activeId } ?: return
        runBubbleTranslationForAreas(listOf(RectF(el.x, el.y, el.x + el.width, el.y + el.height)))
    }

    private fun runBubbleTranslationFromTap(bx: Int, by: Int) {
        val ws = vm.activeWorkspace ?: return
        lifecycleScope.launch {
            val composite = withContext(Dispatchers.IO) {
                binding.canvasView.compositeVisibleLayers()
                    ?: LayerCompositor.composite(ws.layers, ws.width, ws.height)
            } ?: run {
                Toast.makeText(this@MainActivity, "Kanvas kosong", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val tol = binding.canvasView.tolerance.coerceIn(12, 70)
            val region = withContext(Dispatchers.IO) { MagicWandSelector.select(composite, bx, by, tol) }
            composite.recycle()
            val rb = region.bounds
            val area = if (!rb.isEmpty && rb.width() > 8 && rb.height() > 8) {
                RectF(rb)
            } else {
                RectF((bx - 140).toFloat(), (by - 90).toFloat(), (bx + 140).toFloat(), (by + 90).toFloat())
            }
            runBubbleTranslationForAreas(listOf(area))
        }
    }

    private fun runBubbleTranslationForAreas(areas: List<RectF>) {
        val ws = vm.activeWorkspace ?: return
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return
        if (!layer.bitmap.isMutable) {
            Toast.makeText(this, "Layer aktif tidak bisa diedit", Toast.LENGTH_SHORT).show()
            return
        }

        vm.pushHistory(ws)
        updateStatus("Bubble translation: menganalisa ${areas.size} area…")

        lifecycleScope.launch {
            val srcLang = ocrSrcCodes.getOrNull(binding.spinnerOcrSrc.selectedItemPosition) ?: "auto"
            val tgtLang = ocrTgtCodes.getOrNull(binding.spinnerOcrTgt.selectedItemPosition) ?: "id"
            // v11.2 fix: see placeAllPendingTipeRMatches() — cold-cache Typeface decode
            // must not run on the Main dispatcher.
            val fonts = withContext(Dispatchers.Default) { buildFontList() }

            val composite = withContext(Dispatchers.IO) {
                binding.canvasView.compositeVisibleLayers()
                    ?: LayerCompositor.composite(ws.layers, ws.width, ws.height)
            } ?: run {
                updateStatus("Bubble translation gagal: komposit kosong")
                return@launch
            }

            var translatedCount = 0
            for ((idx, rawArea) in areas.withIndex()) {
                val area = RectF(
                    rawArea.left.coerceIn(0f, (composite.width - 1).toFloat()),
                    rawArea.top.coerceIn(0f, (composite.height - 1).toFloat()),
                    rawArea.right.coerceIn(1f, composite.width.toFloat()),
                    rawArea.bottom.coerceIn(1f, composite.height.toFloat())
                )
                if (area.width() < 6f || area.height() < 6f) continue

                val x = area.left.toInt().coerceIn(0, composite.width - 1)
                val y = area.top.toInt().coerceIn(0, composite.height - 1)
                val w = area.width().toInt().coerceIn(1, composite.width - x)
                val h = area.height().toInt().coerceIn(1, composite.height - y)
                val crop = Bitmap.createBitmap(composite, x, y, w, h)

                val pair = detectAndTranslateAreaText(crop, srcLang, tgtLang)
                val translated = pair?.second?.trim().orEmpty()
                if (translated.isNotEmpty()) {
                    val strictRect = Rect(x, y, x + w, y + h)
                    withContext(Dispatchers.IO) {
                        applyTextMaskOnly(composite, layer.bitmap, strictRect, Color.WHITE, bleedPx = 1)
                    }

                    val linkedText = binding.canvasView.textElements.lastOrNull { el ->
                        RectF.intersects(area, RectF(el.x, el.y, el.x + el.width, el.y + el.height))
                    }
                    val selectedFont = linkedText?.fontName
                        ?.let { name -> fonts.firstOrNull { it.displayName.equals(name, ignoreCase = true) } }
                        ?: chooseFontForTranslation(pair?.first.orEmpty(), fonts)

                    val color = linkedText?.color ?: estimateTextColor(crop)
                    val padding = (minOf(area.width(), area.height()) * 0.08f).coerceIn(4f, 24f)
                    val tx = area.left + padding
                    val ty = area.top + padding
                    val tw = (area.width() - padding * 2f).coerceAtLeast(10f)
                    val th = (area.height() - padding * 2f).coerceAtLeast(10f)
                    val maxTextSize = linkedText?.fontSize?.coerceAtLeast(10f) ?: 84f
                    val size = TextRenderer.autoFitFontSize(
                        text = translated,
                        boxWidth = tw,
                        boxHeight = th,
                        typeface = selectedFont?.typeface,
                        maxFontSize = maxTextSize,
                        roundBubbleMode = area.width() <= area.height() * 1.25f
                    )

                    val activeLayerId = ws.layers.getOrNull(ws.activeLayerIndex)?.id
                    binding.canvasView.textElements.add(
                        TextElement(
                            text = translated,
                            x = tx,
                            y = ty,
                            width = tw,
                            height = th,
                            fontSize = size,
                            typeface = selectedFont?.typeface,
                            fontName = selectedFont?.displayName ?: "Default",
                            color = color,
                            isBold = linkedText?.isBold ?: false,
                            isItalic = linkedText?.isItalic ?: false,
                            align = linkedText?.align ?: TextAlign.CENTER,
                            effect = linkedText?.effect ?: TextEffect.NONE,
                            layerId = activeLayerId
                        )
                    )
                    translatedCount++
                }
                crop.recycle()
                updateStatus("Bubble translation ${idx + 1}/${areas.size}…")
            }
            composite.recycle()

            binding.canvasView.invalidate()
            updateStatus("✓ Bubble translation selesai: $translatedCount/${areas.size}")
        }
    }

    private suspend fun detectAndTranslateAreaText(crop: Bitmap, src: String, tgt: String): Pair<String, String>? {
        val apiKey = GeminiSettings.getApiKey(this)
        if (!apiKey.isNullOrBlank()) {
            return suspendCoroutine { cont ->
                GeminiOcrTranslation.recognizeAndTranslate(
                    bitmap = crop,
                    srcLang = src,
                    tgtLang = tgt,
                    apiKey = apiKey,
                    callback = object : GeminiOcrTranslation.OcrCallback {
                        override fun onSuccess(originalText: String, translatedText: String, detectedLang: String) {
                            cont.resume(originalText to translatedText)
                        }

                        override fun onFailure(error: String) {
                            cont.resume(null)
                        }
                    }
                )
            }
        }

        return suspendCoroutine { cont ->
            MlKitOcrEngine.recognize(crop, null, src, object : MlKitOcrEngine.OcrCallback {
                override fun onSuccess(text: String, lang: String) {
                    TranslationManager.translate(text, lang, tgt, object : TranslationManager.Cb {
                        override fun onSuccess(r: String) { cont.resume(text to r) }
                        override fun onFailure(m: String) { cont.resume(text to text) }
                    })
                }

                override fun onFailure(e: Exception) {
                    cont.resume(null)
                }
            })
        }
    }

    private fun chooseFontForTranslation(originalText: String, fonts: List<FontItem>): FontItem? {
        if (fonts.isEmpty()) return null
        val upperRatio = if (originalText.isNotEmpty()) {
            originalText.count { it.isLetter() && it.isUpperCase() }.toFloat() /
                originalText.count { it.isLetter() }.coerceAtLeast(1).toFloat()
        } else 0f
        return if (upperRatio > 0.65f) {
            fonts.firstOrNull { it.isComic } ?: fonts.firstOrNull()
        } else {
            fonts.firstOrNull { it.displayName.equals("Default", ignoreCase = true) } ?: fonts.firstOrNull()
        }
    }

    private fun estimateTextColor(crop: Bitmap): Int {
        val w = crop.width
        val h = crop.height
        if (w <= 0 || h <= 0) return Color.BLACK
        val px = IntArray(w * h)
        crop.getPixels(px, 0, w, 0, 0, w, h)

        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L
        for (p in px) {
            val a = Color.alpha(p)
            if (a < 128) continue
            val lum = BubbleCleaner.luminance(p)
            if (lum <= 150) {
                sumR += Color.red(p)
                sumG += Color.green(p)
                sumB += Color.blue(p)
                count++
            }
        }
        if (count == 0L) return Color.BLACK
        return Color.rgb((sumR / count).toInt(), (sumG / count).toInt(), (sumB / count).toInt())
    }

    // ════════════════════════════════════════════════════════════════════════════
    // OCR PANEL (integrated with editor selection tools)
    // ════════════════════════════════════════════════════════════════════════════

    private fun toggleOcrPanel() {
        val panel = binding.bottomOcrPanel
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
            binding.canvasView.ocrOverlayDeleteMode = false
            binding.canvasView.ocrDetectOverlay = emptyList()
            binding.canvasView.invalidate()
        } else {
            binding.bottomMaskPanel.visibility = View.GONE
            binding.bottomScriptPanel.visibility = View.GONE
            binding.bottomUnwatermarkPanel.visibility = View.GONE
            panel.visibility = View.VISIBLE
            refreshOcrOverlay()
        }
    }

    private fun setupBottomOcrPanel() {
        ocrAdapter = OcrResultAdapter(
            ocrResults,
            { pos -> removeOcrRegion(pos) },
            { pos -> retranslateOcr(pos) },
            { pos -> copyOcrText(pos, translated = false) },
            { pos -> copyOcrText(pos, translated = true) }
        )
        binding.ocrResultsList.layoutManager = LinearLayoutManager(this)
        binding.ocrResultsList.adapter = ocrAdapter

        binding.spinnerOcrSrc.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Auto", "EN", "ZH", "KO")
        )
        binding.spinnerOcrTgt.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("ID", "EN")
        )
        binding.switchGeminiOcr.setOnCheckedChangeListener { _, checked ->
            useGeminiOcr = checked
            binding.tvOcrHint.text = if (checked)
                "Gemini 3.1 Flash-Lite aktif untuk OCR + terjemahan; ML Kit menjaga koordinat region"
            else
                "ML Kit OCR region aktif — seluruh kanvas, bekerja offline"
        }

        binding.btnRunOcrSel.setOnClickListener { runOcrOnEntireCanvas() }
        binding.btnRunOcrArea.setOnClickListener { runOcrOnCurrentSelection() }
        binding.btnDeleteOcrRegion.setOnClickListener {
            if (ocrResults.none { it.regionRect != null }) {
                Toast.makeText(this, "Belum ada region OCR untuk dihapus", Toast.LENGTH_SHORT).show()
            } else {
                binding.canvasView.ocrOverlayDeleteMode = !binding.canvasView.ocrOverlayDeleteMode
                binding.btnDeleteOcrRegion.text =
                    if (binding.canvasView.ocrOverlayDeleteMode) "Hapus: ON" else "Hapus Region"
                binding.tvOcrHint.text = if (binding.canvasView.ocrOverlayDeleteMode) {
                    "Tap kotak OCR biru di kanvas untuk menghapus region dan teksnya"
                } else {
                    "Mode hapus region dimatikan"
                }
            }
        }
        binding.btnClearOcr.setOnClickListener {
            ocrResults.clear()
            ocrAdapter?.notifyDataSetChanged()
            binding.canvasView.ocrOverlayDeleteMode = false
            binding.btnDeleteOcrRegion.text = "Hapus Region"
            refreshOcrOverlay()
            binding.tvOcrHint.text = "Tekan OCR Seluruh Kanvas untuk mendeteksi semua teks per region"
        }
        binding.btnOcrToScript.setOnClickListener { ocrResultsToScript() }
        binding.btnCloseOcr.setOnClickListener { toggleOcrPanel() }
        binding.canvasView.onOcrOverlayDeleteRequest = { pos -> removeOcrRegion(pos) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Auto Mask Panel — independent offline text-mask detectors
    // ─────────────────────────────────────────────────────────────────────────

    private fun toggleMaskPanel() {
        val panel = binding.bottomMaskPanel
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
        } else {
            // Tutup panel lain dulu
            binding.bottomOcrPanel.visibility = View.GONE
            binding.bottomScriptPanel.visibility = View.GONE
            binding.bottomUnwatermarkPanel.visibility = View.GONE
            panel.visibility = View.VISIBLE
        }
    }

    // Engine pilihan user untuk deteksi teks di panel Mask
    private val maskEngineCodes = arrayOf("ppocr_small", "mlkit_v2")

    // Kode bahasa untuk engine mask yang mendukung pilihan bahasa (termasuk ML Kit v2).
    private val maskLangCodes = arrayOf("auto", "en", "zh", "ko")


    private data class UnwatermarkMatch(
        val sample: Bitmap,
        val preset: WatermarkPresetLoader.Preset?,
        val detection: UnwatermarkEngine.DetectionResult
    )

    private data class UnwatermarkApplyResult(
        val removal: UnwatermarkEngine.Result,
        val detection: UnwatermarkEngine.DetectionResult?,
        val rect: Rect,
        val sample: Bitmap,
        val preset: WatermarkPresetLoader.Preset?,
        val wasHybridManual: Boolean
    )

    private fun clearUnwatermarkSamples() {
        val standalone = unwatermarkSample
            ?.takeUnless { sample -> unwatermarkPresets.any { it.bitmap === sample } }
        standalone?.takeUnless { it.isRecycled }?.recycle()
        unwatermarkPresets.forEach(WatermarkPresetLoader.Preset::recycle)
        unwatermarkPresets.clear()
        activeUnwatermarkPreset = null
        unwatermarkSample = null
    }

    private fun presetSearchHint(
        bitmap: Bitmap,
        preset: WatermarkPresetLoader.Preset,
        scale: Float
    ): Rect {
        val width = (preset.bitmap.width * scale).roundToInt().coerceIn(1, bitmap.width)
        val height = (preset.bitmap.height * scale).roundToInt().coerceIn(1, bitmap.height)
        val offsetX = (preset.offsetX * scale).roundToInt()
        val offsetY = (preset.offsetY * scale).roundToInt()
        val anchor = preset.anchor.lowercase(java.util.Locale.ROOT)
        val baseLeft = when {
            "right" in anchor -> bitmap.width - width
            "center" in anchor || "middle" in anchor -> (bitmap.width - width) / 2
            else -> 0
        }
        val baseTop = when {
            "bottom" in anchor -> bitmap.height - height
            "center" in anchor || "middle" in anchor -> (bitmap.height - height) / 2
            else -> 0
        }
        val left = (baseLeft + offsetX).coerceIn(0, bitmap.width - 1)
        val top = (baseTop + offsetY).coerceIn(0, bitmap.height - 1)
        return Rect(
            left,
            top,
            (left + width).coerceIn(left + 1, bitmap.width),
            (top + height).coerceIn(top + 1, bitmap.height)
        )
    }

    private fun adaptiveUnwatermarkPreset(
        bitmap: Bitmap,
        sample: Bitmap,
        preset: WatermarkPresetLoader.Preset?,
        hint: Rect?,
        requestedStrength: Float = UnwatermarkEngine.DEFAULT_BEST_STRENGTH,
        preferHintScale: Boolean = false
    ): UnwatermarkEngine.AdaptivePreset = UnwatermarkEngine.createAdaptivePreset(
        canvasWidth = bitmap.width,
        canvasHeight = bitmap.height,
        sampleWidth = sample.width,
        sampleHeight = sample.height,
        referenceCanvasWidth = preset?.referenceCanvasWidth,
        hintRect = hint,
        baseStrength = requestedStrength,
        baseJpegRadius = preset?.jpegRadius ?: 3,
        baseJpegThreshold = preset?.jpegThreshold ?: 4,
        preferHintScale = preferHintScale
    )

    private fun findBestUnwatermarkMatch(
        bitmap: Bitmap,
        hint: Rect?
    ): UnwatermarkMatch? {
        val presets = unwatermarkPresets.toList()
        if (presets.isEmpty()) {
            val sample = unwatermarkSample?.takeUnless { it.isRecycled } ?: return null
            val adaptive = adaptiveUnwatermarkPreset(bitmap, sample, null, hint)
            val detection = UnwatermarkEngine.detect(
                bitmap,
                sample,
                hint,
                minimumConfidence = adaptive.minimumConfidence,
                expectedScale = adaptive.expectedScale
            ) ?: return null
            return UnwatermarkMatch(sample, null, detection)
        }

        var best: UnwatermarkMatch? = null
        for (preset in presets) {
            if (preset.bitmap.isRecycled) continue
            val adaptive = adaptiveUnwatermarkPreset(bitmap, preset.bitmap, preset, hint)
            // Preset anchor/offset gives a reliable bounded search area. A manual
            // rough selection always wins when supplied by the user.
            val searchHint = hint ?: presetSearchHint(bitmap, preset, adaptive.expectedScale)
            val detection = UnwatermarkEngine.detect(
                bitmap,
                preset.bitmap,
                searchHint,
                minimumConfidence = preset.minimumConfidence,
                expectedScale = adaptive.expectedScale
            ) ?: continue
            val match = UnwatermarkMatch(preset.bitmap, preset, detection)
            if (best == null || detection.confidence > best.detection.confidence) best = match
            // Near-perfect matches do not justify decoding/scanning dozens more presets.
            if (detection.confidence >= 0.985f) break
        }
        return best
    }

    private fun toggleUnwatermarkPanel() {
        val panel = binding.bottomUnwatermarkPanel
        if (panel.visibility == View.VISIBLE) {
            panel.visibility = View.GONE
            binding.canvasView.unwatermarkTransformEnabled = false
            clearUnwatermarkPreview()
        } else {
            binding.bottomOcrPanel.visibility = View.GONE
            binding.bottomMaskPanel.visibility = View.GONE
            binding.bottomScriptPanel.visibility = View.GONE
            panel.visibility = View.VISIBLE
            binding.canvasView.unwatermarkTransformEnabled = true
            binding.tvUnwmSampleName.text = unwatermarkSampleName
            scheduleUnwatermarkPreview()
        }
    }

    private fun setupUnwatermarkPanel() {
        val engineLabels = listOf(
            "Engine 1 — Octopus Adaptive/Hybrid",
            "Engine 2 — HTML Unwatermarker 1.6 Native"
        )
        binding.spinnerUnwmEngine.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            engineLabels
        )
        var engineSpinnerReady = false
        binding.spinnerUnwmEngine.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                activeUnwatermarkProfile = if (position == 1) {
                    UnwatermarkEngine.Profile.HTML_UNWATERMARKER_V1_6
                } else {
                    UnwatermarkEngine.Profile.OCTOPUS_ADAPTIVE
                }
                if (!engineSpinnerReady) engineSpinnerReady = true
                binding.tvUnwmHint.text = if (position == 1) {
                    "HTML Unwatermarker 1.6 berjalan native/offline langsung pada kanvas. Pilih sampel, Auto Detect, lalu Terapkan."
                } else {
                    "Octopus Adaptive/Hybrid aktif. Pilih preset atau sampel watermark lalu gunakan Auto Detect."
                }
                scheduleUnwatermarkPreview(delayMs = 20L)
            }
        }

        binding.btnCloseUnwm.setOnClickListener {
            binding.bottomUnwatermarkPanel.visibility = View.GONE
            binding.canvasView.unwatermarkTransformEnabled = false
            clearUnwatermarkPreview()
        }
        binding.btnPickUnwmSample.setOnClickListener {
            unwatermarkSamplePickerLauncher.launch("*/*")
        }
        binding.btnDetectUnwm.setOnClickListener { runUnwatermarkAutoDetect() }
        binding.spinnerUnwmPreset.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingUnwatermarkPresetSpinner) return
                val preset = unwatermarkPresets.getOrNull(position) ?: return
                activeUnwatermarkPreset = preset
                unwatermarkSample = preset.bitmap
                unwatermarkSampleName = preset.name
                binding.tvUnwmSampleName.text = preset.name
                scheduleUnwatermarkPreview()
            }
        }
        binding.switchUnwmRealtime.setOnCheckedChangeListener { _, enabled ->
            if (enabled) scheduleUnwatermarkPreview() else clearUnwatermarkPreview()
        }
        binding.switchUnwmAutoAlign.setOnCheckedChangeListener { _, _ -> scheduleUnwatermarkPreview() }
        // Calibrated default: 0.94x. The engine then performs image-based fine tuning.
        if (binding.seekUnwmStrength.progress == 50) binding.seekUnwmStrength.progress = 44
        fun updateStrengthLabel(progress: Int) {
            val strength = 0.5f + progress.coerceIn(0, 100) / 100f
            binding.tvUnwmStrength.text = String.format(java.util.Locale.US, "%.2fx", strength)
        }
        updateStrengthLabel(binding.seekUnwmStrength.progress)
        binding.seekUnwmStrength.setOnSeekBarChangeListener(seekListener { progress ->
            updateStrengthLabel(progress)
            scheduleUnwatermarkPreview()
        })
        binding.switchUnwmSmooth.setOnCheckedChangeListener { _, _ -> scheduleUnwatermarkPreview() }
        binding.btnApplyUnwm.setOnClickListener { applyUnwatermarkToSelection() }
        loadBundledUnwatermarkPresets()
    }

    private fun loadBundledUnwatermarkPresets() {
        if (unwatermarkPresets.isNotEmpty()) return
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    WatermarkPresetLoader.loadAsset(
                        this@MainActivity,
                        "wm_presets_jjaptoon_default.json"
                    )
                }
            }
            result.onSuccess { presets ->
                if (presets.isEmpty()) return@onSuccess
                clearUnwatermarkSamples()
                unwatermarkPresets += presets
                activeUnwatermarkPreset = presets.first()
                unwatermarkSample = presets.first().bitmap
                unwatermarkSampleName = "Jjaptoon default • ${presets.size} preset"
                binding.tvUnwmSampleName.text = unwatermarkSampleName
                binding.tvUnwmHint.text =
                    "Preset bawaan siap, termasuk Logo Bulat dan Banner Besar. Pilih varian lalu tekan Auto Detect; Real-time aktif secara default."
                refreshUnwatermarkPresetSpinner()
                scheduleUnwatermarkPreview()
            }.onFailure { error ->
                binding.tvUnwmHint.text =
                    "Preset bawaan gagal dibaca: ${error.message ?: "format tidak valid"}. Preset custom tetap dapat diimpor."
            }
        }
    }

    private fun refreshUnwatermarkPresetSpinner() {
        val labels = if (unwatermarkPresets.isEmpty()) {
            listOf(unwatermarkSampleName)
        } else {
            unwatermarkPresets.map(WatermarkPresetLoader.Preset::name)
        }
        updatingUnwatermarkPresetSpinner = true
        binding.spinnerUnwmPreset.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        val selected = activeUnwatermarkPreset
            ?.let { active -> unwatermarkPresets.indexOfFirst { it === active } }
            ?.takeIf { it >= 0 }
            ?: 0
        binding.spinnerUnwmPreset.setSelection(selected, false)
        updatingUnwatermarkPresetSpinner = false
    }

    private fun clearUnwatermarkPreview() {
        unwatermarkPreviewGeneration++
        unwatermarkPreviewJob?.cancel()
        unwatermarkPreviewJob = null
        binding.canvasView.clearUnwatermarkPreview()
    }

    /**
     * HTML-style real-time mode: calculate into a small mutable region copy and
     * display it as a canvas overlay. The active layer is never changed until
     * Remove Watermark is pressed, so preview refreshes remain safe and undo-free.
     */
    private fun scheduleUnwatermarkPreview(delayMs: Long = 140L) {
        unwatermarkPreviewGeneration++
        val generation = unwatermarkPreviewGeneration
        unwatermarkPreviewJob?.cancel()
        if (!binding.switchUnwmRealtime.isChecked ||
            binding.bottomUnwatermarkPanel.visibility != View.VISIBLE
        ) {
            binding.canvasView.clearUnwatermarkPreview()
            return
        }
        val ws = vm.activeWorkspace ?: return
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return
        val sample = unwatermarkSample?.takeUnless { it.isRecycled } ?: return
        val preset = activeUnwatermarkPreset
        val scale = preset?.referenceCanvasWidth
            ?.takeIf { it > 0 }
            ?.let { layer.bitmap.width.toFloat() / it.toFloat() }
            ?: 1f
        val rect = currentUnwatermarkSelection(layer.bitmap)
            ?: preset?.let { presetSearchHint(layer.bitmap, it, scale) }
            ?: run {
                binding.canvasView.clearUnwatermarkPreview()
                return
            }
        if (rect.isEmpty || rect.width().toLong() * rect.height().toLong() > 1_500_000L) {
            binding.canvasView.clearUnwatermarkPreview()
            return
        }
        val strength = 0.5f + binding.seekUnwmStrength.progress.coerceIn(0, 100) / 100f
        val adaptive = adaptiveUnwatermarkPreset(
            layer.bitmap,
            sample,
            preset,
            rect,
            strength,
            preferHintScale = true
        )
        val profileStrength = if (activeUnwatermarkProfile == UnwatermarkEngine.Profile.HTML_UNWATERMARKER_V1_6) {
            strength
        } else {
            adaptive.recommendedStrength
        }
        val options = UnwatermarkEngine.createOptions(
            profile = activeUnwatermarkProfile,
            requestedStrength = profileStrength,
            smoothOpaquePixels = binding.switchUnwmSmooth.isChecked,
            autoAlign = binding.switchUnwmAutoAlign.isChecked,
            adaptiveAlignRadius = adaptive.autoAlignRadius,
            adaptiveJpegRadius = adaptive.jpegFilterRadius,
            adaptiveJpegThreshold = adaptive.jpegFilterThreshold,
            presetAlphaAdjust = preset?.alphaAdjust ?: 1f,
            presetJpegEnabled = preset?.jpegFilterEnabled ?: true,
            presetAutoTuneStrength = preset?.autoTuneStrength ?: true
        )

        unwatermarkPreviewJob = lifecycleScope.launch {
            delay(delayMs)
            val previewResult = withContext(Dispatchers.Default) {
                runCatching {
                    val cropped = Bitmap.createBitmap(
                        layer.bitmap,
                        rect.left,
                        rect.top,
                        rect.width(),
                        rect.height()
                    )
                    val preview = cropped.copy(Bitmap.Config.ARGB_8888, true)
                        ?: throw IllegalStateException("Memori preview tidak cukup")
                    if (preview !== cropped && cropped !== layer.bitmap && !cropped.isRecycled) {
                        cropped.recycle()
                    }
                    val sampleCopy = sample.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IllegalStateException("Memori sampel preview tidak cukup")
                    try {
                        UnwatermarkEngine.remove(
                            preview,
                            sampleCopy,
                            Rect(0, 0, preview.width, preview.height),
                            options
                        )
                        preview
                    } catch (error: Throwable) {
                        if (!preview.isRecycled) preview.recycle()
                        throw error
                    } finally {
                        if (!sampleCopy.isRecycled) sampleCopy.recycle()
                    }
                }
            }
            previewResult.onSuccess { preview ->
                if (generation == unwatermarkPreviewGeneration &&
                    binding.switchUnwmRealtime.isChecked &&
                    binding.bottomUnwatermarkPanel.visibility == View.VISIBLE
                ) {
                    binding.canvasView.setUnwatermarkPreview(preview, rect)
                    binding.tvUnwmHint.text =
                        "Real-time preview • ${rect.width()}×${rect.height()} px • non-destruktif"
                } else if (!preview.isRecycled) {
                    preview.recycle()
                }
            }.onFailure { error ->
                if (generation == unwatermarkPreviewGeneration) {
                    binding.canvasView.clearUnwatermarkPreview()
                    binding.tvUnwmHint.text =
                        "Preview real-time gagal: ${error.message ?: "kesalahan pemrosesan"}"
                }
            }
        }
    }

    private fun currentUnwatermarkSelection(bitmap: Bitmap): Rect? {
        val selection = binding.canvasView.selection
        if (!selection.isActive) return null
        val bounds = selection.getBounds()
        return Rect(
            bounds.left.toInt().coerceIn(0, bitmap.width),
            bounds.top.toInt().coerceIn(0, bitmap.height),
            bounds.right.toInt().coerceIn(0, bitmap.width),
            bounds.bottom.toInt().coerceIn(0, bitmap.height)
        ).takeUnless { it.isEmpty }
    }

    private fun showUnwatermarkDetection(detection: UnwatermarkEngine.DetectionResult) {
        val rect = detection.rect
        with(binding.canvasView.selection) {
            clear()
            type = SelectionType.RECT
            this.rect.set(RectF(rect))
            isActive = true
            isManualUnion = true
        }
        unwatermarkSelectionManuallyAdjusted = false
        binding.canvasView.unwatermarkTransformEnabled = true
        binding.canvasView.invalidate()
        scheduleUnwatermarkPreview(delayMs = 20L)
        val confidence = (detection.confidence * 100f).roundToInt()
        val message = "Watermark ditemukan: ${rect.width()}×${rect.height()} px • confidence $confidence%"
        binding.tvUnwmHint.text = "$message. Seleksi sudah di-resize otomatis."
        updateStatus(message)
    }

    private fun runUnwatermarkAutoDetect() {
        val sample = unwatermarkSample
        if (sample == null || sample.isRecycled) {
            Toast.makeText(this, "Pilih PNG sampel watermark terlebih dahulu", Toast.LENGTH_SHORT).show()
            unwatermarkSamplePickerLauncher.launch("*/*")
            return
        }
        val ws = vm.activeWorkspace ?: run {
            Toast.makeText(this, "Buka gambar terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return
        val hint = currentUnwatermarkSelection(layer.bitmap)
        binding.btnDetectUnwm.isEnabled = false
        binding.btnApplyUnwm.isEnabled = false
        binding.unwmProgress.visibility = View.VISIBLE
        binding.tvUnwmHint.text = "Mencari watermark dan ukuran paling mirip…"
        updateStatus("UNWM mendeteksi watermark…")

        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { findBestUnwatermarkMatch(layer.bitmap, hint) }
            }
            binding.btnDetectUnwm.isEnabled = true
            binding.btnApplyUnwm.isEnabled = true
            binding.unwmProgress.visibility = View.GONE
            result.onSuccess { match ->
                if (match == null) {
                    val message = "Watermark belum ditemukan dengan yakin. Buat seleksi kasar di sekitarnya lalu coba lagi."
                    binding.tvUnwmHint.text = message
                    updateStatus(message)
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                } else {
                    unwatermarkSample = match.sample
                    activeUnwatermarkPreset = match.preset
                    match.preset?.let { unwatermarkSampleName = it.name }
                    binding.tvUnwmSampleName.text = unwatermarkSampleName
                    refreshUnwatermarkPresetSpinner()
                    showUnwatermarkDetection(match.detection)
                }
            }.onFailure { error ->
                val message = "Deteksi UNWM gagal: ${error.message ?: "kesalahan pemrosesan"}"
                binding.tvUnwmHint.text = message
                updateStatus(message)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun applyUnwatermarkToSelection() {
        val sample = unwatermarkSample
        if (sample == null || sample.isRecycled) {
            Toast.makeText(this, "Pilih PNG sampel watermark terlebih dahulu", Toast.LENGTH_SHORT).show()
            unwatermarkSamplePickerLauncher.launch("*/*")
            return
        }
        val ws = vm.activeWorkspace ?: run {
            Toast.makeText(this, "Buka gambar terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return
        if (!layer.bitmap.isMutable) {
            Toast.makeText(this, "Layer aktif tidak bisa diedit", Toast.LENGTH_SHORT).show()
            return
        }
        val manualRect = currentUnwatermarkSelection(layer.bitmap)
        val autoFit = binding.switchUnwmAutoAlign.isChecked
        if (manualRect == null && !autoFit) {
            Toast.makeText(this, "Buat seleksi, atau aktifkan Auto-fit", Toast.LENGTH_LONG).show()
            binding.toolRectSelect.performClick()
            return
        }

        clearUnwatermarkPreview()
        val strength = 0.5f + binding.seekUnwmStrength.progress.coerceIn(0, 100) / 100f
        binding.btnDetectUnwm.isEnabled = false
        binding.btnApplyUnwm.isEnabled = false
        binding.unwmProgress.visibility = View.VISIBLE
        binding.tvUnwmHint.text = if (autoFit) {
            "Mencocokkan posisi dan resize watermark sebelum dipulihkan…"
        } else {
            "Memproses area seleksi…"
        }
        updateStatus("UNWM sedang diproses…")
        vm.pushHistory(ws)

        val wasHybridManual = autoFit && unwatermarkSelectionManuallyAdjusted
        lifecycleScope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    // Hybrid rule: automatic detection establishes the first box; a
                    // box explicitly moved/resized by the user is authoritative.
                    val match = if (autoFit && !unwatermarkSelectionManuallyAdjusted) {
                        findBestUnwatermarkMatch(layer.bitmap, manualRect)
                    } else {
                        null
                    }
                    val selectedSample = match?.sample ?: sample
                    val selectedPreset = match?.preset ?: activeUnwatermarkPreset
                    val detection = match?.detection
                    val rect = detection?.rect ?: manualRect
                        ?: throw IllegalStateException("Watermark tidak ditemukan; buat seleksi kasar lalu ulangi")
                    val pixels = rect.width().toLong() * rect.height().toLong()
                    require(!rect.isEmpty && pixels in 1L..6_000_000L) { "Area watermark tidak valid atau terlalu besar" }
                    val adaptive = adaptiveUnwatermarkPreset(
                        layer.bitmap,
                        selectedSample,
                        selectedPreset,
                        rect,
                        strength,
                        preferHintScale = true
                    )
                    val tunedProfileStrength = if (activeUnwatermarkProfile == UnwatermarkEngine.Profile.HTML_UNWATERMARKER_V1_6) {
                        strength
                    } else {
                        adaptive.recommendedStrength
                    }
                    val tunedOptions = UnwatermarkEngine.createOptions(
                        profile = activeUnwatermarkProfile,
                        requestedStrength = tunedProfileStrength,
                        smoothOpaquePixels = binding.switchUnwmSmooth.isChecked,
                        autoAlign = autoFit,
                        adaptiveAlignRadius = adaptive.autoAlignRadius,
                        adaptiveJpegRadius = adaptive.jpegFilterRadius,
                        adaptiveJpegThreshold = adaptive.jpegFilterThreshold,
                        presetAlphaAdjust = selectedPreset?.alphaAdjust ?: 1f,
                        presetJpegEnabled = selectedPreset?.jpegFilterEnabled ?: true,
                        presetAutoTuneStrength = selectedPreset?.autoTuneStrength ?: true
                    )
                    val removal = UnwatermarkEngine.remove(layer.bitmap, selectedSample, rect, tunedOptions)
                    UnwatermarkApplyResult(
                        removal,
                        detection,
                        rect,
                        selectedSample,
                        selectedPreset,
                        wasHybridManual
                    )
                }
            }
            binding.btnDetectUnwm.isEnabled = true
            binding.btnApplyUnwm.isEnabled = true
            binding.unwmProgress.visibility = View.GONE
            result.onSuccess { applied ->
                val info = applied.removal
                val detection = applied.detection
                val rect = applied.rect
                unwatermarkSample = applied.sample
                activeUnwatermarkPreset = applied.preset
                applied.preset?.let { unwatermarkSampleName = it.name }
                binding.tvUnwmSampleName.text = unwatermarkSampleName
                refreshUnwatermarkPresetSpinner()
                if (info.changedPixels > 0) {
                    // Remove the tinted selection overlay so the restored pixels are
                    // immediately visible, including on very tall 800x16000 canvases.
                    binding.canvasView.selection.clear()
                    unwatermarkSelectionManuallyAdjusted = false
                    binding.canvasView.postInvalidateOnAnimation()
                    triggerAutoSave()
                } else if (detection != null) {
                    showUnwatermarkDetection(detection)
                } else {
                    binding.canvasView.invalidate()
                }
                val hasWholeOffset = info.offsetX != 0 || info.offsetY != 0
                val hasSubpixelOffset = abs(info.subpixelOffsetX) >= 0.01f ||
                    abs(info.subpixelOffsetY) >= 0.01f
                val offset = if (hasWholeOffset || hasSubpixelOffset) {
                    String.format(
                        java.util.Locale.US,
                        " • align %d,%dpx • subpixel %.2f,%.2fpx",
                        info.offsetX,
                        info.offsetY,
                        info.subpixelOffsetX,
                        info.subpixelOffsetY
                    )
                } else ""
                val fit = detection?.let { " • auto-fit ${(it.confidence * 100f).roundToInt()}%" }
                    ?: if (applied.wasHybridManual) " • hybrid manual" else ""
                val tunedStrength = if (tunedStrengthChanged(strength, info.appliedStrength)) {
                    String.format(java.util.Locale.US, " • strength otomatis %.2fx", info.appliedStrength)
                } else {
                    ""
                }
                val message = if (info.changedPixels > 0) {
                    "Selesai: ${info.changedPixels} piksel dipulihkan$fit$offset$tunedStrength • area ${rect.width()}×${rect.height()} px. Gunakan Undo jika perlu."
                } else {
                    "Tidak ada piksel watermark terdeteksi. Periksa alpha PNG, Strength, dan seleksi."
                }
                binding.tvUnwmHint.text = message
                updateStatus(message)
            }.onFailure { error ->
                val message = "UNWM gagal: ${error.message ?: "kesalahan pemrosesan"}"
                binding.tvUnwmHint.text = message
                updateStatus(message)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun tunedStrengthChanged(requested: Float, applied: Float): Boolean =
        abs(requested - applied) >= 0.005f

    private fun setupMaskPanel() {
        // Engine selector
        binding.spinnerMaskEngine.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("PP-OCRv6 Small", "ML Kit v2 (offline)")
        )

        // Spinner bahasa
        binding.spinnerMaskLang.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Auto (semua)", "English", "中文", "한국어")
        )

        // The Mask panel intentionally exposes only the two requested engines.
        binding.spinnerMaskOpenCvMode.visibility = View.GONE

        binding.btnCloseMask.setOnClickListener {
            binding.bottomMaskPanel.visibility = View.GONE
        }
        binding.btnMaskDetect.setOnClickListener { runMaskAutoDetect() }
        binding.btnMaskDeleteArea.setOnClickListener {
            val hasRegions = textDetectedRegions.isNotEmpty()
            if (!hasRegions) {
                binding.canvasView.geminiOverlayDeleteMode = false
                binding.btnMaskDeleteArea.text = "Hapus area"
                Toast.makeText(this, "Belum ada region untuk dihapus", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.canvasView.geminiOverlayDeleteMode = !binding.canvasView.geminiOverlayDeleteMode
            binding.btnMaskDeleteArea.text = if (binding.canvasView.geminiOverlayDeleteMode) "Hapus: aktif" else "Hapus area"
            binding.tvMaskStatus.text = if (binding.canvasView.geminiOverlayDeleteMode) {
                "Tap region di kanvas untuk menghapusnya"
            } else {
                "Mode hapus area dimatikan"
            }
        }
        binding.btnMaskReset.setOnClickListener  { resetMaskOverlay() }
        binding.btnMaskToggleList.setOnClickListener {
            maskRegionListExpanded = !maskRegionListExpanded
            refreshMaskRegionList()
        }
        binding.maskRegionListHeader.setOnClickListener {
            maskRegionListExpanded = !maskRegionListExpanded
            refreshMaskRegionList()
        }
        // Aksi mask sengaja memakai pipeline terpisah dari toolbar seleksi biasa.
        binding.btnMaskFillWhite.setOnClickListener { runMaskSolidFill(android.graphics.Color.WHITE) }
        binding.btnMaskFillBlack.setOnClickListener { runMaskSolidFill(android.graphics.Color.BLACK) }
        binding.btnMaskInpaint.visibility = View.VISIBLE
        binding.btnMaskInpaint.setOnClickListener { runMaskRemovR() }

        // This action is now a real detection button. It uses the same detector
        // as the header Detect button and stays enabled even before regions exist.
        binding.btnMaskSelectAll.text = "Deteksi ulang"
        binding.btnMaskSelectAll.isEnabled = true
        binding.btnMaskSelectAll.setOnClickListener { runMaskAutoDetect() }
        binding.btnMaskFillWhite.text = "Isi putih"
        binding.btnMaskFillBlack.text = "Isi hitam"
        binding.btnMaskInpaint.text = "RemovR region"

        binding.tvMaskStatus.text = "Pilih model dan bahasa, lalu mulai deteksi. Setiap hasil dapat ditinjau sebelum diterapkan."
    }

    // Setiap opsi mask memakai detectornya sendiri; pilihan lain tetap independen.

    /**
     * Convert every detected mask region into the canvas selection as a
     * multi-part RECT selection. The user can then apply ANY existing tool
     * (Fill White, SmartFill, Center-in-Bubble) on the whole batch, or
     * fine-tune with the normal selection editing tools.
     */
    private fun maskRegionsToSelection() {
        if (textDetectedRegions.isEmpty()) {
            Toast.makeText(this, "Belum ada region. Tekan Deteksi dulu.", Toast.LENGTH_SHORT).show()
            return
        }
        with(binding.canvasView.selection) {
            clear()
            type = SelectionType.RECT
            isActive = true
            isManualUnion = true
            for (region in textDetectedRegions) {
                addPart(RectF(region.rect))
            }
            // Keep a single bounding rect too, for tools that read `rect`.
            rect.set(getBounds())
        }
        binding.canvasView.invalidate()
        refreshCenterInBubbleBtn()
        val n = textDetectedRegions.size
        binding.tvMaskStatus.text = "✅ $n region dijadikan seleksi — pakai tool apa pun (Fill / SmartFill / teks)."
        updateStatus("$n region → seleksi")
    }

    private fun resetMaskOverlay() {
        textDetectedRegions.clear()
        binding.canvasView.geminiDetectOverlay = emptyList()
        binding.canvasView.geminiOverlayDeleteMode = false
        binding.canvasView.invalidate()
        binding.btnMaskDeleteArea.text = "Hapus area"
        setMaskActionsEnabled(false)
        binding.maskRegionListContainer.removeAllViews()
        maskRegionListExpanded = false
        binding.maskRegionListHeader.visibility = View.GONE
        binding.maskRegionScroll.visibility = View.GONE
        binding.tvMaskStatus.text = "Tekan Deteksi untuk menemukan semua teks di kanvas secara otomatis"
    }

    private fun setMaskActionsEnabled(enabled: Boolean) {
        binding.btnMaskFillWhite.isEnabled = enabled
        binding.btnMaskFillBlack.isEnabled = enabled
        binding.btnMaskInpaint.isEnabled = enabled
        binding.btnMaskDeleteArea.isEnabled = enabled
        if (!enabled) {
            binding.canvasView.geminiOverlayDeleteMode = false
            binding.btnMaskDeleteArea.text = "Hapus area"
        }
        // btnMaskSelectAll is the new Detect button; keep it enabled so users
        // can always start a fresh detection even when no region exists yet.
        binding.btnMaskSelectAll.isEnabled = true
    }

    /**
     * Render ulang daftar region di panel (dipanggil setiap kali textDetectedRegions berubah).
     * Tiap baris: label "#N type" + tombol ❌ untuk hapus region tersebut.
     */
    private fun refreshMaskRegionList() {
        val container = binding.maskRegionListContainer
        container.removeAllViews()

        if (textDetectedRegions.isEmpty()) {
            binding.maskRegionListHeader.visibility = View.GONE
            binding.maskRegionScroll.visibility = View.GONE
            setMaskActionsEnabled(false)
            return
        }

        val regionCount = textDetectedRegions.size
        binding.maskRegionListHeader.visibility = View.VISIBLE
        binding.tvMaskRegionCount.text = "$regionCount kotak baris"
        binding.btnMaskToggleList.text = if (maskRegionListExpanded) "Tutup daftar ▲" else "Lihat daftar ▼"
        binding.maskRegionScroll.visibility = if (maskRegionListExpanded) View.VISIBLE else View.GONE

        // Jangan membangun ratusan View ketika daftar sedang tertutup.
        if (!maskRegionListExpanded) {
            setMaskActionsEnabled(true)
            return
        }

        val dp4  = (4  * resources.displayMetrics.density).toInt()
        val dp28 = (28 * resources.displayMetrics.density).toInt()

        for ((idx, region) in textDetectedRegions.withIndex()) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp4 * 2, dp4, dp4, dp4)
                background = GradientDrawable().apply {
                    cornerRadius = dp4 * 2f
                    setColor(Color.parseColor("#24232C"))
                    setStroke(1, Color.parseColor("#34313D"))
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp4 }
            }

            // Setiap item adalah satu kotak penuh untuk satu baris teks.
            // Fill dan RemovR menerapkan seluruh area kotak, bukan hanya piksel glyph.
            val w = (region.rect.width()).toInt()
            val h = (region.rect.height()).toInt()
            val label = android.widget.TextView(this).apply {
                text = "Area ${idx + 1}  ·  ${w} × ${h} px"
                textSize = 11f
                setTextColor(Color.parseColor("#F2F2F4"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }

            fun removeRegion() {
                if (idx !in textDetectedRegions.indices) return
                textDetectedRegions.removeAt(idx)
                binding.canvasView.geminiDetectOverlay = textDetectedRegions.toList()
                syncDetectedBubbleSelection()
                binding.canvasView.invalidate()
                refreshMaskRegionList()
                val remaining = textDetectedRegions.size
                binding.tvMaskStatus.text = if (remaining > 0)
                    "✅ $remaining region tersisa — pilih aksi di bawah:"
                else
                    "Semua region dihapus. Tekan Deteksi lagi untuk memulai ulang."
            }

            // Klik baris untuk menghapus region
            row.isClickable = true
            row.isFocusable = true
            row.setOnClickListener { removeRegion() }
            label.setOnClickListener { removeRegion() }

            // Tombol hapus per-region
            val delBtn = android.widget.Button(this).apply {
                text = "×"
                textSize = 16f
                contentDescription = "Hapus area ${idx + 1}"
                setTextColor(Color.parseColor("#F87171"))
                background = null
                layoutParams = android.widget.LinearLayout.LayoutParams(dp28, dp28)
                setOnClickListener { removeRegion() }
            }

            row.addView(label)
            row.addView(delBtn)
            container.addView(row)
        }

        setMaskActionsEnabled(true)
    }


    private fun showMaskProgress(message: String, indeterminate: Boolean = false) {
        binding.maskProgressBar.visibility = View.VISIBLE
        binding.tvMaskProgressPct.visibility = View.VISIBLE
        binding.maskProgressBar.isIndeterminate = indeterminate
        binding.maskProgressBar.max = 100
        if (!indeterminate) binding.maskProgressBar.progress = 0
        binding.tvMaskProgressPct.text = if (indeterminate) "…" else "0%"
        binding.tvMaskStatus.text = message
    }

    private fun updateMaskProgress(progress: Float, message: String? = null) {
        val pct = (progress.coerceIn(0f, 1f) * 100f).toInt().coerceIn(0, 100)
        binding.maskProgressBar.post {
            binding.maskProgressBar.visibility = View.VISIBLE
            binding.tvMaskProgressPct.visibility = View.VISIBLE
            binding.maskProgressBar.isIndeterminate = false
            binding.maskProgressBar.progress = pct
            binding.tvMaskProgressPct.text = "$pct%"
            if (message != null) binding.tvMaskStatus.text = message
        }
    }

    private fun hideMaskProgress() {
        binding.maskProgressBar.visibility = View.GONE
        binding.tvMaskProgressPct.visibility = View.GONE
    }

    /** Deteksi teks non-OCR untuk overlay masker. */

    private fun runPaddleDbNetAutoDetect() = runMaskAutoDetect()

    private fun runMaskAutoDetect() {
        val ws = vm.activeWorkspace ?: return
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return

        if (maskDetectionJob?.isActive == true) {
            Toast.makeText(this, "Deteksi mask masih berjalan", Toast.LENGTH_SHORT).show()
            return
        }

        val engine = maskEngineCodes.getOrNull(binding.spinnerMaskEngine.selectedItemPosition) ?: "ppocr_small"
        val lang = maskLangCodes.getOrNull(binding.spinnerMaskLang.selectedItemPosition) ?: "auto"
        val engineLabel = when (engine) {
            "ppocr_small" -> "PP-OCRv6 Small"
            "mlkit_v2" -> "ML Kit v2"
            else -> engine
        }

        if (maskDetectionJob?.isActive == true) return

        showMaskProgress("Mendeteksi $engineLabel…", indeterminate = true)
        binding.btnMaskDetect.isEnabled = false
        binding.btnMaskSelectAll.isEnabled = false

        maskDetectionJob = lifecycleScope.launch {
            var composite: Bitmap? = null
            try {
                composite = withContext(Dispatchers.IO) {
                    try {
                        binding.canvasView.compositeVisibleLayers()
                    } catch (_: OutOfMemoryError) {
                        System.gc()
                        null
                    }
                }
                val source = composite ?: layer.bitmap
                if (source.isRecycled || source.width <= 0 || source.height <= 0) {
                    updateStatus("Gagal: kanvas kosong atau memori tidak cukup")
                    return@launch
                }

                // Jangan campur hasil antarmodel. Sebelumnya semua opsi disaring dan
                // bahkan diganti dengan kotak ML Kit, sehingga pilihan engine tidak
                // benar-benar terpisah dan batas bubble ikut menjadi mask.
                val supportRects = emptyList<RectF>()

                val outcome = detectMaskRegions(engine, source, lang)
                if (outcome.error != null && outcome.regions.isEmpty()) {
                    val msg = outcome.error
                    binding.tvMaskStatus.text = msg
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val filtered = postProcessMaskRegions(source, outcome.regions, engine, supportRects)

                textDetectedRegions.clear()
                textDetectedRegions.addAll(filtered)
                binding.canvasView.geminiDetectOverlay = filtered
                binding.canvasView.geminiOverlayDeleteMode = false
                binding.canvasView.invalidate()
                refreshMaskRegionList()
                setMaskActionsEnabled(filtered.isNotEmpty())

                if (filtered.isEmpty()) {
                    binding.tvMaskStatus.text = "Tidak ada teks terdeteksi"
                    Toast.makeText(this@MainActivity, "Tidak ada teks terdeteksi", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = "${filtered.size} kotak baris terdeteksi; mask menutup seluruh region"
                    binding.tvMaskStatus.text = msg
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                updateStatus("Deteksi mask gagal: ${t.message ?: "kesalahan runtime"}")
                Toast.makeText(this@MainActivity, "Deteksi mask gagal", Toast.LENGTH_SHORT).show()
            } finally {
                val renderedComposite = composite
                if (renderedComposite != null &&
                    renderedComposite !== layer.bitmap &&
                    !renderedComposite.isRecycled
                ) {
                    renderedComposite.recycle()
                }
                hideMaskProgress()
                binding.btnMaskDetect.isEnabled = true
                binding.btnMaskSelectAll.isEnabled = true
                maskDetectionJob = null
            }
        }
    }

    private data class MaskDetectOutcome(
        val regions: List<PaddleDbNetDetector.DetectedRegion>,
        val error: String? = null
    )

    private suspend fun detectMaskRegions(
        engine: String,
        bitmap: Bitmap,
        lang: String
    ): MaskDetectOutcome = withContext(Dispatchers.IO) {
        when (engine) {
            "ppocr_small" -> {
                val detectStart = SystemClock.elapsedRealtime()
                if (!PpOcrSmallDetector.isAvailable(this@MainActivity)) {
                    val err = PpOcrSmallDetector.healthMessage(this@MainActivity)
                    MaskProfiling.logError("PP-OCRv6 Small", IllegalStateException(err), "size=${bitmap.width}x${bitmap.height}")
                    return@withContext MaskDetectOutcome(emptyList(), err)
                }
                val regions = try {
                    withTimeout(75_000L) {
                        PpOcrSmallDetector.detectStriped(this@MainActivity, bitmap)
                    }
                } catch (t: Throwable) {
                    MaskProfiling.logError("PP-OCRv6 Small", t, "size=${bitmap.width}x${bitmap.height}")
                    return@withContext MaskDetectOutcome(emptyList(), t.message ?: "PP-OCRv6 Small error")
                }
                val detectMs = SystemClock.elapsedRealtime() - detectStart
                MaskProfiling.log("PP-OCRv6-small-det", "${bitmap.width}x${bitmap.height}", "detect" to detectMs, regions = regions.size)
                val runtimeError = PpOcrSmallDetector.lastError().takeIf { regions.isEmpty() }
                MaskDetectOutcome(regions, runtimeError)
            }

            "mlkit_v2" -> {
                val detectStart = SystemClock.elapsedRealtime()
                val detected = try {
                    withTimeout(60_000) {
                        MlKitMaskDetector.detectSuspend(bitmap, lang)
                    }
                } catch (t: Throwable) {
                    MaskProfiling.logError("ML Kit v2", t, "size=${bitmap.width}x${bitmap.height}")
                    return@withContext MaskDetectOutcome(emptyList(), t.message ?: "ML Kit v2 error")
                }
                if (detected.isEmpty()) {
                    return@withContext MaskDetectOutcome(
                        emptyList(),
                        "Tidak ada teks terdeteksi oleh ML Kit v2"
                    )
                }

                // Gunakan ROI baris ML Kit langsung. Refinement OpenCV per-region
                // sebelumnya memindai piksel untuk setiap baris dan menjadi bottleneck
                // terbesar pada halaman panjang. Grouping per kalimat dilakukan sekali
                // di postProcessMaskRegions tanpa mengorbankan dukungan aksara.
                val regions = detected.map {
                    PaddleDbNetDetector.DetectedRegion(RectF(it.rect), "text", it.text)
                }
                val detectMs = SystemClock.elapsedRealtime() - detectStart
                MaskProfiling.log(
                    "ML Kit v2",
                    "${bitmap.width}x${bitmap.height}",
                    "detect+refine" to detectMs,
                    regions = regions.size
                )
                MaskDetectOutcome(regions)
            }

            else -> {
                MaskDetectOutcome(emptyList(), "Unknown mask engine: $engine")
            }
        }
    }


    private fun mergeDetectedRegions(regions: List<PaddleDbNetDetector.DetectedRegion>): List<PaddleDbNetDetector.DetectedRegion> {
        if (regions.isEmpty()) return emptyList()
        val out = mutableListOf<PaddleDbNetDetector.DetectedRegion>()
        val sorted = regions.sortedBy { it.rect.top }
        for (r in sorted) {
            var merged = false
            for (i in out.indices.reversed()) {
                val o = out[i].rect
                if (o.bottom + 8f < r.rect.top) break
                if (iou(o, r.rect) > 0.15f || closeEnough(o, r.rect)) {
                    out[i] = PaddleDbNetDetector.DetectedRegion(RectF(minOf(o.left, r.rect.left), minOf(o.top, r.rect.top), maxOf(o.right, r.rect.right), maxOf(o.bottom, r.rect.bottom)), r.type, r.text)
                    merged = true
                    break
                }
            }
            if (!merged) out.add(r)
        }
        return out
    }

    private fun closeEnough(a: RectF, b: RectF): Boolean {
        val padX = maxOf(8f, minOf(a.width(), b.width()) / 4f)
        val padY = maxOf(6f, minOf(a.height(), b.height()) / 4f)
        return a.left - padX < b.right && a.right + padX > b.left && a.top - padY < b.bottom && a.bottom + padY > b.top
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val iw = maxOf(0f, right - left)
        val ih = maxOf(0f, bottom - top)
        val inter = iw * ih
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    /**
     * Menyaring noise geometri tanpa mencampurkan output antarmodel. Region yang
     * berdekatan hanya digabung pada level baris/paragraf teks; batas bubble tidak
     * pernah dipakai oleh pipeline mask.
     */
    private fun postProcessMaskRegions(
        bitmap: Bitmap,
        raw: List<PaddleDbNetDetector.DetectedRegion>,
        engine: String,
        supportRects: List<RectF>
    ): List<PaddleDbNetDetector.DetectedRegion> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()

        val maxW = bitmap.width.toFloat()
        val maxH = bitmap.height.toFloat()
        val minHeight = maxOf(6f, maxW * 0.006f)
        val minArea = maxOf(48f, maxW * maxW * 0.00008f)

        fun hasTextContrast(rect: RectF): Boolean {
            val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
            val top = rect.top.toInt().coerceIn(0, bitmap.height - 1)
            val right = rect.right.toInt().coerceIn(left + 1, bitmap.width)
            val bottom = rect.bottom.toInt().coerceIn(top + 1, bitmap.height)
            val stepX = maxOf(1, (right - left) / 12)
            val stepY = maxOf(1, (bottom - top) / 8)
            var minLum = 255
            var maxLum = 0
            var dark = 0
            var count = 0
            var y = top
            while (y < bottom) {
                var x = left
                while (x < right) {
                    val lum = BubbleCleaner.luminance(bitmap.getPixel(x, y))
                    minLum = minOf(minLum, lum)
                    maxLum = maxOf(maxLum, lum)
                    if (lum < 190) dark++
                    count++
                    x += stepX
                }
                y += stepY
            }
            if (count == 0) return false
            val darkRatio = dark.toFloat() / count.toFloat()
            return maxLum - minLum >= 34 && darkRatio in 0.015f..0.92f
        }

        val supportCandidates = supportRects
            .mapNotNull { rect ->
                val r = RectF(
                    rect.left.coerceIn(0f, maxW),
                    rect.top.coerceIn(0f, maxH),
                    rect.right.coerceIn(0f, maxW),
                    rect.bottom.coerceIn(0f, maxH)
                )
                if (r.width() < 4f || r.height() < 4f) return@mapNotNull null
                PaddleDbNetDetector.DetectedRegion(r, "text", "")
            }
            .let { mergeDetectedRegions(it) }

        fun supportScore(rect: RectF): Float {
            var best = 0f
            for (support in supportCandidates) {
                val s = support.rect
                val score = iou(rect, s)
                if (score > best) best = score
                if (support.rect.contains(rect.centerX(), rect.centerY())) {
                    best = maxOf(best, 0.22f)
                }
                if (rect.contains(s.centerX(), s.centerY())) {
                    best = maxOf(best, 0.18f)
                }
            }
            return best
        }

        val candidates = raw.mapNotNull { item ->
            val r = RectF(
                item.rect.left.coerceIn(0f, maxW),
                item.rect.top.coerceIn(0f, maxH),
                item.rect.right.coerceIn(0f, maxW),
                item.rect.bottom.coerceIn(0f, maxH)
            )
            val area = r.width() * r.height()
            val aspect = r.width() / r.height().coerceAtLeast(1f)
            if (r.width() < 4f || r.height() < minHeight || area < minArea) return@mapNotNull null
            if (r.width() > maxW * 0.96f || r.height() > maxW * 0.65f) return@mapNotNull null
            if (aspect !in 0.12f..28f) return@mapNotNull null

            val contrast = hasTextContrast(r)

            val keep = when (engine) {
                // Kedua ONNX detector sudah menghasilkan probability-map teks.
                // Menjalankan filter sampling kontras kasar sekali lagi membuang
                // tulisan tipis/berwarna dan membuat hasil valid terlihat kosong.
                "ppocr_small" -> true
                "mlkit_v2" -> contrast
                else -> contrast
            }

            if (!keep) return@mapNotNull null
            PaddleDbNetDetector.DetectedRegion(r, "text", item.text)
        }.sortedWith(compareBy<PaddleDbNetDetector.DetectedRegion> { it.rect.top }.thenBy { it.rect.left })

        val strictCandidates = if (candidates.isNotEmpty()) candidates else {
            // When support exists but raw engine is noisy or empty, preserve the
            // grouped support boxes instead of returning nothing.
            supportCandidates.mapNotNull { item ->
                val r = item.rect
                if (r.width() < 4f || r.height() < minHeight) return@mapNotNull null
                val area = r.width() * r.height()
                if (area < minArea) return@mapNotNull null
                if (hasTextContrast(r)) {
                    PaddleDbNetDetector.DetectedRegion(RectF(r), "text", "")
                } else null
            }.sortedWith(compareBy<PaddleDbNetDetector.DetectedRegion> { it.rect.top }.thenBy { it.rect.left })
        }

        if (strictCandidates.isEmpty()) return emptyList()

        // Hilangkan duplikat dari overlap antar strip detector.
        val deduped = mutableListOf<PaddleDbNetDetector.DetectedRegion>()
        for (candidate in strictCandidates) {
            val duplicate = deduped.any { existing ->
                iou(existing.rect, candidate.rect) >= 0.55f ||
                    (existing.rect.contains(candidate.rect.centerX(), candidate.rect.centerY()) &&
                        candidate.rect.width() <= existing.rect.width() * 1.15f &&
                        candidate.rect.height() <= existing.rect.height() * 1.15f)
            }
            if (!duplicate) deduped.add(candidate)
        }

        // Satukan box kata hanya jika benar-benar berada pada baris visual yang sama.
        // Baris bertumpuk tidak pernah digabung, sehingga satu kalimat multi-baris tetap
        // menghasilkan beberapa kotak independen dan bukan satu bubble besar.
        data class LineGroup(
            val members: MutableList<PaddleDbNetDetector.DetectedRegion>,
            val bounds: RectF
        )

        fun belongsToSameLine(bounds: RectF, candidate: RectF): Boolean {
            val overlapY = maxOf(0f, minOf(bounds.bottom, candidate.bottom) - maxOf(bounds.top, candidate.top))
            val minHeightForOverlap = minOf(bounds.height(), candidate.height()).coerceAtLeast(1f)
            val centerDelta = kotlin.math.abs(bounds.centerY() - candidate.centerY())
            val maxHeight = maxOf(bounds.height(), candidate.height()).coerceAtLeast(1f)
            val gapX = maxOf(0f, maxOf(bounds.left, candidate.left) - minOf(bounds.right, candidate.right))
            return overlapY / minHeightForOverlap >= 0.55f &&
                centerDelta <= maxHeight * 0.38f &&
                gapX <= maxOf(10f, maxHeight * 1.35f)
        }

        val lineGroups = mutableListOf<LineGroup>()
        for (candidate in deduped.sortedWith(
            compareBy<PaddleDbNetDetector.DetectedRegion> { it.rect.centerY() }
                .thenBy { it.rect.left }
        )) {
            val target = lineGroups
                .filter { belongsToSameLine(it.bounds, candidate.rect) }
                .minByOrNull { kotlin.math.abs(it.bounds.centerY() - candidate.rect.centerY()) }
            if (target == null) {
                lineGroups += LineGroup(mutableListOf(candidate), RectF(candidate.rect))
            } else {
                target.members += candidate
                target.bounds.union(candidate.rect)
            }
        }

        val result = lineGroups.mapNotNull { group ->
            val bounds = RectF(group.bounds)
            if (group.members.size == 1 && bounds.width() < 12f && bounds.height() < 12f) {
                return@mapNotNull null
            }

            // Kotak hanya sedikit lebih besar daripada hasil OCR: cukup untuk halo
            // antialias, tetapi tidak melebar hingga menyentuh baris atau outline bubble.
            val padX = (bounds.height() * 0.12f).coerceIn(1f, 6f)
            val padY = (bounds.height() * 0.08f).coerceIn(1f, 4f)
            bounds.inset(-padX, -padY)
            if (!bounds.intersect(0f, 0f, maxW, maxH)) return@mapNotNull null

            val lineText = group.members
                .sortedBy { it.rect.left }
                .map { it.text.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(" ")
            PaddleDbNetDetector.DetectedRegion(bounds, "text_line_box", lineText)
        }

        return result.sortedWith(
            compareBy<PaddleDbNetDetector.DetectedRegion> { it.rect.top }
                .thenBy { it.rect.left }
        ).take(200)
    }

    private fun snapshotActiveMaskRegions() {
        val ws = vm.activeWorkspace ?: return
        workspaceMaskStates[ws.id] = textDetectedRegions.map {
            PaddleDbNetDetector.DetectedRegion(RectF(it.rect), it.type, it.text)
        }.toMutableList()
    }

    private fun restoreMaskRegionsFor(ws: Workspace) {
        textDetectedRegions.clear()
        textDetectedRegions.addAll(
            workspaceMaskStates[ws.id].orEmpty().map {
                PaddleDbNetDetector.DetectedRegion(RectF(it.rect), it.type, it.text)
            }
        )
        binding.canvasView.geminiDetectOverlay = textDetectedRegions.toList()
        binding.canvasView.geminiOverlayDeleteMode = false
        maskRegionListExpanded = false
        refreshMaskRegionList()
        setMaskActionsEnabled(textDetectedRegions.isNotEmpty())
    }

    private fun syncDetectedBubbleSelection() {
        val bubbles = textDetectedRegions.filter { it.type == "bubble" }
        if (bubbles.size != textDetectedRegions.size) return
        with(binding.canvasView.selection) {
            clear()
            if (bubbles.isEmpty()) return@with
            type = SelectionType.RECT
            isActive = true
            isManualUnion = true
            bubbles.forEach { addPart(RectF(it.rect)) }
            rect.set(getBounds())
        }
    }

    private fun runBubbleDetect() {
        val ws = vm.activeWorkspace ?: return
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return

        // Cegah beberapa pemindaian OpenCV berjalan bersamaan ketika tombol ditekan cepat.
        if (bubbleDetectionJob?.isActive == true) {
            Toast.makeText(this, "Deteksi bubble masih berjalan", Toast.LENGTH_SHORT).show()
            return
        }

        val modeLabel = "YOLOv8m"
        updateStatus("Mendeteksi bubble ($modeLabel)…")
        binding.toolBubbleDetect.isEnabled = false
        bubbleDetectionJob = lifecycleScope.launch {
            var composite: Bitmap? = null
            try {
                composite = withContext(Dispatchers.IO) {
                    try {
                        binding.canvasView.compositeVisibleLayers()
                    } catch (_: OutOfMemoryError) {
                        System.gc()
                        null
                    }
                }
                val source = composite ?: layer.bitmap
                if (source.isRecycled || source.width <= 0 || source.height <= 0) {
                    updateStatus("Gagal: kanvas kosong atau memori tidak cukup")
                    return@launch
                }

                // Bubble utama: YOLOv8m murni (Drive user, tiling grid 1200/300 di
                // dalam detectTiled). Fallback ke OpenCV+ML Kit bila model kosong.
                val detections = withContext(Dispatchers.Default) {
                    val yolo = runCatching {
                        YoloV8mBubbleDetector.detect(this@MainActivity, source).map {
                            BubbleDetection(
                                bounds = RectF(it.rect),
                                confidence = it.confidence,
                                source = BubbleDetection.Source.YOLO_V8M
                            )
                        }
                    }.getOrNull()
                    if (!yolo.isNullOrEmpty()) yolo else BubbleDetector.detectHybrid(
                        source,
                        BubbleDetectionConfig(),
                        "auto"
                    )
                }
                val regions = detections.map { it.bounds }

                if (regions.isEmpty()) {
                    textDetectedRegions.clear()
                    binding.canvasView.geminiDetectOverlay = emptyList()
                    binding.canvasView.selection.clear()
                    refreshMaskRegionList()
                    binding.canvasView.invalidate()
                    updateStatus("Tidak ada bubble terdeteksi")
                    Toast.makeText(this@MainActivity, "Tidak ada bubble terdeteksi", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val detected = detections.map { item ->
                    PaddleDbNetDetector.DetectedRegion(
                        RectF(item.bounds),
                        "bubble",
                        "${(item.confidence * 100f).toInt()}%"
                    )
                }

                textDetectedRegions.clear()
                textDetectedRegions.addAll(detected)
                binding.canvasView.geminiDetectOverlay = detected
                syncDetectedBubbleSelection()
                maskRegionListExpanded = true
                refreshMaskRegionList()
                binding.bottomMaskPanel.visibility = View.VISIBLE
                binding.tvMaskStatus.text = "${detected.size} region bubble ($modeLabel) — tap item/✕ untuk menghapus"
                binding.canvasView.invalidate()
                refreshCenterInBubbleBtn()

                val averageConfidence = detections.map { it.confidence }.average().toIntPercent()
                val message = "${regions.size} bubble terdeteksi via $modeLabel (keyakinan rata-rata $averageConfidence%)"
                updateStatus(message)
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                updateStatus("Deteksi bubble gagal: ${t.message ?: "kesalahan model lokal"}")
                Toast.makeText(this@MainActivity, "Deteksi bubble gagal", Toast.LENGTH_SHORT).show()
            } finally {
                val renderedComposite = composite
                if (renderedComposite != null &&
                    renderedComposite !== layer.bitmap &&
                    !renderedComposite.isRecycled
                ) {
                    renderedComposite.recycle()
                }
                binding.toolBubbleDetect.isEnabled = true
            }
        }
    }

    private fun Double.toIntPercent(): Int =
        if (isNaN()) 0 else (this * 100.0).toInt().coerceIn(0, 100)

    /**
     * Isi hanya piksel glyph di dalam ROI detector. Kotak detector tidak pernah
     * dijadikan mask fill, sehingga background dan outline balon tetap utuh.
     * TextMaskEngine juga membuang komponen panjang yang menyentuh tepi ROI.
     */
    private fun applyTextMaskOnly(
        sourceBitmap: Bitmap,
        targetBitmap: Bitmap,
        rect: Rect,
        fillColor: Int,
        bleedPx: Int = 1
    ): Boolean {
        if (rect.isEmpty || sourceBitmap.isRecycled || targetBitmap.isRecycled) return false
        if (!targetBitmap.isMutable) return false

        val clipped = Rect(
            rect.left.coerceIn(0, minOf(sourceBitmap.width, targetBitmap.width)),
            rect.top.coerceIn(0, minOf(sourceBitmap.height, targetBitmap.height)),
            rect.right.coerceIn(0, minOf(sourceBitmap.width, targetBitmap.width)),
            rect.bottom.coerceIn(0, minOf(sourceBitmap.height, targetBitmap.height))
        )
        if (clipped.isEmpty) return false

        // Fast path: keep the precise glyph mask as a compact BooleanArray and
        // write one ROI pixel buffer. Converting every row to android.graphics.Region
        // and issuing hundreds of Canvas draw calls was the main Fill bottleneck.
        val mask = TextMaskEngine.detectTextMask(
            context = this,
            bitmap = sourceBitmap,
            searchRect = clipped,
            paddingPx = bleedPx.coerceIn(0, 3)
        ) ?: return false
        val width = clipped.width()
        val height = clipped.height()
        if (mask.size != width * height) return false

        val pixels = IntArray(mask.size)
        targetBitmap.getPixels(pixels, 0, width, clipped.left, clipped.top, width, height)
        var changed = 0
        for (index in pixels.indices) {
            if (mask[index] && pixels[index] != fillColor) {
                pixels[index] = fillColor
                changed++
            }
        }
        if (changed == 0) return false
        targetBitmap.setPixels(pixels, 0, width, clipped.left, clipped.top, width, height)
        return true
    }

    /** Compact precomputed mask used by the Mask panel on very tall canvases. */
    private data class TextMaskPatch(val rect: Rect, val pixels: BooleanArray)

    private fun detectTextMaskPatch(source: Bitmap, requested: Rect, bleedPx: Int = 1): TextMaskPatch? {
        // A small search margin keeps edge glyphs from being mistaken for a bubble
        // border. It does not enlarge the output mask: only detected glyph pixels
        // inside this ROI are written or inpainted.
        val searchMargin = 4
        val rect = Rect(
            (requested.left - searchMargin).coerceIn(0, source.width),
            (requested.top - searchMargin).coerceIn(0, source.height),
            (requested.right + searchMargin).coerceIn(0, source.width),
            (requested.bottom + searchMargin).coerceIn(0, source.height)
        )
        if (rect.isEmpty) return null
        // Detector boxes already localise text, so try the direct-pixel path first.
        // OpenCV segmentation remains a compatibility fallback for unusual glyphs.
        val mask = TextMaskEngine.detectFastTextMask(
            bitmap = source,
            searchRect = rect,
            paddingPx = bleedPx.coerceIn(0, 2)
        ) ?: TextMaskEngine.detectTextMask(
            context = this,
            bitmap = source,
            searchRect = rect,
            paddingPx = bleedPx.coerceIn(0, 3)
        ) ?: return null
        return if (mask.size == rect.width() * rect.height()) TextMaskPatch(rect, mask) else null
    }

    private fun applyTextMaskPatch(target: Bitmap, patch: TextMaskPatch, fillColor: Int): Boolean {
        if (target.isRecycled || !target.isMutable || patch.rect.isEmpty) return false
        val width = patch.rect.width()
        val height = patch.rect.height()
        if (patch.pixels.size != width * height) return false
        val pixels = IntArray(patch.pixels.size)
        target.getPixels(pixels, 0, width, patch.rect.left, patch.rect.top, width, height)
        var changed = false
        for (index in pixels.indices) {
            if (patch.pixels[index] && pixels[index] != fillColor) {
                pixels[index] = fillColor
                changed = true
            }
        }
        if (changed) {
            target.setPixels(pixels, 0, width, patch.rect.left, patch.rect.top, width, height)
        }
        return changed
    }

    @Suppress("unused")
    private fun applyTextMaskOnlyLegacy(
        sourceBitmap: Bitmap,
        targetBitmap: Bitmap,
        rect: Rect,
        fillColor: Int,
        bleedPx: Int = 1
    ): Boolean {
        if (rect.isEmpty) return false
        val left = rect.left.coerceIn(0, sourceBitmap.width)
        val top = rect.top.coerceIn(0, sourceBitmap.height)
        val right = rect.right.coerceIn(0, sourceBitmap.width)
        val bottom = rect.bottom.coerceIn(0, sourceBitmap.height)
        if (right <= left || bottom <= top) return false

        val rw = right - left
        val rh = bottom - top
        val src = IntArray(rw * rh)
        sourceBitmap.getPixels(src, 0, rw, left, top, rw, rh)

        val mask = BooleanArray(rw * rh)
        var hit = 0
        for (y in 0 until rh) {
            val row = y * rw
            for (x in 0 until rw) {
                val i = row + x
                val px = src[i]
                if (Color.alpha(px) < 128) continue
                val lum = BubbleCleaner.luminance(px)
                var minL = 255
                var maxL = 0
                for (ny in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(rh - 1)) {
                    val nRow = ny * rw
                    for (nx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(rw - 1)) {
                        val nl = BubbleCleaner.luminance(src[nRow + nx])
                        if (nl < minL) minL = nl
                        if (nl > maxL) maxL = nl
                    }
                }
                val contrast = maxL - minL
                // Wajib ada kontras lokal. Ambang lama menerima semua pixel gelap
                // (termasuk ilustrasi di belakang teks), lalu menghasilkan blok putih.
                val likelyText = (lum < 110 && contrast >= 10) ||
                    (lum < 185 && contrast >= 18) ||
                    (lum < 220 && contrast >= 32)
                if (likelyText) {
                    mask[i] = true
                    hit++
                }
            }
        }
        if (hit == 0) return false

        // Buang komponen yang menyentuh tepi ROI. Garis speech-bubble biasanya
        // masuk dari tepi kotak detector, sedangkan glyph teks berada di dalam.
        // Tanpa tahap ini, dilasi mask mengubah outline bubble menjadi putih dan
        // menyisakan artifact tipis di sekeliling area yang dibersihkan.
        val textOnlyMask = BooleanArray(mask.size)
        val visited = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var head = 0
            var tail = 0
            var touchesEdge = false
            var minX = rw
            var maxX = 0
            var minY = rh
            var maxY = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val pos = queue[head++]
                val x = pos % rw
                val y = pos / rw
                minX = minOf(minX, x)
                maxX = maxOf(maxX, x)
                minY = minOf(minY, y)
                maxY = maxOf(maxY, y)
                if (x <= 1 || y <= 1 || x >= rw - 2 || y >= rh - 2) touchesEdge = true
                for (ny in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(rh - 1)) {
                    val nRow = ny * rw
                    for (nx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(rw - 1)) {
                        val next = nRow + nx
                        if (mask[next] && !visited[next]) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
            }
            val componentW = maxX - minX + 1
            val componentH = maxY - minY + 1
            val spansRegion = componentW >= (rw * 0.90f).toInt() || componentH >= (rh * 0.90f).toInt()
            if (!touchesEdge && !spansRegion && tail >= 2) {
                for (i in 0 until tail) textOnlyMask[queue[i]] = true
            }
        }
        if (!textOnlyMask.any { it }) return false

        // 2 px cukup untuk menutup anti-alias glyph setelah komponen garis bubble
        // dipisahkan. Nilai lebih besar mudah menjangkau outline di ROI sempit.
        val bleed = bleedPx.coerceIn(0, 2)
        val expanded = if (bleed == 0) textOnlyMask else {
            var current = textOnlyMask.copyOf()
            repeat(bleed) {
                val out = current.copyOf()
                for (y in 0 until rh) {
                    val row = y * rw
                    for (x in 0 until rw) {
                        if (!current[row + x]) continue
                        for (ny in (y - 1).coerceAtLeast(0)..(y + 1).coerceAtMost(rh - 1)) {
                            val nRow = ny * rw
                            for (nx in (x - 1).coerceAtLeast(0)..(x + 1).coerceAtMost(rw - 1)) {
                                out[nRow + nx] = true
                            }
                        }
                    }
                }
                current = out
            }
            current
        }

        val dst = IntArray(rw * rh)
        targetBitmap.getPixels(dst, 0, rw, left, top, rw, rh)
        var changed = 0
        for (i in dst.indices) {
            if (expanded[i]) {
                dst[i] = fillColor
                changed++
            }
        }
        if (changed > 0) {
            targetBitmap.setPixels(dst, 0, rw, left, top, rw, rh)
            return true
        }
        return false
    }

    /**
     * Fill White/Black untuk panel Mask. Setiap hasil deteksi adalah satu kotak baris
     * yang sudah diberi padding kecil; seluruh kotak diisi, bukan hanya piksel glyph.
     */
    private fun runMaskSolidFill(fillColor: Int) {
        if (textDetectedRegions.isEmpty()) return
        val ws = vm.activeWorkspace ?: return
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return
        if (!layer.bitmap.isMutable) {
            layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val regions = textDetectedRegions.map { RectF(it.rect) }
        if (regions.isEmpty()) return
        val label = if (fillColor == Color.WHITE) "Isi Putih" else "Isi Hitam"

        vm.pushHistory(ws)
        setMaskActionsEnabled(false)
        binding.btnMaskDetect.isEnabled = false
        showMaskProgress("$label: mengisi ${regions.size} kotak baris…", indeterminate = true)

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    BoxMaskWhiteFiller.fill(
                        targetBitmap = layer.bitmap,
                        regions = regions,
                        fillColor = fillColor,
                        paddingPx = 0,
                        dilationPx = 0
                    )
                }
                binding.canvasView.invalidate()
                val msg = "$label selesai: ${result.validRegions}/${regions.size} kotak baris, " +
                    "${result.changedPixels} piksel diisi"
                updateStatus(msg)
                binding.tvMaskStatus.text = msg
            } catch (t: Throwable) {
                val msg = "Gagal $label: ${t.message ?: "unknown error"}"
                updateStatus(msg)
                binding.tvMaskStatus.text = msg
            } finally {
                hideMaskProgress()
                binding.btnMaskDetect.isEnabled = true
                setMaskActionsEnabled(textDetectedRegions.isNotEmpty())
            }
        }
    }


    private fun smartFillBackendLabel(backend: SmartFillBackend): String = when (backend) {
        SmartFillBackend.LAMA_MANGA -> "LaMa Manga"
        SmartFillBackend.OPENCV_PATCH -> "OpenCV Fast Inpaint"
        @Suppress("DEPRECATION")
        SmartFillBackend.NAVIER_STOKES -> "OpenCV Fast Inpaint"
        SmartFillBackend.AGNES_IMAGE_AI -> "Agnes Image 2.1 Flash"
        SmartFillBackend.IDEOGRAM_AI -> "Ideogram (Nexray)"
    }

    private fun showSmartFillChooser(onSelected: (SmartFillBackend) -> Unit) {
        val lamaMangaReady = ModelDownloader.isLamaMangaReady(this)
        val db = com.vasiliastyper.databinding.DialogSmartfillChooserBinding.inflate(layoutInflater)
        db.tvSmartFillStatus.text = if (lamaMangaReady) {
            "LaMa Manga tersedia · dioptimalkan untuk manga/anime"
        } else {
            "LaMa Manga belum ada · akan fallback ke OpenCV Patch"
        }
        db.tvLamaMangaDesc.text = if (lamaMangaReady) {
            "Model tersedia dan siap dipakai untuk hasil yang lebih natural."
        } else {
            "Letakkan model di assets/models/lama_manga/v1/ atau gunakan engine lain."
        }

        val dialog = showBottomSheetDialog {
            setContentView(db.root)
        }

        db.btnLamaManga.setOnClickListener {
            if (!lamaMangaReady) {
                Toast.makeText(this, "lama-manga-dynamic.onnx tidak ditemukan. Letakkan di assets/models/lama_manga/v1/ atau unduh model.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            onSelected(SmartFillBackend.LAMA_MANGA)
        }
        db.btnNavier.setOnClickListener {
            dialog.dismiss()
            onSelected(SmartFillBackend.OPENCV_PATCH)
        }
        db.btnAgnes.setOnClickListener {
            if (!AgnesAiSettings.isConfigured(this)) {
                dialog.dismiss()
                showAgnesSettingsDialog { onSelected(SmartFillBackend.AGNES_IMAGE_AI) }
            } else {
                dialog.dismiss()
                onSelected(SmartFillBackend.AGNES_IMAGE_AI)
            }
        }
        db.btnIdeogram.setOnClickListener {
            dialog.dismiss()
            showIdeogramPromptDialog { onSelected(SmartFillBackend.IDEOGRAM_AI) }
        }
        db.cardLamaManga.setOnClickListener { db.btnLamaManga.performClick() }
        db.cardNavier.setOnClickListener { db.btnNavier.performClick() }
        db.cardAgnes.setOnClickListener { db.btnAgnes.performClick() }
        db.cardIdeogram.setOnClickListener { db.btnIdeogram.performClick() }
        db.btnSmartFillCancel.setOnClickListener { dialog.dismiss() }
    }

    private fun showAgnesSettingsDialog(onSaved: (() -> Unit)? = null) {
        val current = AgnesAiSettings.load(this)
        val db = com.vasiliastyper.databinding.DialogAgnesSettingsBinding.inflate(layoutInflater)
        db.etAgnesApiKey.hint = if (current.apiKey.isBlank()) {
            "Agnes AI API key"
        } else {
            "New API key (leave blank to keep current)"
        }
        db.etAgnesPrompt.setText(current.prompt)

        val dialog = showBottomSheetDialog {
            setContentView(db.root)
        }
        db.btnAgnesSave.setOnClickListener {
            val resolvedKey = db.etAgnesApiKey.text.toString().trim().ifBlank { current.apiKey }
            if (resolvedKey.isBlank()) {
                Toast.makeText(this, "Agnes AI API key is required", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            AgnesAiSettings.save(
                this,
                resolvedKey,
                db.etAgnesPrompt.text.toString()
            )
            dialog.dismiss()
            Toast.makeText(this, "Agnes AI configuration saved", Toast.LENGTH_SHORT).show()
            onSaved?.invoke()
        }
        db.btnAgnesCancel.setOnClickListener { dialog.dismiss() }
    }

    private fun showIdeogramPromptDialog(onConfirmed: () -> Unit) {
        val promptInput = EditText(this).apply {
            setText(IdeogramSettings.getPrompt(this@MainActivity))
            hint = "Prompt Ideogram"
            minLines = 3
            maxLines = 7
            gravity = Gravity.TOP or Gravity.START
            setPadding(32, 24, 32, 24)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 0)
            addView(TextView(this@MainActivity).apply {
                text = "Ideogram menerima prompt teks, bukan gambar sumber. Gambar hasil akan dipotong dan diterapkan hanya di dalam mask."
                textSize = 12f
                setPadding(0, 0, 0, 16)
            })
            addView(promptInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(this)
            .setTitle("Ideogram (Nexray)")
            .setView(container)
            .setPositiveButton("Jalankan") { _, _ ->
                IdeogramSettings.setPrompt(this, promptInput.text.toString())
                onConfirmed()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /**
     * Terapkan RemovR ke seluruh area setiap kotak baris. Region persegi dipakai
     * langsung oleh Resynthesizer; tidak ada lagi segmentasi glyph atau local inpaint.
     */
    private fun runMaskRemovR() {
        if (textDetectedRegions.isEmpty()) return
        val ws = vm.activeWorkspace ?: return
        val layer = ws.layers.getOrNull(ws.activeLayerIndex) ?: return
        if (!layer.bitmap.isMutable) {
            layer.bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val regions = textDetectedRegions.mapNotNull { detected ->
            Rect(
                kotlin.math.floor(detected.rect.left.toDouble()).toInt().coerceIn(0, layer.bitmap.width),
                kotlin.math.floor(detected.rect.top.toDouble()).toInt().coerceIn(0, layer.bitmap.height),
                kotlin.math.ceil(detected.rect.right.toDouble()).toInt().coerceIn(0, layer.bitmap.width),
                kotlin.math.ceil(detected.rect.bottom.toDouble()).toInt().coerceIn(0, layer.bitmap.height)
            ).takeUnless { it.isEmpty }
        }
        if (regions.isEmpty()) return

        vm.pushHistory(ws)
        setMaskActionsEnabled(false)
        binding.btnMaskDetect.isEnabled = false
        showMaskProgress("RemovR: ${regions.size} kotak baris…")

        lifecycleScope.launch {
            var done = 0
            var failed = 0
            try {
                for ((index, rect) in regions.withIndex()) {
                    binding.tvMaskStatus.text = "RemovR ${index + 1}/${regions.size}…"
                    val result = withContext(Dispatchers.Default) {
                        val region = Region(rect)
                        var removRResult = ResynthesizerEngine.healSelection(
                            bitmap = layer.bitmap,
                            region = region,
                            params = ResynthesizerEngine.Params(
                                searchRadius = 220,
                                maxNeighbors = 24,
                                maxRandomCandidates = 80
                            )
                        ) { progress ->
                            val overall = (index + progress) / regions.size.toFloat()
                            updateMaskProgress(overall, "RemovR ${index + 1}/${regions.size}…")
                        }
                        if (!removRResult.success) {
                            val fallback = CriminisiEngine.inpaint(layer.bitmap, region) { progress ->
                                val overall = (index + progress) / regions.size.toFloat()
                                updateMaskProgress(overall, "RemovR fallback ${index + 1}/${regions.size}…")
                            }
                            if (fallback.success) {
                                removRResult = ResynthesizerEngine.Result(
                                    success = true,
                                    message = "Criminisi fallback",
                                    processedPixels = fallback.processedPixels
                                )
                            }
                        }
                        removRResult
                    }
                    if (result.success) done++ else failed++
                }
            } catch (t: Throwable) {
                Log.e("VasiliasTyper", "RemovR mask region failed", t)
                failed = maxOf(failed, regions.size - done)
                updateStatus("RemovR gagal: ${t.message ?: "unknown error"}")
            } finally {
                withContext(Dispatchers.Main) {
                    binding.canvasView.selection.clear()
                    binding.canvasView.invalidate()
                    hideMaskProgress()
                    binding.btnMaskDetect.isEnabled = true
                    val msg = "RemovR selesai: $done/${regions.size} kotak baris" +
                        if (failed > 0) " ($failed gagal diproses)" else ""
                    updateStatus(msg)
                    binding.tvMaskStatus.text = msg
                    setMaskActionsEnabled(textDetectedRegions.isNotEmpty())
                }
            }
        }
    }

    /**
     * Scans the complete visible canvas and keeps every ML Kit line as an
     * independent, editable OCR region. Region coordinates stay aligned with the
     * canvas overlay and are also used as SOURCE/OCR entries for the Script panel.
     */
    private fun runOcrOnEntireCanvas() {
        val ws = vm.activeWorkspace ?: run {
            Toast.makeText(this, "Buka gambar kanvas terlebih dahulu", Toast.LENGTH_SHORT).show()
            return
        }
        val src = ocrSrcCodes.getOrElse(binding.spinnerOcrSrc.selectedItemPosition) { MlKitOcrEngine.LANG_AUTO }
        val tgt = ocrTgtCodes.getOrElse(binding.spinnerOcrTgt.selectedItemPosition) { "id" }

        binding.ocrProgressBar.visibility = View.VISIBLE
        binding.btnRunOcrSel.isEnabled = false
        binding.btnRunOcrArea.isEnabled = false
        binding.tvOcrHint.text = "Memindai seluruh kanvas dan memisahkan region teks…"
        binding.canvasView.ocrOverlayDeleteMode = false
        binding.btnDeleteOcrRegion.text = "Hapus Region"

        lifecycleScope.launch {
            val visibleComposite = binding.canvasView.compositeVisibleLayers()
            val composite = visibleComposite ?: withContext(Dispatchers.IO) {
                LayerCompositor.composite(ws.layers, ws.width, ws.height)
            }
            try {
                val detected = MlKitMaskDetector.detectSuspend(composite, src)
                    .filter { it.text.isNotBlank() && it.rect.width() > 0f && it.rect.height() > 0f }
                    .sortedWith(compareBy<MlKitMaskDetector.DetectedRegion> { it.rect.top }.thenBy { it.rect.left })

                ocrResults.clear()
                detected.forEachIndexed { index, region ->
                    val normalized = region.text
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .lowercase(Locale.ROOT)
                    if (normalized.isNotBlank()) {
                        ocrResults += OcrResult(
                            originalText = normalized,
                            sourceLang = region.type.ifBlank { src },
                            translatedText = null,
                            targetLang = tgt,
                            selectionIndex = index,
                            regionRect = RectF(region.rect)
                        )
                    }
                }
                ocrAdapter?.notifyDataSetChanged()
                refreshOcrOverlay()

                ocrResults.forEachIndexed { index, result ->
                    if (useGeminiOcr && !GeminiSettings.getApiKey(this@MainActivity).isNullOrBlank()) {
                        runGeminiForDetectedRegion(composite, index, result, src, tgt)
                    } else {
                        transOcr(index, result)
                    }
                }
                binding.tvOcrHint.text = if (ocrResults.isEmpty()) {
                    "Tidak ada teks ditemukan di seluruh kanvas"
                } else {
                    "${ocrResults.size} region ditemukan • edit teks, hapus kartu, atau tap Hapus Region"
                }
                if (ocrResults.isNotEmpty()) binding.ocrResultsList.scrollToPosition(0)
            } catch (error: Throwable) {
                binding.tvOcrHint.text = "OCR gagal: ${error.message ?: "kesalahan tidak diketahui"}"
                Toast.makeText(this@MainActivity, binding.tvOcrHint.text, Toast.LENGTH_LONG).show()
            } finally {
                if (!composite.isRecycled) composite.recycle()
                binding.ocrProgressBar.visibility = View.GONE
                binding.btnRunOcrSel.isEnabled = true
                binding.btnRunOcrArea.isEnabled = true
            }
        }
    }

    private fun refreshOcrOverlay() {
        binding.canvasView.ocrDetectOverlay = ocrResults.mapNotNull { result ->
            result.regionRect?.let { rect ->
                PaddleDbNetDetector.DetectedRegion(
                    rect = RectF(rect),
                    type = result.sourceLang,
                    text = result.originalText
                )
            }
        }
        binding.canvasView.invalidate()
    }

    private fun removeOcrRegion(position: Int) {
        if (position !in ocrResults.indices) return
        ocrResults.removeAt(position)
        ocrAdapter?.notifyItemRemoved(position)
        ocrAdapter?.notifyItemRangeChanged(position, ocrResults.size - position)
        refreshOcrOverlay()
        if (ocrResults.isEmpty()) {
            binding.canvasView.ocrOverlayDeleteMode = false
            binding.btnDeleteOcrRegion.text = "Hapus Region"
        }
        binding.tvOcrHint.text = if (ocrResults.isEmpty()) {
            "Semua region OCR telah dihapus"
        } else {
            "${ocrResults.size} region OCR tersisa"
        }
    }

    private fun runOcrOnCurrentSelection() {
        val ws  = vm.activeWorkspace ?: return
        val sel = binding.canvasView.selection
        if (!sel.isActive) {
            Toast.makeText(this, "Buat seleksi di kanvas dulu", Toast.LENGTH_SHORT).show()
            return
        }
        val areas = sel.getAreas().ifEmpty { listOf(sel.getBounds()) }
            .mapNotNull { area ->
                val rect = RectF(area)
                if (rect.width() > 0f && rect.height() > 0f) rect else null
            }
        if (areas.isEmpty()) {
            Toast.makeText(this, "Seleksi terlalu kecil", Toast.LENGTH_SHORT).show()
            return
        }

        val src = ocrSrcCodes[binding.spinnerOcrSrc.selectedItemPosition]
        val tgt = ocrTgtCodes[binding.spinnerOcrTgt.selectedItemPosition]

        binding.ocrProgressBar.visibility = View.VISIBLE
        binding.btnRunOcrSel.isEnabled = false
        binding.btnRunOcrArea.isEnabled = false
        binding.tvOcrHint.text = if (areas.size == 1) "OCR area seleksi…" else "OCR area seleksi… (${areas.size} area)"

        lifecycleScope.launch {
            val composite = withContext(Dispatchers.IO) {
                binding.canvasView.compositeVisibleLayers()
                    ?: LayerCompositor.composite(ws.layers, ws.width, ws.height)
            }
            val pendingResults = arrayOfNulls<OcrResult>(areas.size)
            var failed = 0

            fun finishOcrBatch() {
                if (!composite.isRecycled) composite.recycle()
                binding.ocrProgressBar.visibility = View.GONE
                binding.btnRunOcrSel.isEnabled = true
                binding.btnRunOcrArea.isEnabled = true

                val newResults = pendingResults.filterNotNull()
                if (newResults.isEmpty()) {
                    binding.tvOcrHint.text = "Tidak ada teks ditemukan — coba seleksi ulang"
                    return
                }

                for (res in newResults) {
                    val pos = ocrResults.size
                    ocrResults.add(res)
                    ocrAdapter?.notifyItemInserted(pos)
                    transOcr(pos, res)
                }
                ocrAdapter?.notifyDataSetChanged()
                refreshOcrOverlay()
                binding.ocrResultsList.scrollToPosition(ocrResults.lastIndex)
                val total = ocrResults.size
                binding.tvOcrHint.text = if (failed > 0) {
                    "OCR area selesai: ${newResults.size} hasil baru ($total total, $failed area gagal)"
                } else {
                    "OCR area selesai: ${newResults.size} hasil baru ($total total)"
                }
            }

            fun processNextArea(index: Int) {
                if (index >= areas.size) {
                    finishOcrBatch()
                    return
                }
                binding.tvOcrHint.text = "OCR area ${index + 1}/${areas.size}…"
                val area = areas[index]
                val x = area.left.toInt().coerceIn(0, composite.width - 1)
                val y = area.top.toInt().coerceIn(0, composite.height - 1)
                val w = area.width().toInt().coerceIn(1, composite.width - x)
                val h = area.height().toInt().coerceIn(1, composite.height - y)
                val crop = Bitmap.createBitmap(composite, x, y, w, h)

                fun releaseCropAndContinue() {
                    if (!crop.isRecycled) crop.recycle()
                    processNextArea(index + 1)
                }

                fun handleOcrSuccess(text: String, lang: String) {
                    val normalized = text
                        .replace(Regex("\r\n|\r"), "\n")
                        .lines()
                        .joinToString(" ") { ln -> ln.trim() }
                        .replace(Regex("""\s{2,}"""), " ")
                        .trim()
                        .lowercase(Locale.ROOT)
                    if (normalized.isNotBlank()) {
                        pendingResults[index] = OcrResult(
                            originalText = normalized,
                            sourceLang = lang,
                            translatedText = null,
                            targetLang = tgt,
                            selectionIndex = index,
                            regionRect = RectF(area)
                        )
                    }
                    releaseCropAndContinue()
                }

                fun handleOcrFailure() {
                    failed++
                    releaseCropAndContinue()
                }

                if (useGeminiOcr) {
                    GeminiOcrManager.recognize(this@MainActivity, crop, src, object : GeminiOcrManager.Cb {
                        override fun onSuccess(text: String, lang: String) = handleOcrSuccess(text, lang)
                        override fun onFailure(message: String) = handleOcrFailure()
                    })
                } else {
                    MlKitOcrEngine.recognize(crop, null, src, object : MlKitOcrEngine.OcrCallback {
                        override fun onSuccess(text: String, lang: String) = handleOcrSuccess(text, lang)
                        override fun onFailure(e: Exception) = handleOcrFailure()
                    })
                }
            }

            processNextArea(0)
        }
    }

    private fun transOcr(pos: Int, res: OcrResult) {
        val geminiKey = GeminiSettings.getApiKey(this)
        if (useGeminiOcr && !geminiKey.isNullOrBlank()) {
            GeminiOcrTranslation.translateText(
                res.originalText,
                res.sourceLang,
                res.targetLang,
                geminiKey,
                object : GeminiOcrTranslation.TranslationCallback {
                    override fun onSuccess(translatedText: String) {
                        res.translatedText = translatedText.trim()
                        val currentPosition = ocrResults.indexOf(res)
                        if (currentPosition >= 0) ocrAdapter?.notifyItemChanged(currentPosition)
                    }

                    override fun onFailure(error: String) {
                        translateOcrOffline(res)
                    }
                }
            )
        } else {
            translateOcrOffline(res)
        }
    }

    private fun translateOcrOffline(res: OcrResult) {
        TranslationManager.translate(
            res.originalText, res.sourceLang, res.targetLang,
            object : TranslationManager.Cb {
                override fun onSuccess(r: String) {
                    res.translatedText = r.trim()
                    val currentPosition = ocrResults.indexOf(res)
                    if (currentPosition >= 0) ocrAdapter?.notifyItemChanged(currentPosition)
                }
                override fun onFailure(m: String) {
                    res.translatedText = "[gagal: $m]"
                    val currentPosition = ocrResults.indexOf(res)
                    if (currentPosition >= 0) ocrAdapter?.notifyItemChanged(currentPosition)
                }
            }
        )
    }

    private fun runGeminiForDetectedRegion(
        composite: Bitmap,
        index: Int,
        result: OcrResult,
        src: String,
        tgt: String
    ) {
        val key = GeminiSettings.getApiKey(this) ?: run {
            transOcr(index, result)
            return
        }
        val rect = result.regionRect ?: run {
            transOcr(index, result)
            return
        }
        val x = rect.left.toInt().coerceIn(0, composite.width - 1)
        val y = rect.top.toInt().coerceIn(0, composite.height - 1)
        val w = rect.width().toInt().coerceIn(1, composite.width - x)
        val h = rect.height().toInt().coerceIn(1, composite.height - y)
        val crop = Bitmap.createBitmap(composite, x, y, w, h)
        GeminiOcrTranslation.recognizeAndTranslate(
            crop,
            src,
            tgt,
            key,
            object : GeminiOcrTranslation.OcrCallback {
                override fun onSuccess(originalText: String, translatedText: String, detectedLang: String) {
                    result.originalText = originalText.trim()
                    result.translatedText = translatedText.trim()
                    result.sourceLang = detectedLang
                    val currentPosition = ocrResults.indexOf(result)
                    if (currentPosition >= 0) {
                        ocrAdapter?.notifyItemChanged(currentPosition)
                        refreshOcrOverlay()
                    }
                }

                override fun onFailure(error: String) {
                    translateOcrOffline(result)
                }
            }
        )
        if (!crop.isRecycled) crop.recycle()
    }

    private fun copyOcrText(position: Int, translated: Boolean) {
        val result = ocrResults.getOrNull(position) ?: return
        val text = if (translated) result.translatedText.orEmpty() else result.originalText
        if (text.isBlank()) {
            Toast.makeText(this, "Teks belum tersedia", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val label = if (translated) "Terjemahan OCR" else "Teks asli OCR"
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label disalin", Toast.LENGTH_SHORT).show()
    }

    private fun retranslateOcr(pos: Int) {
        val res = ocrResults.getOrNull(pos) ?: return
        res.targetLang = ocrTgtCodes[binding.spinnerOcrTgt.selectedItemPosition]
        transOcr(pos, res)
    }

    private fun ocrResultsToScript() {
        if (ocrResults.none { it.originalText.isNotBlank() }) {
            Toast.makeText(this, "Tidak ada hasil OCR yang dapat ditempel", Toast.LENGTH_SHORT).show()
            return
        }

        fun pasteIntoSource(replaceExisting: Boolean) {
            if (replaceExisting) scriptLines.clear()
            val sorted = ocrResults.sortedWith(
                compareBy<OcrResult> { it.regionRect?.top ?: Float.MAX_VALUE }
                    .thenBy { it.regionRect?.left ?: Float.MAX_VALUE }
            )
            var added = 0
            sorted.forEach { result ->
                val source = result.originalText.trim()
                val translated = result.translatedText?.trim().orEmpty()
                if (source.isNotBlank()) {
                    scriptLines += ScriptLine(
                        text = translated.takeIf { it.isNotBlank() && !it.startsWith("[gagal:") } ?: source,
                        sourceText = source
                    )
                    added++
                }
            }
            scriptHasOcrPairs = scriptLines.any { !it.sourceText.isNullOrBlank() }
            scriptSourceLanguageCode = detectImportedSourceLanguageCode(scriptLines)
            scriptSourceLanguage = sourceLanguageLabel(scriptSourceLanguageCode)
            scriptFileName = "SOURCE/OCR dari seluruh kanvas"
            refreshScriptPanel()
            showScriptPanel()
            Toast.makeText(
                this,
                "$added region ditempel ke kolom SOURCE/OCR Script",
                Toast.LENGTH_SHORT
            ).show()
        }

        if (scriptLines.isEmpty()) {
            pasteIntoSource(replaceExisting = false)
        } else {
            AlertDialog.Builder(this)
                .setTitle("Tempel ke SOURCE/OCR")
                .setMessage("Ganti isi Script saat ini atau tambahkan region OCR di bawahnya?")
                .setPositiveButton("Ganti") { _, _ -> pasteIntoSource(replaceExisting = true) }
                .setNeutralButton("Tambahkan") { _, _ -> pasteIntoSource(replaceExisting = false) }
                .setNegativeButton("Batal", null)
                .show()
        }
    }


      // ── v5.4: Font preview adapter (renders font name in its own typeface) ──────
      private inner class FontPreviewAdapter(
          ctx: android.content.Context,
          private val fonts: List<FontItem>
      ) : android.widget.BaseAdapter() {
          private val inflater = android.view.LayoutInflater.from(ctx)
          override fun getCount()                     = fonts.size
          override fun getItem(pos: Int)              = fonts[pos]
          override fun getItemId(pos: Int)            = pos.toLong()
          override fun getView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View =
              makeView(pos, cv, parent)
          override fun getDropDownView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View =
              makeView(pos, cv, parent)
          private fun makeView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
              val tv = (cv as? android.widget.TextView) ?: android.widget.TextView(parent.context).apply {
                  setPadding(24, 10, 24, 10)
                  textSize = 15f
                  setTextColor(android.graphics.Color.WHITE)
              }
              val f = fonts[pos]
              tv.text     = f.displayName
              tv.typeface = f.typeface ?: android.graphics.Typeface.DEFAULT
              return tv
          }
      }
  
      // ══════════════════════════════════════════════════════════════════════════
      // CRASH-SAFE AUTO-SAVE + DIRECT EXPORT HELPERS (v12)
      // ══════════════════════════════════════════════════════════════════════════

      /**
       * Records lightweight crash metadata, then immediately delegates to Android's
       * original handler. Bitmap serialization is intentionally forbidden here:
       * an uncaught-exception handler runs on the failing thread and heavy I/O can
       * turn a normal crash into a long "application not responding" state.
       */
      private fun setupCrashAutoSave() {
          val prev = Thread.getDefaultUncaughtExceptionHandler()
          Thread.setDefaultUncaughtExceptionHandler { thread, error ->
              runCatching {
                  getSharedPreferences("vasilias_crash_state", MODE_PRIVATE)
                      .edit()
                      .putLong("last_crash_ms", System.currentTimeMillis())
                      .putString("last_project_id", currentProjectId)
                      .putString("last_error", error.javaClass.simpleName)
                      .commit()
              }
              prev?.uncaughtException(thread, error)
          }
      }

      /**
       * Save the current workspace directly to Pictures/VasiliasTyper without showing
       * a file-picker dialog.  Uses [pendingExportFormat] and [pendingExportQuality]
       * set by [showExportDialog].
       */
      private fun directExport() {
          val ws = vm.activeWorkspace ?: run {
              Toast.makeText(this, "Tidak ada workspace aktif", Toast.LENGTH_SHORT).show()
              return
          }
          val ext      = extensionFor(pendingExportFormat)
          val safeName = (ws.name.ifBlank { "export" }).replace(Regex("[^\\w\\-. ]"), "_")
          val filename = "$safeName.$ext"
          lifecycleScope.launch {
              Toast.makeText(this@MainActivity, "Menyimpan ke Pictures/VasiliasTyper…", Toast.LENGTH_SHORT).show()
              val composite = withContext(Dispatchers.IO) { buildFlatExportBitmap(ws) }
              val ok = FileManager.exportBitmapDirect(
                  context  = this@MainActivity,
                  bitmap   = composite,
                  format   = pendingExportFormat,
                  quality  = pendingExportQuality,
                  filename = filename
              )
              composite.recycle()
              if (ok) {
                  Toast.makeText(this@MainActivity, "Tersimpan: Pictures/VasiliasTyper/$filename", Toast.LENGTH_LONG).show()
              } else {
                  Toast.makeText(this@MainActivity, "Gagal menyimpan ke Pictures — coba Export biasa", Toast.LENGTH_LONG).show()
              }
          }
      }

      private fun directExportAllTabs() {
          val workspaces = vm.workspaces.value?.toList().orEmpty()
          if (workspaces.isEmpty()) {
              Toast.makeText(this, "Tidak ada tab untuk diekspor", Toast.LENGTH_SHORT).show()
              return
          }
          snapshotActiveWorkspaceElements()
          val jobs = workspaces.map { ws ->
              Triple(
                  ws,
                  workspaceTextStates[ws.id]?.map { it.copy() }.orEmpty(),
                  workspaceImageStates[ws.id]?.map { it.copy() }.orEmpty()
              )
          }
          val format = pendingExportFormat
          val quality = pendingExportQuality
          val extension = extensionFor(format)

          lifecycleScope.launch {
              var succeeded = 0
              var failed = 0
              jobs.forEachIndexed { index, (ws, texts, images) ->
                  updateStatus("Export tab ${index + 1}/${jobs.size}: ${ws.name}")
                  val safeName = ws.name.ifBlank { "tab_${index + 1}" }
                      .replace(Regex("[^\\w\\-. ]"), "_")
                  val filename = "%03d_%s.%s".format(index + 1, safeName, extension)
                  val ok = withContext(Dispatchers.IO) {
                      val composite = runCatching {
                          buildFlatExportBitmap(ws, texts, images)
                      }.getOrNull() ?: return@withContext false
                      try {
                          FileManager.exportBitmapDirect(
                              context = this@MainActivity,
                              bitmap = composite,
                              format = format,
                              quality = quality,
                              filename = filename
                          )
                      } finally {
                          if (!composite.isRecycled) composite.recycle()
                      }
                  }
                  if (ok) succeeded++ else failed++
                  yield()
              }
              val result = "$succeeded/${jobs.size} tab tersimpan di Pictures/VasiliasTyper" +
                  if (failed > 0) " • $failed gagal" else ""
              updateStatus(result)
              Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
          }
      }

      private fun extensionFor(fmt: Bitmap.CompressFormat): String {
          val name = fmt.name.uppercase()
          return when {
              "JPEG" in name -> "jpg"
              "WEBP" in name -> "webp"
              else           -> "png"
          }
      }

    // ══════════════════════════════════════════════════════════════════════════
    // GEMINI API KEY DIALOG
    // ══════════════════════════════════════════════════════════════════════════

    private fun showApiKeyDialog() {
        val PREFS   = "gemini_settings"
        val KEY_API = "gemini_api_key"
        val prefs   = getSharedPreferences(PREFS, MODE_PRIVATE)
        val current = prefs.getString(KEY_API, "") ?: ""

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val tvInfo = android.widget.TextView(this).apply {
            text = "Masukkan Gemini API Key untuk fitur OCR dan terjemahan AI.\n" +
                   "Dapatkan key gratis di: aistudio.google.com"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            setPadding(0, 0, 0, 12)
        }
        val et = EditText(this).apply {
            hint = "AIza..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setText(current)
            setSelection(current.length)
            setTextColor(android.graphics.Color.parseColor("#EEEEEE"))
        }
        layout.addView(tvInfo)
        layout.addView(et)

        AlertDialog.Builder(this)
            .setTitle("🔑 Gemini API Key")
            .setView(layout)
            .setPositiveButton("Simpan") { _, _ ->
                val key = et.text.toString().trim()
                prefs.edit().putString(KEY_API, key).apply()
                val msg = if (key.isNotBlank()) "API key disimpan. Gemini AI aktif!" else "API key dihapus."
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Hapus") { _, _ ->
                prefs.edit().putString(KEY_API, "").apply()
                Toast.makeText(this, "API key dihapus.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // MULTI REPLACE DIALOG — find & replace text across all text elements
    // ══════════════════════════════════════════════════════════════════════════

    private fun showMultiReplaceDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        fun makeField(hint: String): EditText = EditText(this).apply {
            this.hint = hint
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setTextColor(android.graphics.Color.parseColor("#EEEEEE"))
            setHintTextColor(android.graphics.Color.parseColor("#666666"))
            setPadding(0, 8, 0, 8)
        }
        val etFind    = makeField("Cari teks…")
        val etReplace = makeField("Ganti dengan…")
        val tvCount   = android.widget.TextView(this).apply {
            textSize = 11f
            setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        }

        val watcher = object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable) {
                val q = etFind.text.toString()
                if (q.isEmpty()) { tvCount.text = ""; return }
                val n = binding.canvasView.textElements.count {
                    it.text.contains(q, ignoreCase = true)
                }
                tvCount.text = "Ditemukan di $n elemen teks"
            }
            override fun beforeTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence, st: Int, c: Int, a: Int) {}
        }
        etFind.addTextChangedListener(watcher)

        layout.addView(etFind)
        layout.addView(etReplace)
        layout.addView(tvCount)

        AlertDialog.Builder(this)
            .setTitle("Multi Replace")
            .setView(layout)
            .setPositiveButton("Ganti Semua") { _, _ ->
                val q = etFind.text.toString()
                val r = etReplace.text.toString()
                if (q.isEmpty()) return@setPositiveButton
                var count = 0
                binding.canvasView.textElements.forEach { el ->
                    if (el.text.contains(q, ignoreCase = true)) {
                        el.text = el.text.replace(q, r, ignoreCase = true)
                        count++
                    }
                }
                binding.canvasView.invalidate()
                Toast.makeText(this, "Diganti di $count elemen teks", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════════════
    // STYLE RULES DIALOG — manage pattern→style auto-assignment rules
    // ══════════════════════════════════════════════════════════════════════════

    private fun showStyleRulesDialog() {
        val rules = StyleManager.loadStyleRules(this).toMutableList()
        val styles = StyleManager.loadStyles(this)

        if (styles.isEmpty()) {
            Toast.makeText(this, "Belum ada style. Buat style dulu lewat Style Manager.", Toast.LENGTH_LONG).show()
            return
        }

        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        fun rounded(color: Int, radius: Int, strokeColor: Int? = null): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(radius).toFloat()
                if (strokeColor != null) setStroke(dp(1), strokeColor)
            }

        data class RuleVisual(val token: String, val title: String, val detail: String, val accent: Int)
        fun visualFor(rule: StyleRule): RuleVisual = when (rule.patternType) {
            "WRAP_PAREN" -> RuleVisual("( )", "Teks dalam kurung", "Contoh: (suara pelan)", Color.parseColor("#8B5CF6"))
            "WRAP_QUOTE" -> RuleVisual("\" \"", "Teks dalam petik", "Contoh: \"dialog\"", Color.parseColor("#3B82F6"))
            "WRAP_SQUARE" -> RuleVisual("[ ]", "Teks dalam kurung siku", "Contoh: [efek suara]", Color.parseColor("#06B6D4"))
            "CONTAINS" -> RuleVisual("Aa", "Mengandung teks", "Mencari: “${rule.pattern}”", Color.parseColor("#10B981"))
            "STARTS_WITH" -> RuleVisual("A…", "Dimulai dengan", "Awalan: “${rule.pattern}”", Color.parseColor("#F59E0B"))
            "PREFIX_CODE" -> RuleVisual("ID", "Prefix kode", "Kode: “${rule.pattern}”", Color.parseColor("#F97316"))
            else -> RuleVisual("?", "Pola lain", rule.pattern.ifBlank { "Tanpa pola" }, Color.parseColor("#64748B"))
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(8))
            setBackgroundColor(Color.parseColor("#111522"))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "Style Rules"
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Atur style otomatis berdasarkan pola teks"
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
            })
        }
        header.addView(titleBlock, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val countView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#C7D2FE"))
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = rounded(Color.parseColor("#252B45"), 18, Color.parseColor("#424B73"))
        }
        header.addView(countView)
        root.addView(header)

        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(Color.parseColor("#18233A"), 12, Color.parseColor("#29466F"))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_info)
                imageTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#7DD3FC"))
            }, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(10) })
            addView(TextView(this@MainActivity).apply {
                text = "Rule diproses dari atas ke bawah. Rule pertama yang cocok akan dipakai."
                textSize = 11f
                setTextColor(Color.parseColor("#D5E7FF"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14); bottomMargin = dp(10)
        })

        val listContainer = FrameLayout(this)
        val listView = ListView(this).apply {
            divider = null
            dividerHeight = 0
            setPadding(0, dp(2), 0, dp(4))
            clipToPadding = false
        }
        val emptyView = TextView(this).apply {
            text = "Belum ada rule\nTekan ‘Tambah Rule’ untuk membuat otomatisasi style."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#7C879F"))
            visibility = View.GONE
        }
        listContainer.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        listContainer.addView(listView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        listView.emptyView = emptyView
        root.addView(listContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))

        lateinit var ruleAdapter: BaseAdapter
        fun refreshRules() {
            countView.text = "${rules.size} rule"
            ruleAdapter.notifyDataSetChanged()
        }

        ruleAdapter = object : BaseAdapter() {
            override fun getCount(): Int = rules.size
            override fun getItem(position: Int): StyleRule = rules[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val rule = getItem(position)
                val visual = visualFor(rule)
                val targetStyle = styles.firstOrNull {
                    it.name.equals(rule.styleName, true) && it.folder.equals(rule.folder, true)
                }
                val card = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(10), dp(10), dp(8), dp(10))
                    background = rounded(Color.parseColor("#1A2031"), 14, Color.parseColor("#303A55"))
                }
                val token = TextView(this@MainActivity).apply {
                    text = visual.token
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    background = rounded(visual.accent, 12)
                }
                card.addView(token, LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginEnd = dp(10) })

                val info = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                info.addView(TextView(this@MainActivity).apply {
                    text = visual.title
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                info.addView(TextView(this@MainActivity).apply {
                    text = visual.detail
                    textSize = 10f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(Color.parseColor("#9CA8C0"))
                })
                val targetRow = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(6), 0, 0)
                }
                targetRow.addView(View(this@MainActivity).apply {
                    background = rounded(targetStyle?.color ?: Color.parseColor("#64748B"), 8, Color.WHITE)
                }, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginEnd = dp(6) })
                targetRow.addView(TextView(this@MainActivity).apply {
                    text = rule.styleName
                    textSize = 11f
                    setTextColor(Color.parseColor("#E2E8F0"))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                targetRow.addView(TextView(this@MainActivity).apply {
                    text = rule.folder
                    textSize = 9f
                    setTextColor(Color.parseColor("#A5B4FC"))
                    setPadding(dp(7), dp(3), dp(7), dp(3))
                    background = rounded(Color.parseColor("#282E50"), 10)
                })
                info.addView(targetRow)
                card.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                card.addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_delete)
                    contentDescription = "Hapus rule ${visual.title}"
                    setColorFilter(Color.parseColor("#FDA4AF"))
                    background = rounded(Color.parseColor("#38232D"), 10)
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Hapus rule?")
                            .setMessage("${visual.title} akan dihapus. Style tersimpan tidak ikut terhapus.")
                            .setPositiveButton("Hapus") { _, _ ->
                                val currentIndex = rules.indexOf(rule)
                                if (currentIndex >= 0) {
                                    rules.removeAt(currentIndex)
                                    StyleManager.saveStyleRules(this@MainActivity, rules)
                                    refreshRules()
                                }
                            }
                            .setNegativeButton("Batal", null)
                            .show()
                    }
                }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginStart = dp(8) })

                return FrameLayout(this@MainActivity).apply {
                    addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, dp(4), 0, dp(4))
                    })
                }
            }
        }
        listView.adapter = ruleAdapter
        refreshRules()

        val addButton = Button(this).apply {
            text = "Tambah Rule"
            textSize = 13f
            setTextColor(Color.WHITE)
            isAllCaps = false
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4F46E5"))
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0)
            compoundDrawablePadding = dp(8)
        }
        root.addView(addButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .setNegativeButton("Tutup", null)
            .create()

        val patternLabels = arrayOf(
            "Teks dalam kurung  ( ... )",
            "Teks dalam petik  \" ... \"",
            "Teks dalam kurung siku  [ ... ]",
            "Mengandung teks tertentu",
            "Dimulai dengan teks tertentu",
            "Prefix kode  (contoh: []:)"
        )
        val patternKeys = arrayOf("WRAP_PAREN", "WRAP_QUOTE", "WRAP_SQUARE", "CONTAINS", "STARTS_WITH", "PREFIX_CODE")
        val styleNames = styles.map { "${it.name}  ·  ${it.folder}" }.toTypedArray()

        addButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Pilih pengenal teks")
                .setItems(patternLabels) { _, patternIndex ->
                    val patternKey = patternKeys[patternIndex]
                    fun pickStyle(pattern: String) {
                        AlertDialog.Builder(this)
                            .setTitle("Style yang diterapkan")
                            .setItems(styleNames) { _, styleIndex ->
                                val selectedStyle = styles[styleIndex]
                                rules.add(StyleRule(pattern, patternKey, selectedStyle.name, selectedStyle.folder))
                                StyleManager.saveStyleRules(this, rules)
                                refreshRules()
                                listView.smoothScrollToPosition(rules.lastIndex)
                            }
                            .setNegativeButton("Batal", null)
                            .show()
                    }
                    if (patternKey in setOf("CONTAINS", "STARTS_WITH", "PREFIX_CODE")) {
                        val input = EditText(this).apply {
                            hint = when (patternKey) {
                                "CONTAINS" -> "Teks yang dicari"
                                "STARTS_WITH" -> "Awalan teks"
                                else -> "Prefix, contoh: []: atau //:"
                            }
                            setSingleLine(true)
                            setPadding(dp(18), dp(12), dp(18), dp(12))
                        }
                        val inputDialog = AlertDialog.Builder(this)
                            .setTitle("Masukkan teks pengenal")
                            .setView(input)
                            .setPositiveButton("Lanjut", null)
                            .setNegativeButton("Batal", null)
                            .create()
                        inputDialog.setOnShowListener {
                            inputDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val value = input.text.toString().trim()
                                if (value.isBlank()) {
                                    input.error = "Teks pengenal tidak boleh kosong"
                                } else {
                                    inputDialog.dismiss()
                                    pickStyle(value)
                                }
                            }
                        }
                        inputDialog.show()
                    } else {
                        pickStyle("")
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }

        dialog.show()
    }
}
