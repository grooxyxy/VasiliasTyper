package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Typeface
import com.vasiliastyper.adapter.FontItem
import com.vasiliastyper.model.TextElement
import com.vasiliastyper.model.TextSpan
import java.util.concurrent.ConcurrentHashMap

/**
 * v5.0 — Unified font resolver.
 *
 * The "Font Bank" (built-in fonts + recursively scanned assets/fonts/ and
 * assets/font/ files + user-imported fonts under filesDir/fonts/) is the single
 * place that stores a `fontName` string (TextStyle, TextElement, TextSpan).
 *
 * Use [resolve] to turn a stored fontName into a Typeface; falls back to
 * Typeface.DEFAULT when no match is found.
 *
 * Use [buildBuiltInFonts] to obtain the built-in (programmatic) font list,
 * matching the list used inside MainActivity.buildFontList(). This avoids
 * duplicating the list when the resolver is needed outside MainActivity.
 *
 * Use [hydrateTextElements] right after loading a workspace from disk so the
 * Typeface field on every TextElement (and on every per-span override) gets
 * populated from the merged font bank.
 */
object FontResolver {

    @Volatile
    private var cachedMergedBank: List<FontItem>? = null
    private val targetedTypefaceCache = ConcurrentHashMap<String, Typeface>()

    /**
     * Clears the merged bank after a font import/removal. Style previews otherwise
     * reuse one bank instead of rescanning assets and reopening every font per row.
     */
    fun invalidateCache() {
        cachedMergedBank = null
        targetedTypefaceCache.clear()
    }

    /**
     * The fixed built-in font list. Kept in sync with MainActivity.buildFontList().
     * Lives here so non-Activity code (auto-save restore, PSD import, etc.) can
     * still resolve "CC Bold", "Bangers Style"… without a Context.
     */
    fun buildBuiltInFonts(): List<FontItem> = listOf(
        FontItem("Default",       Typeface.DEFAULT, folder = "System"),
        FontItem("Serif",         Typeface.SERIF, folder = "System"),
        FontItem("Monospace",     Typeface.MONOSPACE, folder = "System"),
        FontItem("Sans-Serif",    Typeface.SANS_SERIF, folder = "System"),
        FontItem("CC Bold",       Typeface.DEFAULT_BOLD, isComic = true, folder = "Comic"),
        FontItem("Wild Words",    Typeface.create("cursive",              Typeface.BOLD),   isComic = true, folder = "Comic"),
        FontItem("Bangers Style", Typeface.create("sans-serif-condensed", Typeface.BOLD),   isComic = true, folder = "Comic"),
        FontItem("Impact Style",  Typeface.create("sans-serif-condensed", Typeface.NORMAL), isComic = true, folder = "Comic"),
        FontItem("Manga Temple",  Typeface.MONOSPACE, isComic = true, folder = "Comic")
    )

    /**
     * Merged Font Bank = built-in + assets/fonts/ + user-imported (filesDir/fonts/).
     */
    @Synchronized
    fun buildMergedBank(context: Context): List<FontItem> {
        cachedMergedBank?.let { return it }
        val result = mutableListOf<FontItem>()
        result.addAll(buildBuiltInFonts())

        // Asset fonts from either root, recursively preserving nested folders.
        val seenAssetPaths = mutableSetOf<String>()
        fun scanAssetFonts(root: String, relativeDir: String = "") {
            val assetDir = listOf(root, relativeDir).filter(String::isNotBlank).joinToString("/")
            val entries = runCatching { context.assets.list(assetDir)?.sorted().orEmpty() }
                .getOrDefault(emptyList())
            for (entry in entries) {
                val relativePath = if (relativeDir.isBlank()) entry else "$relativeDir/$entry"
                val fullPath = "$root/$relativePath"
                val extension = entry.substringAfterLast('.', "").lowercase()
                if (extension in setOf("ttf", "otf", "ttc")) {
                    if (!seenAssetPaths.add(fullPath)) continue
                    runCatching {
                        FontItem(
                            displayName = entry.substringBeforeLast('.'),
                            typeface = Typeface.createFromAsset(context.assets, fullPath),
                            isComic = true,
                            fileName = fullPath,
                            folder = relativeDir.ifBlank {
                                if (root == "font") "Font" else "Uncategorized"
                            }
                        )
                    }.onSuccess(result::add)
                } else {
                    val children = runCatching { context.assets.list(fullPath).orEmpty() }
                        .getOrDefault(emptyArray())
                    if (children.isNotEmpty()) scanAssetFonts(root, relativePath)
                }
            }
        }
        scanAssetFonts("fonts")
        scanAssetFonts("font")

        // User-imported
        result.addAll(CustomFontManager.loadAll(context))
        return result.toList().also { bank ->
            cachedMergedBank = bank
            bank.forEach { targetedTypefaceCache[fontKey(it.displayName)] = it.typeface }
        }
    }

