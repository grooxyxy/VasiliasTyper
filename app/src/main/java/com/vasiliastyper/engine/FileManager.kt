package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.content.ContentValues
  import android.media.MediaScannerConnection
  import android.os.Build
  import android.os.Environment
  import android.provider.MediaStore
  import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object FileManager {

    /**
     * Load any image (JPG / PNG / WebP / BMP) from a Uri, keeping it as sharp
     * as possible within the device's available heap.
     *
     * Quality strategy:
     *  1. Probe native dimensions.
     *  2. Compute a memory-safe inSampleSize — NOT an arbitrary pixel cap.
     *     The bitmap will be as large as 60% of max heap allows (no 4000px limit).
     *  3. When the native image is so large that even inSampleSize=1 risks OOM,
     *     use BitmapRegionDecoder to stitch full-res tiles at the target resolution.
     *  4. If any final scale-down is still needed, use progressive halving
     *     (each step 0.5×, with bilinear filtering) instead of one big jump —
     *     this matches Lanczos quality for manga/text content.
     *
     * For a 3000×20000 manga strip on a device with 512 MB heap:
     *   native = 240 MB → fits → loaded at 3000×20000, pixel-perfect.
     * On a device with 128 MB heap:
     *   inSampleSize = 2 → 750×5000 → 15 MB → still 5× sharper than the old 4000px cap.
     */
    suspend fun loadBitmap(
        context: Context,
        uri: Uri
    ): Bitmap? = withContext(Dispatchers.IO) {
        try {
            // ── Step 1: probe native dimensions ──────────────────────────────────
            val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, probe)
            }
            val rawW = probe.outWidth
            val rawH = probe.outHeight
            if (rawW <= 0 || rawH <= 0) return@withContext null

            // ── Step 2: memory-safe inSampleSize ─────────────────────────────────
            // A decoded image is followed by optional layer and undo allocations.
            // Use the same device-aware canvas budget as blank projects instead
            // of consuming 60% of the heap with the first bitmap alone.
            val heapBudget = BitmapSafety.canvasPixelBudget().toLong() * 4L
            val bytesPerPixel = 4L   // ARGB_8888
            val nativeBytes   = rawW.toLong() * rawH * bytesPerPixel

            var sample = 1
            while (nativeBytes / (sample.toLong() * sample) > heapBudget) {
                sample *= 2
            }

            // ── Step 3: decode ────────────────────────────────────────────────────
            val decoded: Bitmap = if (sample == 1 && nativeBytes <= heapBudget) {
                // Native resolution fits in memory — load pixel-perfect
                val opts = BitmapFactory.Options().apply {
                    inSampleSize      = 1
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable         = true
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@withContext null
            } else if (sample <= 4) {
                // Moderate downscale — standard decode then progressive refine
                val opts = BitmapFactory.Options().apply {
                    inSampleSize      = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inMutable         = true
                }
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: return@withContext null
            } else {
                // Very large image — use BitmapRegionDecoder to stitch tiles
                // at the target resolution, avoiding a single monster allocation.
                val targetW = (rawW.toLong() * 1 / sample).toInt().coerceAtLeast(1)
                val targetH = (rawH.toLong() * 1 / sample).toInt().coerceAtLeast(1)
                decodeTiled(context, uri, rawW, rawH, targetW, targetH)
                    ?: return@withContext null
            }

            // ── Step 4: progressive scale-down if still slightly over budget ─────
            val decodedBytes = decoded.width.toLong() * decoded.height * bytesPerPixel
            if (decodedBytes <= heapBudget) {
                return@withContext decoded
            }

            // Progressive halving until it fits — much sharper than a single big scale
            val result = progressiveScaleDown(decoded, heapBudget, bytesPerPixel)
            if (result !== decoded) decoded.recycle()
            result

        } catch (e: OutOfMemoryError) {
            e.printStackTrace()
            // Last-resort fallback: reload at a very conservative size
            loadFallback(context, uri)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Tile-based decode for very large images ───────────────────────────────

    /**
     * Decode [rawW]×[rawH] source into a [targetW]×[targetH] bitmap using
     * BitmapRegionDecoder to read tiles at native resolution and downscale
     * each tile individually, avoiding one massive intermediate allocation.
     *
     * Each tile is decoded at native resolution then scaled — this gives much
     * sharper edges than decoding the full image at a low inSampleSize.
     */
    private fun decodeTiled(
        context: Context,
        uri: Uri,
        rawW: Int, rawH: Int,
        targetW: Int, targetH: Int
    ): Bitmap? {
        val decoder = context.contentResolver.openInputStream(uri)?.use { stream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BitmapRegionDecoder.newInstance(stream)
            } else {
                @Suppress("DEPRECATION")
                BitmapRegionDecoder.newInstance(stream, false)
            }
        } ?: return null

        val result = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint  = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true; isDither = true
        }

        // Tile across source in blocks of ~1024px to keep per-tile memory small
        val tileSize = 1024
        val cols = ceil(rawW.toDouble() / tileSize).toInt()
        val rows = ceil(rawH.toDouble() / tileSize).toInt()

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val srcX1 = col * tileSize
                val srcY1 = row * tileSize
                val srcX2 = min(srcX1 + tileSize, rawW)
                val srcY2 = min(srcY1 + tileSize, rawH)

                val tileBmp = try {
                    decoder.decodeRegion(Rect(srcX1, srcY1, srcX2, srcY2), opts)
                } catch (e: Exception) { null } ?: continue

                // Map source tile rect → destination rect
                val dstX1 = (srcX1.toLong() * targetW / rawW).toInt()
                val dstY1 = (srcY1.toLong() * targetH / rawH).toInt()
                val dstX2 = (srcX2.toLong() * targetW / rawW).toInt().coerceAtMost(targetW)
                val dstY2 = (srcY2.toLong() * targetH / rawH).toInt().coerceAtMost(targetH)

                val dstW = (dstX2 - dstX1).coerceAtLeast(1)
                val dstH = (dstY2 - dstY1).coerceAtLeast(1)

                val scaledTile = Bitmap.createScaledBitmap(tileBmp, dstW, dstH, true)
                canvas.drawBitmap(scaledTile, dstX1.toFloat(), dstY1.toFloat(), paint)
                if (scaledTile !== tileBmp) scaledTile.recycle()
                tileBmp.recycle()
            }
        }
        decoder.recycle()
        return result
    }

    // ── Progressive halving for maximum sharpness ─────────────────────────────

    /**
     * Repeatedly scale the bitmap by 0.5× with bilinear filtering until it fits
     * within [heapBudget] bytes. Each halving step is dramatically sharper than
     * a single large-ratio scale, because bilinear filtering works best on 2:1.
     */
    private fun progressiveScaleDown(
        src: Bitmap,
        heapBudget: Long,
        bytesPerPixel: Long
    ): Bitmap {
        var cur = src
        while (cur.width.toLong() * cur.height * bytesPerPixel > heapBudget) {
            val nW = max(1, cur.width  / 2)
            val nH = max(1, cur.height / 2)
            val next = Bitmap.createBitmap(nW, nH, Bitmap.Config.ARGB_8888)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
                isFilterBitmap = true; isDither = true
            }
            Canvas(next).drawBitmap(
                cur,
                Matrix().also { it.setScale(nW.toFloat() / cur.width, nH.toFloat() / cur.height) },
                paint
            )
            if (cur !== src) cur.recycle()
            cur = next
            // Prevent infinite loop if already at 1×1
            if (nW == 1 && nH == 1) break
        }
        return cur
    }

    // ── OOM last-resort ───────────────────────────────────────────────────────

    private fun loadFallback(context: Context, uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize      = 8
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable         = true
            }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: Exception) {
            null
        }
    }

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Export bitmap to Uri.
     * JPEG has no alpha — pixels are flattened onto white before encoding.
     */
    suspend fun exportBitmap(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        destUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val toEncode = if (format == Bitmap.CompressFormat.JPEG && bitmap.hasAlpha()) {
                val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                Canvas(flat).apply {
                    drawARGB(255, 255, 255, 255)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                flat
            } else bitmap

            context.contentResolver.openOutputStream(destUri)?.use { out ->
                toEncode.compress(format, quality.coerceIn(0, 100), out)
            }
            if (toEncode !== bitmap) toEncode.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
       * Export bitmap directly to Pictures/VasiliasTyper without SAF file-picker dialog.
       *   • Android Q+ (API 29+): MediaStore — no WRITE_EXTERNAL_STORAGE needed.
       *   • Older: File API in public Pictures folder.
       */
      suspend fun exportBitmapDirect(
          context:  Context,
          bitmap:   Bitmap,
          format:   Bitmap.CompressFormat,
          quality:  Int,
          filename: String
      ): Boolean = withContext(Dispatchers.IO) {
          try {
              // Sanitasi defense-in-depth: pemanggil sudah sanitasi, tapi tolak
              // path traversal / nama ekstrem bila ada jalur lain di masa depan.
              val safeName = filename.substringAfterLast('/').substringAfterLast('\\')
                  .replace(Regex("[^\\w\\-. ]"), "_").trim().take(128)
                  .ifBlank { "export" }
              val ext = when {
                  "JPEG" in format.name -> "jpg"
                  "WEBP" in format.name -> "webp"
                  else -> "png"
              }
              val finalName = if (safeName.substringAfterLast('.', "") in listOf("jpg", "jpeg", "png", "webp")) safeName else "$safeName.$ext"
              val toEncode = if (format == Bitmap.CompressFormat.JPEG && bitmap.hasAlpha()) {
                  val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                  Canvas(flat).apply { drawARGB(255, 255, 255, 255); drawBitmap(bitmap, 0f, 0f, null) }
                  flat
              } else bitmap

              val mimeType = when {
                  "JPEG" in format.name -> "image/jpeg"
                  "WEBP" in format.name -> "image/webp"
                  else                  -> "image/png"
              }

              if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                  val values = ContentValues().apply {
                      put(MediaStore.Images.Media.DISPLAY_NAME, finalName)
                      put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                      put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/VasiliasTyper")
                      put(MediaStore.Images.Media.IS_PENDING, 1)
                  }
                  val uri = context.contentResolver.insert(
                      MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                  ) ?: return@withContext false
                  context.contentResolver.openOutputStream(uri)?.use { out ->
                      toEncode.compress(format, quality.coerceIn(0, 100), out)
                  }
                  values.clear()
                  values.put(MediaStore.Images.Media.IS_PENDING, 0)
                  context.contentResolver.update(uri, values, null, null)
              } else {
                  val dir = java.io.File(
                      Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                      "VasiliasTyper"
                  )
                  dir.mkdirs()
                  val file = java.io.File(dir, finalName)
                  java.io.FileOutputStream(file).use { out ->
                      toEncode.compress(format, quality.coerceIn(0, 100), out)
                  }
                  MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
              }
              if (toEncode !== bitmap) toEncode.recycle()
              true
          } catch (e: Exception) {
              e.printStackTrace()
              false
          }
      }

    fun formatFromExtension(ext: String): Bitmap.CompressFormat = when (ext.lowercase()) {
        "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
        "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        else -> Bitmap.CompressFormat.PNG
    }
}
