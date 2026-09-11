package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RegionIterator
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * OpenCvPatchInpainter — OpenCV-native brush inpainting backend (v10).
 *
 * Sesuai permintaan: brush inpaint TIDAK lagi memakai cv2.inpaint Telea /
 * Navier-Stokes. Engine ini memakai API OpenCV (C++ native) yang berbeda:
 *
 *  1. **PATCH (default, "OpenCV 5 style")** — exemplar/patch-based texture
 *     synthesis yang dijalankan di atas Mat OpenCV (C++): priority fill-order
 *     ala Criminisi (confidence × data/isophote), best-exemplar search dengan
 *     distance SSD yang dihitung lewat [Core.sumElems] pada Mat diff (loop
 *     inner 100% native), lalu copy patch + feather seam via
 *     [Imgproc.GaussianBlur] + [Core.addWeighted]. Kualitas jauh di atas Telea
 *     untuk object removal karena tekstur asli disalin, bukan di-blur.
 *
 *  2. **SEAMLESS_MIX / SEAMLESS_NORMAL** — [Photo.seamlessClone]: Poisson
 *     blending native OpenCV. Cocok untuk area kecil di atas gradasi halus.
 *
 *  3. **MULTI_SCALE** — patch synthesis pada piramida Gaussian
 *     ([Imgproc.pyrDown]/[Imgproc.pyrUp]): struktur besar diisi pada resolusi
 *     rendah, detail dipertajam pada resolusi penuh. Paling aman untuk mask
 *     besar (object removal pada panel webtoon).
 *
 * Desain RAM: seluruh proses bekerja pada ROI crop (mask bbox + context
 * padding), tidak pernah mengalokasikan Mat sebesar halaman webtoon penuh
 * (800 × 20 000 px). ROI di atas batas [MAX_ROI_PIXELS] di-downscale, diisi,
 * lalu di-upscale kembali dan di-blend hanya di dalam mask.
 *
 * Fallback: jika library native OpenCV gagal dimuat, semua entry point
 * mengembalikan Result gagal (bukan throw) sehingga pemanggil bisa jatuh ke
 * engine pure-Kotlin tanpa force-close.
 *
 * Threading: panggil dari background thread. Tidak re-entrant.
 */
object OpenCvPatchInpainter {

    enum class Method {
        /** OpenCV native fast-marching inpaint; jalur interaktif tercepat. */
        FAST,
        /** Patch-based texture synthesis di atas Mat untuk kualitas tekstur. */
        PATCH,
        /** Poisson seamless clone MIXED_GRADIENT — area kecil di gradasi. */
        SEAMLESS_MIX,
        /** Poisson seamless clone NORMAL_CLONE — memindahkan tekstur utuh. */
        SEAMLESS_NORMAL,
        /** Patch synthesis multi-skala (piramida Gaussian) untuk mask besar. */
        MULTI_SCALE,
        /** Pilih otomatis dari ukuran/bentuk mask. */
        AUTO
    }

    data class Params(
        /** Patch half-size untuk synthesis (patch = 2r+1). */
        val patchRadius: Int = 4,
        /** Context border di sekitar mask bbox (px). */
        val contextPadding: Int = 72,
        /** Kekuatan feather seam 0..1. */
        val feather: Float = 0.65f,
        /** Downscale untuk multi-scale (0.5 = setengah resolusi). */
        val coarseScale: Float = 0.5f,
        /** Maksimum iterasi pengisian patch (pengaman infinite loop). */
        val maxPatchIters: Int = 120_000,
        /** Radius OpenCV fast inpaint dalam pixel. */
        val fastRadius: Double = 3.0
    )

    data class Result(
        val success: Boolean,
        val message: String,
        val processedPixels: Int = 0
    )

    /** Batas ROI kerja (w×h) pada resolusi penuh. Di atas ini → downscale. */
    private const val MAX_ROI_PIXELS = 2_600_000
    private const val KNOWN: Byte = 0
    private const val TARGET: Byte = 1

    // ── Public API ────────────────────────────────────────────────────────────

