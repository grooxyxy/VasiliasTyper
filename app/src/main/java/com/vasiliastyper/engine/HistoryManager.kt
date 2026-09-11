package com.vasiliastyper.engine

import android.graphics.Bitmap

class HistoryManager(private val maxSteps: Int = 5) {
    private val undoStack = ArrayDeque<Bitmap>()
    private val redoStack = ArrayDeque<Bitmap>()

    fun isEmpty(): Boolean = undoStack.isEmpty()

    fun push(bitmap: Bitmap) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val snapshot = try {
            bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
        } catch (_: OutOfMemoryError) {
            // Editing must remain usable even when the device cannot afford an
            // additional undo snapshot. Existing history is kept intact.
            return
        } catch (_: RuntimeException) {
            return
        }
        undoStack.addLast(snapshot)
        if (undoStack.size > maxSteps) {
            undoStack.removeFirst().recycle()
        }
        redoStack.forEach { if (!it.isRecycled) it.recycle() }
        redoStack.clear()
    }

    fun undo(): Bitmap? {
        if (undoStack.size <= 1) return null
        val current = undoStack.removeLast()
        redoStack.addLast(current)
        return try {
            undoStack.lastOrNull()?.copy(
                undoStack.last().config ?: Bitmap.Config.ARGB_8888,
                true
            )
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun redo(): Bitmap? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(next)
        return try {
            next.copy(next.config ?: Bitmap.Config.ARGB_8888, true)
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun canUndo() = undoStack.size > 1
    fun canRedo() = redoStack.isNotEmpty()

    fun clear() {
        undoStack.forEach { it.recycle() }
        undoStack.clear()
        redoStack.forEach { it.recycle() }
        redoStack.clear()
    }
}
