package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.min

/**
 * MlKitMaskDetector v2 — low-end friendly OCR detector.
 *
 * Perubahan utama:
 *  - Gambar tinggi diproses per strip agar width tetap utuh.
 *  - Tidak lagi men-scale seluruh kanvas vertikal tinggi menjadi bitmap tipis.
 *  - Mengembalikan bounding box per baris, bukan per blok/paragraf, supaya
 *    mask lebih rapat pada teks dan tidak ikut menutup ruang kosong bubble.
 *  - Mode Auto menunggu semua recognizer selesai sebelum bitmap dibersihkan,
 *    sehingga recognizer lain tidak membaca bitmap yang sudah di-recycle.
 *  - Tetap mendukung mode single-shot untuk gambar kecil.
 */
object MlKitMaskDetector {

    data class DetectedRegion(
        val rect: RectF,
        val type: String = "text",
        val text: String = ""
    )

    interface DetectCallback {
        fun onSuccess(regions: List<DetectedRegion>)
        fun onFailure(error: String)
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val latinRec by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val chRec    by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val koRec    by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    private const val TILE_HEIGHT = 1440
    private const val TILE_OVERLAP = 64
    private const val MAX_PARALLEL_TILES = 2
    private const val LARGE_HEIGHT_THRESHOLD = 1800
    private const val LARGE_AREA_THRESHOLD = 2_500_000L

    fun detect(bitmap: Bitmap, lang: String, cb: DetectCallback) {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            main.post { cb.onFailure("Bitmap kosong") }
            return
        }

        val shouldTile = bitmap.height > LARGE_HEIGHT_THRESHOLD ||
            (bitmap.width.toLong() * bitmap.height.toLong()) > LARGE_AREA_THRESHOLD

        if (shouldTile) {
            detectTiled(bitmap, lang, cb)
        } else {
            detectSingle(bitmap, lang, cb)
        }
    }


    suspend fun detectSuspend(bitmap: Bitmap, lang: String): List<DetectedRegion> =
        suspendCancellableCoroutine { cont ->
            detect(bitmap, lang, object : DetectCallback {
                override fun onSuccess(regions: List<DetectedRegion>) {
                    if (cont.isActive) cont.resume(regions)
                }

                override fun onFailure(error: String) {
                    if (cont.isActive) cont.resume(emptyList())
                }
            })
        }

    private fun detectTiled(bitmap: Bitmap, lang: String, cb: DetectCallback) {
        val results = mutableListOf<DetectedRegion>()
        val starts = StripeTiling.startPositions(bitmap.height, TILE_HEIGHT, TILE_OVERLAP, 512)
        val lock = Any()
        var nextIndex = 0
        var active = 0
        var delivered = false

        fun pump() {
            val launches = mutableListOf<Pair<Int, Bitmap>>()
            var finalResults: List<DetectedRegion>? = null

            synchronized(lock) {
                while (active < MAX_PARALLEL_TILES && nextIndex < starts.size) {
                    val top = starts[nextIndex++]
                    val bottom = min(bitmap.height, top + TILE_HEIGHT)
                    val height = bottom - top
                    if (height <= 0) continue
                    val stripe = try {
                        Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
                    } catch (_: Throwable) {
                        continue
                    }
                    active++
                    launches += top to stripe
                }

                if (!delivered && active == 0 && nextIndex >= starts.size) {
                    delivered = true
                    finalResults = results
                        .sortedWith(compareBy<DetectedRegion> { it.rect.top }.thenBy { it.rect.left })
                        .distinctBy {
                            "${(it.rect.left / 3f).toInt()}:${(it.rect.top / 3f).toInt()}:" +
                                "${(it.rect.right / 3f).toInt()}:${(it.rect.bottom / 3f).toInt()}"
                        }
                }
            }

            val completed = finalResults
            if (completed != null) {
                if (completed.isEmpty()) main.post { cb.onFailure("Tidak ada teks terdeteksi") }
                else main.post { cb.onSuccess(completed) }
                return
            }

            for ((top, stripe) in launches) {
                detectSingle(stripe, lang, object : DetectCallback {
                    private fun finish(regions: List<DetectedRegion>) {
                        synchronized(lock) {
                            regions.forEach { item ->
                                results += item.copy(
                                    rect = RectF(
                                        item.rect.left,
                                        item.rect.top + top,
                                        item.rect.right,
                                        item.rect.bottom + top
                                    )
                                )
                            }
                            active--
                        }
                        if (!stripe.isRecycled) stripe.recycle()
                        pump()
                    }

                    override fun onSuccess(regions: List<DetectedRegion>) = finish(regions)
                    override fun onFailure(error: String) = finish(emptyList())
                })
            }
        }

        pump()
    }

