package com.vasiliastyper.adapter

import android.graphics.Typeface

data class FontItem(
    val displayName: String,
    val typeface: Typeface,
    val isComic: Boolean = false,
    val isCustom: Boolean = false,
    val fileName: String = "",
    /** Logical bank folder. Asset subdirectories are preserved verbatim. */
    val folder: String = "All Fonts"
)
