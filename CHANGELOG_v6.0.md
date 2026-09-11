# VasiliasTyper v6.0 — Changelog

## Perubahan Utama: Penggantian Comic Text Detector

### Masalah dengan Comic Text Detector (versi lama)
- Model ONNX dmMaze ~30 MB harus diunduh terlebih dahulu sebelum bisa digunakan
- Inferensi ONNX lambat di device low-end (tidak ada GPU acceleration)
- Hanya menghasilkan bounding box persegi, bukan mask piksel yang ketat
- Mask yang longgar menyebabkan fill white / inpainting bocor keluar balon

---

## Pipeline Baru: Smart Text Detector (ML Kit + OpenCV)

### Cara Kerja

**Tahap 1 — ML Kit Text Recognition**
- Deteksi cepat per blok teks menggunakan Google ML Kit
- Hardware-accelerated via Google Play Services — sangat cepat di semua device
- Mendukung teks Latin (EN), Chinese (ZH), Korean (KO), auto-detect
- Tidak memerlukan model tambahan atau unduhan internet

**Tahap 2 — OpenCV PreciseTextSegmenter (per region)**
- Untuk setiap bounding box dari ML Kit, jalankan MSER + adaptive threshold
- Menghasilkan mask **pixel-level** yang ketat tepat di tepi stroke huruf
- MSER menangkap: teks gelap, teks putih, teks beroutline, SFX stylized
- Adaptive threshold menangkap: teks ukuran kecil (furigana) hingga besar (SFX)

**Hasil**
- Mask sangat ketat di tepi piksel teks → fill white / inpaint **tidak bocor**
- Fallback otomatis ke OpenCV MSER penuh jika ML Kit tidak menemukan teks

---

## File yang Diubah

| File | Perubahan |
|------|-----------|
| `engine/ComicTextDetector.kt` | Diganti stub — `isAvailable()` selalu false, `detect()` selalu kosong |
| `engine/SmartPixelMaskRefiner.kt` | **BARU** — pipeline ML Kit + OpenCV pixel mask |
| `engine/ModelDownloader.kt` | Hapus Comic Detector dari MODELS, stub API lama tetap ada |
| `engine/PaddleDbNetDetector.kt` | SmartPixelMaskRefiner jadi prioritas 1, OpenCV MSER jadi fallback |
| `MainActivity.kt` | Hapus logika download Comic Detector, update label status |
| `app/build.gradle` | Update versionCode 5→6, versionName 5.0→6.0 |

---

## Keuntungan vs Versi Sebelumnya

| Aspek | Comic Text Detector (lama) | Smart Text Detector (baru) |
|-------|---------------------------|---------------------------|
| Unduhan model | ~30 MB wajib | **Tidak perlu** |
| Kecepatan low-end | Lambat (ONNX CPU) | **Cepat** (ML Kit GPU/NPU) |
| Tipe mask | Bounding box persegi | **Pixel-level ketat** |
| Bocor saat fill | Ya (kotak longgar) | **Tidak** (batas tepat di stroke) |
| Offline | Perlu unduh dulu | **Langsung offline** |
| Teks SFX stylized | Baik | Baik (MSER) |

---

## Catatan Teknis

- `ONNX Runtime` tetap dipertahankan karena digunakan oleh **MI-GAN Inpainting**
- Semua public API `ModelDownloader` dan `ComicTextDetector` tetap ada (stub)
  sehingga tidak ada breaking change pada kode lain
- `SmartPixelMaskRefiner.detectAndRefine()` menggunakan `CountDownLatch`
  untuk menyinkronkan callback async ML Kit ke thread executor
- `PreciseTextSegmenter` dijalankan dalam `BOX_PAD_PX = 6` padding
  di setiap region ML Kit untuk menghindari teks terpotong di tepi kotak
