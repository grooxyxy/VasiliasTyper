package com.vasiliastyper.viewmodel

  import android.graphics.Bitmap
  import android.graphics.Canvas
  import android.graphics.Color
  import android.graphics.PorterDuff
  import androidx.lifecycle.MutableLiveData
  import androidx.lifecycle.ViewModel
  import com.vasiliastyper.engine.BitmapSafety
  import com.vasiliastyper.engine.HistoryManager
  import com.vasiliastyper.engine.LayerCompositor
  import com.vasiliastyper.model.Layer
  import com.vasiliastyper.model.LayerFolder
  import com.vasiliastyper.model.Tool
  import com.vasiliastyper.model.Workspace

  class WorkspaceViewModel : ViewModel() {

      val workspaces           = MutableLiveData<MutableList<Workspace>>(mutableListOf())
      val activeWorkspaceIndex = MutableLiveData(0)
      val currentTool          = MutableLiveData(Tool.PAN)
      val foregroundColor      = MutableLiveData(Color.BLACK)
      val backgroundColor      = MutableLiveData(Color.WHITE)
      val brushSize            = MutableLiveData(20f)
      val brushOpacity         = MutableLiveData(100)
      val tolerance            = MutableLiveData(15)
      val layersChanged        = MutableLiveData(false)

      private val historyMap = mutableMapOf<String, HistoryManager>()

      val activeWorkspace: Workspace?
          get() = workspaces.value?.getOrNull(activeWorkspaceIndex.value ?: 0)

      fun createWorkspace(name: String = "Untitled", width: Int = 800, height: Int = 1200): Workspace {
          val (safeWidth, safeHeight) = BitmapSafety.fitCanvasToDevice(width, height)
          val ws = Workspace(name = name, width = safeWidth, height = safeHeight)
          val bg = try {
              Bitmap.createBitmap(safeWidth, safeHeight, Bitmap.Config.ARGB_8888)
          } catch (_: OutOfMemoryError) {
              // Last-resort canvas keeps the editor responsive instead of killing
              // the process when another app has temporarily reduced free heap.
              Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888).also {
                  ws.width = 800
                  ws.height = 1200
              }
          }
          bg.eraseColor(Color.WHITE)
          ws.layers.add(Layer(name = "Background", bitmap = bg))
          val list = workspaces.value ?: mutableListOf()
          list.add(ws)
          workspaces.value = list
          activeWorkspaceIndex.value = list.size - 1
          // The initial undo bitmap is seeded lazily on the first edit. Copying a
          // multi-megapixel blank canvas here caused the first editor open to stall.
          historyMap[ws.id] = HistoryManager()
          return ws
      }

      fun openImage(bitmap: Bitmap, name: String): Workspace {
          val mutable = if (bitmap.isMutable) bitmap
                        else bitmap.copy(Bitmap.Config.ARGB_8888, true)

          val ws = Workspace(name = name, width = mutable.width, height = mutable.height)
          ws.layers.add(Layer(name = "Image", bitmap = mutable))

          val heapFree   = Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory() +
                           Runtime.getRuntime().freeMemory()
          val layerBytes = mutable.width.toLong() * mutable.height * 4
          if (heapFree > layerBytes * 4L) {
              try {
                  val textLayer = Bitmap.createBitmap(mutable.width, mutable.height, Bitmap.Config.ARGB_8888)
                  ws.layers.add(Layer(name = "Text", bitmap = textLayer))
                  ws.activeLayerIndex = 1
              } catch (_: OutOfMemoryError) {
                  // A separate text layer is optional; keep the imported image open.
              }
          }

          val list = workspaces.value ?: mutableListOf()
          list.add(ws)
          workspaces.value = list
          activeWorkspaceIndex.value = list.size - 1
          // Seed history lazily on first edit so image opening is not blocked by a
          // full-size duplicate allocation before the editor becomes interactive.
          historyMap[ws.id] = HistoryManager()
          return ws
      }

      /**
       * v2.0: Add an already-constructed workspace (e.g. restored from auto-save)
       * to the ViewModel without rebuilding layers from scratch.
       */
      fun addExistingWorkspace(ws: Workspace): Workspace {
          val list = workspaces.value ?: mutableListOf()
          list.add(ws)
          workspaces.value = list
          activeWorkspaceIndex.value = list.size - 1
          historyMap[ws.id] = HistoryManager()
          return ws
      }

      fun addLayer(workspace: Workspace) {
          val bm  = Bitmap.createBitmap(workspace.width, workspace.height, Bitmap.Config.ARGB_8888)
          val idx = workspace.layers.size + 1
          workspace.layers.add(Layer(name = "Layer $idx", bitmap = bm))
          workspace.activeLayerIndex = workspace.layers.size - 1
          layersChanged.value = true
      }

      fun duplicateLayer(workspace: Workspace) {
          val src = workspace.layers.getOrNull(workspace.activeLayerIndex) ?: return
          val copy = src.duplicate()
          val insertAt = workspace.activeLayerIndex + 1
          workspace.layers.add(insertAt, copy)
          workspace.activeLayerIndex = insertAt
          layersChanged.value = true
      }

      /**
       * Delete only the active pixel layer.
       * [onLayerRemoved] exposes the old id so callers can detach stale links;
       * text and image stack items remain independent and must not be deleted.
       */
      fun deleteLayer(workspace: Workspace, onLayerRemoved: ((removedId: String) -> Unit)? = null) {
          if (workspace.layers.size <= 1) return
          val idx = workspace.activeLayerIndex
          if (idx !in workspace.layers.indices) return
          val removed = workspace.layers.removeAt(idx)
          onLayerRemoved?.invoke(removed.id)
          if (!removed.bitmap.isRecycled) removed.bitmap.recycle()
          removed.alphaMask?.let { if (!it.isRecycled) it.recycle() }
          workspace.activeLayerIndex = (idx - 1).coerceAtLeast(0)
          layersChanged.value = true
      }

      fun renameLayer(workspace: Workspace, index: Int, newName: String) {
          if (index !in workspace.layers.indices) return
          workspace.layers[index].name = newName.trim().ifBlank { workspace.layers[index].name }
          layersChanged.value = true
      }

      fun createFolderForActiveLayer(workspace: Workspace, name: String = "Folder"): LayerFolder? {
          val layer = workspace.layers.getOrNull(workspace.activeLayerIndex) ?: return null
          if (workspace.activeLayerIndex == 0) return null
          val folder = LayerFolder(name = name.trim().ifBlank { "Folder" })
          workspace.layerFolders.add(folder)
          layer.folderId = folder.id
          workspace.syncFolderVisibility()
          layersChanged.value = true
          return folder
      }

      fun renameFolder(workspace: Workspace, folderId: String, newName: String) {
          val folder = workspace.layerFolders.firstOrNull { it.id == folderId } ?: return
          folder.name = newName.trim().ifBlank { folder.name }
          layersChanged.value = true
      }

      fun toggleFolderVisibility(workspace: Workspace, folderId: String) {
          val folder = workspace.layerFolders.firstOrNull { it.id == folderId } ?: return
          folder.isVisible = !folder.isVisible
          workspace.syncFolderVisibility()
          layersChanged.value = true
      }

      fun toggleFolderExpanded(workspace: Workspace, folderId: String) {
          val folder = workspace.layerFolders.firstOrNull { it.id == folderId } ?: return
          folder.isExpanded = !folder.isExpanded
          layersChanged.value = true
      }

      fun moveActiveLayerToFolder(workspace: Workspace, folderId: String?) {
          val layer = workspace.layers.getOrNull(workspace.activeLayerIndex) ?: return
          if (workspace.activeLayerIndex == 0) return
          if (folderId != null && workspace.layerFolders.none { it.id == folderId }) return
          layer.folderId = folderId
          workspace.syncFolderVisibility()
          layersChanged.value = true
      }

      fun deleteFolder(workspace: Workspace, folderId: String) {
          if (workspace.layerFolders.removeAll { it.id == folderId }) {
              workspace.layers.filter { it.folderId == folderId }.forEach {
                  it.folderId = null
                  it.isFolderVisible = true
              }
              layersChanged.value = true
          }
      }

      fun toggleClippingMask(workspace: Workspace, index: Int): Boolean {
          val layer = workspace.layers.getOrNull(index) ?: return false
          if (index == 0) return false
          layer.isClippingMask = !layer.isClippingMask
          layersChanged.value = true
          return true
      }

      fun toggleAlphaMask(workspace: Workspace, index: Int): Boolean {
          val layer = workspace.layers.getOrNull(index) ?: return false
          if (!layer.hasAlphaMask) layer.createAlphaMaskFromLayer()
          else layer.isAlphaMaskEnabled = !layer.isAlphaMaskEnabled
          layersChanged.value = true
          return true
      }

      fun removeAlphaMask(workspace: Workspace, index: Int) {
          workspace.layers.getOrNull(index)?.removeAlphaMask() ?: return
          layersChanged.value = true
      }

      // ── v2.0: Merge selected layers (merge active + layer below) ──────────────
      fun mergeDown(workspace: Workspace) {
          if (workspace.layers.size < 2) return
          val idx = workspace.activeLayerIndex
          if (idx !in workspace.layers.indices || idx == 0) return

          val top = workspace.layers.getOrNull(idx) ?: return
          val bottom = workspace.layers.getOrNull(idx - 1) ?: return
          if (top.bitmap.isRecycled || bottom.bitmap.isRecycled) return

          val merged = LayerCompositor.composite(
              listOf(bottom, top), workspace.width, workspace.height
          )

          bottom.name = "${bottom.name}+${top.name}"
          if (!bottom.bitmap.isRecycled) bottom.bitmap.recycle()
          bottom.alphaMask?.let { if (!it.isRecycled) it.recycle() }
          bottom.bitmap = merged
          bottom.opacity = 100
          bottom.blendMode = android.graphics.PorterDuff.Mode.SRC_OVER
          bottom.isClippingMask = false
          bottom.alphaMask = null
          bottom.isAlphaMaskEnabled = false

          workspace.layers.removeAt(idx)
          if (!top.bitmap.isRecycled) top.bitmap.recycle()
          top.alphaMask?.let { if (!it.isRecycled) it.recycle() }
          workspace.activeLayerIndex = (idx - 1).coerceAtLeast(0)
          layersChanged.value = true
      }

      // ── v2.0: Merge ALL visible layers into one ───────────────────────────────
      fun mergeVisible(workspace: Workspace) {
          val visible = workspace.layers.filter { it.isEffectivelyVisible }
          if (visible.size < 2) return
          val result = LayerCompositor.composite(
              visible, workspace.width, workspace.height
          )
          // Remove visible layers and replace with merged
          workspace.layers.filter { it.isEffectivelyVisible }.forEach { layer ->
              if (layer.bitmap !== result && !layer.bitmap.isRecycled) layer.bitmap.recycle()
              layer.alphaMask?.let { if (!it.isRecycled) it.recycle() }
          }
          workspace.layers.removeAll { it.isEffectivelyVisible }
          workspace.layers.add(Layer(name = "Merged", bitmap = result))
          workspace.activeLayerIndex = workspace.layers.size - 1
          layersChanged.value = true
      }

      // ── v2.0: Toggle lock on active layer ─────────────────────────────────────
      fun toggleLock(workspace: Workspace, index: Int) {
          if (index !in workspace.layers.indices) return
          workspace.layers[index].isLocked = !workspace.layers[index].isLocked
          layersChanged.value = true
      }

      fun moveLayerUp(workspace: Workspace) {
          val idx = workspace.activeLayerIndex
          if (idx !in workspace.layers.indices || idx >= workspace.layers.size - 1) return
          val layer = workspace.layers.removeAt(idx)
          workspace.layers.add(idx + 1, layer)
          workspace.activeLayerIndex = idx + 1
          layersChanged.value = true
      }

      fun moveLayerDown(workspace: Workspace) {
          val idx = workspace.activeLayerIndex
          if (idx !in workspace.layers.indices || idx <= 0) return
          val layer = workspace.layers.removeAt(idx)
          workspace.layers.add(idx - 1, layer)
          workspace.activeLayerIndex = idx - 1
          layersChanged.value = true
      }

      fun pushHistory(workspace: Workspace) {
          val idx = workspace.activeLayerIndex
          val bm = workspace.layers.getOrNull(idx)?.bitmap ?: return
          if (bm.isRecycled) return
          val history = historyMap.getOrPut(workspace.id) { HistoryManager() }
          // HistoryManager's undo contract keeps the current snapshot at the top.
          // Two identical pre-edit snapshots on the first edit preserve first Undo,
          // while moving their expensive allocation out of project startup.
          if (history.isEmpty()) history.push(bm)
          history.push(bm)
      }

      fun undo(workspace: Workspace): Bitmap? {
          val bm = historyMap[workspace.id]?.undo() ?: return null
          val idx = workspace.activeLayerIndex
          val old = workspace.layers.getOrNull(idx)?.bitmap ?: return bm
          if (!old.isRecycled) {
              val c = Canvas(old)
              c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
              c.drawBitmap(bm, 0f, 0f, null)
          }
          return bm
      }

      fun redo(workspace: Workspace): Bitmap? {
          val bm = historyMap[workspace.id]?.redo() ?: return null
          val idx = workspace.activeLayerIndex
          val old = workspace.layers.getOrNull(idx)?.bitmap ?: return bm
          if (!old.isRecycled) {
              val c = Canvas(old)
              c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
              c.drawBitmap(bm, 0f, 0f, null)
          }
          return bm
      }

      fun closeWorkspace(index: Int) {
          val list = workspaces.value ?: return
          if (list.size <= 1) return
          if (index !in list.indices) return
          val removed = list.removeAt(index)
          historyMap.remove(removed.id)?.clear()
          removed.layers.forEach {
              if (!it.bitmap.isRecycled) it.bitmap.recycle()
              it.alphaMask?.let { mask -> if (!mask.isRecycled) mask.recycle() }
          }
          val newIdx = index.coerceAtMost(list.size - 1)
          workspaces.value = list
          activeWorkspaceIndex.value = newIdx
      }

      /** Close every editor tab and release its bitmap/history memory. */
      fun closeAllWorkspaces() {
          val list = workspaces.value.orEmpty().toList()
          list.forEach { workspace ->
              historyMap.remove(workspace.id)?.clear()
              workspace.layers.forEach { layer ->
                  if (!layer.bitmap.isRecycled) layer.bitmap.recycle()
                  layer.alphaMask?.let { mask -> if (!mask.isRecycled) mask.recycle() }
              }
          }
          historyMap.values.forEach { it.clear() }
          historyMap.clear()
          workspaces.value = mutableListOf()
          activeWorkspaceIndex.value = 0
      }
  }