    /**
     * Resolves one style/preset font without constructing the complete Font Bank.
     *
     * Style previews and Apply actions only need one Typeface. The old [resolve]
     * path decoded every bundled and user-imported font on the first preset row,
     * causing a large allocation burst while RecyclerView was opening. This targeted
     * path walks asset names but decodes only the requested file, and checks only the
     * matching imported file. It is safe to call from a background dispatcher.
     */
    @Synchronized
    fun resolveStyleTypeface(context: Context, fontName: String?): Typeface {
        val requested = fontName?.trim().orEmpty()
        if (requested.isBlank()) return Typeface.DEFAULT
        val key = fontKey(requested)
        targetedTypefaceCache[key]?.let { return it }

        buildBuiltInFonts().firstOrNull { fontKey(it.displayName) == key }?.typeface?.let {
            targetedTypefaceCache[key] = it
            return it
        }
        cachedMergedBank?.firstOrNull { fontKey(it.displayName) == key }?.typeface?.let {
            targetedTypefaceCache[key] = it
            return it
        }

        val assetPath = findAssetFontPath(context, requested)
        if (assetPath != null) {
            runCatching { Typeface.createFromAsset(context.assets, assetPath) }.getOrNull()?.let {
                targetedTypefaceCache[key] = it
                return it
            }
        }

        // Imported file names are sanitized for storage and may not match the
        // user-facing name (for example "Comic Sans" -> "Comic_Sans.ttf").
        // Resolve through CustomFontManager's sidecar metadata so saved projects
        // keep using the imported font after an app restart.
        val imported = CustomFontManager.findFileByDisplayName(context, requested)
        if (imported != null) {
            runCatching { Typeface.createFromFile(imported) }.getOrNull()?.let {
                targetedTypefaceCache[key] = it
                return it
            }
        }
        return Typeface.DEFAULT
    }

    private fun findAssetFontPath(context: Context, requestedName: String): String? {
        val target = fontKey(requestedName.substringBeforeLast('.', requestedName))
        fun scan(directory: String): String? {
            val entries = runCatching { context.assets.list(directory).orEmpty() }
                .getOrDefault(emptyArray())
            for (entry in entries) {
                val path = "$directory/$entry"
                val extension = entry.substringAfterLast('.', "").lowercase()
                if (extension in FONT_EXTENSIONS) {
                    if (fontKey(entry.substringBeforeLast('.')) == target) return path
                } else if (runCatching { context.assets.list(path).orEmpty().isNotEmpty() }.getOrDefault(false)) {
                    scan(path)?.let { return it }
                }
            }
            return null
        }
        return scan("fonts") ?: scan("font")
    }

    private fun fontKey(value: String): String = value.trim().lowercase()

    /**
     * Resolve a stored fontName from the merged bank.
     * Lookup is case-insensitive and ignores leading/trailing whitespace.
     * Falls back to Typeface.DEFAULT.
     */
    fun resolve(context: Context, fontName: String?): Typeface {
        if (fontName.isNullOrBlank()) return Typeface.DEFAULT
        val target = fontName.trim().lowercase()
        val bank   = buildMergedBank(context)
        val match  = bank.firstOrNull { it.displayName.trim().lowercase() == target }
        return match?.typeface ?: Typeface.DEFAULT
    }

    /**
     * Resolve from an already-built merged bank (avoids re-scanning on a tight loop).
     */
    fun resolveFromBank(bank: List<FontItem>, fontName: String?): Typeface {
        if (fontName.isNullOrBlank()) return Typeface.DEFAULT
        val target = fontName.trim().lowercase()
        val match  = bank.firstOrNull { it.displayName.trim().lowercase() == target }
        return match?.typeface ?: Typeface.DEFAULT
    }

    /**
     * Walk every [TextElement] in [list] and fill its (and each span's)
     * Typeface field by looking up fontName in the merged bank.
     *
     * Call this right after WorkspaceSerializer.tryRestore() / .tryRestoreFull()
     * and after any PSD import that produces TextElements.
     */
    fun hydrateTextElements(context: Context, list: List<TextElement>) {
        if (list.isEmpty()) return
        val bank = buildMergedBank(context)
        for (el in list) {
            el.typeface = resolveFromBank(bank, el.fontName)
            el.spans?.forEach { sp ->
                if (!sp.fontName.isNullOrBlank()) {
                    sp.typeface = resolveFromBank(bank, sp.fontName)
                }
            }
        }
    }

    /**
     * Check whether a font with [displayName] (case-insensitive) is already
     * registered in the merged bank. Used by the import flow to prevent
     * duplicate or shadowing entries.
     */
    fun isRegistered(context: Context, displayName: String): Boolean {
        val target = displayName.trim().lowercase()
        return buildMergedBank(context).any {
            it.displayName.trim().lowercase() == target
        }
    }

    private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")
}
