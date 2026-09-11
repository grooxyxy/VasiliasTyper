package com.vasiliastyper.model

enum class SmartFillBackend {
    LAMA_MANGA,
    /** Patch synthesis OpenCV native (bukan Telea/Navier-Stokes) + seamless clone. */
    OPENCV_PATCH,
    AGNES_IMAGE_AI,
    /** Prompt-based masked fill via api.nexray.eu.cc/ai/ideogram. */
    IDEOGRAM_AI,
    /** Legacy alias — dipetakan ke OPENCV_PATCH di semua call site. */
    @Deprecated("Gunakan OPENCV_PATCH", ReplaceWith("OPENCV_PATCH"))
    NAVIER_STOKES
}