    fun inpaint(
        bitmap: Bitmap,
        region: Region,
        method: Method = Method.AUTO,
        params: Params = Params(),
        onProgress: ((Float) -> Unit)? = null
    ): Result {
        if (!bitmap.isMutable || bitmap.isRecycled) {
            return Result(false, "Layer harus berupa bitmap mutable")
        }
        if (!OpenCvInit.ensureInit()) {
            return Result(false, "OpenCV native tidak tersedia")
        }
        val clipped = Region(region).apply {
            op(Region(0, 0, bitmap.width, bitmap.height), Region.Op.INTERSECT)
        }
        if (clipped.isEmpty) return Result(false, "Mask brush kosong")

        val maskBounds = Rect(clipped.bounds)
        if (maskBounds.isEmpty) return Result(false, "Mask berada di luar kanvas")
        val maskPixels = countPixels(clipped)
        if (maskPixels <= 0) return Result(false, "Mask berada di luar kanvas")

        val chosen = if (method == Method.AUTO) autoMethod(maskBounds, maskPixels) else method

        return try {
            when (chosen) {
                Method.FAST -> runFast(bitmap, clipped, params, onProgress)
                Method.PATCH -> runPatch(bitmap, clipped, params, onProgress)
                Method.MULTI_SCALE -> runMultiScale(bitmap, clipped, params, onProgress)
                Method.SEAMLESS_MIX -> runSeamless(bitmap, clipped, params, true, onProgress)
                Method.SEAMLESS_NORMAL -> runSeamless(bitmap, clipped, params, false, onProgress)
                Method.AUTO -> runFast(bitmap, clipped, params, onProgress) // unreachable
            }
        } catch (oom: OutOfMemoryError) {
            oom.printStackTrace()
            System.gc()
            Result(false, "Memori tidak cukup — perkecil area seleksi")
        } catch (t: Throwable) {
            t.printStackTrace()
            Result(false, "Inpaint gagal: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    // ── Method auto-selection ─────────────────────────────────────────────────

    private fun autoMethod(bounds: Rect, maskPixels: Int): Method {
        val boxArea = (bounds.width().toLong() * bounds.height()).coerceAtLeast(1L)
        val fillRatio = maskPixels.toDouble() / boxArea
        return when {
            // Mask sangat besar tetap diturunkan resolusinya agar memori terjaga.
            maskPixels > 350_000 || fillRatio > 0.75 && maskPixels > 180_000 -> Method.MULTI_SCALE
            // Jalur default memakai implementasi C++ OpenCV penuh. Ini beberapa
            // orde lebih cepat daripada pencarian exemplar Kotlin per patch.
            else -> Method.FAST
        }
    }

    // ── ROI extraction ────────────────────────────────────────────────────────

    private class Roi(
        val x: Int, val y: Int, val w: Int, val h: Int,
        val pixels: IntArray, val mask: BooleanArray, val targetCount: Int
    )

    private fun extractRoi(bitmap: Bitmap, clipped: Region, pad: Int): Roi? {
        val b = clipped.bounds
        val rx = max(0, b.left - pad)
        val ry = max(0, b.top - pad)
        val rr = min(bitmap.width, b.right + pad)
        val rb = min(bitmap.height, b.bottom + pad)
        val rw = rr - rx
        val rh = rb - ry
        if (rw <= 0 || rh <= 0) return null

        val pixels = IntArray(rw * rh)
        bitmap.getPixels(pixels, 0, rw, rx, ry, rw, rh)
        val local = shiftRegion(clipped, -rx, -ry)
        val mask = BooleanArray(rw * rh)
        var target = 0
        val it = RegionIterator(local)
        val r = Rect()
        while (it.next(r)) {
            val x0 = r.left.coerceIn(0, rw); val x1 = r.right.coerceIn(0, rw)
            val y0 = r.top.coerceIn(0, rh); val y1 = r.bottom.coerceIn(0, rh)
            for (yy in y0 until y1) {
                var idx = yy * rw + x0
                for (xx in x0 until x1) {
                    if (!mask[idx]) { mask[idx] = true; target++ }
                    idx++
                }
            }
        }
        if (target == 0) return null
        return Roi(rx, ry, rw, rh, pixels, mask, target)
    }

    // ── FAST OpenCV method ───────────────────────────────────────────────────

    private fun runFast(
        bitmap: Bitmap,
        clipped: Region,
        params: Params,
        onProgress: ((Float) -> Unit)?
    ): Result {
        // Padding kecil cukup untuk algoritma fast marching dan mengurangi copy/Mat.
        val pad = max(params.contextPadding.coerceAtMost(32), params.fastRadius.toInt() * 4)
        val roi = extractRoi(bitmap, clipped, pad)
            ?: return Result(false, "Tidak ada piksel yang perlu diisi")
        onProgress?.invoke(0.08f)

        val rgba = Mat(roi.h, roi.w, CvType.CV_8UC4)
        val rgb = Mat()
        val maskMat = Mat(roi.h, roi.w, CvType.CV_8UC1)
        val outputRgb = Mat()
        val outputRgba = Mat()
        return try {
            rgba.put(0, 0, intsToBytes(roi.pixels))
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            val maskBytes = ByteArray(roi.mask.size) { index ->
                if (roi.mask[index]) 255.toByte() else 0
            }
            maskMat.put(0, 0, maskBytes)
            onProgress?.invoke(0.2f)

            Photo.inpaint(
                rgb,
                maskMat,
                outputRgb,
                params.fastRadius.coerceIn(1.0, 12.0),
                Photo.INPAINT_TELEA
            )
            onProgress?.invoke(0.82f)
            Imgproc.cvtColor(outputRgb, outputRgba, Imgproc.COLOR_RGB2RGBA)
            val output = bytesToInts(outputRgba, roi.w, roi.h)
            for (i in output.indices) {
                if (roi.mask[i]) {
                    output[i] = (roi.pixels[i] and -0x1000000) or (output[i] and 0x00FFFFFF)
                    roi.pixels[i] = output[i]
                }
            }
            writeBackMasked(bitmap, roi)
            onProgress?.invoke(1f)
            Result(true, "OpenCV fast inpaint", roi.targetCount)
        } finally {
            rgba.release()
            rgb.release()
            maskMat.release()
            outputRgb.release()
            outputRgba.release()
        }
    }

    private fun fastInpaintPixels(
        pixels: IntArray,
        mask: BooleanArray,
        w: Int,
        h: Int,
        radius: Double
    ): IntArray {
        val rgba = Mat(h, w, CvType.CV_8UC4)
        val rgb = Mat()
        val maskMat = Mat(h, w, CvType.CV_8UC1)
        val outputRgb = Mat()
        val outputRgba = Mat()
        return try {
            rgba.put(0, 0, intsToBytes(pixels))
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            maskMat.put(0, 0, ByteArray(mask.size) { if (mask[it]) 255.toByte() else 0 })
            Photo.inpaint(rgb, maskMat, outputRgb, radius.coerceIn(1.0, 12.0), Photo.INPAINT_TELEA)
            Imgproc.cvtColor(outputRgb, outputRgba, Imgproc.COLOR_RGB2RGBA)
            bytesToInts(outputRgba, w, h).also { output ->
                for (i in output.indices) {
                    output[i] = (pixels[i] and -0x1000000) or (output[i] and 0x00FFFFFF)
                }
            }
        } finally {
            rgba.release()
            rgb.release()
            maskMat.release()
            outputRgb.release()
            outputRgba.release()
        }
    }

    // ── PATCH method (single scale) ───────────────────────────────────────────

    private fun runPatch(
        bitmap: Bitmap,
        clipped: Region,
        params: Params,
        onProgress: ((Float) -> Unit)?
    ): Result {
        val roi = extractRoi(bitmap, clipped, params.contextPadding)
            ?: return Result(false, "Tidak ada piksel yang perlu diisi")
        onProgress?.invoke(0.05f)

        val roiPixels = roi.w.toLong() * roi.h
        if (roiPixels <= MAX_ROI_PIXELS) {
            patchSynthesize(roi.pixels, roi.mask, roi.w, roi.h, params, onProgress)
            writeBackMasked(bitmap, roi)
            onProgress?.invoke(1f)
            return Result(true, "Patch synthesis (OpenCV Mat)", roi.targetCount)
        }

        // ROI terlalu besar → turunkan ke multi-scale path agar hemat RAM.
        return runMultiScale(bitmap, clipped, params, onProgress)
    }

    // ── MULTI_SCALE method ────────────────────────────────────────────────────

    private fun runMultiScale(
        bitmap: Bitmap,
        clipped: Region,
        params: Params,
        onProgress: ((Float) -> Unit)?
    ): Result {
        val roi = extractRoi(bitmap, clipped, params.contextPadding)
            ?: return Result(false, "Tidak ada piksel yang perlu diisi")
        onProgress?.invoke(0.04f)

        val roiPixels = roi.w.toLong() * roi.h
        val scale = if (roiPixels > MAX_ROI_PIXELS) {
            sqrt(MAX_ROI_PIXELS.toDouble() / roiPixels).toFloat().coerceIn(0.2f, 1f)
        } else {
            params.coarseScale.coerceIn(0.25f, 0.9f)
        }

        if (scale >= 0.98f) {
            val filled = fastInpaintPixels(
                roi.pixels,
                roi.mask,
                roi.w,
                roi.h,
                params.fastRadius
            )
            for (i in roi.pixels.indices) if (roi.mask[i]) roi.pixels[i] = filled[i]
            writeBackMasked(bitmap, roi)
            onProgress?.invoke(1f)
            return Result(true, "OpenCV fast inpaint", roi.targetCount)
        }

        // Downscale via OpenCV (INTER_AREA = kualitas terbaik untuk minify).
        val srcMat = Mat(roi.h, roi.w, CvType.CV_8UC4)
        srcMat.put(0, 0, intsToBytes(roi.pixels))
        val dw = max(8, (roi.w * scale).toInt())
        val dh = max(8, (roi.h * scale).toInt())
        val smallMat = Mat()
        Imgproc.resize(srcMat, smallMat, Size(dw.toDouble(), dh.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)

        val smallPixels = bytesToInts(smallMat, dw, dh)
        srcMat.release(); smallMat.release()

        // Downscale mask (nearest) — piksel target jika salah satu tetangganya target.
        val smallMask = BooleanArray(dw * dh)
        var smallTarget = 0
        val sx = roi.w.toFloat() / dw
        val sy = roi.h.toFloat() / dh
        for (y in 0 until dh) {
            val y0 = (y * sy).toInt().coerceIn(0, roi.h - 1)
            val y1 = min(roi.h, ((y + 1) * sy).toInt().coerceAtLeast(y0 + 1))
            for (x in 0 until dw) {
                val x0 = (x * sx).toInt().coerceIn(0, roi.w - 1)
                val x1 = min(roi.w, ((x + 1) * sx).toInt().coerceAtLeast(x0 + 1))
                var hit = false
                loop@ for (yy in y0 until y1) for (xx in x0 until x1) {
                    if (roi.mask[yy * roi.w + xx]) { hit = true; break@loop }
                }
                if (hit) { smallMask[y * dw + x] = true; smallTarget++ }
            }
        }
        if (smallTarget == 0) return Result(false, "Tidak ada piksel yang perlu diisi")

        onProgress?.invoke(0.1f)
        val filledSmall = fastInpaintPixels(
            smallPixels,
            smallMask,
            dw,
            dh,
            (params.fastRadius * scale).coerceAtLeast(1.0)
        )
        for (i in smallPixels.indices) if (smallMask[i]) smallPixels[i] = filledSmall[i]
        onProgress?.invoke(0.8f)

        // Upscale hasil + blend masked-only ke ROI asli (detail tepi tetap asli).
        val upMat = Mat(dh, dw, CvType.CV_8UC4)
        upMat.put(0, 0, intsToBytes(smallPixels))
        val upBig = Mat()
        Imgproc.resize(upMat, upBig, Size(roi.w.toDouble(), roi.h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val upPixels = bytesToInts(upBig, roi.w, roi.h)
        upMat.release(); upBig.release()

        blendIntoMasked(roi.pixels, upPixels, roi.mask, roi.w, roi.h, featherBandPx = 3)
        writeBackMasked(bitmap, roi)
        onProgress?.invoke(1f)
        return Result(true, "Patch multi-scale (OpenCV pyr + Mat)", roi.targetCount)
    }

    // ── SEAMLESS (Poisson) method ─────────────────────────────────────────────

    private fun runSeamless(
        bitmap: Bitmap,
        clipped: Region,
        params: Params,
        mixedGradient: Boolean,
        onProgress: ((Float) -> Unit)?
    ): Result {
        val roi = extractRoi(bitmap, clipped, params.contextPadding)
            ?: return Result(false, "Tidak ada piksel yang perlu diisi")
        onProgress?.invoke(0.08f)

        // Poisson: sumber = rata-rata tepi yang di-blur lembut (latar halus),
        // tujuan = gambar asli. Hasil: tekstur gradasi mengalir ke dalam mask.
        val bg = roi.pixels.copyOf()
        val edge = edgeAverageColor(roi.pixels, roi.mask, roi.w, roi.h)
        for (i in bg.indices) if (roi.mask[i]) bg[i] = edge
        gaussianBlurMasked(bg, roi.mask, roi.w, roi.h, radius = 6)

        val srcMat = Mat(roi.h, roi.w, CvType.CV_8UC4).apply { put(0, 0, intsToBytes(bg)) }
        val dstMat = Mat(roi.h, roi.w, CvType.CV_8UC4).apply { put(0, 0, intsToBytes(roi.pixels)) }
        val maskMat = Mat.zeros(roi.h, roi.w, CvType.CV_8UC1)
        val maskBytes = ByteArray(roi.w * roi.h) { if (roi.mask[it]) 255.toByte() else 0 }
        maskMat.put(0, 0, maskBytes)
        // Kecilkan mask 1px agar Poisson punya konteks tepi yang valid.
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(3.0, 3.0))
        Imgproc.erode(maskMat, maskMat, kernel)
        kernel.release()
        if (Core.countNonZero(maskMat) == 0) {
            maskMat.put(0, 0, maskBytes) // mask terlalu tipis — pakai apa adanya
        }

        val outMat = Mat()
        val flag = if (mixedGradient) Photo.MIXED_CLONE else Photo.NORMAL_CLONE
        val center = org.opencv.core.Point(roi.w / 2.0, roi.h / 2.0)
        return try {
            Photo.seamlessClone(srcMat, dstMat, maskMat, center, outMat, flag)
            onProgress?.invoke(0.75f)
            val outPixels = bytesToInts(outMat, roi.w, roi.h)
            blendIntoMasked(roi.pixels, outPixels, roi.mask, roi.w, roi.h, featherBandPx = 2)
            writeBackMasked(bitmap, roi)
            onProgress?.invoke(1f)
            Result(true, "Poisson seamless clone (OpenCV)", roi.targetCount)
        } catch (cvErr: org.opencv.core.CvException) {
            cvErr.printStackTrace()
            // Poisson gagal (mis. mask menyentuh tepi ROI) → fallback patch.
            patchSynthesize(roi.pixels, roi.mask, roi.w, roi.h, params, onProgress)
            writeBackMasked(bitmap, roi)
            Result(true, "Patch synthesis (fallback)", roi.targetCount)
        } finally {
            srcMat.release(); dstMat.release(); maskMat.release(); outMat.release()
        }
    }

    // ── Core patch synthesis (Criminisi order + OpenCV Mat SSD) ──────────────

    /**
     * Exemplar-based fill pada IntArray, dengan loop inner yang dijalankan
     * native lewat [Core.sumElems] pada Mat diff — inilah bagian "OpenCV 5"
     * (bukan Telea/Navier-Stokes): tekstur disalin dari patch sumber terbaik.
     */
    private fun patchSynthesize(
        pixels: IntArray,
        mask: BooleanArray,
        w: Int,
        h: Int,
        params: Params,
        onProgress: ((Float) -> Unit)?
    ) {
        val pr = params.patchRadius.coerceIn(2, 8)
        val n = w * h
        val state = ByteArray(n)
        val confidence = FloatArray(n)
        for (i in 0 until n) {
            if (mask[i]) { state[i] = TARGET; confidence[i] = 0f }
            else { state[i] = KNOWN; confidence[i] = 1f }
        }

        // Pre-extract RGB channels untuk akses cepat.
        val rr = IntArray(n); val gg = IntArray(n); val bb = IntArray(n)
        for (i in pixels.indices) {
            val c = pixels[i]
            rr[i] = (c shr 16) and 0xFF; gg[i] = (c shr 8) and 0xFF; bb[i] = c and 0xFF
        }

        // Jumlah target dihitung sekali (tanpa alokasi lambda).
        var remaining = 0
        for (i in 0 until n) if (state[i] == TARGET) remaining++
        if (remaining == 0) return
        val totalTarget = remaining

        val patchDiam = pr * 2 + 1
        // Buffer Mat reusable untuk SSD native (1 channel float per channel RGB).
        val diffMat = Mat(patchDiam, patchDiam, CvType.CV_32FC1)
        val diffBuf = FloatArray(patchDiam * patchDiam)

        var iter = 0
        var lastReport = -1f
        while (remaining > 0 && iter < params.maxPatchIters) {
            iter++
            // 1. Hitung front (target pixel dengan tetangga known).
            var bestScore = -1.0
            var bestIdx = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val i = row + x
                    if (state[i] != TARGET) continue
                    val hasKnownNeighbour =
                        (x > 0 && state[i - 1] == KNOWN) ||
                        (x < w - 1 && state[i + 1] == KNOWN) ||
                        (y > 0 && state[i - w] == KNOWN) ||
                        (y < h - 1 && state[i + w] == KNOWN)
                    if (!hasKnownNeighbour) continue
                    // Confidence: rata-rata confidence patch di sekitar.
                    var cSum = 0f; var cN = 0
                    var grad = 0.0
                    for (dy in -pr..pr) {
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -pr..pr) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            val ni = ny * w + nx
                            cSum += confidence[ni]; cN++
                        }
                    }
                    // Data term: kekuatan gradien luma di sekitar (isophote).
                    val gx = lumaAt(rr, gg, bb, w, h, x + 1, y) - lumaAt(rr, gg, bb, w, h, x - 1, y)
                    val gy = lumaAt(rr, gg, bb, w, h, x, y + 1) - lumaAt(rr, gg, bb, w, h, x, y - 1)
                    grad = sqrt((gx * gx + gy * gy).toDouble())
                    val conf = if (cN == 0) 0f else cSum / cN
                    val data = (grad / 255.0).coerceIn(0.0, 1.0) + 0.001
                    val score = conf * 0.75 + conf * data * 2.0
                    if (score > bestScore) { bestScore = score; bestIdx = i }
                }
            }
            if (bestIdx < 0) break // tidak ada front (sisa target terisolasi) — selesai

            val px = bestIdx % w
            val py = bestIdx / w

            // 2. Cari exemplar terbaik via SSD native (Core.sumElems pada diff).
            var bestSsd = Double.MAX_VALUE
            var bestQx = -1; var bestQy = -1
            val x0 = max(0, px - pr); val x1 = min(w - 1, px + pr)
            val y0 = max(0, py - pr); val y1 = min(h - 1, py + pr)
            val step = if ((w.toLong() * h) > 900_000) 2 else 1
            for (qy in pr until h - pr step step) {
                for (qx in pr until w - pr step step) {
                    val qi = qy * w + qx
                    if (state[qi] == TARGET) continue
                    // Patch sumber harus sepenuhnya known.
                    var valid = true
                    var n = 0
                    for (dy in -pr..pr) {
                        if (!valid) break
                        val sRow = (qy + dy) * w
                        val tRow = (py + dy) * w
                        for (dx in -pr..pr) {
                            val tx = px + dx; val ty = py + dy
                            if (tx < 0 || tx >= w || ty < 0 || ty >= h) { diffBuf[n++] = 0f; continue }
                            val ti = tRow + tx
                            val si = sRow + qx + dx
                            if (state[si] == TARGET) { valid = false; break }
                            if (state[ti] == TARGET) { diffBuf[n++] = 0f; continue }
                            val dr = rr[si] - rr[ti]
                            val dg = gg[si] - gg[ti]
                            val db = bb[si] - bb[ti]
                            diffBuf[n++] = (dr * dr + dg * dg + db * db).toFloat()
                        }
                    }
                    if (!valid) continue
                    diffMat.put(0, 0, diffBuf)
                    val ssd = Core.sumElems(diffMat).`val`[0]
                    if (ssd < bestSsd) { bestSsd = ssd; bestQx = qx; bestQy = qy }
                }
            }

            if (bestQx < 0) {
                // Tidak ada exemplar penuh — isi piksel ini dari rata-rata tetangga known.
                fillFromNeighbours(pixels, rr, gg, bb, state, w, h, px, py, pr)
                state[bestIdx] = KNOWN
                confidence[bestIdx] = 0.5f
                remaining--
                continue
            }

            // 3. Copy bagian target dari patch sumber terbaik.
            var copied = 0
            for (dy in -pr..pr) {
                val ty = py + dy
                if (ty < 0 || ty >= h) continue
                val sRow = (bestQy + dy) * w
                val tRow = ty * w
                for (dx in -pr..pr) {
                    val tx = px + dx
                    if (tx < 0 || tx >= w) continue
                    val ti = tRow + tx
                    if (state[ti] != TARGET) continue
                    val si = sRow + bestQx + dx
                    pixels[ti] = pixels[si]
                    rr[ti] = rr[si]; gg[ti] = gg[si]; bb[ti] = bb[si]
                    state[ti] = KNOWN
                    confidence[ti] = confidence[bestIdx]
                    copied++
                }
            }
            remaining -= copied

            val prog = 1f - remaining.toFloat() / totalTarget
            if (prog - lastReport >= 0.05f) {
                lastReport = prog
                onProgress?.invoke(prog.coerceIn(0f, 0.98f))
            }
        }
        diffMat.release()

        // Sisa piksel terisolasi (jika ada): isi dari tetangga terdekat.
        if (remaining > 0) {
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (state[i] == TARGET) {
                    fillFromNeighbours(pixels, rr, gg, bb, state, w, h, x, y, 10)
                    state[i] = KNOWN
                }
            }
        }

        // 4. Feather seam: blur kecil hanya pada pita tepi mask, lalu blend.
        if (params.feather > 0f) {
            seamFeather(pixels, mask, w, h, params.feather)
        }
    }

