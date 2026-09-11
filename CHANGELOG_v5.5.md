# VasiliasTyper v5.5 — Perbaikan & Pembersihan

Ringkasan perubahan pada rilis ini (oleh perbaikan otomatis).

## 1. Perbaikan crash pada gambar besar (mis. 800×1600) — Masking
- **`AndroidManifest.xml`**: `android:largeHeap` diubah `false` → **`true`**, dan ditambahkan
  `android:hardwareAccelerated="true"`. Ini penyebab utama Out-Of-Memory saat memproses
  gambar besar / webtoon panjang.
- **`MlKitMaskDetector.kt`**: `preprocess()` ditulis ulang.
  - Dulu: gambar kecil di-*upscale* ×2/×3 dan gambar besar tetap full-size, lalu membuat
    **2–3 salinan bitmap ARGB_8888 penuh** + salinan internal ML Kit → OOM.
  - Sekarang: ada **batas sisi terpanjang `MAX_DETECT_SIDE = 2048`** (gambar besar
    di-*downscale*), hanya **1 bitmap tambahan** yang dialokasikan, koordinat hasil
    deteksi dipetakan kembali ke skala asli, dan bitmap deteksi **di-recycle** setelah
    semua recognizer selesai. Ditambah penanganan `OutOfMemoryError`.
- **`CanvasView.compositeVisibleLayers()`**: menambah retry sekali setelah `System.gc()`
  bila terjadi OOM, agar tidak langsung mengembalikan null/crash.

## 2. Selection tools
- **`MagicWandSelector.kt`**: memperbaiki potensi **rekursi tak terbatas / StackOverflow**
  pada `selectInternalCropped()` ketika `Bitmap.createBitmap` gagal pada gambar sangat besar
  (dulu memanggil ulang `selectInternal` dengan bitmap oversized yang sama). Sekarang
  mengembalikan `Region()` kosong dengan aman.

## 3. Fill White / Fill Black (panel Auto Mask)
- **`MainActivity.runMaskBubbleClean()`** ditulis ulang menjadi **hybrid**:
  1. Coba `BubbleCleaner.clean` (flood-fill presisi mengikuti kontur balon) per region.
  2. Bila gagal (mis. SFX/teks di luar balon, atau titik tengah jatuh di garis tepi/teks),
     **jatuh kembali ke pengisian persegi langsung** pada rect region.
  - Dulu hanya bubble-fill; region yang gagal dilewati diam-diam sehingga terlihat "rusak".

## 4. Inpainting
- Diverifikasi memakai jalur **`Inpainter.inpaintInPlace`** (algoritmik, hemat memori,
  hanya memproses sub-region) ketika model `migan.onnx` tidak tersedia — aman untuk gambar
  besar. `MiGanInpainter` sudah berbasis crop bbox + watchdog memori.

## 5. Bubble cleaning
- **`BubbleCleaner.kt`**: `MAX_CROP_DIM` diturunkan `3000` → **`2200`** untuk menekan
  alokasi puncak pada gambar besar; jalur crop `dstCrop` dirapikan (menghapus
  `getPixels` ganda yang sia-sia).

## 6. Pembersihan kode mati (dihapus — tidak terpakai sama sekali)
File berikut tidak direferensikan di mana pun (atau hanya saling mereferensi dalam klaster
mati LaMa lama yang sudah digantikan MI-GAN):

- `ComicTextDetector.kt`
- `ExportSettings.kt`
- `FeatheredFiller.kt`
- `GeminiMaskDetector.kt`
- `LaMaMaskFix.kt`
- `LargeImageCleaner.kt`
- `MemoryGuard.kt`
- `OcrEngine.kt`
- `PixelPatchHistory.kt`
- `LamaInpainter.kt`        (digantikan `MiGanInpainter`)
- `LamaModelManager.kt`     (hanya dipakai `LamaInpainter`)
- `MaskRefiner.kt`          (hanya dipakai `LamaInpainter`)
- `WebtoonRefiner.kt`       (digantikan `WebtoonContextEnhancer`)
- `StripeProcessor.kt`      (hanya dipakai `LargeImageCleaner`)
- `assets/lama_model_README.txt` (sisa LaMa)

Total: 14 file Kotlin + 1 asset dihapus. Proyek tetap **compile & build sukses** (APK debug).

## Build
- Diuji build dengan: Gradle 8.4, AGP 8.1.0, Kotlin 1.9.0, JDK 17, compileSdk 34.
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**, menghasilkan `app-debug.apk`.
