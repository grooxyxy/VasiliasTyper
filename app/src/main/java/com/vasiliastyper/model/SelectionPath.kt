package com.vasiliastyper.model

import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region

enum class SelectionType {
    NONE, RECT, OVAL, FREE, MAGIC_WAND
}

enum class SelectionCombineMode {
    REPLACE, ADD, SUBTRACT
}

data class SelectionState(
    var type: SelectionType = SelectionType.NONE,
    var rect: RectF = RectF(),
    var path: Path = Path(),
    var region: Region? = null,
    var isActive: Boolean = false,
    /** Individual area bounds that were selected separately, kept in draw order. */
    var parts: MutableList<RectF> = mutableListOf(),
    /** Exact region for every committed part. This prevents lasso/wand deletion from
     *  accidentally leaving invisible selected pixels inside a removed bounding box. */
    var partRegions: MutableList<Region> = mutableListOf(),
    /** True when the region was built by manually unioning box/free selections (not magic wand).
     *  Controls whether fill should overwrite ALL pixels vs skip dark ink pixels. */
    var isManualUnion: Boolean = false
) {
    fun clear() {
        type = SelectionType.NONE
        rect.setEmpty()
        path.reset()
        region = null
        isActive = false
        parts.clear()
        partRegions.clear()
        isManualUnion = false
    }

    fun hasSelection() = isActive && (type != SelectionType.NONE)

    val isRoundBubble: Boolean
        get() = type == SelectionType.OVAL

    /**
     * Returns the bounding rectangle of the active selection.
     * Used by "Center in Bubble" to determine where to place text.
     */
    fun getBounds(): RectF = when {
        region != null -> {
            val r = region?.bounds
            if (r == null) RectF() else RectF(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
        }
        parts.isNotEmpty() -> {
            val left = parts.minOf { it.left }
            val top = parts.minOf { it.top }
            val right = parts.maxOf { it.right }
            val bottom = parts.maxOf { it.bottom }
            RectF(left, top, right, bottom)
        }
        !rect.isEmpty -> RectF(rect)
        else          -> RectF()
    }

    /**
     * Returns each selected area separately when the user built a multi-area selection.
     * Falls back to the single active bounds if no separate parts were recorded.
     */
    fun getAreas(): List<RectF> = when {
        parts.isNotEmpty() -> parts.map { RectF(it) }
        !rect.isEmpty -> listOf(RectF(rect))
        region != null -> listOf(getBounds())
        else -> emptyList()
    }

    fun addPart(area: RectF, exactRegion: Region? = null) {
        if (area.width() <= 0f || area.height() <= 0f) return
        parts.add(RectF(area))
        partRegions.add(
            exactRegion?.let(::Region) ?: Region(
                area.left.toInt(),
                area.top.toInt(),
                area.right.toInt(),
                area.bottom.toInt()
            )
        )
    }

    private fun rebuildRegionFromParts() {
        if (partRegions.isEmpty()) {
            region = null
            return
        }
        region = Region().also { merged ->
            partRegions.forEach { merged.op(it, Region.Op.UNION) }
        }
    }

    /**
     * Subtracts a region from the current selection and keeps the model in sync.
     * Returns true when anything changed.
     */
    fun subtractRegion(candidate: Region): Boolean {
        if (candidate.isEmpty || (!isActive && region == null && partRegions.isEmpty())) return false

        var changed = false

        if (partRegions.isNotEmpty()) {
            val newParts = mutableListOf<RectF>()
            val newPartRegions = mutableListOf<Region>()

            partRegions.forEachIndexed { index, part ->
                val remaining = Region(part)
                remaining.op(candidate, Region.Op.DIFFERENCE)
                if (!remaining.isEmpty) {
                    newPartRegions.add(remaining)
                    val bounds = remaining.bounds
                    newParts.add(RectF(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()))
                } else {
                    changed = true
                }
            }

            if (newPartRegions.size != partRegions.size) changed = true

            partRegions.clear()
            partRegions.addAll(newPartRegions)
            parts.clear()
            parts.addAll(newParts)

            if (partRegions.isEmpty()) {
                clear()
                return true
            }

            rebuildRegionFromParts()
            rect.set(getBounds())
            isActive = true
            if (type == SelectionType.NONE) type = SelectionType.RECT
            isManualUnion = true
            return true
        }

        val current = region ?: return false
        val before = Region(current)
        if (!current.op(candidate, Region.Op.DIFFERENCE)) {
            return false
        }
        changed = !current.isEmpty || !before.isEmpty
        if (current.isEmpty) {
            clear()
        } else {
            rect.set(getBounds())
            isActive = true
        }
        return changed
    }

    /** Remove the top-most exact selection area containing the point. */
    fun removeAreaAt(x: Float, y: Float): Boolean {
        val px = x.toInt()
        val py = y.toInt()
        val exactIndex = partRegions.indexOfLast { it.contains(px, py) }
        if (exactIndex >= 0) {
            partRegions.removeAt(exactIndex)
            if (exactIndex in parts.indices) parts.removeAt(exactIndex)
            if (partRegions.isEmpty()) {
                clear()
            } else {
                rebuildRegionFromParts()
                rect.set(getBounds())
                isActive = true
                type = SelectionType.MAGIC_WAND
            }
            return true
        }

        if (region?.contains(px, py) == true || (!rect.isEmpty && rect.contains(x, y))) {
            clear()
            return true
        }
        return false
    }

}
