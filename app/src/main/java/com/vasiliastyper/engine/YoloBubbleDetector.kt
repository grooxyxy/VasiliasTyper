package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Offline RT-DETR-v2 speech-bubble detector from
 * https://huggingface.co/ogkalu/comic-text-and-bubble-detector.
 *
 * The bundled mobile model is detector-v4-s_int8.onnx. Its interface is:
 *  - images: float32 [N, 3, 640, 640], RGB in the 0..1 range;
 *  - orig_target_sizes: int64 [N, 2], ordered as height then width;
 *  - labels: int64 [N, 300];
 *  - boxes: float32 [N, 300, 4], absolute x1/y1/x2/y2 coordinates;
 *  - scores: float32 [N, 300].
 *
 * Only class 0 (bubble) is returned. Classes 1 (text_bubble) and 2
 * (text_free) belong to the model but are intentionally excluded from bubble
 * geometry. Tall webtoons are processed in overlapping full-width stripes so
 * their content is not collapsed into a very narrow 640-pixel input.
 *
 * A missing or incompatible model returns an empty list. Callers retain the
 * existing OpenCV/ML Kit path as a crash-safe fallback.
 */
object YoloBubbleDetector {
    private const val TAG = "YoloBubbleDetector"
    private const val MODEL_KEY = "comic_text_bubble"
    private const val MODEL_VERSION = 4
    private const val MODEL_NAME = "detector-v4-s_int8.onnx"
    private const val EXPECTED_MODEL_BYTES = 11_120_765L
    private const val DEFAULT_INPUT_SIZE = 640
    private const val BUBBLE_CLASS_ID = 0L
    private const val CONFIDENCE_THRESHOLD = 0.25f
    private const val NMS_IOU_THRESHOLD = 0.50f
    private const val MAX_RESULTS = 160
    private const val MAX_STRIPE_HEIGHT = 1600
    private const val STRIPE_OVERLAP = 192

    data class Detection(val rect: RectF, val confidence: Float)

    @Volatile private var initialized = false
    @Volatile private var unavailable = false
    @Volatile private var environment: OrtEnvironment? = null
    @Volatile private var session: OrtSession? = null
    private val inferenceLock = Any()

    fun isAvailable(context: Context): Boolean = ensureSession(context.applicationContext) != null

    fun detect(context: Context, bitmap: Bitmap): List<Detection> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return emptyList()
        val activeSession = ensureSession(context.applicationContext) ?: return emptyList()
        val env = environment ?: return emptyList()

        return try {
            synchronized(inferenceLock) {
                detectTiled(env, activeSession, bitmap)
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Inferensi RT-DETR dilewati: ${error.message}")
            emptyList()
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
            val createdSession = try {
                env.createSession(modelFile.absolutePath, options)
            } finally {
                options.close()
            }
            validateInterface(createdSession)
            environment = env
            session = createdSession
            initialized = true
            Log.i(TAG, "RT-DETR comic bubble model aktif: ${modelFile.absolutePath}")
            createdSession
        } catch (error: Throwable) {
            Log.w(TAG, "RT-DETR comic bubble model tidak tersedia: ${error.message}")
            initialized = true
            unavailable = true
            session?.close()
            session = null
            null
        }
    }

    private fun validateInterface(activeSession: OrtSession) {
        require(activeSession.inputNames.containsAll(listOf("images", "orig_target_sizes"))) {
            "Input ONNX tidak cocok: ${activeSession.inputNames}"
        }
        require(activeSession.outputNames.containsAll(listOf("labels", "boxes", "scores"))) {
            "Output ONNX tidak cocok: ${activeSession.outputNames}"
        }
        val imageInfo = activeSession.inputInfo["images"]?.info as? TensorInfo
        require(imageInfo?.shape?.size == 4 && imageInfo.shape.getOrNull(1) == 3L) {
            "Shape input images tidak didukung"
        }
    }

    private fun resolveModelFile(context: Context): File? {
        val downloadedCandidates = listOf(
            ModelPathRegistry.versionedFile(context, MODEL_KEY, MODEL_VERSION, MODEL_NAME),
            File(context.filesDir, "models/$MODEL_NAME")
        )
        downloadedCandidates.firstOrNull(::isValidModel)?.let { return it }

        val assetCandidates = listOf(
            ModelPathRegistry.versionedAssetPath(MODEL_KEY, MODEL_VERSION, MODEL_NAME),
            "models/$MODEL_NAME"
        )
        for (assetPath in assetCandidates) {
            try {
                val assetLength = context.assets.openFd(assetPath).use { it.length }
                if (assetLength != EXPECTED_MODEL_BYTES) continue
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
                return cached
            } catch (_: Throwable) {
                // Continue to the next supported asset location.
            }
        }
        return null
    }

    private fun isValidModel(file: File): Boolean =
        file.isFile && file.length() == EXPECTED_MODEL_BYTES

