package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

/**
 * Mengencangkan kotak mask ke bentuk teks/font aktual.
 *
 * Tiap region dipotong dari [bitmap], lalu piksel tinta dipisahkan dari
 * background lewat threshold luminansi adaptif (median tepi sebagai
 * background). Proyeksi baris memecah jadi lajur teks, proyeksi kolom
 * mengencangkan kiri-kanan tiap lajur. Hasil: kotak-kotak ketat mengikuti
 * glyph, bukan satu kotak penuh.
 *
 * Murni CPU, tanpa model — aman untuk halaman webtoon tinggi karena crop
 * dibatasi dan dialokasikan per region.
 */
object TextGlyphMasker {

    private const val MAX_CROP_SIDE = 600
    private const val MAX_BOXES_PER_REGION = 24
    private const val MIN_BOX_PX = 6

    fun tighten(
        bitmap: Bitmap,
        regions: List<PaddleDbNetDetector.DetectedRegion>,
        padPx: Float = 2f
    ): List<PaddleDbNetDetector.DetectedRegion> {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return regions
        if (regions.isEmpty()) return regions
        val out = ArrayList<PaddleDbNetDetector.DetectedRegion>(regions.size)
        for (region in regions) {
            val tight = tightenOne(bitmap, region.rect, padPx)
            if (tight.isEmpty()) {
                out.add(region)
            } else {
                for (rect in tight) out.add(
                    PaddleDbNetDetector.DetectedRegion(rect, region.type, region.text)
                )
            }
        }
        return out
    }

    private fun tightenOne(bitmap: Bitmap, rect: RectF, padPx: Float): List<RectF> {
        val left = rect.left.toInt().coerceIn(0, bitmap.width - 1)
        val top = rect.top.toInt().coerceIn(0, bitmap.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, bitmap.height)
        val w = right - left
        val h = bottom - top
        if (w < MIN_BOX_PX || h < MIN_BOX_PX) return emptyList()

        // Batasi crop agar hemat memori; ingat skala balik ke koordinat sumber.
        val scale = minOf(1f, MAX_CROP_SIDE.toFloat() / maxOf(w, h))
        val cw = maxOf(1, (w * scale).toInt())
        val ch = maxOf(1, (h * scale).toInt())
        val pixels = IntArray(cw * ch)
        try {
            val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(crop, cw, ch, true).also {
                    if (!crop.isRecycled) crop.recycle()
                }
            } else crop
            try {
                small.getPixels(pixels, 0, cw, 0, 0, cw, ch)
            } finally {
                if (!small.isRecycled) small.recycle()
            }
        } catch (_: Exception) {
            return emptyList()
        }

        val lum = IntArray(cw * ch) { i ->
            val c = pixels[i]
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)).toInt()
        }

        // Background = median luminansi tepi crop.
        val border = ArrayList<Int>(cw * 2 + ch * 2)
        for (x in 0 until cw) {
            border.add(lum[x])
            border.add(lum[(ch - 1) * cw + x])
        }
        for (y in 0 until ch) {
            border.add(lum[y * cw])
            border.add(lum[y * cw + cw - 1])
        }
        border.sort()
        val bg = border[border.size / 2]
        // Kontras adaptif: butuh selisih cukup agar bukan noise.
        var minL = 255
        var maxL = 0
        for (v in lum) {
            if (v < minL) minL = v
            if (v > maxL) maxL = v
        }
        val spread = maxL - minL
        if (spread < 30) return emptyList()
        val thresh = maxOf(22, (spread * 0.28f).toInt())

        val ink = BooleanArray(cw * ch) { i ->
            kotlin.math.abs(lum[i] - bg) >= thresh
        }
        if (!ink.any { it }) return emptyList()

        // Proyeksi baris → lajur teks (toleransi gap 2px).
        val rowHasInk = BooleanArray(ch) { y ->
            var x = 0
            while (x < cw) {
                if (ink[y * cw + x]) return@BooleanArray true
                x++
            }
            false
        }
        val bands = ArrayList<IntRange>()
        var y = 0
        while (y < ch) {
            if (!rowHasInk[y]) {
                y++
                continue
            }
            var y2 = y
            var gap = 0
            while (y2 < ch) {
                if (rowHasInk[y2]) {
                    gap = 0
                } else {
                    gap++
                    if (gap > 2) break
                }
                y2++
            }
            bands.add(y..y2 - gap)
            y = y2 + 1
        }
        if (bands.isEmpty()) return emptyList()

        val boxes = ArrayList<RectF>()
        for (band in bands) {
            var x0 = cw
            var x1 = -1
            for (yy in band) {
                var x = 0
                while (x < cw) {
                    if (ink[yy * cw + x]) {
                        if (x < x0) x0 = x
                        if (x > x1) x1 = x
                    }
                    x++
                }
            }
            if (x1 < x0) continue
            // Kembali ke koordinat bitmap sumber + padding kecil.
            val inv = 1f / scale
            val r = RectF(
                (left + (x0 * inv - padPx)).coerceAtLeast(0f),
                (top + (band.first * inv - padPx)).coerceAtLeast(0f),
                (left + ((x1 + 1) * inv + padPx)).coerceAtMost(bitmap.width.toFloat()),
                (top + ((band.last + 1) * inv + padPx)).coerceAtMost(bitmap.height.toFloat())
            )
            if (r.width() >= MIN_BOX_PX && r.height() >= MIN_BOX_PX) boxes.add(r)
            if (boxes.size >= MAX_BOXES_PER_REGION) break
        }
        return boxes
    }
}