    private fun detectSingle(bitmap: Bitmap, lang: String, cb: DetectCallback) {
        val prepared = try {
            preprocess(bitmap)
        } catch (oom: OutOfMemoryError) {
            System.gc()
            main.post { cb.onFailure("Gambar terlalu besar untuk dideteksi (memori habis)") }
            return
        }

        val scale = prepared.scale
        val img = InputImage.fromBitmap(prepared.bitmap, 0)
        val cleanup = { if (!prepared.bitmap.isRecycled) prepared.bitmap.recycle() }

        when (lang) {
            MlKitOcrEngine.LANG_ZH -> run(chRec, img, MlKitOcrEngine.LANG_ZH, scale, cleanup, cb)
            MlKitOcrEngine.LANG_KO -> run(koRec, img, MlKitOcrEngine.LANG_KO, scale, cleanup, cb)
            MlKitOcrEngine.LANG_EN -> run(latinRec, img, MlKitOcrEngine.LANG_EN, scale, cleanup, cb)
            else -> autoRecognizeBest(img, scale, cleanup, cb)
        }
    }

    private fun autoRecognizeBest(
        img: InputImage,
        scale: Float,
        cleanup: () -> Unit,
        cb: DetectCallback
    ) {
        val lock = Any()
        val best = arrayOfNulls<ResultPack>(3)
        var completed = 0

        fun record(index: Int, pack: ResultPack?) {
            var chosen: ResultPack? = null
            var shouldDeliver = false
            synchronized(lock) {
                if (pack != null) best[index] = pack
                completed++
                if (completed == best.size) {
                    chosen = best.filterNotNull().maxWithOrNull(
                        compareBy<ResultPack> { it.blocks.size }.thenBy { it.text.length }
                    )
                    shouldDeliver = true
                }
            }

            if (!shouldDeliver) return
            cleanup()
            val result = chosen
            if (result == null || result.blocks.isEmpty()) {
                main.post { cb.onFailure("Tidak ada teks terdeteksi") }
            } else {
                main.post { cb.onSuccess(result.blocks) }
            }
        }

        latinRec.process(img)
            .addOnSuccessListener { record(0, toPack(it, MlKitOcrEngine.LANG_EN, scale)) }
            .addOnFailureListener { record(0, null) }
        chRec.process(img)
            .addOnSuccessListener { record(1, toPack(it, MlKitOcrEngine.LANG_ZH, scale)) }
            .addOnFailureListener { record(1, null) }
        koRec.process(img)
            .addOnSuccessListener { record(2, toPack(it, MlKitOcrEngine.LANG_KO, scale)) }
            .addOnFailureListener { record(2, null) }
    }

    private data class ResultPack(val blocks: List<DetectedRegion>, val text: String)

    private fun toPack(result: Text, lang: String, scale: Float): ResultPack {
        val inv = if (scale > 0f) 1f / scale else 1f
        val lines = result.textBlocks
            .flatMap { it.lines }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .mapNotNull { line ->
                val value = line.text.trim()
                // Abaikan noise yang hanya berupa tanda baca tunggal.
                if (value.none { it.isLetterOrDigit() }) return@mapNotNull null
                val bb = line.boundingBox ?: return@mapNotNull null
                val rect = RectF(bb.left * inv, bb.top * inv, bb.right * inv, bb.bottom * inv)
                if (rect.width() <= 0f || rect.height() <= 0f) return@mapNotNull null
                DetectedRegion(rect, lang, value)
            }
        val text = if (lines.isNotEmpty()) lines.joinToString(" ") { it.text } else result.text.trim()
        return ResultPack(lines, text)
    }

    private fun run(
        rec: TextRecognizer,
        img: InputImage,
        lang: String,
        scale: Float,
        cleanup: () -> Unit,
        cb: DetectCallback
    ) {
        rec.process(img)
            .addOnSuccessListener { result ->
                cleanup()
                val pack = toPack(result, lang, scale)
                if (pack.blocks.isEmpty()) {
                    main.post { cb.onFailure("Tidak ada teks terdeteksi") }
                } else {
                    main.post { cb.onSuccess(pack.blocks) }
                }
            }
            .addOnFailureListener { e ->
                cleanup()
                main.post { cb.onFailure(e.message ?: "ML Kit error") }
            }
    }

    private data class Prepared(val bitmap: Bitmap, val scale: Float)

    private const val MAX_DETECT_SIDE = 1280

    private fun preprocess(src: Bitmap): Prepared {
        val longest = maxOf(src.width, src.height)
        val scale = when {
            longest > MAX_DETECT_SIDE -> (MAX_DETECT_SIDE.toFloat() / longest).coerceIn(0.05f, 1f)
            src.width < 600 || src.height < 600 -> minOf(MAX_DETECT_SIDE.toFloat() / src.width, MAX_DETECT_SIDE.toFloat() / src.height, 3f).coerceAtLeast(1f)
            src.width < 1200 || src.height < 1200 -> minOf(MAX_DETECT_SIDE.toFloat() / src.width, MAX_DETECT_SIDE.toFloat() / src.height, 2f).coerceAtLeast(1f)
            else -> 1f
        }

        val targetW = (src.width * scale).toInt().coerceAtLeast(1)
        val targetH = (src.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(
                android.graphics.ColorMatrix(floatArrayOf(
                    1.25f, 0f, 0f, 0f, -10f,
                    0f, 1.25f, 0f, 0f, -10f,
                    0f, 0f, 1.25f, 0f, -10f,
                    0f, 0f, 0f, 1f, 0f
                ))
            )
        }
        val dst = android.graphics.RectF(0f, 0f, targetW.toFloat(), targetH.toFloat())
        canvas.drawBitmap(src, null, dst, paint)
        return Prepared(out, scale)
    }
}
