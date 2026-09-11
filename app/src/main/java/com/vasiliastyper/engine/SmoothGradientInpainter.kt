package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic gradient reconstruction for RemovR.
 *
 * This is deliberately not Telea, Navier-Stokes, diffusion, or texture copying.
 * It samples clean pixels around the selection, rejects text-colour outliers,
 * fits a continuous two-dimensional quadratic colour surface, and evaluates
 * that surface inside the mask. Linear, diagonal, radial-ish and very soft
 * multi-stop gradients therefore remain smooth instead of producing patches,
 * streaks, or a repeated texture seam.
 */
object SmoothGradientInpainter {

    data class Result(
        val applied: Boolean,
        val message: String,
        val processedPixels: Int = 0,
        val confidence: Float = 0f
    )

    private data class Sample(
        val x: Int,
        val y: Int,
        val red: Double,
        val green: Double,
        val blue: Double
    )

    private data class Surface(
        val red: DoubleArray,
        val green: DoubleArray,
        val blue: DoubleArray,
        val centerX: Double,
        val centerY: Double,
        val scale: Double
    )

    private const val TERMS = 6
    private const val MASK_EXPANSION = 2
    private const val MAX_SAMPLES = 14_000
    private const val MAX_WORK_PIXELS = 3_000_000
    private const val ACCEPTED_RMSE = 18.0

    fun inpaint(
        bitmap: Bitmap,
        requestedRegion: Region,
        onProgress: ((Float) -> Unit)? = null
    ): Result {
        if (bitmap.isRecycled || !bitmap.isMutable) {
            return Result(false, "Layer harus berupa bitmap mutable")
        }
        val imageBounds = Rect(0, 0, bitmap.width, bitmap.height)
        val region = Region(requestedRegion).apply {
            op(Region(imageBounds), Region.Op.INTERSECT)
        }
        if (region.isEmpty) return Result(false, "Mask kosong")

        val selected = Rect(region.bounds)
        val margin = max(24, min(120, max(selected.width(), selected.height()) / 2))
        val work = Rect(
            max(0, selected.left - margin),
            max(0, selected.top - margin),
            min(bitmap.width, selected.right + margin),
            min(bitmap.height, selected.bottom + margin)
        )
        val workWidth = work.width()
        val workHeight = work.height()
        if (workWidth <= 2 || workHeight <= 2 || workWidth * workHeight > MAX_WORK_PIXELS) {
            return Result(false, "Area gradient terlalu besar untuk rekonstruksi aman")
        }

        val pixels = IntArray(workWidth * workHeight)
        bitmap.getPixels(pixels, 0, workWidth, work.left, work.top, workWidth, workHeight)
        val target = BooleanArray(pixels.size)
        var targetCount = 0
        for (localY in 0 until workHeight) {
            val globalY = work.top + localY
            for (localX in 0 until workWidth) {
                val globalX = work.left + localX
                if (insideExpandedMask(region, globalX, globalY)) {
                    target[localY * workWidth + localX] = true
                    targetCount++
                }
            }
        }
        if (targetCount == 0) return Result(false, "Mask tidak memiliki piksel")

        val candidates = mutableListOf<Sample>()
        val stride = max(1, sqrt((pixels.size / MAX_SAMPLES.toDouble()).coerceAtLeast(1.0)).toInt())
        for (localY in 0 until workHeight step stride) {
            for (localX in 0 until workWidth step stride) {
                val index = localY * workWidth + localX
                if (target[index]) continue
                val pixel = pixels[index]
                if (Color.alpha(pixel) < 245) continue
                candidates += Sample(
                    x = work.left + localX,
                    y = work.top + localY,
                    red = Color.red(pixel).toDouble(),
                    green = Color.green(pixel).toDouble(),
                    blue = Color.blue(pixel).toDouble()
                )
            }
        }
        if (candidates.size < 24) {
            return Result(false, "Piksel gradient bersih di sekitar mask tidak cukup")
        }

        val centerX = selected.exactCenterX().toDouble()
        val centerY = selected.exactCenterY().toDouble()
        val coordinateScale = max(workWidth, workHeight).toDouble().coerceAtLeast(1.0)
        val firstSurface = fitSurface(candidates, centerX, centerY, coordinateScale)
            ?: return Result(false, "Model gradient tidak dapat dihitung")

        // Robust second pass: letters, outlines, and small decorations outside the
        // painted mask have a large residual and must not bend the gradient surface.
        val residuals = candidates.map { residual(firstSurface, it) }.sorted()
        val median = residuals[residuals.size / 2]
        val cutoff = max(10.0, median * 2.8)
        val clean = candidates.filter { residual(firstSurface, it) <= cutoff }
        if (clean.size < 18 || clean.size < candidates.size / 3) {
            return Result(false, "Area sekitar mask bukan gradient halus")
        }
        val surface = fitSurface(clean, centerX, centerY, coordinateScale)
            ?: return Result(false, "Model gradient robust tidak dapat dihitung")
        val rmse = sqrt(clean.sumOf {
            val value = residual(surface, it)
            value * value
        } / clean.size)
        if (!rmse.isFinite() || rmse > ACCEPTED_RMSE) {
            return Result(
                false,
                "Konteks bertekstur (RMSE ${"%.1f".format(rmse)}), gunakan sintesis tekstur",
                confidence = (1.0 - rmse / (ACCEPTED_RMSE * 2.0)).coerceIn(0.0, 1.0).toFloat()
            )
        }

        var completed = 0
        var lastProgress = -1
        for (localY in 0 until workHeight) {
            for (localX in 0 until workWidth) {
                val index = localY * workWidth + localX
                if (!target[index]) continue
                val globalX = work.left + localX
                val globalY = work.top + localY
                val basis = basis(globalX, globalY, surface.centerX, surface.centerY, surface.scale)
                val red = evaluate(surface.red, basis).toInt().coerceIn(0, 255)
                val green = evaluate(surface.green, basis).toInt().coerceIn(0, 255)
                val blue = evaluate(surface.blue, basis).toInt().coerceIn(0, 255)
                pixels[index] = Color.argb(255, red, green, blue)
                completed++
                val progress = completed * 100 / targetCount
                if (progress != lastProgress && progress % 4 == 0) {
                    lastProgress = progress
                    onProgress?.invoke(progress / 100f)
                }
            }
        }

        bitmap.setPixels(pixels, 0, workWidth, work.left, work.top, workWidth, workHeight)
        onProgress?.invoke(1f)
        val confidence = (1.0 - rmse / ACCEPTED_RMSE).coerceIn(0.0, 1.0).toFloat()
        return Result(true, "Smooth gradient surface", completed, confidence)
    }

