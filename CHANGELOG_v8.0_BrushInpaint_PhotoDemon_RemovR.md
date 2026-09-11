# v8.0 — Brush Inpaint + PhotoDemon Content-Aware + RemovR

## File BARU
- `engine/ResynthesizerEngine.kt` — port Kotlin dari pdInpaint (PhotoDemon, BSD) =
  implementasi algoritma Resynthesizer (Paul Harrison) — ekuivalen open-source
  terdekat Content-Aware Fill Photoshop. Juga dipakai fitur RemovR (heal-selection).
- `engine/BrushInpainter.kt` — inpaint brush OpenCV Telea / Navier-Stokes
  (metode Aditya5052/Image_Inpainting) dengan pemilihan metode otomatis.
- `res/drawable/ic_brush_inpaint.xml`, `res/drawable/ic_removr.xml` — ikon tool.

## File DIROMBAK
- `engine/ContentAwareFillEngine.kt` — pipeline kandidat kini diprioritaskan:
  Resynthesizer full-quality → Resynthesizer cepat → PatchMatch → Telea.
  API publik (fill/Result) tidak berubah → semua call site lama otomatis ikut baru.
- `model/Tool.kt` — tambah BRUSH_INPAINT, REMOVR.
- `view/CanvasView.kt` — handler touch + overlay mask + kursor untuk 2 tool baru.
- `MainActivity.kt` — wiring tombol + tooltip + options bar.
- `res/layout/activity_main.xml` — tombol toolBrushInpaint & toolRemovR.

## Validasi
- `./gradlew :app:compileDebugKotlin` → BUILD SUCCESS (tanpa error)
- `./gradlew :app:processDebugResources` → BUILD SUCCESS (tanpa error)
