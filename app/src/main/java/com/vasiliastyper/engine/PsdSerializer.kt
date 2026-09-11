package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import com.vasiliastyper.model.Layer
import com.vasiliastyper.model.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.InflaterInputStream

/**
 * v5.0 — Adobe Photoshop (.PSD) reader / writer.
 *
 * Format reference: "Adobe Photoshop File Formats Specification" (Sept 2024).
 * https://www.adobe.com/devnet-apps/photoshop/fileformatashtml/
 *
 * Implementation scope:
 *  • WRITER  — multi-layer RGBA, 8 bit/channel, uncompressed (compression = 0).
 *              Photoshop, GIMP, Krita, Affinity Photo and Clip Studio all accept
 *              this profile. Output: 4-channel composite + N raster layers.
 *  • READER  — common Photoshop PSD v1 files: 8-bit RGB, grayscale, and CMYK;
 *              raw (0), PackBits RLE (1), ZIP (2), and ZIP prediction (3).
 *              Layer positions, names, visibility, opacity, alpha, and common
 *              blend modes are preserved as editable raster layers.
 *
 * What does NOT survive a round-trip through PSD:
 *  • Vector text — exported as rasterized pixels (text is flattened into its
 *    own layer for portability with non-Adobe apps).
 *  • Blend modes other than the standard SRC_OVER / NORMAL.
 *
 * Layer-name encoding follows the Photoshop "Unicode layer name" additional
 * info block (key "luni"), so non-ASCII names round-trip cleanly.
 */
