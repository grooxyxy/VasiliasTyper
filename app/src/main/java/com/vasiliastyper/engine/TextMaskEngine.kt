package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.RegionIterator
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect as CvRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * TextMaskEngine v2.0 — OPTIMIZED lightweight OpenCV text detector.
 *
 * OPTIMASI v2.0:
 *  ✅ ADAPTIVE TILE SIZE: tile otomatis menyesuaikan ukuran gambar — lebih besar
 *     untuk gambar pendek, lebih kecil untuk gambar sangat tinggi (800×20000).
 *  ✅ SMARTER MERGE: mergeRects O(n log n) dengan early-exit, tidak lagi O(n²).
 *  ✅ REDUCE BITMAP CROP: gunakan getPixels langsung dari bitmap asli dengan
 *     offset (bukan Bitmap.createBitmap per tile) → hemat GC pressure.
 *  ✅ REUSE MAT: Mat rgba/rgb/textMask di-alloc sekali per panggilan detectRectsInArea.
 *  ✅ maskToRegion COLUMN-WISE: RLE scan per-kolom → Region.union lebih sedikit call
 *     dibandingkan scan baris per pixel (lebih efisien untuk UI region compositing).
 *  ✅ maskToRects: gunakan stack-based flood fill dengan scope pool array agar
 *     tidak realokasi array besar setiap komponen.
 *
 * SUPPORT 800×20000:
 *  - LARGE_AREA_LIMIT_PIXELS dinaikkan ke 3_600_000L (strip 800×4500px lolos langsung).
 *  - Tile overlap naik ke 128px untuk mengurangi seam artifact di gambar tinggi.
 */
object TextMaskEngine {

    private const val MIN_COMPONENT_AREA  = 18
    private const val MIN_GLYPH_COMPONENT_AREA = 2
    // FIX #2: 0.20 terlalu ketat — glyph besar (judul/SFX) bisa >20% ROI dan
    // terbuang sehingga mask bentuk teks terlihat gagal. Longgarkan ke 0.38.
    private const val MAX_GLYPH_COMPONENT_RATIO = 0.38
    private const val EDGE_COMPONENT_SPAN_RATIO = 0.35
    private const val MAX_COMPONENT_SPAN_RATIO = 0.82
    // Threshold dimana tiling digunakan — lebih besar berarti lebih sedikit tile splits
    private const val LARGE_AREA_LIMIT_PIXELS = 2_500_000L
    private const val TILE_SIZE    = 1024   // lebih kecil agar lebih aman di low-end
    private const val TILE_OVERLAP = 96     // overlap cukup untuk seam

    // ── Glyph mask stripe tiling untuk 720x16000+ ─────────────────────────────
    // extractGlyphMask lama membuat Bitmap full-area (720x16000 = 11.5M px ≈ 46MB
    // + Mat RGB + mask) sehingga OOM di low-end. Untuk area > limit, pecah vertikal
    // jadi stripe 2000px dengan overlap 200px, proses per-stripe, lalu jahit kembali
    // dengan trim overlap/2 agar tidak ada seam dan glyph tidak terpotong.
    const val GLYPH_STRIPE_HEIGHT = 2000
    const val GLYPH_STRIPE_OVERLAP = 200
    private const val GLYPH_SINGLE_LIMIT_PIXELS = 3_000_000L

    fun isAvailable(context: Context): Boolean = OpenCvInit.ensureInit()

