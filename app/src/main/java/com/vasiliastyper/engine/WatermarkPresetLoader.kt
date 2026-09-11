package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.Locale

/** Loads the WatermarkRemover-style JSON preset supplied by the user. */
object WatermarkPresetLoader {

    data class Preset(
        val name: String,
        val bitmap: Bitmap,
        val anchor: String,
        val offsetX: Int,
        val offsetY: Int,
        val referenceCanvasWidth: Int?,
        val alphaAdjust: Float,
        val jpegFilterEnabled: Boolean,
        val jpegRadius: Int,
        val jpegThreshold: Int,
        /** Per-watermark floor for semi-transparent or full-frame overlays. */
        val minimumConfidence: Float,
        /** Known overlays can use their calibrated alpha without image-based tuning. */
        val autoTuneStrength: Boolean
    ) {
        fun recycle() {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private const val MAX_JSON_BYTES = 8 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    private const val MAX_PRESETS = 64

    /** Detects JSON by content as well as extension/MIME. Many Android providers hide the real name. */
    fun isLikelyPreset(context: Context, uri: Uri, displayName: String? = null): Boolean {
        val name = displayName.orEmpty().lowercase(Locale.ROOT)
        if (name.endsWith(".json") || name.endsWith(".json.txt") || name.endsWith(".txt")) return true
        val type = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
        if (type.contains("json") || type.startsWith("text/")) return true
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val prefix = ByteArray(256)
                val count = input.read(prefix)
                if (count <= 0) return@use false
                val text = String(prefix, 0, count, Charsets.UTF_8)
                    .removePrefix("\uFEFF")
                    .trimStart()
                text.startsWith("{") || text.startsWith("[")
            } ?: false
        }.getOrDefault(false)
    }

    fun load(context: Context, uri: Uri): List<Preset> =
        parse(readJson(context, uri))

    /** Loads the bundled preset without copying it to storage or requiring permission. */
    fun loadAsset(context: Context, assetPath: String): List<Preset> =
        context.assets.open(assetPath).use { input ->
            parse(readJson(input, "Preset bawaan"))
        }

    private fun parse(json: String): List<Preset> {
        var parsed: JsonElement = JsonParser.parseString(json.removePrefix("\uFEFF").trim())
        // Some exporters save the complete JSON document as one quoted JSON string.
        if (parsed.isJsonPrimitive && parsed.asJsonPrimitive.isString) {
            parsed = JsonParser.parseString(parsed.asString)
        }

        val repository = parsed.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.objectOrNull("watermarks")
            ?.takeUnless { objectValue -> looksLikePreset(objectValue) }
        val candidates = collectPresetObjects(parsed)
        val presets = mutableListOf<Preset>()
        var lastDecodeError: Throwable? = null

        try {
            candidates.take(MAX_PRESETS).forEach { item ->
                val encodedUrl = resolveImageData(item, repository)
                if (encodedUrl == null) return@forEach
                val bitmap = try {
                    decodeDataUrl(encodedUrl)
                } catch (error: Throwable) {
                    lastDecodeError = error
                    return@forEach
                }
                val jpeg = item.objectOrNull("jpegFilter") ?: item.objectOrNull("jpeg_filter")
                val name = item.stringFrom("name", "title", "label") ?: "Preset ${presets.size + 1}"
                val offset = item.objectOrNull("offset") ?: item.objectOrNull("position")
                val referenceWidth = item.intFrom(
                    "referenceCanvasWidth",
                    "reference_width",
                    "canvasWidth",
                    "baseWidth"
                ) ?: Regex("(?:^|[-_ ])(\\d{3,5})(?:$|[-_ ])")
                    .find(name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                presets += Preset(
                    name = name,
                    bitmap = bitmap,
                    anchor = item.stringFrom("anchor", "gravity", "placement") ?: "bottom-right",
                    offsetX = offset?.intFrom("x", "left", "offsetX")
                        ?: item.intFrom("offsetX", "x") ?: 0,
                    offsetY = offset?.intFrom("y", "top", "offsetY")
                        ?: item.intFrom("offsetY", "y") ?: 0,
                    referenceCanvasWidth = referenceWidth?.takeIf { it in 1..100_000 },
                    alphaAdjust = item.floatFrom("alphaAdjust", "alpha", "strength")
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(0.5f, 1.5f)
                        ?: 1f,
                    jpegFilterEnabled = jpeg?.booleanOrNull("enabled")
                        ?: item.booleanOrNull("jpegFilterEnabled") ?: true,
                    jpegRadius = (jpeg?.intFrom("radius", "filterRadius") ?: 3).coerceIn(0, 8),
                    jpegThreshold = (jpeg?.intFrom("threshold", "filterThreshold") ?: 4).coerceIn(0, 32),
                    minimumConfidence = item.floatFrom(
                        "minimumConfidence",
                        "minConfidence",
                        "detectionThreshold"
                    )
                        ?.takeIf { it.isFinite() }
                        ?.coerceIn(0.45f, 0.99f)
                        ?: 0.68f,
                    autoTuneStrength = item.booleanOrNull("autoTuneStrength")
                        ?: item.booleanOrNull("autoStrength")
                        ?: true
                )
            }
        } catch (error: Throwable) {
            presets.forEach(Preset::recycle)
            throw error
        }

        if (presets.isEmpty()) {
            throw IllegalArgumentException(
                lastDecodeError?.message ?: "Preset tidak berisi watermark base64 yang valid"
            )
        }
        return presets
    }

    private fun readJson(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { input ->
            readJson(input, "Preset")
        } ?: throw IllegalArgumentException("Preset tidak dapat dibaca")

    private fun readJson(input: java.io.InputStream, sourceName: String): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_JSON_BYTES) { "$sourceName terlalu besar (maksimum 8 MB)" }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun collectPresetObjects(root: JsonElement): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        fun add(element: JsonElement?) {
            when {
                element == null || element.isJsonNull -> Unit
                element.isJsonArray -> element.asJsonArray.forEach { child ->
                    if (child.isJsonObject) result += child.asJsonObject
                }
                element.isJsonObject -> {
                    val objectValue = element.asJsonObject
                    if (looksLikePreset(objectValue)) result += objectValue
                    objectValue.entrySet()
                        .asSequence()
                        .filter { (key, value) -> key.toIntOrNull() != null && value.isJsonObject }
                        .sortedBy { (key, _) -> key.toIntOrNull() ?: Int.MAX_VALUE }
                        .forEach { (_, value) -> result += value.asJsonObject }
                }
            }
        }
        if (root.isJsonArray) add(root)
        if (root.isJsonObject) {
            val objectRoot = root.asJsonObject
            listOf("preset", "presets", "items", "templates", "configurations").forEach { key -> add(objectRoot.get(key)) }
            val watermarks = objectRoot.get("watermarks")
            if (watermarks?.isJsonArray == true) add(watermarks)
            add(objectRoot)
            // Named watermark objects are also used by some exporters. Treat them
            // as presets only as a fallback, because the classic format uses this
            // object merely as an image repository for numeric root entries.
            if (result.isEmpty() && watermarks?.isJsonObject == true) {
                watermarks.asJsonObject.entrySet().forEach { (_, value) ->
                    if (value.isJsonObject && looksLikePreset(value.asJsonObject)) {
                        result += value.asJsonObject
                    }
                }
            }
        }
        return result.distinctBy { it.toString() }
    }

