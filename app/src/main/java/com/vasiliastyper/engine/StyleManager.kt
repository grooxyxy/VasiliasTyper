package com.vasiliastyper.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.vasiliastyper.model.TextStyle
import org.json.JSONArray
import org.json.JSONObject

/**
 * StyleRule — maps a text pattern to a saved TextStyle name.
 * patternType:
 *   WRAP_PAREN   → text wrapped in (...)
 *   WRAP_QUOTE   → text wrapped in "..." or \u201C...\u201D
 *   WRAP_SQUARE  → text wrapped in [...]
 *   CONTAINS     → text contains the pattern string
 *   STARTS_WITH  → text starts with the pattern string
 */
data class StyleRule(
    val pattern: String,
    val patternType: String,
    val styleName: String,
    val folder: String = "Default"
)

object StyleManager {

    private const val PREF_FILE = "vasilias_styles"
    private const val KEY_STYLES = "styles"
    private const val KEY_FOLDERS = "folders"
    private const val KEY_RULES = "style_rules"
    private const val TAG = "StyleManager"
    // User styles only. Bundled presets live in WebtoonSfxPresets and are never
    // copied into this preference anymore, keeping Style Manager lightweight.
    private const val MAX_STYLES = 2000
    private const val MAX_RULES = 2000
    private const val MAX_FOLDERS = 500
    private const val MAX_JSON_CHARS = 4 * 1024 * 1024
    private const val MAX_GRADIENT_COLORS = 32
    private const val MAX_TEXT_LENGTH = 256

    private fun safeString(prefs: SharedPreferences, key: String, fallback: String): String =
        try {
            prefs.getString(key, fallback) ?: fallback
        } catch (error: ClassCastException) {
            Log.w(TAG, "Preferensi '$key' memiliki tipe lama; nilai direset", error)
            prefs.edit().remove(key).apply()
            fallback
        }

    private fun safeLong(prefs: SharedPreferences, key: String): Long =
        try {
            prefs.getLong(key, 0L)
        } catch (error: ClassCastException) {
            Log.w(TAG, "Versi preferensi '$key' rusak; nilai direset", error)
            prefs.edit().remove(key).apply()
            0L
        }

    private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback

    // ── v6.1 In-memory cache ── menghindari disk I/O SharedPreferences berulang
    // yang menyebabkan delay signifikan di device low-end saat script "Use" diklik.
    // Cache di-reload hanya saat ada perubahan (simpan/hapus style/rule).
    @Volatile private var cachedStyles: MutableList<TextStyle>? = null
    @Volatile private var cachedRules: MutableList<StyleRule>? = null
    private var stylesPrefVersion: Long = 0L
    private var rulesPrefVersion: Long = 0L

    fun invalidateCache() {
        cachedStyles = null
        cachedRules = null
    }

    fun normalizeFolderName(input: String): String {
        val cleaned = input.trim().replace(Regex("\\s+"), " ")
        return cleaned.ifBlank { "Default" }
    }

    private fun folderKey(value: String): String = normalizeFolderName(value).lowercase()

