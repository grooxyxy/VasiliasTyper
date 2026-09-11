package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import com.vasiliastyper.adapter.FontItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages fonts imported through Android's Storage Access Framework.
 *
 * Font bytes are copied atomically to filesDir/fonts/. A small sidecar stores
 * the user-facing name separately from the sanitized file name, so saved text
 * continues to resolve the same font after the app is restarted.
 */
object CustomFontManager {

    sealed class ImportResult {
        data class Success(val item: FontItem, val renamed: Boolean) : ImportResult()
        data class Duplicate(val existingDisplayName: String) : ImportResult()
        data class Failed(val message: String) : ImportResult()
    }

    private val supportedExtensions = setOf("ttf", "otf", "ttc")

    private fun fontsDir(context: Context): File =
        File(context.filesDir, "fonts").also { it.mkdirs() }

    private fun displayNameFile(fontFile: File): File =
        File(fontFile.parentFile, "${fontFile.name}.name")

    /** Read a provider-safe display name instead of relying on encoded Uri paths. */
    fun sourceFileName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "custom.ttf"
    }

    /** Validate the actual SFNT signature rather than trusting MIME/extension. */
    suspend fun isFontFile(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = ByteArray(4)
            val count = context.contentResolver.openInputStream(uri)?.use { it.read(bytes) } ?: 0
            if (count < 4) return@runCatching false
            val signature = bytes.toString(Charsets.ISO_8859_1)
            (bytes[0] == 0x00.toByte() && bytes[1] == 0x01.toByte() &&
                bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()) ||
                signature == "OTTO" || signature == "true" ||
                signature == "typ1" || signature == "ttcf"
        }.getOrDefault(false)
    }

    fun suggestedDisplayName(context: Context, uri: Uri): String {
        val raw = sourceFileName(context, uri)
        return raw.substringBeforeLast('.', raw).trim().ifBlank { "Imported Font" }
    }

    suspend fun importFont(
        context: Context,
        uri: Uri,
        desiredDisplayName: String? = null,
        overrideExisting: Boolean = false
    ): ImportResult = withContext(Dispatchers.IO) {
        val rawName = sourceFileName(context, uri)
        val sourceExtension = rawName.substringAfterLast('.', "").lowercase()
        val extension = sourceExtension.takeIf { it in supportedExtensions } ?: "ttf"
        val originalDisplay = rawName.substringBeforeLast('.', rawName).trim()
            .ifBlank { "Imported Font" }
        val display = (desiredDisplayName ?: originalDisplay).trim()
            .ifBlank { "Imported Font" }

        if (!overrideExisting && FontResolver.isRegistered(context, display)) {
            return@withContext ImportResult.Duplicate(display)
        }

        val safeBase = display
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('.', '_')
            .ifBlank { "imported_font" }
        var safeName = "$safeBase.$extension"
        var destination = File(fontsDir(context), safeName)
        var suffix = 2
        while (destination.exists()) {
            safeName = "${safeBase}_$suffix.$extension"
            destination = File(fontsDir(context), safeName)
            suffix++
        }

        val temporary = File(destination.parentFile, ".${destination.name}.importing")
        try {
            temporary.delete()
            val input = context.contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Failed("File font tidak dapat dibuka")
            input.use { source ->
                temporary.outputStream().buffered().use { target -> source.copyTo(target) }
            }
            if (temporary.length() < 12L) {
                temporary.delete()
                return@withContext ImportResult.Failed("File font kosong atau rusak")
            }

            // Typeface parsing is the final authority; unsupported WOFF/archives
            // are rejected before they can pollute the persistent Font Bank.
            val parsed = runCatching { Typeface.createFromFile(temporary) }.getOrNull()
                ?: run {
                    temporary.delete()
                    return@withContext ImportResult.Failed("Format font tidak didukung Android")
                }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = false)
                temporary.delete()
            }
            displayNameFile(destination).writeText(display, Charsets.UTF_8)

            ImportResult.Success(
                FontItem(
                    displayName = display,
                    typeface = parsed,
                    isComic = false,
                    isCustom = true,
                    fileName = destination.name,
                    folder = "Imported"
                ),
                renamed = desiredDisplayName != null && display != originalDisplay
            )
        } catch (error: Exception) {
            temporary.delete()
            destination.delete()
            displayNameFile(destination).delete()
            ImportResult.Failed(error.message ?: "Gagal menyalin font")
        }
    }

    /** Load valid imported fonts and preserve their original user-facing names. */
    fun loadAll(context: Context): List<FontItem> {
        return fontFiles(context)
            .sortedBy { it.name.lowercase() }
            .mapNotNull { file ->
                runCatching {
                    val display = displayNameFile(file)
                        .takeIf { it.isFile }
                        ?.readText(Charsets.UTF_8)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: file.nameWithoutExtension
                    FontItem(
                        displayName = display,
                        typeface = Typeface.createFromFile(file),
                        isComic = false,
                        isCustom = true,
                        fileName = file.name,
                        folder = "Imported"
                    )
                }.getOrNull()
            }
    }

    fun findFileByDisplayName(context: Context, displayName: String): File? {
        val target = displayName.trim().lowercase()
        return fontFiles(context).firstOrNull { file ->
            val storedName = runCatching {
                displayNameFile(file).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()
            }.getOrNull()
            (storedName ?: file.nameWithoutExtension).trim().lowercase() == target
        }
    }

    private fun fontFiles(context: Context): List<File> =
        fontsDir(context).listFiles { file ->
            file.isFile && file.extension.lowercase() in supportedExtensions
        }?.toList().orEmpty()

    fun delete(context: Context, fileName: String) {
        val file = File(fontsDir(context), fileName)
        displayNameFile(file).delete()
        file.delete()
    }
}
