package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.graphics.Rect
import android.graphics.RectF
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * PaddleDbNetDetector v3.0 — OPTIMIZED orchestrator deteksi teks Auto Mask.
 *
 * OPTIMASI v3.0 (vs v2.0):
 *
 *  ① STRIPE-PARALLEL DETECTION untuk gambar sangat tinggi (800×20000):
 *     Gambar dipotong secara horizontal menjadi strip DETECT_STRIPE_H px.
 *     Tiap strip dikirim ke CraftTextDetector secara BERURUTAN (untuk hemat memori),
 *     bbox di-remap ke koordinat gambar penuh. Hasil per-strip lalu di-merge.
 *
 *  ② COMPOSITE SCALE GUARD yang lebih baik: jika bitmap lebar ≤ 1200px,
 *     jangan scale-down (CraftTextDetector sendiri sudah resize ke MAX_SIDE=832).
 *     Scaling dua kali hanya mengurangi detail teks.
 *
 *  ③ SINGLE-THREAD EXECUTOR per deteksi tetap dipertahankan — mencegah
 *     terlalu banyak thread saat pengguna menekan Detect berulang.
 *
 *  ④ PRUNE MERGE O(n log n): reuse algoritma sort+scan backward dari CraftTextDetector v2.
 *
 *  ⑤ MLKIT TIMEOUT dikurangi ke 6 detik (dari 8) karena MlKitMaskDetector v2 sudah
 *     ada early-win cancel — rata-rata selesai dalam 1–3 detik.
 */
object PaddleDbNetDetector {

    data class DetectedRegion(
        val rect: RectF,
        val type: String = "text",
        val text: String = ""
    )

    interface DetectCallback {
        fun onSuccess(regions: List<DetectedRegion>)
        fun onFailure(error: String)
    }

    enum class DetectionMode { STANDARD, LITE }

