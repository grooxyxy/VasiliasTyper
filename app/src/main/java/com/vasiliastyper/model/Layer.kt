package com.vasiliastyper.model

import android.graphics.Bitmap
import android.graphics.PorterDuff

data class Layer(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String = "Layer",
    var bitmap: Bitmap,
    var isVisible: Boolean = true,
    var isLocked: Boolean = false,
    var opacity: Int = 100,
    var blendMode: PorterDuff.Mode = PorterDuff.Mode.SRC_OVER,
    var isClippingMask: Boolean = false,
    var alphaMask: Bitmap? = null,
    var isAlphaMaskEnabled: Boolean = false,
    var folderId: String? = null,
    /** Runtime mirror of the parent folder visibility; persisted by the folder. */
    var isFolderVisible: Boolean = true
) {
    val isEffectivelyVisible: Boolean
        get() = isVisible && isFolderVisible

    val hasAlphaMask: Boolean
        get() = alphaMask?.let { !it.isRecycled } == true

    /**
     * Creates an editable grayscale mask from this layer's current alpha channel.
     * Existing appearance is preserved because transparent pixels become black and
     * opaque pixels become white.
     */
    fun createAlphaMaskFromLayer() {
        val width = bitmap.width
        val height = bitmap.height
        val source = IntArray(width * height)
        bitmap.getPixels(source, 0, width, 0, 0, width, height)
        val maskPixels = IntArray(source.size) { index ->
            val alpha = source[index] ushr 24 and 0xFF
            android.graphics.Color.argb(alpha, 255, 255, 255)
        }
        val replacement = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        replacement.setPixels(maskPixels, 0, width, 0, 0, width, height)
        alphaMask?.let { if (!it.isRecycled) it.recycle() }
        alphaMask = replacement
        isAlphaMaskEnabled = true
    }

    fun removeAlphaMask() {
        alphaMask?.let { if (!it.isRecycled) it.recycle() }
        alphaMask = null
        isAlphaMaskEnabled = false
    }
    /** Exact duplicate — same fields, NEW uuid, copied bitmap. */
    fun duplicate(): Layer = Layer(
        name      = "$name Copy",
        bitmap    = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true),
        isVisible = isVisible,
        isLocked  = false,
        opacity   = opacity,
        blendMode = blendMode,
        isClippingMask = isClippingMask,
        alphaMask = alphaMask?.takeUnless { it.isRecycled }
            ?.copy(Bitmap.Config.ARGB_8888, true),
        isAlphaMaskEnabled = isAlphaMaskEnabled,
        folderId = folderId,
        isFolderVisible = isFolderVisible
    )

    /** Legacy alias kept for backward compatibility. */
    fun clone(): Layer = copy(
        bitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, true),
        alphaMask = alphaMask?.takeUnless { it.isRecycled }
            ?.copy(Bitmap.Config.ARGB_8888, true)
    )
}