    private fun detectTiled(
        env: OrtEnvironment,
        activeSession: OrtSession,
        bitmap: Bitmap
    ): List<Detection> {
        val useStripes = bitmap.height > MAX_STRIPE_HEIGHT || bitmap.height > bitmap.width * 2
        if (!useStripes) return nonMaxSuppression(runInference(env, activeSession, bitmap))

        val stripeHeight = min(bitmap.height, max(bitmap.width * 2, DEFAULT_INPUT_SIZE).coerceAtMost(MAX_STRIPE_HEIGHT))
        val overlap = min(STRIPE_OVERLAP, (stripeHeight / 4).coerceAtLeast(1))
        val detections = mutableListOf<Detection>()
        for (top in StripeTiling.startPositions(bitmap.height, stripeHeight, overlap, DEFAULT_INPUT_SIZE)) {
            val bottom = min(bitmap.height, top + stripeHeight)
            val stripe = try {
                Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bottom - top)
            } catch (_: Throwable) {
                continue
            }
            try {
                runInference(env, activeSession, stripe).forEach { detection ->
                    val rect = RectF(detection.rect).apply { offset(0f, top.toFloat()) }
                    detections += detection.copy(rect = rect)
                }
            } finally {
                if (!stripe.isRecycled) stripe.recycle()
            }
        }
        return nonMaxSuppression(detections)
    }

    private fun runInference(
        env: OrtEnvironment,
        activeSession: OrtSession,
        bitmap: Bitmap
    ): List<Detection> {
        val imageInfo = activeSession.inputInfo["images"]?.info as? TensorInfo
        val shape = imageInfo?.shape
        val inputHeight = shape?.getOrNull(2)?.takeIf { it > 0L }?.toInt() ?: DEFAULT_INPUT_SIZE
        val inputWidth = shape?.getOrNull(3)?.takeIf { it > 0L }?.toInt() ?: DEFAULT_INPUT_SIZE
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        val pixels = IntArray(inputWidth * inputHeight)
        resized.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        if (resized !== bitmap && !resized.isRecycled) resized.recycle()

        val planeSize = inputWidth * inputHeight
        val imageValues = FloatArray(planeSize * 3)
        for (index in pixels.indices) {
            val color = pixels[index]
            imageValues[index] = Color.red(color) / 255f
            imageValues[planeSize + index] = Color.green(color) / 255f
            imageValues[planeSize * 2 + index] = Color.blue(color) / 255f
        }

        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(imageValues),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        ).use { imageTensor ->
            OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(longArrayOf(bitmap.height.toLong(), bitmap.width.toLong())),
                longArrayOf(1, 2)
            ).use { sizeTensor ->
                activeSession.run(
                    mapOf("images" to imageTensor, "orig_target_sizes" to sizeTensor)
                ).use { outputs ->
                    val labelsTensor = outputs["labels"].orElse(null) as? OnnxTensor ?: return emptyList()
                    val boxesTensor = outputs["boxes"].orElse(null) as? OnnxTensor ?: return emptyList()
                    val scoresTensor = outputs["scores"].orElse(null) as? OnnxTensor ?: return emptyList()
                    return decodeOutputs(
                        labelsTensor.value,
                        boxesTensor.value,
                        scoresTensor.value,
                        bitmap.width,
                        bitmap.height
                    )
                }
            }
        }
    }

    private fun decodeOutputs(
        labelsValue: Any,
        boxesValue: Any,
        scoresValue: Any,
        imageWidth: Int,
        imageHeight: Int
    ): List<Detection> {
        val labels = (labelsValue as? Array<*>)?.firstOrNull() as? LongArray ?: return emptyList()
        val scores = (scoresValue as? Array<*>)?.firstOrNull() as? FloatArray ?: return emptyList()
        val boxBatch = (boxesValue as? Array<*>)?.firstOrNull() as? Array<*> ?: return emptyList()
        val count = min(labels.size, min(scores.size, boxBatch.size))
        val detections = ArrayList<Detection>(count)

        for (index in 0 until count) {
            if (labels[index] != BUBBLE_CLASS_ID) continue
            val confidence = scores[index]
            if (!confidence.isFinite() || confidence < CONFIDENCE_THRESHOLD) continue
            val box = boxBatch[index] as? FloatArray ?: continue
            if (box.size < 4 || box.take(4).any { !it.isFinite() }) continue

            val left = min(box[0], box[2]).coerceIn(0f, imageWidth.toFloat())
            val top = min(box[1], box[3]).coerceIn(0f, imageHeight.toFloat())
            val right = max(box[0], box[2]).coerceIn(0f, imageWidth.toFloat())
            val bottom = max(box[1], box[3]).coerceIn(0f, imageHeight.toFloat())
            if (right - left < 12f || bottom - top < 10f) continue
            detections += Detection(RectF(left, top, right, bottom), confidence.coerceIn(0f, 1f))
        }
        return detections
    }

    private fun nonMaxSuppression(input: List<Detection>): List<Detection> {
        val kept = mutableListOf<Detection>()
        for (candidate in input.sortedByDescending { it.confidence }) {
            if (kept.none { iou(it.rect, candidate.rect) >= NMS_IOU_THRESHOLD }) {
                kept += candidate
                if (kept.size >= MAX_RESULTS) break
            }
        }
        return kept.sortedWith(compareBy<Detection> { it.rect.top }.thenBy { it.rect.left })
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        if (intersection <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
