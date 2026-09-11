package com.vasiliastyper.engine

import android.graphics.Bitmap
import android.graphics.Region

/**
 * NavierStokesInpainter — legacy facade kept for backward compatibility.
 *
 * v10 NOTE:
 * Nama historis "Navier-Stokes" dipertahankan agar semua call site lama tetap
 * kompil, tetapi engine di belakangnya BUKAN lagi cv2.inpaint Telea /
 * Navier-Stokes. Facade ini sekarang mendelegasikan ke
 * [OpenCvPatchInpainter] (patch synthesis + seamless clone native OpenCV),
 * dengan fallback pure-Kotlin [CriminisiEngine] → [CustomPdeInpainter] bila
 * library native OpenCV tidak tersedia.
 *
 * Keuntungan untuk halaman manga/webtoon panjang:
 *  • bekerja pada ROI crop — tidak ada Mat/bitmap sebesar halaman penuh;
 *  • kualitas content-aware (tekstur disalin, bukan di-blur PDE);
 *  • fallback bertingkat → tidak pernah force-close.
 *
 * Parameter [algorithm] mempertahankan nilai flag OpenCV (0 = NS, 1 = Telea)
 * hanya untuk kompatibilitas tanda tangan; keduanya kini memetakan ke engine
 * patch modern (AUTO).
 */
object NavierStokesInpainter {

    /** OpenCV-compatible flags (cv2.INPAINT_NS / cv2.INPAINT_TELEA). */
    const val INPAINT_NS = 0
    const val INPAINT_TELEA = 1

    fun inpaintInPlace(
        bitmap: Bitmap,
        region: Region,
        onProgress: ((Float) -> Unit)? = null,
        algorithm: Int = INPAINT_NS
    ): Boolean {
        if (region.isEmpty) return false
        if (!bitmap.isMutable) return false

        return try {
            BrushInpainter.inpaint(
                bitmap = bitmap,
                region = region,
                method = BrushInpainter.Method.AUTO,
                onProgress = onProgress
            ).success
        } catch (oom: OutOfMemoryError) {
            oom.printStackTrace(); false
        } catch (t: Throwable) {
            t.printStackTrace(); false
        }
    }
}