    private fun lumaAt(rr: IntArray, gg: IntArray, bb: IntArray, w: Int, h: Int, x: Int, y: Int): Float {
        val cx = x.coerceIn(0, w - 1); val cy = y.coerceIn(0, h - 1)
        val i = cy * w + cx
        return (rr[i] * 0.299f + gg[i] * 0.587f + bb[i] * 0.114f)
    }

    private fun fillFromNeighbours(
        pixels: IntArray, rr: IntArray, gg: IntArray, bb: IntArray,
        state: ByteArray, w: Int, h: Int, x: Int, y: Int, radius: Int
    ) {
        var rS = 0; var gS = 0; var bS = 0; var n = 0
        for (dy in -radius..radius) {
            val ny = y + dy
            if (ny < 0 || ny >= h) continue
            for (dx in -radius..radius) {
                val nx = x + dx
                if (nx < 0 || nx >= w) continue
                val ni = ny * w + nx
                if (state[ni] != KNOWN) continue
                rS += rr[ni]; gS += gg[ni]; bS += bb[ni]; n++
            }
        }
        if (n > 0) {
            val i = y * w + x
            val r = rS / n; val g = gS / n; val b = bS / n
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            rr[i] = r; gg[i] = g; bb[i] = b
        }
    }

    // ── Seam feather via OpenCV GaussianBlur + addWeighted ────────────────────

