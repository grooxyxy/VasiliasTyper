package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.Base64
import com.vasiliastyper.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * VasiliasTyper v2.0 — WorkspaceSerializer
 *
 * Handles full project save/load:
 *  • Layer bitmaps          → Base64-encoded PNG
 *  • TextElement list       → JSON (all fields; typeface reconstructed from fontName)
 *  • ImageElement list      → JSON (bitmap as Base64 PNG)
 *
 * Auto-save:
 *  • [autoSave]  — writes to  filesDir/autosave/proj_{projectId}.json
 *  • [tryRestore] — reads the same file and returns (textElements, imageElements)
 *    so the caller can restore them to the canvas.
 *
 * The file-based approach avoids SharedPreferences size limits and keeps
 * layer bitmaps off the main thread when called from a coroutine.
 */
object WorkspaceSerializer {

    private const val AUTOSAVE_DIR = "autosave"

    // ── Public auto-save API ──────────────────────────────────────────────────

    /**
     * Save the complete project state (workspace layers + text elements + image elements)
     * to internal storage.  Call from an IO coroutine — may be slow for large bitmaps.
     */
    fun autoSave(
        context:       Context,
        ws:            com.vasiliastyper.model.Workspace,
        textElements:  List<TextElement>,
        imageElements: List<ImageElement>,
        projectId:     String
    ) {
        try {
            val root = JSONObject()
            root.put("workspace",     serialize(ws))
            root.put("textElements",  serializeTextElements(textElements))
            root.put("imageElements", serializeImageElements(imageElements))
            root.put("savedMs",       System.currentTimeMillis())

            val dir  = File(context.filesDir, AUTOSAVE_DIR).also { it.mkdirs() }
            val file = File(dir, "proj_$projectId.json")
            file.writeText(root.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Try to restore text and image elements for a project.
     * Returns `null` if no auto-save file exists or it cannot be parsed.
     * Layer bitmaps from the workspace are NOT restored here — the caller
     * is responsible for loading the workspace through [deserialize] separately
     * if needed, or simply restoring only the element overlays.
     */
    fun tryRestore(
        context:   Context,
        projectId: String
    ): RestoredElements? {
        return try {
            val file = File(context.filesDir, "$AUTOSAVE_DIR/proj_$projectId.json")
            if (!file.exists()) return null
            val root  = JSONObject(file.readText())
            RestoredElements(
                textElements  = deserializeTextElements(root.optJSONArray("textElements")),
                imageElements = deserializeImageElements(root.optJSONArray("imageElements"))
            )
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    /** Also restores workspace layers — heavier; use when reopening a project cold. */
    fun tryRestoreFull(context: Context, projectId: String): FullRestore? {
        return try {
            val file = File(context.filesDir, "$AUTOSAVE_DIR/proj_$projectId.json")
            if (!file.exists()) return null
            val root = JSONObject(file.readText())
            val wsJson = root.optJSONObject("workspace") ?: return null
            FullRestore(
                workspace     = deserialize(wsJson, context),
                textElements  = deserializeTextElements(root.optJSONArray("textElements")),
                imageElements = deserializeImageElements(root.optJSONArray("imageElements"))
            )
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    fun deleteAutoSave(context: Context, projectId: String) {
        try {
            File(context.filesDir, "$AUTOSAVE_DIR/proj_$projectId.json").delete()
        } catch (_: Exception) {}
    }

    data class RestoredElements(
        val textElements:  List<TextElement>,
        val imageElements: List<ImageElement>
    )

    data class FullRestore(
        val workspace:     com.vasiliastyper.model.Workspace,
        val textElements:  List<TextElement>,
        val imageElements: List<ImageElement>
    )

    // ── Workspace serialize / deserialize ─────────────────────────────────────

    fun serialize(ws: com.vasiliastyper.model.Workspace): JSONObject {
        val root = JSONObject()
        root.put("id",               ws.id)
        root.put("name",             ws.name)
        root.put("width",            ws.width)
        root.put("height",           ws.height)
        root.put("activeLayerIndex", ws.activeLayerIndex)
        ws.filePath?.let { root.put("filePath", it) }

        val layersArr = JSONArray()
        for (layer in ws.layers) layersArr.put(serializeLayer(layer))
        root.put("layers", layersArr)
        val foldersArr = JSONArray()
        ws.layerFolders.forEach { folder ->
            foldersArr.put(JSONObject().apply {
                put("id", folder.id)
                put("name", folder.name)
                put("isVisible", folder.isVisible)
                put("isExpanded", folder.isExpanded)
            })
        }
        root.put("layerFolders", foldersArr)
        return root
    }

    fun deserialize(json: JSONObject, @Suppress("UNUSED_PARAMETER") context: Context): com.vasiliastyper.model.Workspace {
        val ws = com.vasiliastyper.model.Workspace(
            id     = json.optString("id",   UUID.randomUUID().toString()),
            name   = json.optString("name", "Untitled"),
            width  = json.optInt("width",   800),
            height = json.optInt("height",  1200)
        )
        ws.activeLayerIndex = json.optInt("activeLayerIndex", 0)
        if (json.has("filePath")) ws.filePath = json.getString("filePath")

        json.optJSONArray("layerFolders")?.let { folders ->
            for (i in 0 until folders.length()) {
                val folder = folders.optJSONObject(i) ?: continue
                ws.layerFolders.add(LayerFolder(
                    id = folder.optString("id", UUID.randomUUID().toString()),
                    name = folder.optString("name", "Folder"),
                    isVisible = folder.optBoolean("isVisible", true),
                    isExpanded = folder.optBoolean("isExpanded", true)
                ))
            }
        }
        val layersArr = json.optJSONArray("layers") ?: JSONArray()
        for (i in 0 until layersArr.length()) {
            deserializeLayer(layersArr.getJSONObject(i))?.let { ws.layers.add(it) }
        }
        if (ws.layers.isEmpty()) {
            ws.layers.add(Layer(
                name   = "Background",
                bitmap = Bitmap.createBitmap(ws.width, ws.height, Bitmap.Config.ARGB_8888)
            ))
        }
        ws.syncFolderVisibility()
        ws.activeLayerIndex = ws.activeLayerIndex.coerceIn(0, ws.layers.lastIndex)
        return ws
    }

    private fun serializeLayer(layer: Layer): JSONObject {
        val obj = JSONObject()
        obj.put("id",        layer.id)
        obj.put("name",      layer.name)
        obj.put("isVisible", layer.isVisible)
        obj.put("isLocked",  layer.isLocked)
        obj.put("opacity",   layer.opacity)
        obj.put("blendMode", layer.blendMode.name)
        obj.put("isClippingMask", layer.isClippingMask)
        obj.put("isAlphaMaskEnabled", layer.isAlphaMaskEnabled)
        layer.folderId?.let { obj.put("folderId", it) }
        layer.alphaMask?.takeUnless { it.isRecycled }?.let {
            obj.put("alphaMask", bitmapToBase64(it))
        }
        obj.put("bitmap",    bitmapToBase64(layer.bitmap))
        return obj
    }

    private fun deserializeLayer(obj: JSONObject): Layer? {
        return try {
            val bm = base64ToBitmap(obj.getString("bitmap")) ?: return null
            Layer(
                id        = obj.optString("id",   UUID.randomUUID().toString()),
                name      = obj.optString("name", "Layer"),
                bitmap    = bm,
                isVisible = obj.optBoolean("isVisible", true),
                isLocked  = obj.optBoolean("isLocked",  false),
                opacity   = obj.optInt("opacity",   100),
                blendMode = blendModeByName(obj.optString("blendMode", "SRC_OVER")),
                isClippingMask = obj.optBoolean("isClippingMask", false),
                alphaMask = obj.optString("alphaMask", "")
                    .takeIf { it.isNotEmpty() }
                    ?.let(::base64ToBitmap),
                isAlphaMaskEnabled = obj.optBoolean("isAlphaMaskEnabled", false),
                folderId = obj.optString("folderId", "").ifEmpty { null }
            )
        } catch (e: Exception) { null }
    }

    // ── TextElement serialize / deserialize ───────────────────────────────────

    fun serializeTextElements(elements: List<TextElement>): JSONArray {
        val arr = JSONArray()
        for (el in elements) arr.put(serializeTextElement(el))
        return arr
    }

    private fun serializeTextElement(el: TextElement): JSONObject = JSONObject().apply {
        put("id",                 el.id)
        put("name",               el.name)
        put("isVisible",          el.isVisible)
        put("isLocked",           el.isLocked)
        put("blendMode",          el.blendMode)
        put("text",               el.text)
        put("x",                  el.x.toDouble())
        put("y",                  el.y.toDouble())
        put("width",              el.width.toDouble())
        put("height",             el.height.toDouble())
        put("fontSize",           el.fontSize.toDouble())
        put("fontName",           el.fontName)
        put("color",              el.color)
        put("isBold",             el.isBold)
        put("isItalic",           el.isItalic)
        put("effect",             el.effect.name)
        put("outlineColor",       el.outlineColor)
        put("outlineWidth",       el.outlineWidth.toDouble())
        put("outlineOpacity",     el.outlineOpacity)
        put("shadowDx",           el.shadowDx.toDouble())
        put("shadowDy",           el.shadowDy.toDouble())
        put("shadowRadius",       el.shadowRadius.toDouble())
        put("shadowSpread",       el.shadowSpread.toDouble())
        put("shadowColor",        el.shadowColor)
        put("shadowOpacity",      el.shadowOpacity)
        put("gradientStartColor", el.gradientStartColor)
        put("gradientEndColor",   el.gradientEndColor)
        put("gradientColors", JSONArray().apply {
            el.gradientColors.forEach { put(it) }
        })
        put("gradientAngle", el.gradientAngle.toDouble())
        put("align",              el.align.name)
        put("rotation",           el.rotation.toDouble())
        el.layerId?.let { put("layerId", it) }
        // v5.0
        el.textureUri?.let { put("textureUri", it) }
        el.perspCorners?.let { c ->
            val arr = JSONArray()
            c.forEach { arr.put(it.toDouble()) }
            put("perspCorners", arr)
        }
        el.meshPoints?.let { pts ->
            val arr = JSONArray()
            pts.forEach { arr.put(it.toDouble()) }
            put("meshPoints", arr)
            put("meshCols", el.meshCols)
            put("meshRows", el.meshRows)
        }
        el.spans?.let { spans ->
            if (spans.isNotEmpty()) {
                val arr = JSONArray()
                for (sp in spans) arr.put(serializeTextSpan(sp))
                put("spans", arr)
            }
        }
        // v5.2
        put("opacity",           el.opacity)
        put("blurType",          el.blurType)
        put("blurRadius",        el.blurRadius.toDouble())
        put("blurMotionAngle",   el.blurMotionAngle.toDouble())
        put("blurMotionDistance",el.blurMotionDistance.toDouble())
        // v5.3
        put("leading",           el.leading.toDouble())
        put("tracking",          el.tracking.toDouble())
        put("justify",           el.justify)
        put("paragraphSpacing",  el.paragraphSpacing.toDouble())
        put("textPathMode",      el.textPathMode)
        put("textPathAmount",    el.textPathAmount.toDouble())
        put("textPathCycles",    el.textPathCycles.toDouble())
        put("enableOutline",     el.enableOutline)
        put("enableShadow",      el.enableShadow)
        put("enableGradient",    el.enableGradient)
        put("enableTexture",     el.enableTexture)
        put("textTransform",     el.textTransform)
        put("enableOutlineGradient", el.enableOutlineGradient)
        put("outlineGradStartColor", el.outlineGradStartColor)
        put("outlineGradEndColor", el.outlineGradEndColor)
    }

    private fun serializeTextSpan(sp: com.vasiliastyper.model.TextSpan): JSONObject =
        JSONObject().apply {
            put("start", sp.start)
            put("end",   sp.end)
            sp.fontName?.let       { put("fontName", it) }
            sp.isBold?.let         { put("isBold",   it) }
            sp.isItalic?.let       { put("isItalic", it) }
            sp.color?.let          { put("color",    it) }
            sp.effect?.let         { put("effect",   it) }
            sp.outlineColor?.let   { put("outlineColor",   it) }
            sp.outlineWidth?.let   { put("outlineWidth",   it.toDouble()) }
            sp.outlineOpacity?.let { put("outlineOpacity", it) }
            sp.shadowColor?.let    { put("shadowColor",   it) }
            sp.shadowDx?.let       { put("shadowDx",      it.toDouble()) }
            sp.shadowDy?.let       { put("shadowDy",      it.toDouble()) }
            sp.shadowRadius?.let   { put("shadowRadius",  it.toDouble()) }
            sp.shadowOpacity?.let  { put("shadowOpacity", it) }
            sp.opacity?.let        { put("spanOpacity",   it) }
            sp.textureUri?.let     { put("textureUri",    it) }
        }

    fun deserializeTextElements(arr: JSONArray?): List<TextElement> {
        arr ?: return emptyList()
        val list = mutableListOf<TextElement>()
        for (i in 0 until arr.length()) {
            try {
                val o  = arr.getJSONObject(i)
                val el = TextElement(
                    id                = o.optString("id",       UUID.randomUUID().toString()),
                    name              = o.optString("name",     "Text Layer"),
                    isVisible         = o.optBoolean("isVisible", true),
                    isLocked          = o.optBoolean("isLocked", false),
                    blendMode         = o.optString("blendMode", "Normal"),
                    text              = o.optString("text",     ""),
                    x                 = o.optDouble("x",        0.0).toFloat(),
                    y                 = o.optDouble("y",        0.0).toFloat(),
                    width             = o.optDouble("width",    300.0).toFloat(),
                    height            = o.optDouble("height",   100.0).toFloat(),
                    fontSize          = o.optDouble("fontSize", 36.0).toFloat(),
                    fontName          = o.optString("fontName", "default"),
                    color             = o.optInt("color",       android.graphics.Color.WHITE),
                    isBold            = o.optBoolean("isBold",  false),
                    isItalic          = o.optBoolean("isItalic",false),
                    effect            = textEffectByName(o.optString("effect", "NONE")),
                    outlineColor      = o.optInt("outlineColor",    android.graphics.Color.BLACK),
                    outlineWidth      = o.optDouble("outlineWidth",  4.0).toFloat(),
                    outlineOpacity    = o.optInt("outlineOpacity",   100),
                    shadowDx          = o.optDouble("shadowDx",      4.0).toFloat(),
                    shadowDy          = o.optDouble("shadowDy",      4.0).toFloat(),
                    shadowRadius      = o.optDouble("shadowRadius",   6.0).toFloat(),
                    shadowSpread      = o.optDouble("shadowSpread",   0.0).toFloat().coerceIn(0f, 128f),
                    shadowColor       = o.optInt("shadowColor",      android.graphics.Color.BLACK),
                    shadowOpacity     = o.optInt("shadowOpacity",    80),
                    gradientStartColor= o.optInt("gradientStartColor",android.graphics.Color.WHITE),
                    gradientEndColor  = o.optInt("gradientEndColor",  android.graphics.Color.GRAY),
                    gradientColors    = mutableListOf<Int>().apply {
                        o.optJSONArray("gradientColors")?.let { colors ->
                            for (index in 0 until colors.length()) add(colors.optInt(index))
                        }
                    },
                    gradientAngle     = o.optDouble("gradientAngle", 90.0).toFloat(),
                    align             = textAlignByName(o.optString("align", "CENTER")),
                    rotation          = o.optDouble("rotation", 0.0).toFloat(),
                    layerId           = o.optString("layerId", "").ifEmpty { null },
                    textureUri        = o.optString("textureUri", "").ifEmpty { null }
                )
                // Restore perspective corners if present
                o.optJSONArray("perspCorners")?.let { pc ->
                    if (pc.length() == 8) {
                        el.perspCorners = FloatArray(8) { idx -> pc.optDouble(idx, 0.0).toFloat() }
                    }
                }
                // Restore mesh points if present
                o.optJSONArray("meshPoints")?.let { pp ->
                    if (pp.length() >= 4 && pp.length() % 2 == 0) {
                        el.meshPoints = FloatArray(pp.length()) { idx ->
                            pp.optDouble(idx, 0.0).toFloat()
                        }
                        el.meshCols = o.optInt("meshCols", 3).coerceIn(1, 8)
                        el.meshRows = o.optInt("meshRows", 3).coerceIn(1, 8)
                    }
                }
                // Restore spans if present
                o.optJSONArray("spans")?.let { spArr ->
                    val spans = mutableListOf<com.vasiliastyper.model.TextSpan>()
                    for (k in 0 until spArr.length()) {
                        try { spans.add(deserializeTextSpan(spArr.getJSONObject(k))) } catch (_: Exception) {}
                    }
                    if (spans.isNotEmpty()) el.spans = spans
                }
                // v5.2 fields
                el.opacity            = o.optInt("opacity", 100)
                el.blurType           = o.optString("blurType", "NONE")
                el.blurRadius         = o.optDouble("blurRadius", 0.0).toFloat()
                el.blurMotionAngle    = o.optDouble("blurMotionAngle", 0.0).toFloat()
                el.blurMotionDistance = o.optDouble("blurMotionDistance", 20.0).toFloat()
                // v5.3 fields
                el.leading  = o.optDouble("leading",  120.0).toFloat()
                el.tracking = o.optDouble("tracking", 0.0).toFloat()
                el.justify  = o.optBoolean("justify", false)
                el.paragraphSpacing = o.optDouble("paragraphSpacing", 0.0).toFloat()
                    .takeIf(Float::isFinite)?.coerceIn(-50f, 50f) ?: 0f
                el.textPathMode = o.optString("textPathMode", "NONE").uppercase().takeIf {
                    it in setOf("NONE", "CURVE_UP", "CURVE_DOWN", "WAVE", "ARCH", "VALLEY")
                } ?: "NONE"
                el.textPathAmount = o.optDouble("textPathAmount", 35.0).toFloat()
                    .takeIf(Float::isFinite)?.coerceIn(-100f, 100f) ?: 35f
                el.textPathCycles = o.optDouble("textPathCycles", 1.5).toFloat()
                    .takeIf(Float::isFinite)?.coerceIn(0.5f, 5f) ?: 1.5f
                el.enableOutline = o.optBoolean("enableOutline", false)
                el.enableShadow = o.optBoolean("enableShadow", false)
                el.enableGradient = o.optBoolean(
                    "enableGradient", el.effect == com.vasiliastyper.model.TextEffect.GRADIENT
                )
                el.enableTexture = o.optBoolean(
                    "enableTexture", el.effect == com.vasiliastyper.model.TextEffect.TEXTURE
                )
                el.textTransform = o.optString("textTransform", "NONE")
                el.enableOutlineGradient = o.optBoolean("enableOutlineGradient", false)
                el.outlineGradStartColor = o.optInt(
                    "outlineGradStartColor", android.graphics.Color.BLACK
                )
                el.outlineGradEndColor = o.optInt(
                    "outlineGradEndColor", android.graphics.Color.GRAY
                )
                list.add(el)
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    private fun deserializeTextSpan(o: JSONObject): com.vasiliastyper.model.TextSpan {
        return com.vasiliastyper.model.TextSpan(
            start          = o.optInt("start", 0),
            end            = o.optInt("end",   0),
            fontName       = if (o.has("fontName")) o.optString("fontName", "").ifEmpty { null } else null,
            isBold         = if (o.has("isBold"))   o.optBoolean("isBold")   else null,
            isItalic       = if (o.has("isItalic")) o.optBoolean("isItalic") else null,
            color          = if (o.has("color"))    o.optInt("color")        else null,
            effect         = if (o.has("effect"))   o.optString("effect", "").ifEmpty { null } else null,
            outlineColor   = if (o.has("outlineColor"))   o.optInt("outlineColor")   else null,
            outlineWidth   = if (o.has("outlineWidth"))   o.optDouble("outlineWidth").toFloat() else null,
            outlineOpacity = if (o.has("outlineOpacity")) o.optInt("outlineOpacity") else null,
            shadowColor    = if (o.has("shadowColor"))    o.optInt("shadowColor")    else null,
            shadowDx       = if (o.has("shadowDx"))       o.optDouble("shadowDx").toFloat() else null,
            shadowDy       = if (o.has("shadowDy"))       o.optDouble("shadowDy").toFloat() else null,
            shadowRadius   = if (o.has("shadowRadius"))   o.optDouble("shadowRadius").toFloat() else null,
            shadowOpacity  = if (o.has("shadowOpacity"))  o.optInt("shadowOpacity")  else null,
            opacity        = if (o.has("spanOpacity"))   o.optInt("spanOpacity")    else null,
            textureUri     = if (o.has("textureUri"))    o.optString("textureUri", "").ifEmpty { null } else null
        )
    }

    // ── ImageElement serialize / deserialize ──────────────────────────────────

    fun serializeImageElements(elements: List<ImageElement>): JSONArray {
        val arr = JSONArray()
        for (el in elements) {
            try { arr.put(serializeImageElement(el)) } catch (_: Exception) {}
        }
        return arr
    }

    private fun serializeImageElement(el: ImageElement): JSONObject = JSONObject().apply {
        put("id",          el.id)
        put("name",        el.name)
        put("isVisible",   el.isVisible)
        put("isLocked",    el.isLocked)
        put("blendMode",   el.blendMode)
        put("x",           el.x.toDouble())
        put("y",           el.y.toDouble())
        put("width",       el.width.toDouble())
        put("height",      el.height.toDouble())
        put("rotation",    el.rotation.toDouble())
        put("aspectRatio", el.aspectRatio.toDouble())
        put("opacity",     el.opacity)
        put("blurRadius",  el.blurRadius.toDouble())
        put("blurType",    el.blurType.name)
        put("blurMotionAngle", el.blurMotionAngle.toDouble())
        put("blurMotionDistance", el.blurMotionDistance.toDouble())
        put("bitmap",      bitmapToBase64(el.bitmap))
    }

    fun deserializeImageElements(arr: JSONArray?): List<ImageElement> {
        arr ?: return emptyList()
        val list = mutableListOf<ImageElement>()
        for (i in 0 until arr.length()) {
            try {
                val o  = arr.getJSONObject(i)
                val bm = base64ToBitmap(o.getString("bitmap")) ?: continue
                list.add(ImageElement(
                    id          = o.optString("id",  UUID.randomUUID().toString()),
                    name        = o.optString("name", "Image Layer"),
                    isVisible   = o.optBoolean("isVisible", true),
                    isLocked    = o.optBoolean("isLocked", false),
                    blendMode   = o.optString("blendMode", "Normal"),
                    bitmap      = bm,
                    x           = o.optDouble("x",           0.0).toFloat(),
                    y           = o.optDouble("y",           0.0).toFloat(),
                    width       = o.optDouble("width",       200.0).toFloat(),
                    height      = o.optDouble("height",      200.0).toFloat(),
                    rotation    = o.optDouble("rotation",    0.0).toFloat(),
                    aspectRatio = o.optDouble("aspectRatio", 1.0).toFloat(),
                    opacity     = o.optInt("opacity", 100).coerceIn(0, 100),
                    blurRadius  = o.optDouble("blurRadius", 0.0).toFloat(),
                    blurType    = runCatching {
                        com.vasiliastyper.model.BlurType.valueOf(o.optString("blurType", "GAUSSIAN"))
                    }.getOrDefault(com.vasiliastyper.model.BlurType.GAUSSIAN),
                    blurMotionAngle = o.optDouble("blurMotionAngle", 0.0).toFloat(),
                    blurMotionDistance = o.optDouble("blurMotionDistance", 20.0).toFloat()
                ))
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    // ── Bitmap helpers ────────────────────────────────────────────────────────

    private fun bitmapToBase64(bm: Bitmap): String {
        val out = ByteArrayOutputStream()
        bm.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun base64ToBitmap(b64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }

    // ── Enum helpers ──────────────────────────────────────────────────────────

    fun blendModeByName(name: String): PorterDuff.Mode =
        PorterDuff.Mode.values().find { it.name == name } ?: PorterDuff.Mode.SRC_OVER

    /** Backward-compat alias. */
    fun findByName(name: String): PorterDuff.Mode = blendModeByName(name)

    private fun textEffectByName(name: String): TextEffect =
        TextEffect.values().find { it.name == name } ?: TextEffect.NONE

    private fun textAlignByName(name: String): TextAlign =
        TextAlign.values().find { it.name == name } ?: TextAlign.CENTER
}
