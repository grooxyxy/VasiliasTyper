package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
import kotlin.math.roundToInt

/**
 * Android ONNX runtime for dmMaze/zyddnys ComicTextDetector beta-0.2.1.
 *
 * Only the model's `seg` output is used. This is the dedicated pixel-level text
 * segmentation head; bubble/block output is intentionally ignored so Auto Mask
 * cannot turn speech-bubble bounds into masks again.
 *
 * Model location:
 *   app/src/main/assets/models/comictextdetector/v1/comictextdetector.pt.onnx
 * Runtime copy:
 *   filesDir/models/comictextdetector/v1/comictextdetector.pt.onnx
 */
object ComicTextDetector {
    private const val TAG = "ComicTextDetector"
    private const val MODEL_KEY = "comictextdetector"
    private const val MODEL_VERSION = 1
    private const val MODEL_FILE = "comictextdetector.pt.onnx"
    private const val MIN_MODEL_BYTES = 70_000_000L
    private const val INPUT_SIZE = 1024
    private const val STRIP_HEIGHT = 1536
    private const val STRIP_OVERLAP = 96
    private const val MASK_THRESHOLD = 0.30f
    private const val MIN_COMPONENT_AREA = 18.0

    @Volatile private var env: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    @Volatile private var initFailed = false
    @Volatile private var initError: String? = null
    private val initLock = Any()
    private val inFlight = AtomicBoolean(false)

    fun lastError(): String? = initError

    fun healthMessage(context: Context): String = when {
        isAvailable(context) -> "ComicTextDetector ready"
        !initError.isNullOrBlank() -> initError!!
        else -> "ComicTextDetector unavailable"
    }

    fun isAvailable(context: Context): Boolean {
        if (session != null) return true
        if (initFailed) return false
        synchronized(initLock) {
            if (session != null) return true
            if (initFailed) return false
            return try {
                session = loadSession(context)
                val ok = session != null
                if (!ok) {
                    initFailed = true
                    initError = "Model ComicTextDetector tidak ditemukan; lihat assets/models/comictextdetector/v1"
                } else {
                    initError = null
                }
                ok
            } catch (t: Throwable) {
                initFailed = true
                initError = t.message ?: t.javaClass.simpleName
                Log.e(TAG, "ComicTextDetector init failed", t)
                false
            }
        }
    }

    fun warmup(context: Context) {
        isAvailable(context)
    }

    fun clearSession() {
        synchronized(initLock) {
            session?.close()
            session = null
            initFailed = false
            initError = null
        }
    }

    fun detect(context: Context, bitmap: Bitmap): List<RectF> {
        if (bitmap.width <= 0 || bitmap.height <= 0 || !isAvailable(context)) return emptyList()
        if (!inFlight.compareAndSet(false, true)) return emptyList()
        return try {
            val output = mutableListOf<RectF>()
            val starts = StripeTiling.startPositions(bitmap.height, STRIP_HEIGHT, STRIP_OVERLAP, 256)
            for (top in starts) {
                val bottom = min(bitmap.height, top + STRIP_HEIGHT)
                if (bottom <= top) continue
                val strip = try {
                    Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
                } catch (_: Throwable) {
                    continue
                }
                try {
                    detectSingle(strip).forEach { rect ->
                        output += RectF(rect.left, rect.top + top, rect.right, rect.bottom + top)
                    }
                } finally {
                    if (!strip.isRecycled) strip.recycle()
                }
            }
            deduplicate(output)
        } catch (t: Throwable) {
            initError = "ComicTextDetector inference gagal: ${t.message ?: t.javaClass.simpleName}"
            Log.e(TAG, "ComicTextDetector inference failed", t)
            emptyList()
        } finally {
            inFlight.set(false)
        }
    }

