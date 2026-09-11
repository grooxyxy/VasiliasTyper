
package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * PP-OCRv6 Small detector for Android.
 *
 * Expected asset/file layout:
 *   assets/models/ppocrv6_small_det_onnx/v1/inference.onnx
 *   filesDir/models/ppocrv6_small_det_onnx/v1/inference.onnx
 *
 * The runtime accepts a dynamic NCHW DBNet-style ONNX export. It deliberately
 * has no fallback to CRAFT/ML Kit so this option remains a distinct model path.
 */
object PpOcrSmallDetector {
    private const val TAG = "PpOcrSmallDetector"
    private const val MODEL_KEY = "ppocrv6_small_det_onnx"
    private const val MODEL_VERSION = 1
    private const val MODEL_FILE = "inference.onnx"
    private const val MIN_MODEL_BYTES = 5_000_000L
    private const val MAX_INPUT_SIDE = 1280
    private const val STRIP_HEIGHT = 1280
    private const val STRIP_OVERLAP = 96
    private const val MIN_AREA = 40
    private const val MIN_HEIGHT = 6
    private const val THRESH = 0.34f

    @Volatile private var session: OrtSession? = null
    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var initFailed = false
    @Volatile private var initError: String? = null
    private val initLock = Any()
    private val inFlight = AtomicBoolean(false)

    data class ResultRect(val rect: Rect)

    fun lastError(): String? = initError

    fun resetRuntimeState() {
        synchronized(initLock) {
            session?.close()
            session = null
            initFailed = false
            initError = null
        }
    }

    fun healthMessage(context: Context): String = when {
        isAvailable(context) -> "PP-OCRv6 Small ready"
        !initError.isNullOrBlank() -> initError!!
        else -> "PP-OCRv6 Small unavailable"
    }