    private fun looksLikePreset(value: JsonObject): Boolean =
        listOf("img", "image", "sample", "url", "src", "dataUrl", "data").any(value::has)

    private fun resolveImageData(item: JsonObject, repository: JsonObject?): String? {
        fun validEncoded(value: String?): String? = value
            ?.trim()
            ?.takeIf { encoded ->
                encoded.startsWith("data:image/", true) ||
                    (encoded.length >= 32 && encoded.none { it == '{' || it == '}' })
            }
        fun direct(element: JsonElement?): String? = when {
            element == null || element.isJsonNull -> null
            element.isJsonPrimitive -> validEncoded(element.asString)
            element.isJsonObject -> validEncoded(
                element.asJsonObject.stringFrom("url", "src", "dataUrl", "data", "base64")
            )
            else -> null
        }
        for (key in listOf("img", "image", "sample", "url", "src", "dataUrl", "data")) {
            direct(item.get(key))?.let { return it }
        }
        val imageInfo = listOf("img", "image", "sample")
            .firstNotNullOfOrNull { key -> item.objectOrNull(key) }
        val hash = imageInfo?.stringFrom("hash", "id", "key")
            ?: item.stringFrom("hash", "imageHash")
        if (hash != null) {
            val stored = repository?.get(hash)
            direct(stored)?.let { return it }
        }
        return null
    }

    private fun decodeDataUrl(dataUrl: String): Bitmap {
        val comma = dataUrl.indexOf(',')
        val encoded = if (comma > 0) {
            val metadata = dataUrl.substring(0, comma)
            require(metadata.startsWith("data:image/", true) && metadata.contains(";base64", true)) {
                "Format gambar preset harus data:image base64"
            }
            dataUrl.substring(comma + 1)
        } else {
            // Accept raw base64 fields used by a few preset exporters.
            dataUrl
        }
        require(encoded.length <= MAX_IMAGE_BYTES * 2) { "Data gambar watermark terlalu besar" }
        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Base64 gambar watermark tidak valid")
        }
        require(bytes.size in 1..MAX_IMAGE_BYTES) { "Gambar watermark preset terlalu besar" }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth in 1..4096 && bounds.outHeight in 1..4096) {
            "Dimensi gambar watermark preset tidak valid"
        }
        return BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
        ) ?: throw IllegalArgumentException("Gambar watermark preset gagal didekode")
    }

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString

    private fun JsonObject.stringFrom(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> stringOrNull(key) }

    private fun JsonObject.intOrNull(key: String): Int? =
        stringOrNull(key)?.toDoubleOrNull()?.toInt()

    private fun JsonObject.intFrom(vararg keys: String): Int? =
        keys.firstNotNullOfOrNull { key -> intOrNull(key) }

    private fun JsonObject.floatOrNull(key: String): Float? =
        stringOrNull(key)?.toFloatOrNull()

    private fun JsonObject.floatFrom(vararg keys: String): Float? =
        keys.firstNotNullOfOrNull { key -> floatOrNull(key) }

    private fun JsonObject.booleanOrNull(key: String): Boolean? =
        stringOrNull(key)?.let { value ->
            when (value.lowercase()) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
        }
}
