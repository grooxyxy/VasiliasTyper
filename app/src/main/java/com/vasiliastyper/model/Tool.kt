package com.vasiliastyper.model

enum class Tool {
    PAN,              // Pan / scroll the canvas (no element interaction)
    MOVE,             // Move/resize selected text element or image element
    RECT_SELECT,
    FREE_SELECT,
    MAGIC_WAND,
    BUBBLE_CLEAN,     // Auto Clean Bubble — one-tap BFS+scanline speech-bubble eraser
    BUBBLE_TRANSLATE, // Bubble Translation — select area then OCR+translate+fill
    BRUSH,            // Regular colour brush with size, opacity, and hardness
    CONTENT_AWARE_BRUSH, // Paint a mask, then reconstruct it with the PhotoDemon/Resynthesizer pipeline
    BRUSH_INPAINT,    // Brush Inpaint — OpenCV Telea / Navier-Stokes (Aditya5052/Image_Inpainting)
    REMOVR,           // RemovR — Resynthesizer heal-selection (light-and-ray/resynthesizer-python-lib)
    CLONE_STAMP,      // Sample a frozen source, then clone it with a soft aligned brush
    BLEMISH_REMOVAL,  // Two-tap healing brush: target blemish, then clean source
    TEXT,
    TEXT_SHAPER,      // Select an already-placed text layer and reshape its word wrapping
    MESH_FORM,        // Mesh form warp for active text
    TEXT_ERASE,       // Erase text elements (brush over to delete; long-press TEXT tool)
    ADD_IMAGE,
    REFERENCE_WINDOW, // Open floating reference gallery
    WATERMARK,        // Auto Add Watermark — tile watermark text over canvas
    CROP,             // Crop canvas to selection
    ZOOM
}
