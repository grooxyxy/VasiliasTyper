package com.vasiliastyper.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * YOLOv8m speech-bubble detector (pengganti RT-DETR).
 *
 * Model: `comic-speech-bubble-detector.onnx` (~99MB) dari Google Drive milik user:
 *   https://drive.google.com/file/d/13B42NV0mPPBzUUVsIvD4SvE3QXEaLOlv/view
 * (unduh & bundle saat build via task Gradle `bundleBubbleModel`, atau letakkan
 * manual di filesDir seperti model lain).
 *
 * Model lain (LaMa Manga, PP-OCR, ComicTextDetector) tetap dari Hugging Face
 * sesuai README di tiap folder assets/models.
 *
 * Kontrak ONNX (terverifikasi dari referensi Manhwa-Translator):
 *  - input `[1,3,640,640]` RGB 0..1 (letterbox pad abu 114);
 *  - output `[1,6,8400]` = cx,cy,w,h + 2 skor (single-class langsung,
 *    multi-class argmax, skor MENTAH tanpa sigmoid).
 * Aturan: conf 0.30, NMS IoU 0.45.
 *
 * Gambar tinggi (720x16000 bahkan lebih) diproses per tile grid 1200px dengan
 * overlap 300px + NMS global, sehingga:
 *  - konten tidak digepeng ke input 640 sekaligus (bubble kecil tidak hancur);
 *  - memori tetap kecil (satu tile ±5MB, langsung di-recycle);
 *  - gambar super-tinggi hanya menambah jumlah tile, bukan memori.
 * Model hilang/rusak = daftar kosong; pemanggil memakai fallback OpenCV/ML Kit.
 */
object YoloV8mBubbleDetector {
    private const val TAG = "YoloV8mBubbleDetector"

    const val MODEL_KEY = "bubble_yolov8m"
    const val MODEL_VERSION = 1
    const val MODEL_NAME = "comic-speech-bubble-detector.onnx"

    /** ID file Drive milik user (publik "Anyone with the link"). */
    const val DRIVE_FILE_ID = "13B42NV0mPPBzUUVsIvD4SvE3QXEaLOlv"
    const val DRIVE_DOWNLOAD_URL =
        "https://drive.google.com/uc?export=download&id=$DRIVE_FILE_ID"

    /** Batas bawah 50MB menolak HTML/error-page; revisi valid tetap lolos. */
    const val MIN_MODEL_BYTES = 50L * 1024 * 1024

    const val INPUT = 640
    const val PAD = 114
    const val CONF_TH = 0.30f
    const val IOU_TH = 0.45f
    const val TILE_SIZE = 1200
    const val TILE_OVERLAP = 300
    const val MAX_RESULTS = 400

    data class Detection(val rect: RectF, val confidence: Float)

    @Volatile private var initialized = false
    @Volatile private var unavailable = false
    @Volatile private var environment: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    private val inferenceLock = Any()

    fun isAvailable(context: Context): Boolean = ensureSession(context.applicationContext) != null

    fun healthMessage(context: Context): String = when {
        isAvailable(context) -> "Bubble YOLOv8m ready"
        else -> "Bubble YOLOv8m belum ada — bundle saat build atau letakkan $MODEL_NAME manual"
    }