    private fun insideExpandedMask(region: Region, x: Int, y: Int): Boolean {
        for (dy in -MASK_EXPANSION..MASK_EXPANSION) {
            for (dx in -MASK_EXPANSION..MASK_EXPANSION) {
                if (dx * dx + dy * dy <= MASK_EXPANSION * MASK_EXPANSION &&
                    region.contains(x + dx, y + dy)
                ) return true
            }
        }
        return false
    }

    private fun fitSurface(
        samples: List<Sample>,
        centerX: Double,
        centerY: Double,
        scale: Double
    ): Surface? {
        val normal = Array(TERMS) { DoubleArray(TERMS) }
        val redRhs = DoubleArray(TERMS)
        val greenRhs = DoubleArray(TERMS)
        val blueRhs = DoubleArray(TERMS)
        for (sample in samples) {
            val b = basis(sample.x, sample.y, centerX, centerY, scale)
            for (row in 0 until TERMS) {
                redRhs[row] += b[row] * sample.red
                greenRhs[row] += b[row] * sample.green
                blueRhs[row] += b[row] * sample.blue
                for (column in 0 until TERMS) normal[row][column] += b[row] * b[column]
            }
        }
        // Small ridge term prevents singular matrices on thin/edge selections.
        for (index in 0 until TERMS) normal[index][index] += if (index == 0) 1e-8 else 1e-5
        val red = solve(normal, redRhs) ?: return null
        val green = solve(normal, greenRhs) ?: return null
        val blue = solve(normal, blueRhs) ?: return null
        return Surface(red, green, blue, centerX, centerY, scale)
    }

    private fun basis(x: Int, y: Int, centerX: Double, centerY: Double, scale: Double): DoubleArray {
        val nx = (x - centerX) / scale
        val ny = (y - centerY) / scale
        return doubleArrayOf(1.0, nx, ny, nx * nx, nx * ny, ny * ny)
    }

    private fun residual(surface: Surface, sample: Sample): Double {
        val b = basis(sample.x, sample.y, surface.centerX, surface.centerY, surface.scale)
        val dr = evaluate(surface.red, b) - sample.red
        val dg = evaluate(surface.green, b) - sample.green
        val db = evaluate(surface.blue, b) - sample.blue
        return sqrt((dr * dr + dg * dg + db * db) / 3.0)
    }

    private fun evaluate(coefficients: DoubleArray, basis: DoubleArray): Double {
        var result = 0.0
        for (index in coefficients.indices) result += coefficients[index] * basis[index]
        return result
    }

    /** Gaussian elimination with partial pivoting. Inputs remain untouched. */
    private fun solve(matrix: Array<DoubleArray>, values: DoubleArray): DoubleArray? {
        val size = values.size
        val augmented = Array(size) { row ->
            DoubleArray(size + 1).also { line ->
                for (column in 0 until size) line[column] = matrix[row][column]
                line[size] = values[row]
            }
        }
        for (pivot in 0 until size) {
            var best = pivot
            for (row in pivot + 1 until size) {
                if (abs(augmented[row][pivot]) > abs(augmented[best][pivot])) best = row
            }
            if (abs(augmented[best][pivot]) < 1e-12) return null
            if (best != pivot) {
                val swap = augmented[pivot]
                augmented[pivot] = augmented[best]
                augmented[best] = swap
            }
            val divisor = augmented[pivot][pivot]
            for (column in pivot until size + 1) augmented[pivot][column] /= divisor
            for (row in 0 until size) {
                if (row == pivot) continue
                val factor = augmented[row][pivot]
                if (abs(factor) < 1e-15) continue
                for (column in pivot until size + 1) {
                    augmented[row][column] -= factor * augmented[pivot][column]
                }
            }
        }
        return DoubleArray(size) { augmented[it][size] }
    }
}
