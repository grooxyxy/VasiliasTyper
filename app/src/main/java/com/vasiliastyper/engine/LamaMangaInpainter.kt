package com.vasiliastyper.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Region
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Android inference pipeline for ogkalu/lama-manga-onnx-dynamic.
 *
 * Verified model contract (opset 18):
 *  image     float32 [N,3,H,W], RGB in [0,1]
 *  mask      float32 [N,1,H,W], 1 = hole, 0 = keep
 *  inpainted float32 [N,3,H,W], RGB in [0,1]
 *
 * H and W are dynamic. This implementation keeps them multiples of eight for
 * LaMa's encoder/decoder, limits the inference patch to protect Android memory,
 * and composites only the exact requested Region back onto the source bitmap.
 */
object LamaMangaInpainter {
    private const val TAG = "LamaMangaInpainter"
    private const val CONTEXT_PADDING = 96
    private const val ALIGNMENT = 8
    private const val MIN_SIDE = 64
    private const val MAX_SIDE_NORMAL = 768
    private const val MAX_SIDE_LOW_END = 512
    private const val MEMORY_ABORT_RATIO = 0.82f

    fun inpaint(
        context: Context,
        bitmap: Bitmap,
        region: Region,
        webtoonMode: Boolean = true,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        if (!bitmap.isMutable || bitmap.isRecycled || region.isEmpty) return false
        if (!LamaMangaModelManager.isAvailable(context)) {
            return fallback(bitmap, region, onProgress)
        }
        return try {
            runInference(context, bitmap, region, webtoonMode, onProgress)
        } catch (error: OutOfMemoryError) {
            Log.e(TAG, "LaMa Manga stopped by memory guard", error)
            System.gc()
            fallback(bitmap, region, onProgress)
        } catch (error: Throwable) {
            Log.e(TAG, "LaMa Manga inference failed: ${error.message}", error)
            fallback(bitmap, region, onProgress)
        }
    }