    fun loadFolderRegistry(context: Context): MutableList<String> {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val json = safeString(prefs, KEY_FOLDERS, "[]")
        if (json.length > MAX_JSON_CHARS) {
            Log.e(TAG, "Registry folder terlalu besar; registry direset agar panel tetap dapat dibuka")
            prefs.edit().remove(KEY_FOLDERS).apply()
            return mutableListOf()
        }
        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<String>()
            for (i in 0 until minOf(arr.length(), MAX_FOLDERS)) {
                val raw = arr.optString(i, "")
                val folder = normalizeFolderName(raw)
                if (folderKey(folder) != "default" && out.none { it.equals(folder, ignoreCase = true) }) {
                    out.add(folder)
                }
            }
            out
        } catch (_: OutOfMemoryError) {
            Log.e(TAG, "Memori tidak cukup saat membaca registry folder")
            mutableListOf()
        } catch (error: Exception) {
            Log.w(TAG, "Registry folder rusak; folder tambahan diabaikan", error)
            mutableListOf()
        }
    }

    fun saveFolderRegistry(context: Context, folders: List<String>) {
        try {
            val arr = JSONArray()
            folders.map(::normalizeFolderName)
                .filter { folderKey(it) != "default" }
                .distinctBy { folderKey(it) }
                .forEach { arr.put(it) }
            context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                .edit().putString(KEY_FOLDERS, arr.toString()).apply()
        } catch (error: Throwable) {
            Log.e(TAG, "Registry folder gagal disimpan", error)
        }
    }

    fun addFolder(context: Context, folder: String) {
        val list = loadFolderRegistry(context)
        val normalized = normalizeFolderName(folder)
        if (folderKey(normalized) == "default") return
        if (list.none { it.equals(normalized, ignoreCase = true) }) list.add(normalized)
        saveFolderRegistry(context, list)
    }

    fun removeFolder(context: Context, folder: String) {
        val key = folderKey(folder)
        if (key == "default") return
        saveFolderRegistry(context, loadFolderRegistry(context).filterNot { folderKey(it) == key })
    }

    fun folders(context: Context, styles: List<TextStyle>): List<String> {
        val seen = linkedMapOf<String, String>()
        seen[folderKey("Default")] = "Default"

        loadFolderRegistry(context).forEach {
            val n = normalizeFolderName(it)
            val key = folderKey(n)
            if (key != "default") seen.putIfAbsent(key, n)
        }
        for (s in styles) {
            val n = normalizeFolderName(s.folder)
            val key = folderKey(n)
            if (key != "default") seen.putIfAbsent(key, n)
        }
        return seen.values.toList()
    }

    fun canonicalFolderName(input: String, context: Context, styles: List<TextStyle>): String {
        val normalized = normalizeFolderName(input)
        return folders(context, styles).firstOrNull { it.equals(normalized, ignoreCase = true) } ?: normalized
    }

    @Synchronized
    fun loadStyles(context: Context): MutableList<TextStyle> {
        // v6.1: gunakan cache memory — hindari baca SharedPreferences berulang.
        // A hard size/count limit also prevents a damaged preference from exhausting
        // the heap before Style Manager can draw its first frame.
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val currentVersion = safeLong(prefs, "__styles_version")
        cachedStyles?.let { cached ->
            if (stylesPrefVersion == currentVersion) {
                return cached.mapTo(mutableListOf(), TextStyle::safeCopyForApply)
            }
        }

        val json = safeString(prefs, KEY_STYLES, "[]")
        if (json.length > MAX_JSON_CHARS) {
            Log.e(TAG, "Data style terlalu besar (${json.length} karakter); preferensi direset agar aplikasi dapat pulih")
            prefs.edit().remove(KEY_STYLES).putLong("__styles_version", System.currentTimeMillis()).commit()
            invalidateCache()
            return mutableListOf()
        }
        val result = try {
            val arr = JSONArray(json)
            val count = minOf(arr.length(), MAX_STYLES)
            val list = ArrayList<TextStyle>(count)
            var skipped = 0
            for (i in 0 until count) {
                val item = arr.optJSONObject(i)
                if (item == null) {
                    skipped++
                    continue
                }
                try {
                    // Recover item-by-item. One malformed imported style must not make
                    // every valid style disappear or force-close the manager.
                    list.add(fromJson(item).safeCopyForApply())
                } catch (error: Exception) {
                    skipped++
                    Log.w(TAG, "Style indeks $i rusak dan dilewati", error)
                }
            }
            if (arr.length() > MAX_STYLES) {
                Log.w(TAG, "${arr.length() - MAX_STYLES} style melewati batas dan diabaikan")
            }
            if (skipped > 0) Log.w(TAG, "$skipped style rusak berhasil diisolasi")
            list
        } catch (error: OutOfMemoryError) {
            invalidateCache()
            Log.e(TAG, "Memori tidak cukup saat membaca style; data style diabaikan untuk sesi ini")
            mutableListOf()
        } catch (error: Exception) {
            Log.w(TAG, "Data style rusak; daftar style dimulai kosong", error)
            mutableListOf()
        }
        cachedStyles = result
        stylesPrefVersion = currentVersion
        // Never expose the mutable gradient lists held by the process cache.
        return result.mapTo(mutableListOf(), TextStyle::safeCopyForApply)
    }

    @Synchronized
    fun saveStyles(context: Context, styles: List<TextStyle>): Boolean {
        return try {
            val safeStyles = styles.take(MAX_STYLES).map(TextStyle::safeCopyForApply)
            val arr = JSONArray()
            safeStyles.forEach { arr.put(toJson(it)) }
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val newVersion = System.nanoTime()
            // SharedPreferences.apply updates the in-process map synchronously and
            // flushes disk asynchronously, so Save/Load remains instant without I/O
            // on the editor thread. Seed the cache now to avoid reparsing the JSON.
            prefs.edit()
                .putString(KEY_STYLES, arr.toString())
                .putLong("__styles_version", newVersion)
                .apply()
            cachedStyles = safeStyles.toMutableList()
            stylesPrefVersion = newVersion
            true
        } catch (error: Throwable) {
            // A malformed legacy style must not force-close the editor/manager.
            Log.e(TAG, "Style gagal disimpan", error)
            false
        }
    }

    fun addStyle(context: Context, style: TextStyle): Boolean {
        val list = loadStyles(context)
        val canonicalFolder = canonicalFolderName(style.folder, context, list)
        val normalizedName = style.name.trim().ifBlank { "Style" }
        list.removeAll { it.name.equals(normalizedName, ignoreCase = true) && it.folder.equals(canonicalFolder, ignoreCase = true) }
        list.add(0, style.copy(name = normalizedName, folder = canonicalFolder).safeCopyForApply())
        val saved = saveStyles(context, list)
        if (saved) addFolder(context, canonicalFolder)
        return saved
    }

    fun deleteStyle(context: Context, style: TextStyle): Boolean {
        val list = loadStyles(context)
        list.removeAll { it.name.equals(style.name, ignoreCase = true) && it.folder.equals(style.folder, ignoreCase = true) }
        return saveStyles(context, list)
    }

    // ── Style Rules ───────────────────────────────────────────────────────────

    @Synchronized
    fun loadStyleRules(context: Context): MutableList<StyleRule> {
        // v6.1: gunakan cache memory — hindari baca SharedPreferences berulang
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val currentVersion = safeLong(prefs, "__rules_version")
        cachedRules?.let { if (rulesPrefVersion == currentVersion) return it.toMutableList() }

        val json = safeString(prefs, KEY_RULES, "[]")
        if (json.length > MAX_JSON_CHARS) {
            Log.e(TAG, "Data aturan style terlalu besar; preferensi aturan direset")
            prefs.edit().remove(KEY_RULES).putLong("__rules_version", System.currentTimeMillis()).commit()
            invalidateCache()
            return mutableListOf()
        }
        val result = try {
            val arr = JSONArray(json)
            val list = mutableListOf<StyleRule>()
            for (i in 0 until minOf(arr.length(), MAX_RULES)) {
                val o = arr.optJSONObject(i) ?: continue
                list.add(StyleRule(
                    pattern     = o.optString("pattern", "").take(MAX_TEXT_LENGTH),
                    patternType = o.optString("patternType", "CONTAINS").take(32),
                    styleName   = o.optString("styleName", "").take(MAX_TEXT_LENGTH),
                    folder      = o.optString("folder", "Default").take(MAX_TEXT_LENGTH)
                ))
            }
            list
        } catch (error: OutOfMemoryError) {
            invalidateCache()
            Log.e(TAG, "Memori tidak cukup saat membaca aturan style")
            mutableListOf()
        } catch (error: Exception) {
            Log.w(TAG, "Data aturan style rusak; aturan diabaikan", error)
            mutableListOf()
        }
        cachedRules = result
        rulesPrefVersion = currentVersion
        return result.toMutableList()
    }

    @Synchronized
    fun saveStyleRules(context: Context, rules: List<StyleRule>) {
        try {
            val arr = JSONArray()
            rules.take(MAX_RULES).forEach { r ->
                arr.put(JSONObject().apply {
                    put("pattern", r.pattern)
                    put("patternType", r.patternType)
                    put("styleName", r.styleName)
                    put("folder", r.folder)
                })
            }
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val newVersion = System.currentTimeMillis()
            prefs.edit()
                .putString(KEY_RULES, arr.toString())
                .putLong("__rules_version", newVersion)
                .apply()
            invalidateCache()
        } catch (error: Throwable) {
            Log.e(TAG, "Aturan style gagal disimpan", error)
        }
    }

    /**
     * Checks the text against all saved StyleRules and returns the first matching
     * TextStyle, or null if no rule matches.
     */
    fun findMatchingStyle(context: Context, text: String): TextStyle? {
        val rules  = loadStyleRules(context)
        val styles = loadStyles(context)
        val t = text.trim()
        for (rule in rules) {
            val matches = when (rule.patternType) {
                "WRAP_PAREN"   -> t.startsWith("(") && t.endsWith(")")
                "WRAP_QUOTE"   -> (t.startsWith("\"") && t.endsWith("\"")) ||
                                  (t.startsWith("\u201C") && t.endsWith("\u201D"))
                "WRAP_SQUARE"  -> t.startsWith("[") && t.endsWith("]")
                "CONTAINS"     -> rule.pattern.isNotEmpty() && t.contains(rule.pattern)
                "STARTS_WITH"  -> rule.pattern.isNotEmpty() && t.startsWith(rule.pattern)
                // PREFIX_CODE: matches text that starts with the code prefix, e.g. "":, ():, []:
                "PREFIX_CODE"  -> rule.pattern.isNotEmpty() && t.startsWith(rule.pattern)
                else           -> false
            }
            if (matches) {
                return styles.firstOrNull {
                    it.name.equals(rule.styleName, ignoreCase = true) &&
                    it.folder.equals(rule.folder, ignoreCase = true)
                }
            }
        }
        return null
    }

    /**
     * If the text starts with a PREFIX_CODE rule's pattern, strip the prefix and
     * return the remaining content text. Otherwise return the original text.
     * If the leading token looks like a prefix format (ends with ':') but has no
     * matching rule, the full original text is returned unchanged so the dialog
     * shows it as-is.
     */
    fun stripMatchedPrefix(text: String, rules: List<StyleRule>): String {
        val t = text.trim()
        for (rule in rules) {
            if (rule.patternType == "PREFIX_CODE" && rule.pattern.isNotEmpty() && t.startsWith(rule.pattern)) {
                return t.removePrefix(rule.pattern).trimStart()
            }
        }
        return text
    }

    private fun toJson(s: TextStyle) = JSONObject().apply {
        put("name", s.name); put("folder", s.folder)
        put("previewText", s.previewText)
        put("presetCategory", s.presetCategory)
        put("presetDescription", s.presetDescription)
        put("fontSize", s.fontSize.finiteOr(36f)); put("fontName", s.fontName)
        put("color", s.color); put("opacity", s.opacity); put("isBold", s.isBold)
        put("isItalic", s.isItalic); put("effect", s.effect)
        put("outlineWidth", s.outlineWidth.finiteOr(4f)); put("outlineOpacity", s.outlineOpacity)
        put("outlineColor", s.outlineColor); put("shadowDx", s.shadowDx.finiteOr(4f))
        put("shadowDy", s.shadowDy.finiteOr(4f)); put("shadowRadius", s.shadowRadius.finiteOr(6f))
        put("shadowSpread", s.shadowSpread.finiteOr(0f))
        put("shadowColor", s.shadowColor); put("shadowOpacity", s.shadowOpacity)
        put("gradientStart", s.gradientStartColor)
        put("gradientEnd", s.gradientEndColor)
        put("gradientColors", JSONArray().apply { s.gradientColors.forEach { put(it) } })
        put("gradientAngle", s.gradientAngle.finiteOr(90f).toDouble())
        put("align", s.align); put("tracking", s.tracking.finiteOr(0f))
        put("leading", s.leading.finiteOr(120f)); put("justify", s.justify)
        put("textPathMode", s.textPathMode)
        put("textPathAmount", s.textPathAmount.finiteOr(35f))
        put("textPathCycles", s.textPathCycles.finiteOr(1.5f))
        put("enableOutline", s.enableOutline)
        put("enableShadow", s.enableShadow)
        put("enableGradient", s.enableGradient)
        put("enableTexture", s.enableTexture)
        put("textTransform", s.textTransform)
        put("enableOutlineGradient", s.enableOutlineGradient)
        put("outlineGradStart", s.outlineGradStartColor)
        put("outlineGradEnd", s.outlineGradEndColor)
    }

    private fun fromJson(o: JSONObject) = TextStyle(
        name = o.optString("name", "Style").take(MAX_TEXT_LENGTH).ifBlank { "Style" },
        folder = o.optString("folder", "Default").take(MAX_TEXT_LENGTH).ifBlank { "Default" },
        previewText = o.optString("previewText", "Ag").take(64).ifBlank { "Ag" },
        presetCategory = o.optString("presetCategory", "").take(64),
        presetDescription = o.optString("presetDescription", "").take(512),
        fontSize = o.optDouble("fontSize", 36.0).toFloat().finiteOr(36f).coerceIn(1f, 512f),
        fontName = o.optString("fontName", "Default").take(MAX_TEXT_LENGTH).ifBlank { "Default" },
        color = o.optInt("color", android.graphics.Color.WHITE),
        opacity = o.optInt("opacity", 100).coerceIn(0, 100),
        isBold = o.optBoolean("isBold"),
        isItalic = o.optBoolean("isItalic"),
        effect = o.optString("effect", "NONE"),
        outlineWidth = o.optDouble("outlineWidth", 4.0).toFloat().finiteOr(4f).coerceIn(0f, 128f),
        outlineOpacity = o.optInt("outlineOpacity", 100).coerceIn(0, 100),
        outlineColor = o.optInt("outlineColor", android.graphics.Color.BLACK),
        shadowDx = o.optDouble("shadowDx", 4.0).toFloat().finiteOr(4f).coerceIn(-256f, 256f),
        shadowDy = o.optDouble("shadowDy", 4.0).toFloat().finiteOr(4f).coerceIn(-256f, 256f),
        shadowRadius = o.optDouble("shadowRadius", 6.0).toFloat().finiteOr(6f).coerceIn(0f, 128f),
        shadowSpread = o.optDouble("shadowSpread", 0.0).toFloat().finiteOr(0f).coerceIn(0f, 128f),
        shadowColor = o.optInt("shadowColor", android.graphics.Color.BLACK),
        shadowOpacity = o.optInt("shadowOpacity", 80).coerceIn(0, 100),
        gradientStartColor = o.optInt("gradientStart", android.graphics.Color.WHITE),
        gradientEndColor = o.optInt("gradientEnd", android.graphics.Color.GRAY),
        gradientColors = mutableListOf<Int>().apply {
            o.optJSONArray("gradientColors")?.let { colors ->
                for (index in 0 until minOf(colors.length(), MAX_GRADIENT_COLORS)) add(colors.optInt(index))
            }
        },
        gradientAngle = o.optDouble("gradientAngle", 90.0).toFloat().finiteOr(90f),
        align = o.optString("align", "CENTER"),
        tracking = o.optDouble("tracking", 0.0).toFloat().finiteOr(0f).coerceIn(-128f, 512f),
        leading = o.optDouble("leading", 120.0).toFloat().finiteOr(120f).coerceIn(-50f, 1000f),
        justify = o.optBoolean("justify", false),
        textPathMode = o.optString("textPathMode", "NONE"),
        textPathAmount = o.optDouble("textPathAmount", 35.0).toFloat().finiteOr(35f).coerceIn(-100f, 100f),
        textPathCycles = o.optDouble("textPathCycles", 1.5).toFloat().finiteOr(1.5f).coerceIn(0.5f, 5f),
        enableOutline = o.optBoolean("enableOutline", false),
        enableShadow = o.optBoolean("enableShadow", false),
        enableGradient = o.optBoolean("enableGradient", o.optString("effect") == "GRADIENT"),
        enableTexture = o.optBoolean("enableTexture", o.optString("effect") == "TEXTURE"),
        textTransform = o.optString("textTransform", "NONE"),
        enableOutlineGradient = o.optBoolean("enableOutlineGradient", false),
        outlineGradStartColor = o.optInt("outlineGradStart", android.graphics.Color.BLACK),
        outlineGradEndColor = o.optInt("outlineGradEnd", android.graphics.Color.GRAY)
    )
}