    private fun detectSingle(bitmap: Bitmap): List<RectF> {
        val sess = session ?: return emptyList()
        val inputName = sess.inputNames.firstOrNull() ?: return emptyList()
        val srcW = bitmap.width
        val srcH = bitmap.height
        val scale = min(INPUT_SIZE.toFloat() / srcW, INPUT_SIZE.toFloat() / srcH)
        val resizedW = max(1, (srcW * scale).roundToInt())
        val resizedH = max(1, (srcH * scale).roundToInt())
        // Model resmi memakai letterbox top-left dengan padding hitam hanya di
        // sisi kanan/bawah. Implementasi lama memusatkan gambar pada padding abu-
        // abu, sehingga posisi output dan crop balik tidak sesuai dengan training.
        val padX = 0
        val padY = 0

        val prepared = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888)
        Canvas(prepared).apply {
            drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            drawBitmap(bitmap, null, Rect(0, 0, resizedW, resizedH), paint)
        }

        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        prepared.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)
        prepared.recycle()
        val chw = FloatArray(3 * INPUT_SIZE * INPUT_SIZE)
        val plane = INPUT_SIZE * INPUT_SIZE
        for (i in pixels.indices) {
            val pixel = pixels[i]
            chw[i] = Color.red(pixel) / 255f
            chw[plane + i] = Color.green(pixel) / 255f
            chw[plane * 2 + i] = Color.blue(pixel) / 255f
        }

        val environment = env ?: OrtEnvironment.getEnvironment().also { env = it }
        OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(chw),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        ).use { tensor ->
            sess.run(mapOf(inputName to tensor)).use { result ->
                val segTensor = findSegmentationTensor(sess, result) ?: return emptyList()
                val shape = (segTensor.info as? TensorInfo)?.shape ?: return emptyList()
                if (shape.size < 2) return emptyList()
                val outH = shape[shape.size - 2].toInt().coerceAtLeast(1)
                val outW = shape[shape.size - 1].toInt().coerceAtLeast(1)
                val count = outW * outH
                val values = FloatArray(count)
                val buffer = segTensor.floatBuffer
                buffer.rewind()
                buffer.get(values, 0, min(count, buffer.remaining()))

                val probability = Mat(outH, outW, CvType.CV_32FC1)
                probability.put(0, 0, values)
                val resizedProbability = Mat()
                Imgproc.resize(
                    probability,
                    resizedProbability,
                    Size(INPUT_SIZE.toDouble(), INPUT_SIZE.toDouble()),
                    0.0,
                    0.0,
                    Imgproc.INTER_LINEAR
                )
                probability.release()

                val binaryFloat = Mat()
                Imgproc.threshold(
                    resizedProbability,
                    binaryFloat,
                    MASK_THRESHOLD.toDouble(),
                    255.0,
                    Imgproc.THRESH_BINARY
                )
                resizedProbability.release()
                val binary = Mat()
                binaryFloat.convertTo(binary, CvType.CV_8UC1)
                binaryFloat.release()

                val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
                Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel)
                kernel.release()

                val contours = mutableListOf<MatOfPoint>()
                val hierarchy = Mat()
                Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                hierarchy.release()

                val usableRight = padX + resizedW
                val usableBottom = padY + resizedH
                val rects = mutableListOf<RectF>()
                for (contour in contours) {
                    val area = Imgproc.contourArea(contour)
                    if (area < MIN_COMPONENT_AREA) continue
                    val r = Imgproc.boundingRect(contour)
                    val left = max(r.x, padX)
                    val top = max(r.y, padY)
                    val right = min(r.x + r.width, usableRight)
                    val bottom = min(r.y + r.height, usableBottom)
                    if (right <= left || bottom <= top) continue
                    val mapped = RectF(
                        ((left - padX) / scale).coerceIn(0f, srcW.toFloat()),
                        ((top - padY) / scale).coerceIn(0f, srcH.toFloat()),
                        ((right - padX) / scale).coerceIn(0f, srcW.toFloat()),
                        ((bottom - padY) / scale).coerceIn(0f, srcH.toFloat())
                    )
                    if (mapped.width() >= 3f && mapped.height() >= 3f &&
                        mapped.width() < srcW * 0.92f && mapped.height() < srcH * 0.45f) {
                        rects += mapped
                    }
                }
                contours.forEach { it.release() }
                binary.release()
                return mergeNearby(rects)
            }
        }
    }

    private fun findSegmentationTensor(
        sess: OrtSession,
        result: OrtSession.Result
    ): OnnxTensor? {
        val names = sess.outputNames.toList()
        val preferred = names.firstOrNull { it.equals("seg", true) || it.contains("seg", true) }
        if (preferred != null) {
            val value = result[preferred].orElse(null)
            if (value is OnnxTensor) return value
        }
        // beta-0.2.1 exports blk, seg, det in that order. Shape-based fallback
        // selects the one-channel spatial map rather than the YOLO/block tensor.
        for (name in names) {
            val tensor = result[name].orElse(null) as? OnnxTensor ?: continue
            val shape = (tensor.info as? TensorInfo)?.shape ?: continue
            if (shape.size >= 4 && shape[shape.size - 3] == 1L) return tensor
        }
        return null
    }

    private fun mergeNearby(input: List<RectF>): List<RectF> {
        val output = mutableListOf<RectF>()
        for (rect in input.sortedWith(compareBy<RectF> { it.top }.thenBy { it.left })) {
            val index = output.indexOfLast { existing ->
                val yOverlap = max(0f, min(existing.bottom, rect.bottom) - max(existing.top, rect.top))
                val gapX = max(0f, max(existing.left, rect.left) - min(existing.right, rect.right))
                yOverlap >= min(existing.height(), rect.height()) * 0.35f &&
                    gapX <= max(6f, min(existing.height(), rect.height()) * 0.7f)
            }
            if (index >= 0) output[index].union(rect) else output += RectF(rect)
        }
        return output
    }

    private fun deduplicate(input: List<RectF>): List<RectF> {
        val output = mutableListOf<RectF>()
        for (candidate in input.sortedByDescending { it.width() * it.height() }) {
            val duplicate = output.any { existing ->
                val left = max(existing.left, candidate.left)
                val top = max(existing.top, candidate.top)
                val right = min(existing.right, candidate.right)
                val bottom = min(existing.bottom, candidate.bottom)
                val intersection = max(0f, right - left) * max(0f, bottom - top)
                val smaller = min(
                    existing.width() * existing.height(),
                    candidate.width() * candidate.height()
                ).coerceAtLeast(1f)
                intersection / smaller >= 0.68f
            }
            if (!duplicate) output += candidate
        }
        return output.sortedWith(compareBy<RectF> { it.top }.thenBy { it.left })
    }

    private fun loadSession(context: Context): OrtSession? {
        val model = modelFile(context)
        val usable = when {
            model.exists() && model.length() >= MIN_MODEL_BYTES -> model
            assetExists(context) -> copyAsset(context)
            else -> null
        } ?: return null
        val environment = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(max(2, min(4, Runtime.getRuntime().availableProcessors() - 1)))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return try {
            environment.createSession(usable.absolutePath, options)
        } finally {
            options.close()
        }
    }

    private fun assetExists(context: Context): Boolean = try {
        context.assets.open(assetPath()).close()
        true
    } catch (_: Throwable) {
        false
    }

    private fun copyAsset(context: Context): File {
        val target = modelFile(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        context.assets.open(assetPath()).use { input ->
            temporary.outputStream().use { output -> input.copyTo(output) }
        }
        if (temporary.length() < MIN_MODEL_BYTES) {
            temporary.delete()
            throw IllegalStateException("Asset ComicTextDetector tidak lengkap")
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return target
    }

    private fun modelFile(context: Context): File =
        ModelPathRegistry.versionedFile(context, MODEL_KEY, MODEL_VERSION, MODEL_FILE)

    private fun assetPath(): String =
        ModelPathRegistry.versionedAssetPath(MODEL_KEY, MODEL_VERSION, MODEL_FILE)
}