    private fun runInference(
        context: Context,
        bitmap: Bitmap,
        region: Region,
        webtoonMode: Boolean,
        onProgress: ((Float) -> Unit)?
    ): Boolean {
        val env = LamaMangaModelManager.getEnvironment() ?: return fallback(bitmap, region, onProgress)
        val session = LamaMangaModelManager.getSession() ?: return fallback(bitmap, region, onProgress)
        validateContract(session.inputInfo, session.outputInfo)

        val bounds = region.bounds
        val left = (bounds.left - CONTEXT_PADDING).coerceAtLeast(0)
        val top = (bounds.top - CONTEXT_PADDING).coerceAtLeast(0)
        val right = (bounds.right + CONTEXT_PADDING).coerceAtMost(bitmap.width)
        val bottom = (bounds.bottom + CONTEXT_PADDING).coerceAtMost(bitmap.height)
        val cropWidth = right - left
        val cropHeight = bottom - top
        if (cropWidth <= 0 || cropHeight <= 0) return false

        checkMemory()
        onProgress?.invoke(0.08f)
        val source = IntArray(cropWidth * cropHeight)
        bitmap.getPixels(source, 0, cropWidth, left, top, cropWidth, cropHeight)
        val shifted = TilingEngine.shiftRegion(region, -left, -top)
        val sourceMask = TilingEngine.buildMask(shifted, cropWidth, cropHeight)
        if (!sourceMask.any { it }) return false

        val maxSide = if (DeviceProfile.isLowEnd(context)) MAX_SIDE_LOW_END else MAX_SIDE_NORMAL
        val scale = min(1f, min(maxSide.toFloat() / cropWidth, maxSide.toFloat() / cropHeight))
        val inputWidth = alignedSize(cropWidth, scale)
        val inputHeight = alignedSize(cropHeight, scale)
        val pixels = inputWidth * inputHeight
        val imageData = FloatArray(3 * pixels)
        val maskData = FloatArray(pixels)

        for (y in 0 until inputHeight) {
            val sourceY = ((y + 0.5f) * cropHeight / inputHeight - 0.5f)
                .toInt().coerceIn(0, cropHeight - 1)
            for (x in 0 until inputWidth) {
                val sourceX = ((x + 0.5f) * cropWidth / inputWidth - 0.5f)
                    .toInt().coerceIn(0, cropWidth - 1)
                val inputIndex = y * inputWidth + x
                val sourceIndex = sourceY * cropWidth + sourceX
                val color = source[sourceIndex]
                imageData[inputIndex] = Color.red(color) / 255f
                imageData[pixels + inputIndex] = Color.green(color) / 255f
                imageData[2 * pixels + inputIndex] = Color.blue(color) / 255f
                maskData[inputIndex] = if (sourceMask[sourceIndex]) 1f else 0f
            }
        }

        onProgress?.invoke(0.25f)
        checkMemory()
        val imageTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(imageData),
            longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
        )
        val maskTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(maskData),
            longArrayOf(1, 1, inputHeight.toLong(), inputWidth.toLong())
        )

        val generated = try {
            val imageName = session.inputNames.firstOrNull { it.equals("image", true) }
                ?: throw IllegalStateException("LaMa input 'image' is missing")
            val maskName = session.inputNames.firstOrNull { it.equals("mask", true) }
                ?: throw IllegalStateException("LaMa input 'mask' is missing")
            session.run(mapOf(imageName to imageTensor, maskName to maskTensor)).use { result ->
                val outputName = session.outputNames.firstOrNull { it.equals("inpainted", true) }
                    ?: session.outputNames.firstOrNull()
                    ?: throw IllegalStateException("LaMa output is missing")
                val output = result[outputName].orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException("LaMa output is not a tensor")
                val buffer = output.floatBuffer
                FloatArray(3 * pixels) { index -> buffer.get(index) }
            }
        } finally {
            imageTensor.close()
            maskTensor.close()
        }

        onProgress?.invoke(0.82f)
        val inferred = IntArray(cropWidth * cropHeight)
        for (y in 0 until cropHeight) {
            val modelY = (y + 0.5f) * inputHeight / cropHeight - 0.5f
            for (x in 0 until cropWidth) {
                val modelX = (x + 0.5f) * inputWidth / cropWidth - 0.5f
                val index = y * cropWidth + x
                inferred[index] = Color.rgb(
                    (sample(generated, 0, modelX, modelY, inputWidth, inputHeight) * 255f)
                        .toInt().coerceIn(0, 255),
                    (sample(generated, 1, modelX, modelY, inputWidth, inputHeight) * 255f)
                        .toInt().coerceIn(0, 255),
                    (sample(generated, 2, modelX, modelY, inputWidth, inputHeight) * 255f)
                        .toInt().coerceIn(0, 255)
                )
            }
        }

        val adapted = if (webtoonMode && OpenCvInit.ensureInit()) {
            runCatching {
                WebtoonContextEnhancer().adaptColorsOnly(
                    source,
                    inferred,
                    sourceMask,
                    cropWidth,
                    cropHeight
                )
            }.getOrDefault(inferred)
        } else {
            inferred
        }

        // The model already composites known pixels, but writing only the exact
        // Region guarantees that an oversized context crop cannot alter artwork.
        val destination = source.copyOf()
        sourceMask.indices.forEach { index ->
            if (sourceMask[index]) destination[index] = adapted[index]
        }
        bitmap.setPixels(destination, 0, cropWidth, left, top, cropWidth, cropHeight)
        onProgress?.invoke(1f)
        return true
    }

    private fun validateContract(
        inputs: Map<String, ai.onnxruntime.NodeInfo>,
        outputs: Map<String, ai.onnxruntime.NodeInfo>
    ) {
        fun channels(info: ai.onnxruntime.NodeInfo?): Long? =
            (info?.info as? TensorInfo)?.shape?.getOrNull(1)
        val image = inputs.entries.firstOrNull { it.key.equals("image", true) }
        val mask = inputs.entries.firstOrNull { it.key.equals("mask", true) }
        require(image != null && channels(image.value) == 3L) {
            "Model tidak kompatibel: input image [N,3,H,W] tidak ditemukan"
        }
        require(mask != null && channels(mask.value) == 1L) {
            "Model tidak kompatibel: input mask [N,1,H,W] tidak ditemukan"
        }
        require(outputs.keys.any { it.equals("inpainted", true) } || outputs.size == 1) {
            "Model tidak kompatibel: output inpainted tidak ditemukan"
        }
    }

    private fun alignedSize(source: Int, scale: Float): Int {
        val requested = (source * scale).toInt().coerceAtLeast(MIN_SIDE)
        return (ceil(requested / ALIGNMENT.toDouble()).toInt() * ALIGNMENT)
    }

    private fun sample(
        data: FloatArray,
        channel: Int,
        x: Float,
        y: Float,
        width: Int,
        height: Int
    ): Float {
        val x0 = floor(x).toInt().coerceIn(0, width - 1)
        val y0 = floor(y).toInt().coerceIn(0, height - 1)
        val x1 = (x0 + 1).coerceAtMost(width - 1)
        val y1 = (y0 + 1).coerceAtMost(height - 1)
        val fx = (x - floor(x)).coerceIn(0f, 1f)
        val fy = (y - floor(y)).coerceIn(0f, 1f)
        val offset = channel * width * height
        val top = data[offset + y0 * width + x0] * (1f - fx) +
            data[offset + y0 * width + x1] * fx
        val bottom = data[offset + y1 * width + x0] * (1f - fx) +
            data[offset + y1 * width + x1] * fx
        return (top * (1f - fy) + bottom * fy).coerceIn(0f, 1f)
    }

    private fun fallback(
        bitmap: Bitmap,
        region: Region,
        onProgress: ((Float) -> Unit)?
    ): Boolean = BrushInpainter.inpaint(
        bitmap,
        region,
        BrushInpainter.Method.AUTO,
        onProgress
    ).success

    private fun checkMemory() {
        val runtime = Runtime.getRuntime()
        val usedRatio = (runtime.totalMemory() - runtime.freeMemory()).toFloat() / runtime.maxMemory()
        if (usedRatio > MEMORY_ABORT_RATIO) {
            System.gc()
            if ((runtime.totalMemory() - runtime.freeMemory()).toFloat() / runtime.maxMemory() >
                MEMORY_ABORT_RATIO
            ) {
                throw OutOfMemoryError("LaMa Manga memory guard")
            }
        }
    }
}