    fun detect(context: Context, bitmap: Bitmap): List<Detection> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        val activeSession = ensureSession(context.applicationContext) ?: return emptyList()
        val env = environment ?: return emptyList()
        return try {
            synchronized(inferenceLock) { detectTiled(env, activeSession, bitmap) }
        } catch (error: Throwable) {
            Log.w(TAG, "Inferensi YOLOv8m dilewati: ${error.message}")
            emptyList()
        }
    }

    fun resetRuntimeState() {
        synchronized(inferenceLock) {
            session?.close()
            session = null
            environment = null
            initialized = false
            unavailable = false
        }
    }

    @Synchronized
    private fun ensureSession(context: Context): OrtSession? {
        if (initialized) return session
        if (unavailable) return null
        return try {
            val modelFile = resolveModelFile(context) ?: run {
                initialized = true
                unavailable = true
                return null
            }
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(ProcessingConfig.NUM_THREADS.coerceIn(1, 4))
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val created = try {
                env.createSession(modelFile.absolutePath, options)
            } finally {
                options.close()
            }
            validateInterface(created)
            environment = env
            session = created
            initialized = true
            Log.i(TAG, "Bubble YOLOv8m aktif: ${modelFile.absolutePath}")
            created
        } catch (error: Throwable) {
            Log.w(TAG, "Bubble YOLOv8m tidak tersedia: ${error.message}")
            initialized = true
            unavailable = true
            session?.close()
            session = null
            null
        }
    }

    private fun validateInterface(activeSession: OrtSession) {
        val inName = activeSession.inputNames.firstOrNull()
            ?: error("Model bubble tanpa input")
        val imageInfo = activeSession.inputInfo[inName]?.info as? TensorInfo
        val shape = imageInfo?.shape
        require(shape != null && shape.size == 4 && shape.getOrNull(1) == 3L) {
            "Input model bubble tidak didukung: $shape"
        }
    }

    private fun resolveModelFile(context: Context): File? {
        val downloaded = ModelPathRegistry.versionedFile(context, MODEL_KEY, MODEL_VERSION, MODEL_NAME)
        if (isValidModel(downloaded)) return downloaded
        val legacy = File(context.filesDir, "models/$MODEL_NAME")
        if (isValidModel(legacy)) return legacy

        // Asset hasil bundle saat build (task bundleBubbleModel).
        return try {
            val assetPath = ModelPathRegistry.versionedAssetPath(MODEL_KEY, MODEL_VERSION, MODEL_NAME)
            val assetLength = context.assets.openFd(assetPath).use { it.length }
            if (assetLength < MIN_MODEL_BYTES) return null
            val cached = File(context.cacheDir, "models/$MODEL_KEY/v$MODEL_VERSION/$MODEL_NAME")
            if (!isValidModel(cached)) {
                cached.parentFile?.mkdirs()
                val temporary = File(cached.parentFile, "$MODEL_NAME.tmp")
                context.assets.open(assetPath).use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
                require(isValidModel(temporary)) { "Asset model tidak lengkap" }
                if (cached.exists() && !cached.delete()) error("Cache model tidak dapat diganti")
                require(temporary.renameTo(cached)) { "Cache model tidak dapat diselesaikan" }
            }
            cached
        } catch (_: Throwable) {
            null
        }
    }

    private fun isValidModel(file: File): Boolean =
        file.isFile && file.length() >= MIN_MODEL_BYTES

    /** Port split-tiles: [(y_start, y_end)] dengan overlap. */
    internal fun splitTiles(height: Int, tileSize: Int, overlap: Int): List<Pair<Int, Int>> {
        require(tileSize > 0) { "tile_size harus >0" }
        require(overlap >= 0) { "overlap harus >=0" }
        require(overlap < tileSize) { "overlap harus < tile_size" }
        if (height <= tileSize) return listOf(0 to height)
        val tiles = mutableListOf<Pair<Int, Int>>()
        var y = 0
        while (y < height) {
            val yEnd = min(y + tileSize, height)
            tiles += y to yEnd
            if (yEnd >= height) break
            y = yEnd - overlap
        }
        return tiles
    }

    private data class ScoredBox(val rect: RectF, val score: Float)

    /** Satu tile grid: (x0,y0) inklusif — (x1,y1) eksklusif dalam koordinat bitmap. */
    private data class Tile(val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    /** Grid 2D dari splitter 1D: kolom × baris, tiap tile ≤TILE_SIZE + overlap. */
    internal fun splitGrid(width: Int, height: Int): List<Tile> {
        val xs = splitTiles(width, TILE_SIZE, TILE_OVERLAP)
        val ys = splitTiles(height, TILE_SIZE, TILE_OVERLAP)
        val tiles = ArrayList<Tile>(xs.size * ys.size)
        for ((x0, x1) in xs) {
            for ((y0, y1) in ys) {
                if (x1 > x0 && y1 > y0) tiles += Tile(x0, y0, x1, y1)
            }
        }
        return tiles
    }

    private fun detectTiled(
        env: OrtEnvironment,
        activeSession: OrtSession,
        bitmap: Bitmap,
    ): List<Detection> {
        val inName = activeSession.inputNames.firstOrNull() ?: return emptyList()
        val tiles = splitGrid(bitmap.width, bitmap.height)
        val all = mutableListOf<ScoredBox>()
        for (t in tiles) {
            val tw = t.x1 - t.x0
            val th = t.y1 - t.y0
            if (tw <= 0 || th <= 0) continue
            val tile = try {
                Bitmap.createBitmap(bitmap, t.x0, t.y0, tw, th)
            } catch (_: Throwable) {
                continue
            }
            try {
                detectTile(env, activeSession, inName, tile).forEach { b ->
                    all += ScoredBox(
                        RectF(
                            b.rect.left + t.x0, b.rect.top + t.y0,
                            b.rect.right + t.x0, b.rect.bottom + t.y0
                        ),
                        b.score,
                    )
                }
            } finally {
                if (!tile.isRecycled) tile.recycle()
            }
        }
        val final = if (tiles.size > 1 && all.isNotEmpty()) nms(all) else all
        return final.take(MAX_RESULTS).map { Detection(it.rect, it.score) }
    }

    /** 1 tile -> letterbox 640 -> inferensi -> NMS lokal (koordinat tile). */
    private fun detectTile(
        env: OrtEnvironment,
        activeSession: OrtSession,
        inName: String,
        tile: Bitmap,
    ): List<ScoredBox> {
        val scale = min(INPUT / tile.width.toFloat(), INPUT / tile.height.toFloat())
        val nw = max(1, (tile.width * scale).roundToInt())
        val nh = max(1, (tile.height * scale).roundToInt())
        val dx = (INPUT - nw) / 2
        val dy = (INPUT - nh) / 2
        val small = Bitmap.createScaledBitmap(tile, nw, nh, true)
        val square = Bitmap.createBitmap(INPUT, INPUT, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(square).apply {
            drawColor((0xFF shl 24) or (PAD shl 16) or (PAD shl 8) or PAD)
            drawBitmap(small, dx.toFloat(), dy.toFloat(), null)
        }
        if (!small.isRecycled) small.recycle()

        val px = IntArray(INPUT * INPUT)
        square.getPixels(px, 0, INPUT, 0, 0, INPUT, INPUT)
        if (!square.isRecycled) square.recycle()
        val plane = INPUT * INPUT
        val buf = FloatBuffer.allocate(3 * plane)
        for (i in px.indices) {
            val p = px[i]
            buf.put(i, ((p shr 16) and 0xFF) / 255f)
            buf.put(plane + i, ((p shr 8) and 0xFF) / 255f)
            buf.put(plane * 2 + i, (p and 0xFF) / 255f)
        }
        buf.rewind()

        OnnxTensor.createTensor(
            env, buf, longArrayOf(1L, 3L, INPUT.toLong(), INPUT.toLong())
        ).use { tensor ->
            activeSession.run(mapOf(inName to tensor)).use { res ->
                val boxes = parseOutput(
                    res[0].value, scale, dx.toFloat(), dy.toFloat(), tile.width, tile.height
                )
                return nms(boxes)
            }
        }
    }

    /**
     * Dukung [1,C,N] maupun [1,N,C]. Skor MENTAH seperti referensi:
     * 5 kolom -> kolom ke-4 langsung; >=6 kolom -> argmax mentah.
     */
    private fun parseOutput(
        v: Any?, scale: Float, dx: Float, dy: Float, w: Int, h: Int,
    ): MutableList<ScoredBox> {
        val out = mutableListOf<ScoredBox>()
        val batch = (v as? Array<*>)?.getOrNull(0) as? Array<*> ?: return out
        if (batch.isEmpty()) return out
        val firstLen = (batch[0] as? FloatArray)?.size ?: return out
        val rows: List<FloatArray>
        val cols: Int
        if (batch.size <= 32 && firstLen > 32) {
            val c = batch.size
            val colArr = batch.map { it as FloatArray }
            rows = List(firstLen) { n -> FloatArray(c) { cc -> colArr[cc][n] } }
            cols = c
        } else {
            rows = batch.mapNotNull { it as? FloatArray }
            cols = firstLen
        }
        if (cols < 5) return out
        val singleClass = cols == 5
        for (r in rows) {
            val score = if (singleClass) {
                r[4]
            } else {
                var best = Float.NEGATIVE_INFINITY
                for (k in 4 until cols) if (r[k] > best) best = r[k]
                best
            }
            if (!score.isFinite() || score < CONF_TH) continue
            val bw = r[2] / scale
            val bh = r[3] / scale
            val cx = (r[0] - dx) / scale
            val cy = (r[1] - dy) / scale
            val rect = RectF(
                (cx - bw / 2f).coerceIn(0f, (w - 1).toFloat()),
                (cy - bh / 2f).coerceIn(0f, (h - 1).toFloat()),
                (cx + bw / 2f).coerceIn(0f, (w - 1).toFloat()),
                (cy + bh / 2f).coerceIn(0f, (h - 1).toFloat()),
            )
            if (rect.right > rect.left && rect.bottom > rect.top) {
                out += ScoredBox(rect, score)
            }
        }
        return out
    }

    private fun iou(a: RectF, b: RectF): Float {
        val l = max(a.left, b.left)
        val t = max(a.top, b.top)
        val r = min(a.right, b.right)
        val bo = min(a.bottom, b.bottom)
        val inter = max(0f, r - l) * max(0f, bo - t)
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun nms(boxes: List<ScoredBox>): List<ScoredBox> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.score }
        val kept = mutableListOf<ScoredBox>()
        for (b in sorted) {
            if (kept.none { iou(it.rect, b.rect) > IOU_TH }) kept += b
        }
        return kept
    }
}
