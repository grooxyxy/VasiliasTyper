package com.vasiliastyper.model

data class Workspace(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "Untitled",
    var width: Int = 800,
    var height: Int = 1200,
    val layers: MutableList<Layer> = mutableListOf(),
    val layerFolders: MutableList<LayerFolder> = mutableListOf(),
    var activeLayerIndex: Int = 0,
    var filePath: String? = null,
    var isDirty: Boolean = false
) {
    fun syncFolderVisibility() {
        val visibility = layerFolders.associate { it.id to it.isVisible }
        layers.forEach { layer ->
            layer.isFolderVisible = layer.folderId?.let { visibility[it] } ?: true
            if (layer.folderId != null && layer.folderId !in visibility) layer.folderId = null
        }
    }
}
