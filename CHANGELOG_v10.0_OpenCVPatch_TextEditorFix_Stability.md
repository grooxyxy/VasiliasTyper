# Changelog v10.0 — OpenCV Patch Inpaint + Text Editor Fix + Stability

## 1. Brush Inpainting: engine OpenCV baru (BUKAN Telea / Navier-Stokes)
- **File baru: `engine/OpenCvPatchInpainter.kt`** — engine inpaint berbasis OpenCV native (C++)
  yang berbeda total dari `cv2.inpaint` Telea/Navier-Stokes:
  - **PATCH (default)** — exemplar/patch texture synthesis di atas `Mat` OpenCV:
    fill-order prioritas (confidence × data/isophote ala Criminisi), pencarian exemplar
    dengan SSD yang dihitung **native** via `Core.sumElems`, copy patch, lalu feather seam
    via `Imgproc.GaussianBlur` + blend. Tekstur asli **disalin**, bukan di-blur → hasil tajam.
  - **MULTI_SCALE** — patch synthesis pada piramida (`pyrDown`/`pyrUp` + `resize INTER_AREA`)
    untuk mask besar; aman untuk halaman webtoon 800 × 20 000 px (ROI crop, bukan full-page).
  - **SEAMLESS_MIX / SEAMLESS_NORMAL** — `Photo.seamlessClone` (Poisson blending native)
    untuk noda kecil di atas gradasi halus.
  - **AUTO** — memilih metode dari ukuran/bentuk mask secara otomatis.
- **`engine/BrushInpainter.kt`** ditulis ulang menjadi orchestrator berjenjang (anti force-close):
  1. `OpenCvPatchInpainter` (OpenCV native) → 2. `CriminisiEngine` (pure Kotlin) →
  3. `CustomPdeInpainter` (fallback terakhir, selalu tersedia).
- **`engine/NavierStokesInpainter.kt`** kini facade legacy yang mendelegasikan ke engine baru
  (semua call site lama tetap kompil & berfungsi).
- **`model/SmartFillBackend.kt`** — menambah `OPENCV_PATCH`; `NAVIER_STOKES` jadi alias legacy.
- **`view/CanvasView.kt`** — `inpaintRegion()` memakai `BrushInpainter` (OPENCV_PATCH);
  status brush diperbarui ke "OpenCV patch synthesis".
- **Rekomendasi Selection Tools (hasil maksimal):**
  - Fungsi baru `CanvasView.inpaintSelectionWithOpenCv()` mengisi **seleksi persis**
    (Rect / Lasso / Magic Wand) dengan engine patch OpenCV.
  - Tombol **"Inpaint"** baru di bar aksi seleksi (`activity_main.xml`, id `btnInpaintSelection`)
    + handler di `MainActivity`. Alur: seleksi objek → tap "Inpaint" → tekstur direkonstruksi.
  - Tooltip pada tool Content-Aware & Brush Inpaint kini menyarankan selection tools untuk objek besar.

## 2. Content-Aware Fill: kandidat OpenCV Patch
- **`engine/ContentAwareFillEngine.kt`** — menambah kandidat peringkat-atas
  `OpenCvPatchInpainter` (native, cepat + tajam) sebelum Criminisi/Resynthesizer/PatchMatch/Telea.
  Semua kandidat tetap diskor (boundary continuity + texture stability) dan pemenang saja yang
  di-commit; ROI asli dipulihkan bila gagal (transactional, tidak merusak layer).

## 3. Perbaikan Text Editor (delay & crash)
- **`MainActivity.safeShowTextEditorDialogInBounds()`**:
  - Menambah flag `textEditorDialogOpen` + pemeriksaan `activeTextEditorDialog.isShowing`
    → mencegah **dua dialog editor menumpuk** akibat double-tap cepat (sumber utama tampilan
    "delay lalu crash"/ANR).
  - Menambah guard `isFinishing || isDestroyed` → mencegah crash saat membuka dialog setelah
    activity ditutup.
  - Flag selalu dilepas di `onFailure` dan `setOnDismissListener` → tidak pernah macet.
- Daftar font sudah memakai cache (`cachedFontList`) sejak v6.2 — tidak ada lagi disk I/O berulang
  saat editor dibuka, sehingga delay pembukaan hilang.

## 4. Optimasi anti force-close
- Semua engine inpaint baru menangkap `OutOfMemoryError` & `Throwable` → mengembalikan
  `Result` gagal (bukan throw) sehingga pemanggil jatuh ke fallback tanpa crash.
- Desain RAM: pemrosesan hanya pada ROI crop (mask bbox + padding), tidak pernah mengalokasikan
  buffer sebesar halaman penuh; ROI besar otomatis di-downscale.
- `Mat` OpenCV selalu di-`release()` (termasuk di `finally`) → tidak ada native memory leak.
- Crash handler ringan (`setupCrashAutoSave`) tetap aktif; tidak ada serialisasi bitmap berat
  di jalur crash.

## Catatan
- API publik (`BrushInpainter.inpaint`, `ContentAwareFillEngine.fill`, dll.) tidak berubah —
  seluruh call site lama otomatis memperoleh engine baru tanpa modifikasi.
- OpenCV tetap opsional: bila native gagal dimuat, aplikasi otomatis memakai engine pure-Kotlin.
