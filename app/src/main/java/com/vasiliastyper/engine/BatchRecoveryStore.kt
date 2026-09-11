package com.vasiliastyper.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Durable queue/checkpoint storage for the batch page processor.
 *
 * State and images are written with temp-file + rename semantics. A process death can
 * therefore leave at most the currently running stage unfinished; completed pages and
 * the last inpaint checkpoint remain readable on the next launch.
 */
object BatchRecoveryStore {
    const val STATUS_QUEUED = "QUEUED"
    const val STATUS_MASKING = "MASKING"
    const val STATUS_INPAINTING = "INPAINTING"
    const val STATUS_READY_SCRIPT = "READY_SCRIPT"
    const val STATUS_SCRIPTING = "SCRIPTING"
    const val STATUS_COMPLETED = "COMPLETED"
    const val STATUS_FAILED = "FAILED"

    const val OP_CLEAN_ONLY = "CLEAN_ONLY"
    const val OP_PLACE_ALL = "PLACE_ALL"
    const val OP_SCRIPT_OCR = "SCRIPT_OCR"

    data class Page(
        val id: String = UUID.randomUUID().toString(),
        val sourceUri: String,
        val displayName: String,
        var status: String = STATUS_QUEUED,
        var checkpointPath: String? = null,
        var projectId: String? = null,
        var regionCount: Int = 0,
        var regionCursor: Int = 0,
        var error: String? = null
    )

    data class QueueState(
        val pages: MutableList<Page> = mutableListOf(),
        var operation: String = OP_CLEAN_ONLY,
        var scriptCursor: Int = 0,
        var running: Boolean = false,
        var updatedAt: Long = System.currentTimeMillis()
    ) {
        val hasRecoverableWork: Boolean
            get() = pages.any { it.status != STATUS_COMPLETED }
    }

    private fun root(context: Context): File =
        File(context.filesDir, "batch_recovery").also { it.mkdirs() }

    private fun stateFile(context: Context) = File(root(context), "queue.json")

    @Synchronized
    fun load(context: Context): QueueState? {
        val file = stateFile(context)
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(file.readText())
            val pagesJson = json.optJSONArray("pages") ?: JSONArray()
            val pages = mutableListOf<Page>()
            for (index in 0 until pagesJson.length()) {
                val item = pagesJson.getJSONObject(index)
                pages += Page(
                    id = item.optString("id", UUID.randomUUID().toString()),
                    sourceUri = item.getString("sourceUri"),
                    displayName = item.optString("displayName", "page_${index + 1}"),
                    status = normaliseInterruptedStatus(item.optString("status", STATUS_QUEUED)),
                    checkpointPath = item.optString("checkpointPath").takeIf { it.isNotBlank() },
                    projectId = item.optString("projectId").takeIf { it.isNotBlank() },
                    regionCount = item.optInt("regionCount", 0),
                    regionCursor = item.optInt("regionCursor", 0).coerceAtLeast(0),
                    error = item.optString("error").takeIf { it.isNotBlank() }
                )
            }
            QueueState(
                pages = pages,
                operation = json.optString("operation", OP_CLEAN_ONLY),
                scriptCursor = json.optInt("scriptCursor", 0).coerceAtLeast(0),
                running = false,
                updatedAt = json.optLong("updatedAt", 0L)
            )
        }.getOrNull()
    }

    private fun normaliseInterruptedStatus(status: String): String = when (status) {
        STATUS_MASKING -> STATUS_QUEUED
        STATUS_SCRIPTING -> STATUS_READY_SCRIPT
        else -> status
    }

    @Synchronized
    fun save(context: Context, state: QueueState) {
        state.updatedAt = System.currentTimeMillis()
        val pages = JSONArray()
        state.pages.forEach { page ->
            pages.put(JSONObject().apply {
                put("id", page.id)
                put("sourceUri", page.sourceUri)
                put("displayName", page.displayName)
                put("status", page.status)
                put("checkpointPath", page.checkpointPath ?: "")
                put("projectId", page.projectId ?: "")
                put("regionCount", page.regionCount)
                put("regionCursor", page.regionCursor)
                put("error", page.error ?: "")
            })
        }
        val json = JSONObject().apply {
            put("version", 1)
            put("operation", state.operation)
            put("scriptCursor", state.scriptCursor)
            put("running", state.running)
            put("updatedAt", state.updatedAt)
            put("pages", pages)
        }
        atomicWrite(stateFile(context), json.toString())
    }

    fun replaceQueue(
        context: Context,
        uris: List<Uri>,
        names: List<String>,
        operation: String
    ): QueueState {
        clear(context)
        val state = QueueState(
            pages = uris.mapIndexed { index, uri ->
                Page(sourceUri = uri.toString(), displayName = names.getOrNull(index) ?: "page_${index + 1}")
            }.toMutableList(),
            operation = operation
        )
        save(context, state)
        return state
    }

    fun saveCheckpoint(context: Context, page: Page, bitmap: Bitmap): String {
        val destination = File(root(context), "page_${page.id}.png")
        val temporary = File(root(context), ".${destination.name}.tmp")
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "PNG checkpoint tidak dapat ditulis"
            }
            output.fd.sync()
        }
        if (destination.exists() && !destination.delete()) {
            throw IllegalStateException("Checkpoint lama tidak dapat diganti")
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
        page.checkpointPath = destination.absolutePath
        return destination.absolutePath
    }

    fun loadCheckpoint(page: Page): Bitmap? {
        val path = page.checkpointPath ?: return null
        val file = File(path)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        })
    }

    @Synchronized
    fun clear(context: Context) {
        root(context).listFiles()?.forEach { it.delete() }
    }

    private fun atomicWrite(destination: File, value: String) {
        val temporary = File(destination.parentFile, ".${destination.name}.tmp")
        temporary.writeText(value)
        if (destination.exists() && !destination.delete()) {
            throw IllegalStateException("State batch lama tidak dapat diganti")
        }
        if (!temporary.renameTo(destination)) {
            temporary.copyTo(destination, overwrite = true)
            temporary.delete()
        }
    }
}
