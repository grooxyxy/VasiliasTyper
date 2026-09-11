package com.vasiliastyper.model

/**
 * A lightweight reference used to keep pixel layers, image elements and text
 * elements in one shared z-order. The list is stored bottom-to-top.
 */
data class CanvasStackItem(
    val type: CanvasStackType,
    val id: String
)

enum class CanvasStackType {
    PIXEL,
    IMAGE,
    TEXT
}