    fun isAvailable(context: Context): Boolean {
        if (initFailed) return false
        if (session != null) return true
        synchronized(initLock) {
            if (session != null) return true
            if (initFailed) return false
            return try {
                val s = loadSession(context)
                session = s
                if (s != null) {
                    initError = null
                    true
                } else {
                    initFailed = true
                    initError = "PP-OCRv6 Small model tidak ditemukan/kompatibel; lihat folder model v1"
                    false
                }
            } catch (t: Throwable) {
                initFailed = true
                initError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "PP-OCRv6 Small init failed", t)
                false
            }
        }
    }

    fun detectStriped(context: Context, bitmap: Bitmap): List<PaddleDbNetDetector.DetectedRegion> {
        if (bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        if (!isAvailable(context)) return emptyList()
        if (!inFlight.compareAndSet(false, true)) {
            return emptyList()
        }
        try {
            val result = mutableListOf<PaddleDbNetDetector.DetectedRegion>()
            val starts = StripeTiling.startPositions(bitmap.height, STRIP_HEIGHT, STRIP_OVERLAP, 256)
            for (top in starts) {
                val bottom = min(bitmap.height, top + STRIP_HEIGHT)
                val sh = bottom - top
                if (sh <= 0) continue
                val crop = try {
                    Bitmap.createBitmap(bitmap, 0, top, bitmap.width, sh)
                } catch (t: Throwable) {
                    continue
                }
                try {
                    val rects = try {
                        detectSingleStrip(crop)
                    } catch (t: Throwable) {
                        initError = "PP-OCRv6 Small inference gagal: ${t.message ?: t.javaClass.simpleName}"
                        Log.e(TAG, "PP-OCRv6 Small strip inference failed at y=$top", t)
                        emptyList()
                    }
                    for (r in rects) {
                        result.add(
                            PaddleDbNetDetector.DetectedRegion(
                                android.graphics.RectF(r.left.toFloat(), (r.top + top).toFloat(), r.right.toFloat(), (r.bottom + top).toFloat()),
                                "text",
                                ""
                            )
                        )
                    }
                } finally {
                    crop.recycle()
                }
            }
            return mergeRegions(result, bitmap.width, bitmap.height)
        } finally {
            inFlight.set(false)
        }
    }

    private fun detectSingleStrip(bitmap: Bitmap): List<Rect> {
        val sess = session ?: return emptyList()
        val inNames = sess.inputNames.toList()
        if (inNames.isEmpty()) return emptyList()

        val w = bitmap.width
        val h = bitmap.height
        val scale = min(1f, MAX_INPUT_SIDE.toFloat() / max(w, h))
        val resizedW = max(1, (w * scale).toInt())
        val resizedH = max(1, (h * scale).toInt())
        val padW = max(32, ((resizedW + 31) / 32) * 32)
        val padH = max(32, ((resizedH + 31) / 32) * 32)

        // Pertahankan aspect ratio lalu pad kanan/bawah. Versi lama men-stretch
        // gambar asli langsung ke padW×padH; pada webtoon lebar, karakter menjadi
        // gepeng dan probability map DBNet hampir selalu kosong.
        val padded = if (padW == w && padH == h) bitmap else Bitmap.createBitmap(padW, padH, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.WHITE)
            val dst = android.graphics.Rect(0, 0, resizedW, resizedH)
            canvas.drawBitmap(bitmap, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }

        val inputTensor = bitmapToInputTensor(padded, padW, padH)
        if (padded !== bitmap) padded.recycle()
        if (inputTensor == null) return emptyList()

        val outMap = try {
            sess.run(mapOf(inNames[0] to inputTensor))
        } catch (t: Throwable) {
            inputTensor.close()
            throw t
        }

        try {
            val outputName = sess.outputNames.firstOrNull() ?: return emptyList()
            val outTensor = outMap[outputName]?.orElse(null) as? OnnxTensor ?: return emptyList()
            val outShape = (outTensor.info as? TensorInfo)?.shape ?: longArrayOf()
            val raw = outTensor.floatBuffer
            val outW: Int
            val outH: Int
            when {
                outShape.size >= 4 -> {
                    outH = outShape[outShape.size - 2].toInt().coerceAtLeast(1)
                    outW = outShape[outShape.size - 1].toInt().coerceAtLeast(1)
                }
                outShape.size == 3 -> {
                    outH = outShape[1].toInt().coerceAtLeast(1)
                    outW = outShape[2].toInt().coerceAtLeast(1)
                }
                else -> {
                    outH = h.coerceAtLeast(1)
                    outW = w.coerceAtLeast(1)
                }
            }
            val need = outW * outH
            val arr = FloatArray(need)
            raw.rewind()
            val got = min(need, raw.remaining())
            raw.get(arr, 0, got)

            val prob = Mat(outH, outW, CvType.CV_32FC1)
            prob.put(0, 0, arr)
            val resized = Mat()
            Imgproc.resize(prob, resized, Size(padW.toDouble(), padH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            prob.release()

            val maskFloat = Mat()
            Imgproc.threshold(resized, maskFloat, THRESH.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            resized.release()

            // findContours hanya menerima CV_8UC1. Output threshold tetap CV_32F
            // bila probability map berasal dari tensor float; inilah sumber crash
            // OpenCV assertion pada implementasi sebelumnya.
            val mask = Mat()
            maskFloat.convertTo(mask, CvType.CV_8UC1)
            maskFloat.release()

            val closeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, closeKernel)
            closeKernel.release()

            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(mask, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            val out = mutableListOf<Rect>()
            val areaTotal = resizedW.toDouble() * resizedH.toDouble()
            for (c in contours) {
                val rect = Imgproc.boundingRect(c)
                if (rect.x >= resizedW || rect.y >= resizedH) continue
                val clippedRight = min(rect.x + rect.width, resizedW)
                val clippedBottom = min(rect.y + rect.height, resizedH)
                val clippedW = clippedRight - rect.x
                val clippedH = clippedBottom - rect.y
                val area = clippedW.toLong() * clippedH.toLong()
                val aspect = clippedW.toDouble() / clippedH.coerceAtLeast(1)
                if (clippedW < 4 || clippedH < MIN_HEIGHT) continue
                if (area < MIN_AREA || area > (areaTotal * 0.35).toLong()) continue
                if (aspect < 0.10 || aspect > 28.0) continue
                val clippedRect = org.opencv.core.Rect(rect.x, rect.y, clippedW, clippedH)
                val roi = mask.submat(clippedRect)
                val fillRatio = try {
                    org.opencv.core.Core.countNonZero(roi).toDouble() / area.coerceAtLeast(1).toDouble()
                } finally {
                    roi.release()
                }
                if (fillRatio < 0.025 || fillRatio > 0.96) continue
                val invScale = 1f / scale.coerceAtLeast(0.0001f)
                out.add(Rect(
                    (rect.x * invScale).toInt().coerceIn(0, w),
                    (rect.y * invScale).toInt().coerceIn(0, h),
                    (clippedRight * invScale).toInt().coerceIn(0, w),
                    (clippedBottom * invScale).toInt().coerceIn(0, h)
                ))
            }
            mask.release()
            contours.forEach { it.release() }
            return mergeRects(out)
        } finally {
            outMap.close()
            inputTensor.close()
        }
    }

    private fun bitmapToInputTensor(bitmap: Bitmap, w: Int, h: Int): OnnxTensor? {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buf = FloatArray(3 * w * h)
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        var rOff = 0
        var gOff = w * h
        var bOff = 2 * w * h
        for (i in pixels.indices) {
            val p = pixels[i]
            // Model PP-OCR memakai tensor RGB CHW. Versi lama menaruh BGR
            // sehingga confidence teks turun dan false-positive meningkat.
            val r = (android.graphics.Color.red(p) / 255f - mean[0]) / std[0]
            val g = (android.graphics.Color.green(p) / 255f - mean[1]) / std[1]
            val b = (android.graphics.Color.blue(p) / 255f - mean[2]) / std[2]
            buf[rOff++] = r
            buf[gOff++] = g
            buf[bOff++] = b
        }
        val envLocal = env ?: OrtEnvironment.getEnvironment().also { env = it }
        return OnnxTensor.createTensor(envLocal, FloatBuffer.wrap(buf), longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    private fun mergeRegions(input: List<PaddleDbNetDetector.DetectedRegion>, maxW: Int, maxH: Int): List<PaddleDbNetDetector.DetectedRegion> {
        if (input.isEmpty()) return emptyList()
        val rects = input.map {
            Rect(
                it.rect.left.toInt().coerceIn(0, maxW),
                it.rect.top.toInt().coerceIn(0, maxH),
                it.rect.right.toInt().coerceIn(0, maxW),
                it.rect.bottom.toInt().coerceIn(0, maxH)
            )
        }
        return mergeRects(rects).map { r ->
            PaddleDbNetDetector.DetectedRegion(android.graphics.RectF(r), "text", "")
        }
    }

    private fun mergeRects(rects: List<Rect>): List<Rect> {
        if (rects.isEmpty()) return emptyList()
        val sorted = rects.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
        val out = mutableListOf<Rect>()
        for (r in sorted) {
            if (r.width() <= 0 || r.height() <= 0) continue
            var merged = false
            for (i in out.indices.reversed()) {
                val o = out[i]
                if (o.bottom + 8 < r.top) break
                if (iou(o, r) > 0.15f || horizontalClose(o, r)) {
                    out[i] = Rect(min(o.left, r.left), min(o.top, r.top), max(o.right, r.right), max(o.bottom, r.bottom))
                    merged = true
                    break
                }
            }
            if (!merged) out.add(Rect(r))
        }
        return out
    }

    private fun horizontalClose(a: Rect, b: Rect): Boolean {
        val padX = max(8, min(a.width(), b.width()) / 4)
        val padY = max(6, min(a.height(), b.height()) / 4)
        return a.left - padX < b.right && a.right + padX > b.left && a.top - padY < b.bottom && a.bottom + padY > b.top
    }

    private fun iou(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val iw = max(0, right - left)
        val ih = max(0, bottom - top)
        val inter = iw.toLong() * ih.toLong()
        val union = a.width().toLong() * a.height().toLong() + b.width().toLong() * b.height().toLong() - inter
        return if (union <= 0) 0f else inter.toFloat() / union.toFloat()
    }


    @Synchronized
    private fun loadSession(context: Context): OrtSession? {
        val file = modelFile(context)
        val envLocal = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(2, min(4, Runtime.getRuntime().availableProcessors() - 1)))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return try {
            val usableFile = when {
                file.exists() && file.length() >= MIN_MODEL_BYTES -> file
                assetExists(context) -> cacheAsset(context, replace = true)
                else -> null
            }
            usableFile?.let { envLocal.createSession(it.absolutePath, opts) }
        } finally {
            opts.close()
        }
    }

    private fun assetExists(context: Context): Boolean = try {
        context.assets.open(assetPath()).close(); true
    } catch (_: Throwable) { false }

    private fun cacheAsset(context: Context, replace: Boolean = false): File {
        val out = modelFile(context)
        out.parentFile?.mkdirs()
        if (replace || !out.exists() || out.length() < MIN_MODEL_BYTES) {
            val temp = File(out.parentFile, "${out.name}.tmp")
            context.assets.open(assetPath()).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (temp.length() < MIN_MODEL_BYTES) {
                temp.delete()
                throw IllegalStateException("Asset PP-OCRv6 Small tidak lengkap")
            }
            if (out.exists()) out.delete()
            if (!temp.renameTo(out)) {
                temp.copyTo(out, overwrite = true)
                temp.delete()
            }
        }
        return out
    }

    private fun modelFile(context: Context): File {
        return ModelPathRegistry.versionedFile(context, MODEL_KEY, MODEL_VERSION, MODEL_FILE)
    }

    private fun assetPath(): String = ModelPathRegistry.versionedAssetPath(MODEL_KEY, MODEL_VERSION, MODEL_FILE)
}