object PsdSerializer {

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Write [ws] to [destUri] as a PSD file. Each [Layer] in the workspace is
     * exported as its own raster layer; the composite is the flattened result
     * via [LayerCompositor].
     *
     * Caller is responsible for choosing [destUri] (typically via
     * `ActivityResultContracts.CreateDocument("image/vnd.adobe.photoshop")`).
     */
    suspend fun export(
        context: Context,
        ws: Workspace,
        destUri: Uri
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val composite = LayerCompositor.composite(ws.layers, ws.width, ws.height)
            try {
                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    write(out, ws.width, ws.height, ws.layers, composite)
                } ?: return@withContext false
                true
            } finally {
                composite.recycle()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /** Result of a successful PSD import. */
    data class ImportedPsd(
        val workspace: Workspace,
        val width: Int,
        val height: Int
    )

    /**
     * Read [srcUri] as a PSD file. Each PSD layer becomes a [Layer] in the
     * returned [Workspace]. Returns null on any parse/IO failure.
     */
    suspend fun import(
        context: Context,
        srcUri: Uri,
        suggestedName: String = "Imported.psd"
    ): ImportedPsd? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(srcUri)?.use { input ->
                read(input, suggestedName)
            }
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // WRITER
    // ══════════════════════════════════════════════════════════════════════════

    private fun write(
        out: OutputStream,
        width: Int,
        height: Int,
        layers: List<Layer>,
        composite: Bitmap
    ) {
        val dos = DataOutputStream(out)

        // ── File Header (26 bytes) ───────────────────────────────────────────
        dos.writeBytes("8BPS")           // signature
        dos.writeShort(1)                // version 1
        dos.write(ByteArray(6))          // reserved
        dos.writeShort(4)                // channels (R,G,B,A composite)
        dos.writeInt(height)             // height
        dos.writeInt(width)              // width
        dos.writeShort(8)                // depth (8 bpc)
        dos.writeShort(3)                // color mode: 3 = RGB

        // ── Color Mode Data Block ────────────────────────────────────────────
        dos.writeInt(0)                  // empty for RGB

        // ── Image Resources Block ────────────────────────────────────────────
        val resources = buildImageResources(width, height)
        dos.writeInt(resources.size)
        dos.write(resources)

        // ── Layer & Mask Information Block ───────────────────────────────────
        val layerMaskInfo = buildLayerMaskInfo(width, height, layers)
        dos.writeInt(layerMaskInfo.size)
        dos.write(layerMaskInfo)

        // ── Image Data Section — composite, planar RGBA, uncompressed ────────
        dos.writeShort(0)                // compression: 0 = raw
        writePlanarComposite(dos, composite)

        dos.flush()
    }

    /** Image-Resources block content (not including the leading length int). */
    private fun buildImageResources(width: Int, height: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        val d    = DataOutputStream(baos)

        // Resource ID 0x03ED — Resolution Info (16 bytes payload)
        d.writeBytes("8BIM"); d.writeShort(0x03ED)
        d.writeShort(0)                     // empty Pascal name
        d.writeByte(0)                      // pad to even
        d.writeInt(16)                      // payload size
        d.writeInt(72 * 65536)              // h-res: 72dpi (fixed-point 16.16)
        d.writeShort(1); d.writeShort(1)
        d.writeInt(72 * 65536)              // v-res
        d.writeShort(1); d.writeShort(1)

        return baos.toByteArray()
    }

    /** Build the Layer & Mask Information block body (not the leading length). */
    private fun buildLayerMaskInfo(width: Int, height: Int, layers: List<Layer>): ByteArray {
        if (layers.isEmpty()) {
            // Photoshop still expects 4 zero bytes for "Global Layer Mask Info length".
            val empty = ByteArrayOutputStream()
            val d     = DataOutputStream(empty)
            d.writeInt(0)   // layer-info length = 0  → no layers
            d.writeInt(0)   // global layer mask info length = 0
            return empty.toByteArray()
        }

        // 1) Build per-layer planar channel data first (so we know sizes).
        val perLayerChannels = layers.map { extractPlanarChannels(it.bitmap, width, height) }

        // 2) Build "Layer Info" body.
        val layerInfoBody = ByteArrayOutputStream()
        val li             = DataOutputStream(layerInfoBody)
        li.writeShort(layers.size)        // layer count (positive = first alpha is NOT the doc alpha)

        // 2a) layer records
        for ((i, layer) in layers.withIndex()) {
            val channelSizes = perLayerChannels[i].map { 2 + it.size /* 2 bytes for compression marker + data */ }

            li.writeInt(0)                // top
            li.writeInt(0)                // left
            li.writeInt(height)           // bottom
            li.writeInt(width)            // right
            li.writeShort(4)              // 4 channels (R,G,B,A)
            // channel info: id (-1=alpha, 0=R, 1=G, 2=B), length (4 bytes including compression word)
            li.writeShort(0); li.writeInt(channelSizes[0])   // R
            li.writeShort(1); li.writeInt(channelSizes[1])   // G
            li.writeShort(2); li.writeInt(channelSizes[2])   // B
            li.writeShort(-1); li.writeInt(channelSizes[3])  // alpha
            li.writeBytes("8BIM")
            li.writeBytes("norm")         // blend mode (Normal)
            li.writeByte(((layer.opacity / 100f) * 255f).toInt().coerceIn(0, 255))
            li.writeByte(if (layer.isClippingMask) 1 else 0) // clipping: 0 base, 1 clipped
            li.writeByte(if (layer.isVisible) 0 else 2)  // flags: bit1=visible(inverted)
            li.writeByte(0)               // filler

            // Extra data field
            val extra = ByteArrayOutputStream()
            val ex    = DataOutputStream(extra)
            ex.writeInt(0)                // layer mask data: empty
            ex.writeInt(0)                // layer blending ranges: empty
            // Pascal-format ASCII layer name (legacy)
            val asciiName  = layer.name.replace(Regex("[^\\x20-\\x7E]"), "?").take(254)
            val asciiBytes = asciiName.toByteArray(Charsets.US_ASCII)
            ex.writeByte(asciiBytes.size)
            ex.write(asciiBytes)
            // pad to multiple of 4 (length-byte + data + pad)
            val asciiBlockLen = 1 + asciiBytes.size
            val asciiPad = (4 - asciiBlockLen % 4) % 4
            ex.write(ByteArray(asciiPad))
            // Additional info: Unicode layer name ("luni")
            val luniData = unicodeLayerNameBlock(layer.name)
            ex.writeBytes("8BIM")
            ex.writeBytes("luni")
            ex.writeInt(luniData.size)
            ex.write(luniData)
            if (luniData.size % 2 != 0) ex.write(0)   // pad to even

            val extraBytes = extra.toByteArray()
            li.writeInt(extraBytes.size)
            li.write(extraBytes)
        }

        // 2b) layer channel image data
        for (channels in perLayerChannels) {
            for (ch in channels) {
                li.writeShort(0)          // compression: 0 = raw
                li.write(ch)
            }
        }

        var layerInfoBytes = layerInfoBody.toByteArray()
        if (layerInfoBytes.size % 2 != 0) {
            // Pad layer-info body to even length per spec.
            layerInfoBytes = layerInfoBytes + byteArrayOf(0)
        }

        // 3) Wrap in the outer "Layer & Mask Info" block.
        val outer = ByteArrayOutputStream()
        val od    = DataOutputStream(outer)
        od.writeInt(layerInfoBytes.size)   // layer-info length
        od.write(layerInfoBytes)
        od.writeInt(0)                     // global layer mask info length = 0
        return outer.toByteArray()
    }

    /**
     * Photoshop "luni" additional info block payload (without the outer
     * 8BIM/key/length framing).
     */
    private fun unicodeLayerNameBlock(name: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val d    = DataOutputStream(baos)
        d.writeInt(name.length)            // char count
        for (c in name) d.writeShort(c.code) // UTF-16 BE
        return baos.toByteArray()
    }

    /** Returns 4 arrays: R, G, B, A — each `width*height` bytes. */
    private fun extractPlanarChannels(src: Bitmap, width: Int, height: Int): List<ByteArray> {
        // Ensure dimensions match. If not, paint into a canvas of the doc size.
        val bm: Bitmap = if (src.width == width && src.height == height) src
                        else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            Canvas(it).drawBitmap(src, 0f, 0f, null)
                        }
        val pixels = IntArray(width * height)
        bm.getPixels(pixels, 0, width, 0, 0, width, height)
        val r = ByteArray(pixels.size)
        val g = ByteArray(pixels.size)
        val b = ByteArray(pixels.size)
        val a = ByteArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            a[i] = ((p ushr 24) and 0xFF).toByte()
            r[i] = ((p ushr 16) and 0xFF).toByte()
            g[i] = ((p ushr  8) and 0xFF).toByte()
            b[i] = ( p          and 0xFF).toByte()
        }
        if (bm !== src) bm.recycle()
        return listOf(r, g, b, a)
    }

    /** Write the composite section (R, G, B, A planes) uncompressed. */
    private fun writePlanarComposite(dos: DataOutputStream, composite: Bitmap) {
        val w = composite.width; val h = composite.height
        val pixels = IntArray(w * h)
        composite.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = ByteArray(pixels.size)
        // R
        for (i in pixels.indices) tmp[i] = ((pixels[i] ushr 16) and 0xFF).toByte()
        dos.write(tmp)
        // G
        for (i in pixels.indices) tmp[i] = ((pixels[i] ushr  8) and 0xFF).toByte()
        dos.write(tmp)
        // B
        for (i in pixels.indices) tmp[i] = ( pixels[i]          and 0xFF).toByte()
        dos.write(tmp)
        // A
        for (i in pixels.indices) tmp[i] = ((pixels[i] ushr 24) and 0xFF).toByte()
        dos.write(tmp)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // READER
    // ══════════════════════════════════════════════════════════════════════════

    private fun read(input: InputStream, name: String): ImportedPsd? {
        val dis = DataInputStream(input.buffered())

        // ── Header ───────────────────────────────────────────────────────────
        val sig = ByteArray(4); dis.readFully(sig)
        if (String(sig) != "8BPS") return null
        val version = dis.readUnsignedShort()
        if (version != 1) return null         // PSB (version 2) not supported here
        dis.skipFully(6)
        val channels = dis.readUnsignedShort()
        val height   = dis.readInt()
        val width    = dis.readInt()
        val depth    = dis.readUnsignedShort()
        val colorMode = dis.readUnsignedShort()
        if (width <= 0 || height <= 0 || width > 30_000 || height > 30_000) return null
        if (width.toLong() * height.toLong() > 120_000_000L) return null
        if (depth != 8 || colorMode !in setOf(1, 3, 4)) {
            // This raster workspace currently supports 8-bpc Grayscale, RGB, and CMYK.
            return null
        }

        // ── Color Mode Data ──────────────────────────────────────────────────
        val cmdLen = dis.readInt()
        dis.skipFully(cmdLen.toLong())

        // ── Image Resources ──────────────────────────────────────────────────
        val resLen = dis.readInt()
        dis.skipFully(resLen.toLong())

        // ── Layer & Mask Info ────────────────────────────────────────────────
        val layerMaskLen = dis.readInt()
        val ws = Workspace(
            id     = UUID.randomUUID().toString(),
            name   = name.substringBeforeLast('.', name),
            width  = width,
            height = height
        )
        if (layerMaskLen > 0) {
            val layerMaskBytes = ByteArray(layerMaskLen)
            dis.readFully(layerMaskBytes)
            parseLayerMaskInfo(layerMaskBytes, width, height, colorMode)?.let { layers ->
                ws.layers.clear()
                ws.layers.addAll(layers)
            }
        }

        // ── Composite Image Data (we only need it as a fallback) ─────────────
        // If parsing layers failed or there were 0 layers, decode the composite
        // and use it as a single Background layer.
        if (ws.layers.isEmpty()) {
            val compositeBitmap = readComposite(dis, width, height, channels, colorMode)
            if (compositeBitmap != null) {
                ws.layers.add(Layer(name = "Background", bitmap = compositeBitmap))
            } else {
                ws.layers.add(Layer(
                    name   = "Background",
                    bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                ))
            }
        }
        ws.activeLayerIndex = ws.layers.size - 1

        return ImportedPsd(ws, width, height)
    }

    private fun parseLayerMaskInfo(buf: ByteArray, docW: Int, docH: Int, colorMode: Int): List<Layer>? {
        return try {
            val dis = DataInputStream(buf.inputStream())
            val layerInfoLen = dis.readInt()
            if (layerInfoLen <= 0) return null
            val layerInfo = ByteArray(layerInfoLen)
            dis.readFully(layerInfo)
            parseLayerInfo(layerInfo, docW, docH, colorMode)
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    private fun parseLayerInfo(buf: ByteArray, docW: Int, docH: Int, colorMode: Int): List<Layer>? {
        val dis = DataInputStream(buf.inputStream())
        val layerCountRaw = dis.readShort().toInt()
        val layerCount    = kotlin.math.abs(layerCountRaw)
        if (layerCount == 0) return null

        data class LayerRec(
            val top: Int, val left: Int, val bottom: Int, val right: Int,
            val channels: List<Pair<Int, Int>>,   // (id, length)
            val opacity: Int, val visible: Boolean,
            val clipping: Boolean,
            val blendKey: String,
            var name: String
        )

        val recs = ArrayList<LayerRec>(layerCount)
        for (i in 0 until layerCount) {
            val top    = dis.readInt()
            val left   = dis.readInt()
            val bottom = dis.readInt()
            val right  = dis.readInt()
            val numCh  = dis.readUnsignedShort()
            val chs    = ArrayList<Pair<Int, Int>>(numCh)
            for (c in 0 until numCh) {
                val cid = dis.readShort().toInt()
                val cln = dis.readInt()
                chs.add(cid to cln)
            }
            val blendSignature = ByteArray(4); dis.readFully(blendSignature)
            val blendKeyBytes = ByteArray(4); dis.readFully(blendKeyBytes)
            val blendKey = String(blendKeyBytes, Charsets.US_ASCII)
            val opacity = dis.readUnsignedByte()
            val clipping = dis.readUnsignedByte() != 0
            val flags = dis.readUnsignedByte()
            val visible = (flags and 0x02) == 0
            dis.skipFully(1)   // filler

            val extraLen = dis.readInt()
            val extra = ByteArray(extraLen)
            dis.readFully(extra)

            // Parse extra: layer mask data (skip), blending ranges (skip),
            // legacy ASCII name (Pascal), then additional info blocks (look for "luni").
            var name = "Layer"
            try {
                val ex = DataInputStream(extra.inputStream())
                val maskLen = ex.readInt()
                ex.skipFully(maskLen.toLong())
                val blendLen = ex.readInt()
                ex.skipFully(blendLen.toLong())
                val nameLen = ex.readUnsignedByte()
                val asciiNameBytes = ByteArray(nameLen)
                ex.readFully(asciiNameBytes)
                val asciiBlockLen = 1 + nameLen
                val asciiPad = (4 - asciiBlockLen % 4) % 4
                ex.skipFully(asciiPad.toLong())
                name = String(asciiNameBytes, Charsets.US_ASCII)
                // Additional info blocks — scan for "luni"
                while (ex.available() >= 12) {
                    val sig = ByteArray(4); ex.readFully(sig)
                    val key = ByteArray(4); ex.readFully(key)
                    val len = ex.readInt()
                    val data = ByteArray(len)
                    if (ex.available() < len) break
                    ex.readFully(data)
                    if (len % 2 != 0 && ex.available() >= 1) ex.skipFully(1)
                    if (String(key) == "luni") {
                        val ld = DataInputStream(data.inputStream())
                        val chars = ld.readInt()
                        val sb = StringBuilder(chars)
                        for (k in 0 until chars) sb.append(ld.readShort().toInt().toChar())
                        name = sb.toString()
                    }
                }
            } catch (_: Exception) { /* keep ASCII fallback */ }

            recs.add(LayerRec(top, left, bottom, right, chs, opacity, visible, clipping, blendKey, name))
        }

        // Channel image data — read in declared order.
        val result = ArrayList<Layer>(recs.size)
        for (rec in recs) {
            val w = (rec.right - rec.left).coerceAtLeast(0)
            val h = (rec.bottom - rec.top).coerceAtLeast(0)
            val layerBmp = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888)

            // Holders for the planar channels
            val chData = HashMap<Int, ByteArray>()
            for ((cid, clen) in rec.channels) {
                val compRaw = dis.readUnsignedShort()
                val payload = ByteArray((clen - 2).coerceAtLeast(0))
                if (payload.isNotEmpty()) dis.readFully(payload)
                val decoded = when (compRaw) {
                    0 -> payload.copyOf(w * h)                           // raw
                    1 -> decodePackBits(payload, w, h)                   // PackBits RLE
                    2 -> decodeZip(payload, w, h, prediction = false)    // ZIP
                    3 -> decodeZip(payload, w, h, prediction = true)     // ZIP + prediction
                    else -> null
                } ?: continue
                chData[cid] = decoded
            }

            val hasPixels = w > 0 && h > 0 && chData[0] != null
            if (hasPixels) {
                val c0 = chData[0]
                val c1 = chData[1]
                val c2 = chData[2]
                val c3 = chData[3]
                val a = chData[-1]
                val pixels = IntArray(w * h)
                for (i in 0 until w * h) {
                    val v0 = (c0?.getOrNull(i)?.toInt()?.and(0xFF)) ?: 0
                    val ai = (a?.getOrNull(i)?.toInt()?.and(0xFF)) ?: 255
                    val (ri, gi, bi) = if (colorMode == 4) {
                        cmykToRgb(
                            v0,
                            (c1?.getOrNull(i)?.toInt()?.and(0xFF)) ?: 0,
                            (c2?.getOrNull(i)?.toInt()?.and(0xFF)) ?: 0,
                            (c3?.getOrNull(i)?.toInt()?.and(0xFF)) ?: 0
                        )
                    } else {
                        Triple(
                            v0,
                            (c1?.getOrNull(i)?.toInt()?.and(0xFF)) ?: v0,
                            (c2?.getOrNull(i)?.toInt()?.and(0xFF)) ?: v0
                        )
                    }
                    pixels[i] = (ai shl 24) or (ri shl 16) or (gi shl 8) or bi
                }
                val tile = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                tile.setPixels(pixels, 0, w, 0, 0, w, h)
                Canvas(layerBmp).drawBitmap(tile, rec.left.toFloat(), rec.top.toFloat(), null)
                tile.recycle()
            }
            if (hasPixels) {
                result.add(Layer(
                    name      = rec.name.ifBlank { "Layer" },
                    bitmap    = layerBmp,
                    opacity   = ((rec.opacity / 255f) * 100f).toInt().coerceIn(0, 100),
                    isVisible = rec.visible,
                    blendMode = blendModeForKey(rec.blendKey),
                    isClippingMask = rec.clipping
                ))
            } else {
                layerBmp.recycle()
            }
        }
        // PSD records are top-to-bottom; the app composites list entries bottom-to-top.
        return result.asReversed()
    }

    /**
     * Decode PSD-style PackBits (Photoshop variant): the data starts with
     * h*2 bytes of per-row byte counts (uncompressed), then the actual RLE
     * data. We skip the counts because we can just consume to the end.
     */
    private fun decodePackBits(payload: ByteArray, w: Int, h: Int): ByteArray? {
        if (w <= 0 || h <= 0) return null
        if (payload.size < 2 * h) return null
        val out = ByteArray(w * h)
        var srcPos = 2 * h        // skip per-row byte-counts
        var dstPos = 0
        try {
            while (srcPos < payload.size && dstPos < out.size) {
                val n = payload[srcPos].toInt(); srcPos++
                when {
                    n >= 0 -> {
                        val cnt = n + 1
                        if (srcPos + cnt > payload.size) return null
                        System.arraycopy(payload, srcPos, out, dstPos, cnt)
                        srcPos += cnt; dstPos += cnt
                    }
                    n != -128 -> {
                        val cnt = -n + 1
                        if (srcPos >= payload.size) return null
                        val v = payload[srcPos]; srcPos++
                        java.util.Arrays.fill(out, dstPos, dstPos + cnt, v)
                        dstPos += cnt
                    }
                    else -> {}    // -128 = no-op
                }
            }
        } catch (e: Exception) { return null }
        return out
    }

    private fun decodeZip(payload: ByteArray, w: Int, h: Int, prediction: Boolean): ByteArray? {
        if (w <= 0 || h <= 0) return null
        return try {
            val inflated = InflaterInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
            if (inflated.size < w * h) return null
            val out = inflated.copyOf(w * h)
            if (prediction) applyZipPrediction(out, w, h)
            out
        } catch (_: Exception) {
            null
        }
    }

    /** Photoshop ZIP prediction stores each byte as a delta from the byte to its left. */
    private fun applyZipPrediction(data: ByteArray, rowWidth: Int, rows: Int) {
        for (row in 0 until rows) {
            val start = row * rowWidth
            val end = (start + rowWidth).coerceAtMost(data.size)
            for (index in start + 1 until end) {
                data[index] = ((data[index].toInt() and 0xFF) +
                    (data[index - 1].toInt() and 0xFF)).toByte()
            }
        }
    }

    private fun cmykToRgb(c: Int, m: Int, y: Int, k: Int): Triple<Int, Int, Int> {
        // PSD stores CMYK channels inverted (255 = no ink). Multiplication combines K.
        val r = (c * k + 127) / 255
        val g = (m * k + 127) / 255
        val b = (y * k + 127) / 255
        return Triple(r, g, b)
    }

    private fun blendModeForKey(key: String): PorterDuff.Mode = when (key) {
        "mul " -> PorterDuff.Mode.MULTIPLY
        "scrn" -> PorterDuff.Mode.SCREEN
        "over" -> PorterDuff.Mode.OVERLAY
        "dark" -> PorterDuff.Mode.DARKEN
        "lite" -> PorterDuff.Mode.LIGHTEN
        "lddg", "add " -> PorterDuff.Mode.ADD
        else -> PorterDuff.Mode.SRC_OVER
    }

    /** Read the composite section (post-layer-mask block). */
    private fun readComposite(dis: DataInputStream, w: Int, h: Int, channels: Int, colorMode: Int): Bitmap? {
        return try {
            val compression = dis.readUnsignedShort()
            val planeSize   = w * h
            val planes = Array(channels) { ByteArray(planeSize) }
            when (compression) {
                0 -> {                              // raw
                    for (i in 0 until channels) dis.readFully(planes[i])
                }
                1 -> {                              // PackBits — all channels concatenated
                    val totalRows = h * channels
                    val rowLens = IntArray(totalRows) { dis.readUnsignedShort() }
                    val rleData = ByteArray(rowLens.sum())
                    dis.readFully(rleData)
                    var src = 0
                    for (c in 0 until channels) {
                        for (row in 0 until h) {
                            val rowLen = rowLens[c * h + row]
                            val rowEnd = src + rowLen
                            // Decode one row
                            var dst = c * 0       // unused
                            val outRow = ByteArray(w)
                            var p = src; var dstP = 0
                            while (p < rowEnd && dstP < w) {
                                val n = rleData[p].toInt(); p++
                                if (n >= 0) {
                                    val cnt = n + 1
                                    if (p + cnt > rowEnd) break
                                    System.arraycopy(rleData, p, outRow, dstP, cnt)
                                    p += cnt; dstP += cnt
                                } else if (n != -128) {
                                    val cnt = -n + 1
                                    val v = rleData[p]; p++
                                    java.util.Arrays.fill(outRow, dstP, (dstP + cnt).coerceAtMost(w), v)
                                    dstP += cnt
                                }
                            }
                            System.arraycopy(outRow, 0, planes[c], row * w, w)
                            src = rowEnd
                        }
                    }
                }
                2, 3 -> {                         // ZIP / ZIP with prediction
                    val inflated = InflaterInputStream(dis).use { it.readBytes() }
                    if (inflated.size < planeSize * channels) return null
                    for (channel in 0 until channels) {
                        val offset = channel * planeSize
                        System.arraycopy(inflated, offset, planes[channel], 0, planeSize)
                        if (compression == 3) applyZipPrediction(planes[channel], w, h)
                    }
                }
                else -> return null
            }
            val pixels = IntArray(planeSize)
            val baseChannels = when (colorMode) { 4 -> 4; 3 -> 3; else -> 1 }
            val hasAlpha = channels > baseChannels
            for (i in 0 until planeSize) {
                val v0 = planes[0][i].toInt() and 0xFF
                val (r, g, b) = if (colorMode == 4 && channels >= 4) {
                    cmykToRgb(
                        v0,
                        planes[1][i].toInt() and 0xFF,
                        planes[2][i].toInt() and 0xFF,
                        planes[3][i].toInt() and 0xFF
                    )
                } else {
                    Triple(
                        v0,
                        if (colorMode == 3 && channels >= 2) planes[1][i].toInt() and 0xFF else v0,
                        if (colorMode == 3 && channels >= 3) planes[2][i].toInt() and 0xFF else v0
                    )
                }
                val a = if (hasAlpha) planes[baseChannels][i].toInt() and 0xFF else 255
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bm.setPixels(pixels, 0, w, 0, 0, w, h)
            bm
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    // ── Stream helpers ────────────────────────────────────────────────────────

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = this.skip(left)
            if (s <= 0) {
                // Fall back to reading bytes
                val b = this.read()
                if (b < 0) return
                left -= 1
            } else left -= s
        }
    }
}
