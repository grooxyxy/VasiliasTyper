package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import kotlin.math.abs
import kotlin.math.max

/**
 * Inpainting tekstur lokal tanpa deep learning.
 *
 * Tool Content-Aware sengaja hanya memakai algoritma klasik/offline:
 * 1. [PatchMatchInpainter] untuk pola berulang seperti grid, screentone, dan grain.
 * 2. [CriminisiEngine] untuk meneruskan garis/struktur diagonal melalui mask teks.
 * 3. [ResynthesizerEngine] untuk tekstur stokastik dan variasi warna halus.
 *
 * Tidak ada model ONNX, layanan cloud, atau generative AI pada pipeline ini.
 * Mask diperlebar satu piksel untuk menutup anti-alias/halo huruf. Setiap kandidat
 * dinilai dari sambungan batas, energi gradien, dan kestabilan tekstur; hanya hasil
 * terbaik yang dikomit. Seluruh ROI dipulihkan bila proses gagal.
 */
object ContentAwareFillEngine {

    data class Result(
        val success: Boolean,
        val message: String,
        val processedPixels: Int = 0
    )

    private data class Candidate(
        val name: String,
        val pixels: IntArray,
        val score: Double
    )

    fun fill(
        bitmap: Bitmap,
        requestedMask: Region,
        onProgress: ((Float) -> Unit)? = null
    ): Result {
        if (!bitmap.isMutable || bitmap.isRecycled) {
            return Result(false, "Layer harus berupa bitmap mutable")
        }
        val canvas = Region(0, 0, bitmap.width, bitmap.height)
        val requested = Region(requestedMask).apply {
            op(canvas, Region.Op.INTERSECT)
        }
        if (requested.isEmpty) return Result(false, "Mask inpainting tekstur kosong")

        // Teks putih tipis pada latar bertekstur biasanya memiliki halo anti-alias.
        // Dilasi 1 px menghapus halo tanpa memakan pola latar secara berlebihan.
        val mask = dilate(requested, radius = 1, clip = canvas)
        val maskBounds = Rect(mask.bounds)
        val bounds = Rect(
            (maskBounds.left - 8).coerceAtLeast(0),
            (maskBounds.top - 8).coerceAtLeast(0),
            (maskBounds.right + 8).coerceAtMost(bitmap.width),
            (maskBounds.bottom + 8).coerceAtMost(bitmap.height)
        )
        val pixelCount = countPixels(mask)
        if (pixelCount <= 0 || bounds.isEmpty) {
            return Result(false, "Mask berada di luar kanvas")
        }

        val roiSize = bounds.width() * bounds.height()
        val original = IntArray(roiSize)
        bitmap.getPixels(
            original, 0, bounds.width(), bounds.left, bounds.top,
            bounds.width(), bounds.height()
        )
        onProgress?.invoke(0.02f)

        val candidates = ArrayList<Candidate>(3)
        val algorithms = buildList<Pair<String, () -> Boolean>> {
            // PatchMatch menyalin patch nyata dari area sekitar sehingga pola grid,
            // screentone, dan noise tidak berubah menjadi blur seperti PDE/Telea.
            if (roiSize <= 1_800_000) {
                add("PatchMatch tekstur multi-skala" to {
                    PatchMatchInpainter.inpaintInPlace(bitmap, mask)
                })
            }

            // Criminisi mengurutkan pengisian berdasarkan isophote. Ini penting
            // untuk meneruskan garis diagonal yang tertutup huruf pada panel contoh.
            add("Criminisi structure-aware" to {
                CriminisiEngine.inpaint(
                    bitmap,
                    mask,
                    params = CriminisiEngine.Params(
                        patchRadius = 5,
                        contextPadding = 192,
                        lumaWeight = 0.65f,
                        feather = 0.35f
                    )
                ).success
            })

            // Resynthesizer menjadi kandidat keragaman untuk grain/tekstur acak.
            add("Resynthesizer tekstur" to {
                ResynthesizerEngine.inpaint(
                    bitmap,
                    mask,
                    params = ResynthesizerEngine.Params(
                        maxNeighbors = 20,
                        maxRandomCandidates = 64,
                        refinement = 0.45,
                        searchRadius = 192
                    )
                ).success
            })
        }

        try {
            algorithms.forEachIndexed { index, (name, process) ->
                restore(bitmap, bounds, original)
                val ok = try {
                    process()
                } catch (_: Throwable) {
                    false
                }
                if (ok) {
                    val output = IntArray(roiSize)
                    bitmap.getPixels(
                        output, 0, bounds.width(), bounds.left, bounds.top,
                        bounds.width(), bounds.height()
                    )
                    val score = qualityScore(original, output, mask, bounds)
                    if (score.isFinite()) candidates += Candidate(name, output, score)
                }
                onProgress?.invoke(0.06f + 0.86f * (index + 1f) / algorithms.size)
            }

            val winner = candidates.minByOrNull { it.score }
            if (winner == null) {
                restore(bitmap, bounds, original)
                return Result(false, "Tekstur sumber di sekitar mask tidak mencukupi")
            }

            bitmap.setPixels(
                winner.pixels, 0, bounds.width(), bounds.left, bounds.top,
                bounds.width(), bounds.height()
            )
            onProgress?.invoke(1f)
            return Result(
                true,
                "Inpainting tekstur non-AI selesai (${winner.name})",
                pixelCount
            )
        } catch (error: Throwable) {
            restore(bitmap, bounds, original)
            return Result(false, "Rekonstruksi dibatalkan: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /**
     * Lower is better. Boundary mismatch has the largest weight because visible
     * seams are the most objectionable artifact. Texture energy prevents noisy
     * PatchMatch blocks, while a small unchanged-pixel penalty detects backends
     * that silently failed to fill the mask.
     */
    private fun qualityScore(
        original: IntArray,
        candidate: IntArray,
        mask: Region,
        bounds: Rect
    ): Double {
        val width = bounds.width()
        val height = bounds.height()
        val stride = if (candidate.size > 500_000) 2 else 1
        var boundaryError = 0.0
        var boundarySamples = 0
        var textureEnergy = 0.0
        var textureSamples = 0
        var knownGradient = 0.0
        var knownGradientSamples = 0
        var filledGradient = 0.0
        var filledGradientSamples = 0
        var changed = 0
        var maskedSamples = 0

        fun colorDistance(a: Int, b: Int): Double {
            val dr = Color.red(a) - Color.red(b)
            val dg = Color.green(a) - Color.green(b)
            val db = Color.blue(a) - Color.blue(b)
            return (abs(dr) + abs(dg) + abs(db)) / 3.0
        }

        for (y in 0 until height step stride) {
            for (x in 0 until width step stride) {
                val globalX = bounds.left + x
                val globalY = bounds.top + y
                val insideMask = mask.contains(globalX, globalY)
                val index = y * width + x
                if (insideMask) {
                    maskedSamples++
                    if (colorDistance(original[index], candidate[index]) > 2.0) changed++
                }

                val neighbours = intArrayOf(index - 1, index + 1, index - width, index + width)
                val nx = intArrayOf(x - 1, x + 1, x, x)
                val ny = intArrayOf(y, y, y - 1, y + 1)
                for (n in neighbours.indices) {
                    if (nx[n] !in 0 until width || ny[n] !in 0 until height) continue
                    // Hitung setiap pasangan sekali agar statistik gradien tidak bias.
                    if (n == 0 || n == 2) continue
                    val neighbourIndex = neighbours[n]
                    val neighbourMasked = mask.contains(bounds.left + nx[n], bounds.top + ny[n])
                    val difference = colorDistance(candidate[index], candidate[neighbourIndex])
                    when {
                        insideMask && neighbourMasked -> {
                            textureEnergy += max(0.0, difference - 48.0)
                            textureSamples++
                            filledGradient += difference
                            filledGradientSamples++
                        }
                        insideMask != neighbourMasked -> {
                            boundaryError += difference
                            boundarySamples++
                        }
                        else -> {
                            knownGradient += colorDistance(original[index], original[neighbourIndex])
                            knownGradientSamples++
                        }
                    }
                }
            }
        }

        val seam = if (boundarySamples == 0) 10_000.0 else boundaryError / boundarySamples
        val noise = if (textureSamples == 0) 0.0 else textureEnergy / textureSamples
        val referenceEnergy = if (knownGradientSamples == 0) 0.0 else knownGradient / knownGradientSamples
        val outputEnergy = if (filledGradientSamples == 0) 0.0 else filledGradient / filledGradientSamples
        val textureMismatch = abs(outputEnergy - referenceEnergy)
        val changeRatio = if (maskedSamples == 0) 0.0 else changed.toDouble() / maskedSamples
        val unchangedPenalty = if (changeRatio < 0.03) (0.03 - changeRatio) * 1_500.0 else 0.0
        return seam * 4.0 + noise * 1.25 + textureMismatch * 2.25 + unchangedPenalty
    }

    private fun dilate(region: Region, radius: Int, clip: Region): Region {
        if (radius <= 0) return Region(region).apply { op(clip, Region.Op.INTERSECT) }
        val expanded = Region(region)
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                if (dx == 0 && dy == 0) continue
                val shifted = Region(region)
                shifted.translate(dx, dy)
                expanded.op(shifted, Region.Op.UNION)
            }
        }
        expanded.op(clip, Region.Op.INTERSECT)
        return expanded
    }

    private fun restore(bitmap: Bitmap, bounds: Rect, pixels: IntArray) {
        bitmap.setPixels(
            pixels, 0, bounds.width(), bounds.left, bounds.top,
            bounds.width(), bounds.height()
        )
    }

    private fun countPixels(region: Region): Int {
        val iterator = android.graphics.RegionIterator(region)
        val rect = Rect()
        var total = 0L
        while (iterator.next(rect)) {
            total += rect.width().toLong() * rect.height().toLong()
        }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
