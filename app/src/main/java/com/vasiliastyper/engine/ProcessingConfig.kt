package com.vasiliastyper.engine

/**
 * ProcessingConfig v2.0 — Tuning untuk gambar 800×20000 di low-end device.
 *
 * PERUBAHAN v2.0:
 *  - STRIPE_HEIGHT naik 2000 → 2400 (lebih sedikit stripe untuk 800×20000)
 *  - STRIPE_OVERLAP naik 100 → 150 (kurangi seam artifact)
 *  - TEXT_MASK_CROP_LIMIT_PIXELS naik 2.5M → 4M (kurangi tiling overhead)
 *  - NUM_THREADS naik 2 → sesuai CPU (min 2, max 4)
 *  - LARGE_IMAGE_THRESHOLD: pixel batas kapan BubbleCleaner pakai crop path
 */
object ProcessingConfig {

    // ── Stripe decomposition (very tall images like 800 × 20 000) ────────────
    const val STRIPE_HEIGHT  = 2400   // px per stripe (naik dari 2000)
    const val STRIPE_OVERLAP = 150    // px overlap (naik dari 100, kurangi seam)

    // ── Legacy local inpainting tiling defaults ──────────────────────────────
    const val INPAINT_TILE_SIZE = 384
    const val INPAINT_OVERLAP   = 32

    // ── Memory management ─────────────────────────────────────────────────────
    const val MEMORY_THRESHOLD = 0.75f
    const val GC_INTERVAL      = 4

    // ── Lightweight text detection thresholds ────────────────────────────────
    const val MASK_DILATION_PX     = 4
    const val MIN_TEXT_AREA        = 80

    // Naik dari 2.5M → 4M: crop area lebih besar sebelum tiling → lebih sedikit tile
    const val TEXT_MASK_CROP_LIMIT_PIXELS = 4_000_000L

    // If text detection finds a region much smaller than the selection, prefer it.
    const val TEXT_REGION_MIN_REDUCTION = 0.82f

    // ── Threading — sesuai CPU, min 2 max 4 ───────────────────────────────────
    val NUM_THREADS: Int = Runtime.getRuntime().availableProcessors()
        .coerceAtLeast(2).coerceAtMost(4)

    // ── Asset names ───────────────────────────────────────────────────────────
    const val LAMA_MANGA_ASSET = "models/lama_manga/v1/lama-manga-dynamic.onnx"
}
