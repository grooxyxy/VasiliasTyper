package com.vasiliastyper.model

/**
 * Non-destructive folder metadata for pixel layers.
 *
 * Layer order remains owned by [Workspace.layers], so grouping never changes the
 * canvas z-order. A folder can hide its children without destroying each child's
 * own visibility flag and can be collapsed independently in the layer panel.
 */
data class LayerFolder(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "Folder",
    var isVisible: Boolean = true,
    var isExpanded: Boolean = true
)
