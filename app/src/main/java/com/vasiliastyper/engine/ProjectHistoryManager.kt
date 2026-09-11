package com.vasiliastyper.engine

  import android.content.Context
  import android.graphics.Bitmap
  import android.graphics.BitmapFactory
  import android.util.Base64
  import org.json.JSONArray
  import org.json.JSONObject
  import java.io.ByteArrayOutputStream

  data class ProjectRecord(
      val id:         String,
      val name:       String,
      val width:      Int,
      val height:     Int,
      val dateMs:     Long,
      val type:       String,       // "blank" | "image"
      val sourceUri:  String? = null,
      // v2.0: full snapshot
      val layersJson: String? = null,   // JSON array of layer metadata
      val thumbB64:   String? = null,   // base64 PNG thumbnail (small)
      val folder:     String = DEFAULT_PROJECT_FOLDER,
      val createdAtMs: Long = 0L,
      val lastEditedAtMs: Long = dateMs,
      val workDurationMs: Long = 0L
  )

  const val DEFAULT_PROJECT_FOLDER = "Tanpa Folder"

  object ProjectHistoryManager {

      private const val PREF = "vasilias_history"
      private const val KEY  = "projects"
      private const val MAX  = 100
      private const val FOLDER_PREF = "vasilias_project_folders"
      private const val FOLDER_KEY = "folders"

      fun load(context: Context): List<ProjectRecord> {
          val json = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
              .getString(KEY, "[]") ?: "[]"
          return try {
              val arr = JSONArray(json)
              (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
          } catch (e: Exception) { emptyList() }
      }

      fun add(context: Context, rec: ProjectRecord) {
          val list = load(context).toMutableList()
          val existing = list.firstOrNull { it.id == rec.id }
          val editedAt = rec.lastEditedAtMs.takeIf { it > 0L }
              ?: rec.dateMs.takeIf { it > 0L }
              ?: System.currentTimeMillis()
          val createdAt = existing?.createdAtMs?.takeIf { it > 0L }
              ?: rec.createdAtMs.takeIf { it > 0L }
              ?: rec.dateMs.takeIf { it > 0L }
              ?: editedAt
          val previousEdit = existing?.lastEditedAtMs?.takeIf { it > 0L }
              ?: existing?.dateMs?.takeIf { it > 0L }
          val activeDelta = previousEdit
              ?.let { (editedAt - it).coerceIn(0L, 5 * 60_000L) }
              ?: 0L
          val normalized = rec.copy(
              dateMs = editedAt,
              createdAtMs = createdAt,
              lastEditedAtMs = editedAt,
              workDurationMs = (existing?.workDurationMs ?: rec.workDurationMs) + activeDelta
          )
          list.removeAll { it.id == rec.id }
          list.add(0, normalized)
          if (list.size > MAX) list.subList(MAX, list.size).clear()
          save(context, list)
      }

      fun remove(context: Context, id: String) {
          removeMany(context, setOf(id))
      }

      /** Removes multiple project records in one atomic preference update. */
      fun removeMany(context: Context, ids: Set<String>): Int {
          if (ids.isEmpty()) return 0
          val current = load(context)
          val updated = current.filterNot { it.id in ids }
          val removedCount = current.size - updated.size
          if (removedCount > 0) save(context, updated)
          return removedCount
      }

      fun clear(context: Context) = save(context, emptyList())

      fun folders(context: Context): List<String> {
          val saved = context.getSharedPreferences(FOLDER_PREF, Context.MODE_PRIVATE)
              .getStringSet(FOLDER_KEY, emptySet())
              .orEmpty()
              .map(::normalizeFolderName)
              .filter { it != DEFAULT_PROJECT_FOLDER }
          val used = load(context).map { normalizeFolderName(it.folder) }
              .filter { it != DEFAULT_PROJECT_FOLDER }
          return (saved + used).distinct().sortedBy { it.lowercase() }
      }

      fun createFolder(context: Context, rawName: String): Boolean {
          val name = normalizeFolderName(rawName)
          if (name == DEFAULT_PROJECT_FOLDER) return false
          val updated = folders(context).toMutableSet().apply { add(name) }
          persistFolders(context, updated)
          return true
      }

      fun renameFolder(context: Context, oldName: String, rawNewName: String): Boolean {
          val oldFolder = normalizeFolderName(oldName)
          val newFolder = normalizeFolderName(rawNewName)
          if (oldFolder == DEFAULT_PROJECT_FOLDER || newFolder == DEFAULT_PROJECT_FOLDER) return false
          val updatedRecords = load(context).map {
              if (normalizeFolderName(it.folder) == oldFolder) it.copy(folder = newFolder) else it
          }
          save(context, updatedRecords)
          val updatedFolders = folders(context).toMutableSet().apply {
              remove(oldFolder)
              add(newFolder)
          }
          persistFolders(context, updatedFolders)
          return true
      }

      fun moveProject(context: Context, projectId: String, rawFolder: String) {
          val folder = normalizeFolderName(rawFolder)
          if (folder != DEFAULT_PROJECT_FOLDER) createFolder(context, folder)
          save(context, load(context).map { if (it.id == projectId) it.copy(folder = folder) else it })
      }

      fun projectsInFolder(context: Context, folder: String?): List<ProjectRecord> {
          val records = load(context)
          if (folder == null) return records
          val normalized = normalizeFolderName(folder)
          return records.filter { normalizeFolderName(it.folder) == normalized }
      }

      fun normalizeFolderName(value: String): String {
          return value.trim().replace(Regex("\\s+"), " ").take(48)
              .ifBlank { DEFAULT_PROJECT_FOLDER }
      }

      private fun persistFolders(context: Context, folders: Set<String>) {
          context.getSharedPreferences(FOLDER_PREF, Context.MODE_PRIVATE)
              .edit().putStringSet(FOLDER_KEY, folders.filter { it != DEFAULT_PROJECT_FOLDER }.toSet()).apply()
      }

      /** v2.0: build a thumb base64 from composite bitmap */
      fun buildThumb(composite: Bitmap, maxPx: Int = 120): String {
          val scale = maxPx.toFloat() / maxOf(composite.width, composite.height)
          val w = (composite.width * scale).toInt().coerceAtLeast(1)
          val h = (composite.height * scale).toInt().coerceAtLeast(1)
          val small = Bitmap.createScaledBitmap(composite, w, h, true)
          val bos = ByteArrayOutputStream()
          small.compress(Bitmap.CompressFormat.PNG, 80, bos)
          if (small != composite) small.recycle()
          return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
      }

      /** Decode a stored thumb back to Bitmap without trusting persisted input. */
      fun decodeThumb(b64: String): Bitmap? {
          return try {
              val bytes = Base64.decode(b64, Base64.NO_WRAP)
              if (bytes.isEmpty() || bytes.size > 2_000_000) {
                  null
              } else {
                  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                  BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                  if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                      null
                  } else {
                      val opts = BitmapFactory.Options().apply {
                          inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, 256, 256)
                          inPreferredConfig = Bitmap.Config.RGB_565
                      }
                      BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                  }
              }
          } catch (_: Exception) {
              null
          }
      }

      private fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
          var sample = 1
          var halfW = srcW / 2
          var halfH = srcH / 2
          while ((halfW / sample) >= reqW && (halfH / sample) >= reqH) {
              sample *= 2
          }
          return sample.coerceAtLeast(1)
      }

      private fun save(context: Context, list: List<ProjectRecord>) {
          val arr = JSONArray()
          list.forEach { arr.put(toJson(it)) }
          context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
              .edit().putString(KEY, arr.toString()).apply()
      }

      private fun toJson(r: ProjectRecord) = JSONObject().apply {
          put("id",         r.id)
          put("name",       r.name)
          put("width",      r.width)
          put("height",     r.height)
          put("dateMs",     r.dateMs)
          put("type",       r.type)
          if (r.sourceUri  != null) put("sourceUri",  r.sourceUri)
          if (r.layersJson != null) put("layersJson", r.layersJson)
          if (r.thumbB64   != null) put("thumbB64",   r.thumbB64)
          put("folder", normalizeFolderName(r.folder))
          put("createdAtMs", r.createdAtMs)
          put("lastEditedAtMs", r.lastEditedAtMs)
          put("workDurationMs", r.workDurationMs)
      }

      private fun fromJson(o: JSONObject) = ProjectRecord(
          id         = o.optString("id",    java.util.UUID.randomUUID().toString()),
          name       = o.optString("name",  "Untitled"),
          width      = o.optInt("width",    800),
          height     = o.optInt("height",   1200),
          dateMs     = o.optLong("dateMs",  0L),
          type       = o.optString("type",  "blank"),
          sourceUri  = o.optString("sourceUri",  "").ifEmpty { null },
          layersJson = o.optString("layersJson", "").ifEmpty { null },
          thumbB64   = o.optString("thumbB64",   "").ifEmpty { null },
          folder     = normalizeFolderName(o.optString("folder", DEFAULT_PROJECT_FOLDER)),
          createdAtMs = o.optLong("createdAtMs", o.optLong("dateMs", 0L)),
          lastEditedAtMs = o.optLong("lastEditedAtMs", o.optLong("dateMs", 0L)),
          workDurationMs = o.optLong("workDurationMs", 0L).coerceAtLeast(0L)
      )
  }
  