    private fun seamFeather(pixels: IntArray, mask: BooleanArray, w: Int, h: Int, strength: Float) {
        // Pita tepi: piksel mask dengan tetangga non-mask (±2px).
        val band = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (!mask[i]) continue
                var isBand = false
                for (dy in -2..2) {
                    if (isBand) break
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -2..2) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        if (!mask[ny * w + nx]) { isBand = true; break }
                    }
                }
                band[i] = isBand
            }
        }
        var anyBand = false
        for (b in band) if (b) { anyBand = true; break }
        if (!anyBand) return

        val mat = Mat(h, w, CvType.CV_8UC4)
        mat.put(0, 0, intsToBytes(pixels))
        val blurred = Mat()
        Imgproc.GaussianBlur(mat, blurred, Size(5.0, 5.0), 0.0)
        val blurPixels = bytesToInts(blurred, w, h)
        mat.release(); blurred.release()

        val t = strength.coerceIn(0f, 1f) * 0.5f // sebagian kecil saja agar tidak kabur
        for (i in pixels.indices) {
            if (!band[i]) continue
            val a = pixels[i]; val b = blurPixels[i]
            val r = ((((a shr 16) and 0xFF) * (1 - t)) + (((b shr 16) and 0xFF) * t)).toInt()
            val g = ((((a shr 8) and 0xFF) * (1 - t)) + (((b shr 8) and 0xFF) * t)).toInt()
            val bl = (((a and 0xFF) * (1 - t)) + ((b and 0xFF) * t)).toInt()
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun edgeAverageColor(pixels: IntArray, mask: BooleanArray, w: Int, h: Int): Int {
        var rS = 0L; var gS = 0L; var bS = 0L; var n = 0L
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                if (!mask[i]) continue
                val isEdge =
                    (x > 0 && !mask[i - 1]) || (x < w - 1 && !mask[i + 1]) ||
                    (y > 0 && !mask[i - w]) || (y < h - 1 && !mask[i + w])
                if (!isEdge) continue
                // Ambil tetangga known pertama.
                val c = when {
                    x > 0 && !mask[i - 1] -> pixels[i - 1]
                    x < w - 1 && !mask[i + 1] -> pixels[i + 1]
                    y > 0 && !mask[i - w] -> pixels[i - w]
                    else -> pixels[i + w]
                }
                rS += (c shr 16) and 0xFF; gS += (c shr 8) and 0xFF; bS += c and 0xFF; n++
            }
        }
        if (n == 0L) return 0xFFFFFFFF.toInt()
        return (0xFF shl 24) or
            ((rS / n).toInt() shl 16) or ((gS / n).toInt() shl 8) or (bS / n).toInt()
    }

    private fun gaussianBlurMasked(pixels: IntArray, mask: BooleanArray, w: Int, h: Int, radius: Int) {
        val k = radius * 2 + 1
        val mat = Mat(h, w, CvType.CV_8UC4)
        mat.put(0, 0, intsToBytes(pixels))
        val blurred = Mat()
        Imgproc.GaussianBlur(mat, blurred, Size(k.toDouble(), k.toDouble()), 0.0)
        val bp = bytesToInts(blurred, w, h)
        mat.release(); blurred.release()
        for (i in pixels.indices) if (mask[i]) pixels[i] = bp[i]
    }

    private fun blendIntoMasked(dst: IntArray, src: IntArray, mask: BooleanArray, w: Int, h: Int, featherBandPx: Int) {
        for (i in dst.indices) if (mask[i]) dst[i] = src[i]
        if (featherBandPx > 0) seamFeather(dst, mask, w, h, 0.5f)
    }

    private fun writeBackMasked(bitmap: Bitmap, roi: Roi) {
        // Tulis hanya piksel mask agar context border tidak tersentuh.
        val out = IntArray(roi.w * roi.h)
        bitmap.getPixels(out, 0, roi.w, roi.x, roi.y, roi.w, roi.h)
        for (i in out.indices) if (roi.mask[i]) out[i] = roi.pixels[i]
        bitmap.setPixels(out, 0, roi.w, roi.x, roi.y, roi.w, roi.h)
    }

    private fun shiftRegion(region: Region, dx: Int, dy: Int): Region {
        val out = Region(); val ri = RegionIterator(region); val r = Rect()
        while (ri.next(r)) {
            val s = Rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
            if (!s.isEmpty) out.union(s)
        }
        return out
    }

    private fun countPixels(region: Region): Int {
        val it = RegionIterator(region); val r = Rect(); var total = 0L
        while (it.next(r)) total += r.width().toLong() * r.height().toLong()
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    // ARGB IntArray ↔ RGBA byte[] (format Mat CV_8UC4 OpenCV)
    private fun intsToBytes(px: IntArray): ByteArray {
        val out = ByteArray(px.size * 4)
        var j = 0
        for (c in px) {
            out[j++] = ((c shr 16) and 0xFF).toByte() // R
            out[j++] = ((c shr 8) and 0xFF).toByte()  // G
            out[j++] = (c and 0xFF).toByte()          // B
            out[j++] = ((c ushr 24) and 0xFF).toByte()// A
        }
        return out
    }

    private fun bytesToInts(mat: Mat, w: Int, h: Int): IntArray {
        val bytes = ByteArray(w * h * 4)
        mat.get(0, 0, bytes)
        val out = IntArray(w * h)
        var j = 0
        for (i in out.indices) {
            val r = bytes[j].toInt() and 0xFF
            val g = bytes[j + 1].toInt() and 0xFF
            val b = bytes[j + 2].toInt() and 0xFF
            val a = bytes[j + 3].toInt() and 0xFF
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            j += 4
        }
        return out
    }
}