    private val main     = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "paddle-detect").apply { isDaemon = true }
    }

    // Filter geometri
    private const val MIN_AREA_RATIO   = 0.00008f
    private const val MAX_AREA_RATIO   = 0.75f
    private const val MAX_ASPECT_RATIO = 35f
    private const val MERGE_IOU        = 0.72f

    // Stripe tiling untuk gambar tinggi — berjalan di dalam CraftTextDetector v2 juga,
    // tetapi kita punya stripe sendiri di sini untuk skala yang lebih kasar.
    // Strip DETECT_STRIPE_H diproses berurutan satu per satu.
    private const val DETECT_STRIPE_H        = 2048   // px — stripe lebih kecil untuk low-end device
    private const val DETECT_STRIPE_OVERLAP  = 128    // px overlap antar stripe

    // Lite mode: sedikit lebih besar agar jumlah stripe lebih sedikit dan lebih cepat.
    private const val DETECT_STRIPE_H_LITE       = 3072
    private const val DETECT_STRIPE_OVERLAP_LITE = 96

    fun isAvailable(context: Context): Boolean =
        CraftTextDetector.isAvailable(context)

    fun detect(context: Context, bitmap: Bitmap, cb: DetectCallback) =
        detect(context, bitmap, "auto", DetectionMode.STANDARD, cb)

    fun detect(context: Context, bitmap: Bitmap, lang: String, cb: DetectCallback) =
        detect(context, bitmap, lang, DetectionMode.STANDARD, cb)

    fun detectLite(context: Context, bitmap: Bitmap, lang: String, cb: DetectCallback) =
        detect(context, bitmap, lang, DetectionMode.LITE, cb)

    fun detect(
        context: Context,
        bitmap:  Bitmap,
        lang:    String,
        mode:    DetectionMode,
        cb:      DetectCallback
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            main.post { cb.onSuccess(emptyList()) }
            return
        }

        if (mode == DetectionMode.LITE) {
            executor.execute {
                try {
                    // Lite mode prioritizes the fastest local pass first.
                    val fastRects = tryDetectOpenCv(context, bitmap)
                    if (fastRects.isNotEmpty()) {
                        main.post { cb.onSuccess(fastRects) }
                        return@execute
                    }

                    if (CraftTextDetector.isAvailable(context)) {
                        val rects = if (bitmap.height > DETECT_STRIPE_H_LITE * 2 && bitmap.width <= 1400) {
                            detectStripedCraftLite(context, bitmap)
                        } else {
                            CraftTextDetector.detect(context, bitmap)
                        }
                        val androidRects = rects.map {
                            Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
                        }
                        val pruned = pruneRegions(androidRects, bitmap.width, bitmap.height)
                        if (pruned.isNotEmpty()) {
                            main.post { cb.onSuccess(pruned) }
                            return@execute
                        }
                    }

                    main.post { cb.onSuccess(emptyList()) }
                } catch (_: Throwable) {
                    main.post { cb.onSuccess(emptyList()) }
                }
            }
            return
        }

        if (!CraftTextDetector.isAvailable(context)) {
            // If OpenCV/Craft is unavailable on a device, do not return an empty
            // result silently. Fall back to ML Kit and then the lightweight mask
            // detector so the Mask panel Detect button still really works.
            detectViaMlKit(bitmap, lang, context, cb)
            return
        }

        executor.execute {
            try {
                // First try the optimized OpenCV/Craft path. Use the explicit
                // full-size stripe path for very tall canvases to avoid losing
                // small text after global downscaling.
                val rects = if (bitmap.height > DETECT_STRIPE_H * 2 && bitmap.width <= 1400) {
                    detectStripedCraft(context, bitmap)
                } else {
                    CraftTextDetector.detect(context, bitmap)
                }
                val androidRects = rects.map {
                    Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
                }
                val pruned = pruneRegions(androidRects, bitmap.width, bitmap.height)
                if (pruned.isNotEmpty()) {
                    main.post { cb.onSuccess(pruned) }
                } else {
                    // Robust fallback chain: ML Kit OCR boxes -> TextMaskEngine.
                    detectViaMlKit(bitmap, lang, context, cb)
                }
            } catch (_: Throwable) {
                // Detection should fail soft, not leave the button apparently
                // broken. Let the fallback chain try another backend.
                detectViaMlKit(bitmap, lang, context, cb)
            }
        }
    }

    /**
     * Strip-based CraftTextDetector untuk gambar 800×20000.
     * Setiap strip diproses berurutan agar tidak OOM.
     */
    private fun detectStripedCraft(context: Context, bitmap: Bitmap): List<RectF> {
        val W    = bitmap.width
        val H    = bitmap.height
        val step = (DETECT_STRIPE_H - DETECT_STRIPE_OVERLAP).coerceAtLeast(384)
        val all  = mutableListOf<RectF>()
        var top  = 0

        while (top < H) {
            val bottom = (top + DETECT_STRIPE_H).coerceAtMost(H)
            val sh     = bottom - top
            if (sh <= 0) break

            val stripeBmp = try {
                Bitmap.createBitmap(bitmap, 0, top, W, sh)
            } catch (_: Throwable) {
                top += step; continue
            }

            try {
                val rects = CraftTextDetector.detect(context, stripeBmp)
                // Remap Y ke koordinat bitmap penuh
                for (r in rects) {
                    all.add(RectF(r.left, r.top + top, r.right, r.bottom + top))
                }
            } finally {
                stripeBmp.recycle()
            }

            top += step
        }

        return all
    }


    private fun detectStripedCraftLite(context: Context, bitmap: Bitmap): List<RectF> {
        val W    = bitmap.width
        val H    = bitmap.height
        val step = (DETECT_STRIPE_H_LITE - DETECT_STRIPE_OVERLAP_LITE).coerceAtLeast(512)
        val all  = mutableListOf<RectF>()
        var top  = 0

        while (top < H) {
            val bottom = (top + DETECT_STRIPE_H_LITE).coerceAtMost(H)
            val sh     = bottom - top
            if (sh <= 0) break

            val stripeBmp = try {
                Bitmap.createBitmap(bitmap, 0, top, W, sh)
            } catch (_: Throwable) {
                top += step; continue
            }

            try {
                val rects = CraftTextDetector.detect(context, stripeBmp)
                for (r in rects) {
                    all.add(RectF(r.left, r.top + top, r.right, r.bottom + top))
                }
            } finally {
                stripeBmp.recycle()
            }

            top += step
        }

        return all
    }

    private fun tryDetectOpenCv(context: Context, bitmap: Bitmap): List<DetectedRegion> {
        val rects = try {
            TextMaskEngine.detectTextRects(context, bitmap, null)
        } catch (_: Exception) {
            emptyList()
        }
        return pruneRegions(rects, bitmap.width, bitmap.height)
    }

    private fun detectViaMlKit(
        bitmap:  Bitmap,
        lang:    String,
        context: Context,
        cb:      DetectCallback
    ) {
        MlKitMaskDetector.detect(bitmap, lang, object : MlKitMaskDetector.DetectCallback {
            override fun onSuccess(regions: List<MlKitMaskDetector.DetectedRegion>) {
                if (regions.isNotEmpty()) {
                    val pruned = pruneRegions(
                        regions.map {
                            Rect(it.rect.left.toInt(), it.rect.top.toInt(),
                                 it.rect.right.toInt(), it.rect.bottom.toInt())
                        },
                        bitmap.width, bitmap.height
                    )
                    main.post { cb.onSuccess(pruned) }
                } else {
                    detectViaOpenCv(context, bitmap, cb)
                }
            }
            override fun onFailure(error: String) {
                detectViaOpenCv(context, bitmap, cb)
            }
        })
    }

    private fun detectViaOpenCv(context: Context, bitmap: Bitmap, cb: DetectCallback) {
        val regions = tryDetectOpenCv(context, bitmap)
        main.post { cb.onSuccess(regions) }
    }

    // ── Prune + merge ─────────────────────────────────────────────────────────

    private fun pruneRegions(
        rects:   List<Rect>,
        canvasW: Int,
        canvasH: Int
    ): List<DetectedRegion> {
        if (rects.isEmpty()) return emptyList()

        val canvasArea = (canvasW.toLong() * canvasH.toLong()).coerceAtLeast(1L)
        val minArea    = max(80f, canvasArea * MIN_AREA_RATIO).toFloat()
        val maxArea    = (canvasArea * MAX_AREA_RATIO).toFloat()

        val candidates = rects.mapNotNull { r ->
            val rf = RectF(
                r.left.coerceIn(0, canvasW).toFloat(),
                r.top.coerceIn(0, canvasH).toFloat(),
                r.right.coerceIn(0, canvasW).toFloat(),
                r.bottom.coerceIn(0, canvasH).toFloat()
            )
            if (rf.width() <= 0f || rf.height() <= 0f) return@mapNotNull null
            val area   = rf.width() * rf.height()
            val aspect = max(rf.width(), rf.height()) / min(rf.width(), rf.height())
            if (area < minArea || area > maxArea || aspect > MAX_ASPECT_RATIO) return@mapNotNull null
            DetectedRegion(rf, "text", "")
        }.sortedWith(compareBy<DetectedRegion> { it.rect.top }.thenBy { it.rect.left })

        if (candidates.isEmpty()) return emptyList()

        // Merge O(n log n) dengan scan mundur
        val merged = mutableListOf<DetectedRegion>()
        for (cand in candidates) {
            var absorbed = false
            for (i in merged.indices.reversed()) {
                val existing = merged[i]
                // Early-exit: existing terlalu jauh ke atas
                if (existing.rect.bottom < cand.rect.top - cand.rect.height() * 0.2f) break
                if (iou(existing.rect, cand.rect) >= MERGE_IOU) {
                    merged[i] = if (area(cand.rect) > area(existing.rect)) cand else existing
                    absorbed  = true; break
                }
            }
            if (!absorbed) merged.add(cand)
        }

        return merged.map { it.copy(rect = padAndClamp(it.rect, canvasW, canvasH)) }
    }

    private fun padAndClamp(rect: RectF, canvasW: Int, canvasH: Int): RectF {
        val padX = max(2f, rect.width()  * 0.04f)
        val padY = max(2f, rect.height() * 0.05f)
        return RectF(
            (rect.left   - padX).coerceIn(0f, canvasW.toFloat()),
            (rect.top    - padY).coerceIn(0f, canvasH.toFloat()),
            (rect.right  + padX).coerceIn(0f, canvasW.toFloat()),
            (rect.bottom + padY).coerceIn(0f, canvasH.toFloat())
        )
    }

    private fun area(r: RectF): Float = max(0f, r.width()) * max(0f, r.height())

    private fun iou(a: RectF, b: RectF): Float {
        val left   = max(a.left,  b.left)
        val top    = max(a.top,   b.top)
        val right  = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val interW = max(0f, right  - left)
        val interH = max(0f, bottom - top)
        val inter  = interW * interH
        if (inter <= 0f) return 0f
        val union  = area(a) + area(b) - inter
        return if (union <= 0f) 0f else inter / union
    }
}