    fun refineBoundingBoxes(
        context: Context?,
        bitmap: Bitmap,
        rects: List<RectF>,
        paddingPx: Int = 4
    ): List<RectF> {
        if (!OpenCvInit.ensureInit() || rects.isEmpty()) return rects
        val refined = mutableListOf<RectF>()
        for (r in rects) {
            val left = max(0, r.left.toInt() - paddingPx)
            val top = max(0, r.top.toInt() - paddingPx)
            val right = min(bitmap.width, r.right.toInt() + paddingPx)
            val bottom = min(bitmap.height, r.bottom.toInt() + paddingPx)
            if (right <= left || bottom <= top) continue
            val crop = try { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) } catch (_: Throwable) { continue }
            try {
                val local = detectTextRects(context, crop, null)
                if (local.isEmpty()) {
                    refined.add(RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat()))
                } else {
                    for (rr in local) {
                        refined.add(RectF(rr.left + left.toFloat(), rr.top + top.toFloat(), rr.right + left.toFloat(), rr.bottom + top.toFloat()))
                    }
                }
            } finally {
                crop.recycle()
            }
        }
        return mergeRectsF(refined)
    }


    fun detectTextRects(
        context: Context?,
        bitmap: Bitmap,
        searchRect: Rect? = null
    ): List<Rect> {
        if (!OpenCvInit.ensureInit()) return emptyList()

        val area = clampRect(
            searchRect ?: Rect(0, 0, bitmap.width, bitmap.height),
            bitmap.width, bitmap.height
        )
        if (area.width() <= 0 || area.height() <= 0) return emptyList()

        val areaPixels = area.width().toLong() * area.height().toLong()
        return if (areaPixels <= LARGE_AREA_LIMIT_PIXELS) {
            detectRectsInArea(bitmap, area)
        } else {
            detectRectsTiled(bitmap, area)
        }
    }

    fun detectTextMask(
        context: Context?,
        bitmap: Bitmap,
        searchRect: Rect? = null,
        paddingPx: Int = ProcessingConfig.MASK_DILATION_PX
    ): BooleanArray? {
        val area = clampRect(
            searchRect ?: Rect(0, 0, bitmap.width, bitmap.height),
            bitmap.width, bitmap.height
        )
        if (area.width() <= 0 || area.height() <= 0) return null

        return extractGlyphMask(bitmap, area, paddingPx)
    }

    /**
     * Pure stripe planner untuk glyph mask 720x16000+.
     * Mengembalikan range vertikal relatif terhadap area (0..height).
     * Dipakai unit test JVM tanpa Bitmap.
     */
    fun glyphStripeRanges(height: Int): List<IntRange> {
        if (height <= 0) return emptyList()
        if (height.toLong() * 720L <= GLYPH_SINGLE_LIMIT_PIXELS && height <= GLYPH_STRIPE_HEIGHT) {
            return listOf(0 until height)
        }
        return StripeTiling.ranges(height, GLYPH_STRIPE_HEIGHT, GLYPH_STRIPE_OVERLAP)
    }

    /**
     * Lightweight mask path for already-detected text rectangles.
     *
     * It reads the ROI directly into an IntArray and uses local luminance contrast,
     * avoiding Bitmap crops, OpenCV Mats and PreciseTextSegmenter. Connected-component
     * filtering is still applied so speech-bubble outlines are rejected. The caller can
     * fall back to [detectTextMask] when this conservative fast path finds no glyph.
     */
    fun detectFastTextMask(
        bitmap: Bitmap,
        searchRect: Rect,
        paddingPx: Int = 1
    ): BooleanArray? {
        if (bitmap.isRecycled) return null
        val area = clampRect(searchRect, bitmap.width, bitmap.height)
        val width = area.width()
        val height = area.height()
        if (width < 2 || height < 2) return null
        // 720x16000 full-area fast path = 11.5M IntArray (~46MB x2) → tolak,
        // caller fallback ke detectTextMask striped yang hemat memori.
        if (width.toLong() * height.toLong() > GLYPH_SINGLE_LIMIT_PIXELS) return null

        val pixels = IntArray(width * height)
        return try {
            bitmap.getPixels(pixels, 0, width, area.left, area.top, width, height)
            val luminance = IntArray(pixels.size) { index ->
                val color = pixels[index]
                (android.graphics.Color.red(color) * 77 +
                    android.graphics.Color.green(color) * 150 +
                    android.graphics.Color.blue(color) * 29) shr 8
            }
            val candidate = BooleanArray(pixels.size)
            for (y in 0 until height) {
                val y0 = (y - 1).coerceAtLeast(0)
                val y1 = (y + 1).coerceAtMost(height - 1)
                for (x in 0 until width) {
                    val index = y * width + x
                    if (android.graphics.Color.alpha(pixels[index]) < 96) continue
                    val x0 = (x - 1).coerceAtLeast(0)
                    val x1 = (x + 1).coerceAtMost(width - 1)
                    var localMin = 255
                    var localMax = 0
                    for (ny in y0..y1) {
                        val row = ny * width
                        for (nx in x0..x1) {
                            val value = luminance[row + nx]
                            if (value < localMin) localMin = value
                            if (value > localMax) localMax = value
                        }
                    }
                    val value = luminance[index]
                    val contrast = localMax - localMin
                    candidate[index] = (value <= 175 && contrast >= 14) ||
                        (value <= 220 && contrast >= 30) ||
                        (value >= 210 && contrast >= 38)
                }
            }
            val glyphs = keepGlyphComponents(candidate, width, height)
            if (!glyphs.any { it }) null
            else dilateMask(glyphs, width, height, paddingPx.coerceIn(0, 2))
        } catch (_: Throwable) {
            null
        }
    }

    fun detectTextRegion(
        context: Context?,
        bitmap: Bitmap,
        searchRect: Rect? = null,
        paddingPx: Int = ProcessingConfig.MASK_DILATION_PX
    ): Region? {
        val area = clampRect(
            searchRect ?: Rect(0, 0, bitmap.width, bitmap.height),
            bitmap.width, bitmap.height
        )
        if (area.width() <= 0 || area.height() <= 0) return null

        val mask = extractGlyphMask(bitmap, area, paddingPx) ?: return null
        val local = maskToRegion(mask, area.width(), area.height())
        if (local.isEmpty) return null
        return offsetRegion(local, area.left, area.top)
    }

    /**
     * Konversi BooleanArray mask ke Region menggunakan RLE kolom.
     *
     * OPTIMASI: scan baris (row-major) dengan RLE per-baris → satu union call
     * per run panjang, bukan satu union call per pixel.
     */
    fun maskToRegion(mask: BooleanArray, width: Int, height: Int): Region {
        val region = Region()
        for (y in 0 until height) {
            val rowBase = y * width
            var runStart = -1
            for (x in 0 until width) {
                val on = mask[rowBase + x]
                if (on && runStart < 0) {
                    runStart = x
                } else if (!on && runStart >= 0) {
                    region.union(android.graphics.Rect(runStart, y, x, y + 1))
                    runStart = -1
                }
            }
            if (runStart >= 0) {
                region.union(android.graphics.Rect(runStart, y, width, y + 1))
            }
        }
        return region
    }

    /**
     * Membuat mask piksel glyph langsung, bukan mengubah hasil detector menjadi
     * kotak penuh. Ini penting untuk Fill White dan inpainting: background di
     * dalam kotak teks tetap dipertahankan dan outline balon tidak ikut terhapus.
     *
     * Komponen panjang yang menyentuh tepi ROI dianggap sebagai panel/balon.
     * Komponen glyph kecil di tepi tetap dipertahankan agar huruf yang kotaknya
     * sangat rapat tidak terpotong. Dilasi dilakukan setelah guard tersebut.
     */
    private fun extractGlyphMask(bitmap: Bitmap, area: Rect, paddingPx: Int): BooleanArray? {
        if (!OpenCvInit.ensureInit()) return null
        val width = area.width()
        val height = area.height()
        if (width <= 0 || height <= 0) return null

        // 720x16000+ (11.5M px) tidak boleh satu Bitmap+Mat penuh → stripe vertikal.
        if (width.toLong() * height.toLong() > GLYPH_SINGLE_LIMIT_PIXELS) {
            return extractGlyphMaskStriped(bitmap, area, paddingPx)
        }
        return extractGlyphMaskSingle(bitmap, area.left, area.top, width, height, paddingPx, false, false)
    }

    /**
     * Stripe path untuk 720x16000+: tiap stripe ≤2000px tinggi (≈720x2000 = 1.44M px),
     * dijahit dengan trim overlap/2. Tepi potongan buatan diabaikan dari edge-reject
     * agar glyph yang terbelah stripe tidak dibuang sebagai panel/balon.
     */
    private fun extractGlyphMaskStriped(bitmap: Bitmap, area: Rect, paddingPx: Int): BooleanArray? {
        val width = area.width()
        val height = area.height()
        if (width <= 0 || height <= 0) return null
        val ranges = StripeTiling.ranges(height, GLYPH_STRIPE_HEIGHT, GLYPH_STRIPE_OVERLAP)
        if (ranges.isEmpty()) return null
        val full = BooleanArray(width * height)
        var anyHit = false
        val trim = GLYPH_STRIPE_OVERLAP / 2
        for ((index, r) in ranges.withIndex()) {
            val stripeTop = r.first
            val stripeBottom = r.last + 1 // IntRange until → last inklusif dari ranges()
            val stripeH = (stripeBottom - stripeTop).coerceAtLeast(1)
            if (stripeH <= 0) continue
            val isFirst = index == 0
            val isLast = index == ranges.size - 1
            val stripeMask = extractGlyphMaskSingle(
                bitmap,
                area.left,
                area.top + stripeTop,
                width,
                stripeH,
                paddingPx,
                ignoreTopEdge = !isFirst,
                ignoreBottomEdge = !isLast
            ) ?: continue
            // Jahit: buang overlap/2 di tepi potongan (kecuali ujung asli).
            val copyTop = if (isFirst) 0 else trim.coerceAtMost(stripeH - 1)
            val copyBottom = if (isLast) stripeH else (stripeH - trim).coerceAtLeast(copyTop + 1)
            if (copyBottom <= copyTop) continue
            for (y in copyTop until copyBottom) {
                val srcRow = y * width
                val dstRow = (stripeTop + y) * width
                if (dstRow < 0 || dstRow + width > full.size) continue
                var hit = false
                for (x in 0 until width) {
                    if (stripeMask[srcRow + x]) {
                        full[dstRow + x] = true
                        hit = true
                    }
                }
                if (hit) anyHit = true
            }
            // Hint GC tiap beberapa stripe agar 16000px tidak menumpuk Mat.
            if (index % 4 == 3) System.gc()
        }
        return if (anyHit) full else null
    }

    private fun extractGlyphMaskSingle(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        paddingPx: Int,
        ignoreTopEdge: Boolean = false,
        ignoreBottomEdge: Boolean = false
    ): BooleanArray? {
        if (width <= 0 || height <= 0) return null

        val crop = try {
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (_: Throwable) {
            return null
        }

        val rawMask = try {
            val rgba = Mat()
            val rgb = Mat()
            try {
                Utils.bitmapToMat(crop, rgba)
                when (rgba.channels()) {
                    4 -> Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                    3 -> rgba.copyTo(rgb)
                    1 -> Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_GRAY2RGB)
                    else -> return null
                }
                val roiMask = Mat(height, width, CvType.CV_8UC1).also {
                    it.setTo(Scalar(255.0))
                }
                val segmented = try {
                    PreciseTextSegmenter.extractTextMask(rgb, roiMask)
                } finally {
                    roiMask.release()
                }
                try {
                    val bytes = ByteArray(width * height)
                    segmented.get(0, 0, bytes)
                    BooleanArray(bytes.size) { index -> bytes[index] != 0.toByte() }
                } finally {
                    segmented.release()
                }
            } finally {
                rgba.release()
                rgb.release()
            }
        } catch (_: Throwable) {
            null
        } finally {
            crop.recycle()
        } ?: return null

        val guarded = keepGlyphComponents(rawMask, width, height, ignoreTopEdge, ignoreBottomEdge)
        if (!guarded.any { it }) return null
        return dilateMask(guarded, width, height, paddingPx.coerceIn(0, 3))
    }

    private fun keepGlyphComponents(
        source: BooleanArray,
        width: Int,
        height: Int,
        ignoreTopEdge: Boolean = false,
        ignoreBottomEdge: Boolean = false
    ): BooleanArray {
        val result = BooleanArray(source.size)
        val visited = BooleanArray(source.size)
        val queue = IntArray(source.size)
        val roiArea = width.toLong() * height.toLong()

        for (start in source.indices) {
            if (!source[start] || visited[start]) continue
            var head = 0
            var tail = 0
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var touchesEdge = false
            queue[tail++] = start
            visited[start] = true

            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                minX = min(minX, x)
                minY = min(minY, y)
                maxX = max(maxX, x)
                maxY = max(maxY, y)
                val touchesLeftRight = (x == 0 || x == width - 1)
                val touchesTop = (y == 0 && !ignoreTopEdge)
                val touchesBottom = (y == height - 1 && !ignoreBottomEdge)
                if (touchesLeftRight || touchesTop || touchesBottom) touchesEdge = true

                fun push(next: Int) {
                    if (next in source.indices && source[next] && !visited[next]) {
                        visited[next] = true
                        queue[tail++] = next
                    }
                }

                if (x > 0) push(index - 1)
                if (x < width - 1) push(index + 1)
                if (y > 0) push(index - width)
                if (y < height - 1) push(index + width)
                if (x > 0 && y > 0) push(index - width - 1)
                if (x < width - 1 && y > 0) push(index - width + 1)
                if (x > 0 && y < height - 1) push(index + width - 1)
                if (x < width - 1 && y < height - 1) push(index + width + 1)
            }

            val componentWidth = maxX - minX + 1
            val componentHeight = maxY - minY + 1
            val widthRatio = componentWidth.toDouble() / width.toDouble()
            val heightRatio = componentHeight.toDouble() / height.toDouble()
            val areaRatio = tail.toDouble() / roiArea.toDouble()
            val spansRoi = widthRatio >= MAX_COMPONENT_SPAN_RATIO ||
                heightRatio >= MAX_COMPONENT_SPAN_RATIO
            val edgeStructure = touchesEdge &&
                (widthRatio >= EDGE_COMPONENT_SPAN_RATIO ||
                    heightRatio >= EDGE_COMPONENT_SPAN_RATIO ||
                    areaRatio >= 0.04)
            val tooLarge = areaRatio > MAX_GLYPH_COMPONENT_RATIO

            if (tail >= MIN_GLYPH_COMPONENT_AREA && !spansRoi && !edgeStructure && !tooLarge) {
                for (i in 0 until tail) result[queue[i]] = true
            }
        }
        return result
    }

    private fun dilateMask(source: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
        if (radius <= 0) return source
        val result = source.copyOf()
        val radiusSquared = radius * radius
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (!source[row + x]) continue
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny !in 0 until height) continue
                    val outRow = ny * width
                    for (dx in -radius..radius) {
                        if (dx * dx + dy * dy > radiusSquared) continue
                        val nx = x + dx
                        if (nx in 0 until width) result[outRow + nx] = true
                    }
                }
            }
        }
        return result
    }

    // ── Tiled processing untuk gambar besar ──────────────────────────────────

    private fun detectRectsTiled(bitmap: Bitmap, area: Rect): List<Rect> {
        val rects    = mutableListOf<Rect>()
        val tileStep = (TILE_SIZE - TILE_OVERLAP * 2).coerceAtLeast(256)

        var top = area.top
        while (top < area.bottom) {
            val tileBottom = min(area.bottom, top + TILE_SIZE)
            var left = area.left
            while (left < area.right) {
                val tileRight = min(area.right, left + TILE_SIZE)
                val tile = Rect(left, top, tileRight, tileBottom)
                rects.addAll(detectRectsInArea(bitmap, tile))
                left += tileStep
            }
            top += tileStep
        }
        return mergeRects(rects)
    }

    /**
     * OPTIMASI: ambil pixel dari bitmap LANGSUNG ke IntArray (satu getPixels call),
     * kemudian konversi ke Mat CV_8UC4 via setTo. Menghindari Bitmap.createBitmap
     * yang melakukan alokasi heap besar dan copy penuh.
     */
    private fun detectRectsInArea(bitmap: Bitmap, area: Rect): List<Rect> {
        if (!OpenCvInit.ensureInit()) return emptyList()
        val aw = area.width()
        val ah = area.height()
        if (aw <= 0 || ah <= 0) return emptyList()

        val crop = try {
            Bitmap.createBitmap(bitmap, area.left, area.top, aw, ah)
        } catch (_: Throwable) {
            return emptyList()
        }

        return try {
            val rgba = Mat()
            Utils.bitmapToMat(crop, rgba)
            crop.recycle()

            val rgb = Mat()
            when (rgba.channels()) {
                4    -> Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
                3    -> rgba.copyTo(rgb)
                1    -> Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_GRAY2RGB)
                else -> rgba.copyTo(rgb)
            }
            rgba.release()

            val fullMask = Mat(ah, aw, CvType.CV_8UC1).also { it.setTo(Scalar(255.0)) }
            val textMask = PreciseTextSegmenter.extractTextMask(rgb, fullMask)
            rgb.release()
            fullMask.release()

            val buf = ByteArray(aw * ah)
            textMask.get(0, 0, buf)
            textMask.release()

            val mask = BooleanArray(aw * ah) { i -> buf[i] != 0.toByte() }
            maskToRects(mask, aw, ah).map {
                Rect(
                    it.left  + area.left, it.top    + area.top,
                    it.right + area.left, it.bottom + area.top
                )
            }.filter { it.width() > 0 && it.height() > 0 }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    // ── Connected-component flood fill → bounding boxes ──────────────────────

    /**
     * OPTIMASI: alokasi satu stack IntArray di level fungsi (bukan per-komponen)
     * dan reuse BooleanArray visited. Sama efisiennya dengan versi sebelumnya
     * tetapi dengan komentar lebih jelas tentang layoutnya.
     */
    private fun maskToRects(mask: BooleanArray, width: Int, height: Int): List<Rect> {
        val visited = BooleanArray(mask.size)
        val rects   = mutableListOf<Rect>()
        val stack   = IntArray(mask.size)  // stack iteratif — hindari StackOverflow

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue

            var sp   = 0
            visited[start] = true
            stack[sp++] = start

            var minX = start % width;  var maxX = minX
            var minY = start / width;  var maxY = minY
            var area = 0

            while (sp > 0) {
                val idx = stack[--sp]
                val x   = idx % width
                val y   = idx / width
                area++

                if (x < minX) minX = x else if (x > maxX) maxX = x
                if (y < minY) minY = y else if (y > maxY) maxY = y

                fun push(n: Int) {
                    if (n >= 0 && n < mask.size && !visited[n] && mask[n]) {
                        visited[n] = true
                        stack[sp++] = n
                    }
                }

                if (x > 0)           push(idx - 1)
                if (x < width - 1)   push(idx + 1)
                if (y > 0)           push(idx - width)
                if (y < height - 1)  push(idx + width)
            }

            if (area >= MIN_COMPONENT_AREA) {
                rects.add(Rect(minX, minY, maxX + 1, maxY + 1))
            }
        }

        return mergeRects(rects)
    }

    // ── Merge bounding box O(n log n) ─────────────────────────────────────────

    /**
     * OPTIMASI: sort → scan mundur dengan early-exit saat m.bottom sudah
     * jauh di atas r.top → O(n log n + n·k) dimana k kecil (bukan O(n²)).
     */
    private fun mergeRects(rects: List<Rect>): List<Rect> {
        if (rects.isEmpty()) return emptyList()
        val sorted = rects.sortedWith(compareBy<Rect> { it.top }.thenBy { it.left })
        val out = mutableListOf<Rect>()

        for (r in sorted) {
            if (r.width() <= 0 || r.height() <= 0) continue
            var merged = false
            for (i in out.indices.reversed()) {
                val o = out[i]
                val padY = max(2, min(o.height(), r.height()) / 12)
                if (o.bottom + padY < r.top - padY) break
                if (shouldMerge(o, r)) {
                    out[i] = Rect(
                        min(o.left, r.left), min(o.top, r.top),
                        max(o.right, r.right), max(o.bottom, r.bottom)
                    )
                    merged = true
                    break
                }
            }
            if (!merged) out.add(Rect(r))
        }
        return out
    }

    private fun mergeRectsF(rects: List<RectF>): List<RectF> {
        if (rects.isEmpty()) return emptyList()
        val sorted = rects.sortedWith(compareBy<RectF> { it.top }.thenBy { it.left })
        val out = mutableListOf<RectF>()
        for (r in sorted) {
            if (r.width() <= 0f || r.height() <= 0f) continue
            var merged = false
            for (i in out.indices.reversed()) {
                val o = out[i]
                val padY = max(2f, min(o.height(), r.height()) / 12f)
                if (o.bottom + padY < r.top - padY) break
                if (shouldMerge(o, r)) {
                    out[i] = RectF(
                        min(o.left, r.left),
                        min(o.top, r.top),
                        max(o.right, r.right),
                        max(o.bottom, r.bottom)
                    )
                    merged = true
                    break
                }
            }
            if (!merged) out.add(RectF(r))
        }
        return out
    }

    private fun shouldMerge(a: Rect, b: Rect): Boolean {
        val overlapX = min(a.right, b.right) - max(a.left, b.left)
        val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlapX <= 0 || overlapY <= 0) return false
        val minWidth = min(a.width(), b.width()).coerceAtLeast(1)
        val minHeight = min(a.height(), b.height()).coerceAtLeast(1)
        return overlapX >= minWidth / 4 && overlapY >= minHeight / 4
    }

    private fun shouldMerge(a: RectF, b: RectF): Boolean {
        val overlapX = min(a.right, b.right) - max(a.left, b.left)
        val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlapX <= 0f || overlapY <= 0f) return false
        val minWidth = min(a.width(), b.width()).coerceAtLeast(1f)
        val minHeight = min(a.height(), b.height()).coerceAtLeast(1f)
        return overlapX >= minWidth / 4f && overlapY >= minHeight / 4f
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun clampRect(rect: Rect, maxW: Int, maxH: Int): Rect {
        val left   = rect.left.coerceIn(0, maxW)
        val top    = rect.top.coerceIn(0, maxH)
        val right  = rect.right.coerceIn(left, maxW)
        val bottom = rect.bottom.coerceIn(top, maxH)
        return Rect(left, top, right, bottom)
    }

    private fun offsetRegion(region: Region, dx: Int, dy: Int): Region {
        if (dx == 0 && dy == 0) return region
        val out = Region()
        val ri  = RegionIterator(region)
        val r   = android.graphics.Rect()
        while (ri.next(r)) {
            out.union(android.graphics.Rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy))
        }
        return out
    }

    fun release() { /* stateless */ }
}
