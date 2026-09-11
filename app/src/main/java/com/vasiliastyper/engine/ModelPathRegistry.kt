package com.vasiliastyper.engine

import android.content.Context
import java.io.File

/**
 * Centralized versioned storage path for downloadable/bundled models.
 *
 * Each model uses its own versioned subfolder so we can change runtime/model
 * compatibility without forcing users to clear app data.
 */
object ModelPathRegistry {
    private const val MODELS_ROOT = "models"

    fun versionedFile(
        context: Context,
        modelKey: String,
        modelVersion: Int,
        fileName: String,
    ): File {
        val dir = File(context.filesDir, "$MODELS_ROOT/$modelKey/v$modelVersion")
        dir.mkdirs()
        return File(dir, fileName)
    }

    fun versionedAssetPath(
        modelKey: String,
        modelVersion: Int,
        fileName: String,
    ): String = "$MODELS_ROOT/$modelKey/v$modelVersion/$fileName"
}
