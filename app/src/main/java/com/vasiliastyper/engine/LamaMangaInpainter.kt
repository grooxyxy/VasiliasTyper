package com.vasiliastyper.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Region
import android.util.Log
import java.nio.FloatBuffer
import kotlin.math.floor
import kotlin.math.min

/**
 * Android inference pipeline for LaMa manga (ogkalu/lama-manga-onnx-dynamic, Apache-2.0).
 *
 * Model: lama-manga-dynamic.onnx (dinamis, opset 18).
 * Kontrak:
 *  image  float32 [1,3,H,W], RGB [0,1]
 *  mask   float32 [1,1,H,W], 1 = hole, 0 = keep
 *  output inpainted float32 [1,3,H,W], RGB [0,1] (otomatis dinormalisasi bila [0,255])
 * Legacy original fixed-512 (output [0,255]) tetap didukung baca
 * bila file lama masih ada, tapi bundle baru selalu model manga.
 *
 * 720x16000+: region besar diproses via TilingEngine (tile 512 + overlap 64,
 * center-write) sehingga memori tetap ~1MB/tile, bukan 46MB full-strip.
 * Hanya piksel dalam Region yang ditulis balik agar artwork tidak berubah.
 */
object LamaMangaInpainter {
    private const val TAG = "LamaMangaInpainter"
    private const val CONTEXT_PADDING = 96
    private const val INPUT_SIZE = 512
    private const val TILE_SIZE = 512
    private const val TILE_OVERLAP = 64
    // Crop di atas ini memakai jalur tiled (hemat memori untuk 720x16000).
    private const val SINGLE_LIMIT_PIXELS = 1024 * 1024
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
            Log.e(TAG, "LaMa stopped by memory guard", error)
            System.gc()
            fallback(bitmap, region, onProgress)
        } catch (error: Throwable) {
            Log.e(TAG, "LaMa inference failed: ${error.message}", error)
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
        val cropArea = bounds.width().toLong() * bounds.height().toLong()
        // 720x16000 region (11.5M px) wajib tiled; crop kecil single 512.
        if (cropArea > SINGLE_LIMIT_PIXELS || bounds.width() > INPUT_SIZE * 2 || bounds.height() > INPUT_SIZE * 2) {
            return runTiled(context, env, session, bitmap, region, webtoonMode, onProgress)
        }

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

        onProgress?.invoke(0.25f)
        val inferred = inpaintCrop(env, session, source, sourceMask, cropWidth, cropHeight)
            ?: return fallback(bitmap, region, onProgress)

        onProgress?.invoke(0.82f)
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

        val destination = source.copyOf()
        sourceMask.indices.forEach { index ->
            if (sourceMask[index]) destination[index] = adapted[index]
        }
        bitmap.setPixels(destination, 0, cropWidth, left, top, cropWidth, cropHeight)
        onProgress?.invoke(1f)
        return true
    }

    private fun runTiled(
        context: Context,
        env: OrtEnvironment,
        session: OrtSession,
        bitmap: Bitmap,
        region: Region,
        webtoonMode: Boolean,
        onProgress: ((Float) -> Unit)?
    ): Boolean {
        var tiledOk = false
        try {
            TilingEngine.process(
                bitmap = bitmap,
                region = region,
                tileSize = TILE_SIZE,
                overlap = TILE_OVERLAP,
                onProgress = onProgress
            ) { pixels, mask, w, h ->
                checkMemory()
                val out = inpaintCrop(env, session, pixels, mask, w, h)
                if (out != null) {
                    // Tulis hanya piksel mask; adaptasi warna per-tile bila webtoonMode.
                    val final = if (webtoonMode && OpenCvInit.ensureInit()) {
                        runCatching {
                            WebtoonContextEnhancer().adaptColorsOnly(pixels, out, mask, w, h)
                        }.getOrDefault(out)
                    } else out
                    for (i in pixels.indices) {
                        if (mask[i]) pixels[i] = final[i]
                    }
                    tiledOk = true
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "LaMa tiled failed: ${error.message}", error)
            return fallback(bitmap, region, onProgress)
        }
        if (!tiledOk) return fallback(bitmap, region, onProgress)
        onProgress?.invoke(1f)
        return true
    }

    /**
     * Inpaint satu crop (ukuran bebas) via resize 512x512 fixed.
     * Mengembalikan IntArray ukuran crop, atau null bila gagal.
     */
    private fun inpaintCrop(
        env: OrtEnvironment,
        session: OrtSession,
        source: IntArray,
        sourceMask: BooleanArray,
        srcW: Int,
        srcH: Int
    ): IntArray? {
        if (srcW <= 0 || srcH <= 0 || source.size != srcW * srcH) return null
        val plane = INPUT_SIZE * INPUT_SIZE
        val imageData = FloatArray(3 * plane)
        val maskData = FloatArray(plane)
        // Resize crop -> 512 (nearest, cepat + hemat memori).
        for (y in 0 until INPUT_SIZE) {
            val sy = ((y + 0.5f) * srcH / INPUT_SIZE - 0.5f).toInt().coerceIn(0, srcH - 1)
            for (x in 0 until INPUT_SIZE) {
                val sx = ((x + 0.5f) * srcW / INPUT_SIZE - 0.5f).toInt().coerceIn(0, srcW - 1)
                val srcIdx = sy * srcW + sx
                val dstIdx = y * INPUT_SIZE + x
                val c = source[srcIdx]
                imageData[dstIdx] = Color.red(c) / 255f
                imageData[plane + dstIdx] = Color.green(c) / 255f
                imageData[2 * plane + dstIdx] = Color.blue(c) / 255f
                maskData[dstIdx] = if (sourceMask[srcIdx]) 1f else 0f
            }
        }
        val generated = runSingle512(env, session, imageData, maskData) ?: return null
        // Deteksi rentang output: asli [0,255], legacy manga [0,1].
        var maxV = 0f
        for (i in 0 until min(1024, generated.size)) {
            if (generated[i] > maxV) maxV = generated[i]
        }
        val scaleToUnit = if (maxV > 2f) 1f / 255f else 1f
        // Resize 512 -> crop (bilinear).
        val out = IntArray(srcW * srcH)
        for (y in 0 until srcH) {
            val my = (y + 0.5f) * INPUT_SIZE / srcH - 0.5f
            for (x in 0 until srcW) {
                val mx = (x + 0.5f) * INPUT_SIZE / srcW - 0.5f
                val r = (sample(generated, 0, mx, my, INPUT_SIZE, INPUT_SIZE) * scaleToUnit * 255f)
                    .toInt().coerceIn(0, 255)
                val g = (sample(generated, 1, mx, my, INPUT_SIZE, INPUT_SIZE) * scaleToUnit * 255f)
                    .toInt().coerceIn(0, 255)
                val b = (sample(generated, 2, mx, my, INPUT_SIZE, INPUT_SIZE) * scaleToUnit * 255f)
                    .toInt().coerceIn(0, 255)
                out[y * srcW + x] = Color.rgb(r, g, b)
            }
        }
        return out
    }

    private fun runSingle512(
        env: OrtEnvironment,
        session: OrtSession,
        imageData: FloatArray,
        maskData: FloatArray
    ): FloatArray? {
        checkMemory()
        val imageTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(imageData),
            longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        val maskTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(maskData),
            longArrayOf(1, 1, INPUT_SIZE.toLong(), INPUT_SIZE.toLong())
        )
        return try {
            val imageName = session.inputNames.firstOrNull { it.equals("image", true) }
                ?: throw IllegalStateException("LaMa input 'image' is missing")
            val maskName = session.inputNames.firstOrNull { it.equals("mask", true) }
                ?: throw IllegalStateException("LaMa input 'mask' is missing")
            session.run(mapOf(imageName to imageTensor, maskName to maskTensor)).use { result ->
                val outputName = session.outputNames.firstOrNull {
                    it.equals("output", true) || it.equals("inpainted", true)
                } ?: session.outputNames.firstOrNull()
                ?: throw IllegalStateException("LaMa output is missing")
                val output = result[outputName].orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException("LaMa output is not a tensor")
                val buffer = output.floatBuffer
                val plane = INPUT_SIZE * INPUT_SIZE
                FloatArray(3 * plane) { index -> buffer.get(index) }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "LaMa 512 inference failed: ${error.message}", error)
            null
        } finally {
            imageTensor.close()
            maskTensor.close()
        }
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
        require(outputs.keys.any { it.equals("output", true) || it.equals("inpainted", true) } || outputs.size == 1) {
            "Model tidak kompatibel: output output/inpainted tidak ditemukan"
        }
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
        // Jangan clamp ke [0,1] di sini — output asli [0,255] dinormalisasi caller.
        val top = data[offset + y0 * width + x0] * (1f - fx) +
            data[offset + y0 * width + x1] * fx
        val bottom = data[offset + y1 * width + x0] * (1f - fx) +
            data[offset + y1 * width + x1] * fx
        return top * (1f - fy) + bottom * fy
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
                throw OutOfMemoryError("LaMa memory guard")
            }
        }
    }
}
