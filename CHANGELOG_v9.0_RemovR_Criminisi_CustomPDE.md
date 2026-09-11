# v9.0 — RemovR Black-Outline Fix + Criminisi Content-Aware + Custom PDE Brush Inpaint

Rombakan tiga fitur utama untuk manga/webtoon translation (remove object & text dari
halaman panjang 800 × 20 000+ px). Semua engine tetap memory-safe untuk gambar
webtoon tinggi via adaptive ROI cropping.

---

## 1) PERBAIKAN: RemovR — outline hitam setelah erase (sesuai screenshot)

### Root cause (dianalisis dari kode v8)
- Soft brush menghapus piksel dengan `PorterDuff.Mode.DST_OUT` + `BlurMaskFilter`,
  menghasilkan **gradien alpha** di tepi stroke: inti alpha 0, pinggiran alpha 16–254.
- `ResynthesizerEngine.healSelection()` lama hanya menandai `alpha < 16` sebagai
  unknown → **piksel semi-transparan (gelap/premultiplied) dianggap sumber tekstur
  yang sah** dan disalin masuk ke area isian.
- `writeBack()` lama mempertahankan alpha asli piksel fringe → sisa gelap
  premultiplied tampil sebagai **outline hitam** mengikuti bentuk objek yang dihapus.
- Fringe blur juga bisa tumpah 1–3 px **ke luar** region brush dan tidak tersentuh
  write-back sama sekali.

### Perbaikan (`engine/ResynthesizerEngine.kt`)
1. **Mask alpha threshold dinaikkan 16 → 128**: setiap piksel yang pernah disentuh
   eraser (alpha < 128) dianggap unknown, bukan sumber.
2. **Dilasi mask 2 px (4-connected)**: seluruh fringe feather brush masuk ke region
   unknown sehingga tidak bisa bocor sebagai sumber tekstur.
3. **Write-back alpha-aware 3 tingkat**:
   - `alpha < 16` (lubang penuh) → isi opaque penuh.
   - `16 ≤ alpha < 255` (fringe) → warna hasil sintesis di-blend dengan warna lama
     sebanding sisa alpha, lalu **dipaksa opaque** → residu gelap hilang total.
   - `alpha = 255` → perilaku lama (warna baru, alpha utuh).
4. **Write region diperluas 3 px** (`expandRegion`) khusus mode heal: fringe yang
   tumpah keluar garis brush ikut dibersihkan, sementara piksel yang tidak pernah
   disentuh eraser dibiarkan utuh.
5. **CanvasView.applyRemovRMask**: parameter search diperlebar (radius 220,
   neighbors 24, candidates 80) + **fallback otomatis ke CriminisiEngine** bila
   Resynthesizer gagal menemukan tekstur.

---

## 2) ROMBAK: Content-Aware Fill → metode Criminisi et al. (2004)

### File baru: `engine/CriminisiEngine.kt`
Implementasi faithful dari paper:

> A. Criminisi, P. Pérez, K. Toyama — *"Region Filling and Object Removal by
> Exemplar-Based Image Inpainting"*, IEEE TIP Vol. 13 No. 9, 2004.
> (https://www.irisa.fr/vista/Papers/2004_ip_criminisi.pdf)

Referensi implementasi: **github.com/adl1995/image-eraser** (Python, paper yang sama).

Inti algoritma (persis paper §3):
1. Ekstrak fill front δΩ (batas region target).
2. Prioritas tiap piksel front: **P(p) = C(p) · D(p)**
   - `C(p)` confidence — proporsi piksel known di patch sekitar p.
   - `D(p)` data term — kekuatan isophote (gradien ⊥) yang menabrak front,
     sehingga **garis tinta & tepi screentone dilanjutkan lebih dulu** sebelum
     area datar diisi.
3. Patch Ψp̂ berprioritas tertinggi dicari exemplar terbaik Ψq̂ di region sumber
   via SSD berbobot luminansi (tuning manga: dot screentone & garis tinta
   mendominasi jarak patch).
4. Bagian unknown Ψp̂ disalin dari Ψq̂; confidence diperbarui (paper eq. 4).
5. Ulang sampai Ω kosong; seam 1 px di-blend (feather) agar tepi patch hilang
   di stroke manga anti-aliased.

### `engine/ContentAwareFillEngine.kt` — pipeline baru
Urutan kandidat (semua tetap transactional + quality-scored seperti v8):
1. **Criminisi (exemplar)** — primary, structure-first.
2. Resynthesizer (PhotoDemon) — tekstur stokastik (grain kertas).
3. Resynthesizer cepat — area datar luas.
4. PatchMatch multi-scale — pola berulang.
5. Telea (custom FMM) — stroke tipis/gradien.

API publik `fill()`/`Result` **tidak berubah** → semua call site lama
(CanvasView, VasType batch, Unwatermark) otomatis memakai pipeline baru.

---

## 3) ROMBAK: Brush Inpaint → metode OpenCV dibuat dari nol

### File baru: `engine/CustomPdeInpainter.kt`
Re-implementasi murni Kotlin (tanpa `Photo.inpaint`, tanpa native call) dari dua
metode yang sama persis dengan `cv2.inpaint`:

- **TELEA** — Telea 2004, *Fast Marching Method*:
  - Distance map dibangun dengan FMM (binary heap + solver eikonal upwind).
  - Piksel diisi berurutan dari batas ke dalam; tiap piksel = rata-rata berbobot
    tetangga known dengan bobot `dir · dst · lev` (arah, jarak, level-set).
- **NAVIER_STOKES** — Bertalmío et al. 2001:
  - Difusi anisotropik sepanjang isophote (⊥ gradien), bobot menurun saat
    magnitude gradien besar → tepi tetap tajam, gradien halus terisi mulus.

Keunggulan vs binding OpenCV lama:
- Tidak perlu konversi Bitmap↔Mat per tile.
- Tidak bergantung pada inisialisasi native OpenCV untuk path inpaint.
- Berjalan pada ROI crop adaptif → aman untuk halaman webtoon 20 000 px.

### File dirombak
- `engine/BrushInpainter.kt` — delegasi ke `CustomPdeInpainter`; pemilihan
  metode AUTO (tipis/panjang → Telea, blob lebar → NS) dipertahankan.
- `engine/NavierStokesInpainter.kt` — menjadi **facade kompatibel**: signature
  `inpaintInPlace(bitmap, region, onProgress, algorithm)` sama, flag
  `INPAINT_NS`/`INPAINT_TELEA` bernilai sama dengan konstanta OpenCV (0/1),
  tetapi dialihkan ke engine custom. Semua call site lama (MiGanInpainter ×9,
  MainActivity, CanvasView SmartFill) tetap compile tanpa perubahan.

Catatan: `BlemishRemovalEngine` masih memakai `Photo.seamlessClone` (fitur clone,
bukan inpaint) → dependency OpenCV di `build.gradle` tetap diperlukan dan tidak
dihapus.

---

## Ringkasan file

| File | Status |
|---|---|
| `engine/CriminisiEngine.kt` | **BARU** — exemplar-based (paper IRISA 2004) |
| `engine/CustomPdeInpainter.kt` | **BARU** — Telea FMM + Navier-Stokes from scratch |
| `engine/ResynthesizerEngine.kt` | DIROMBAK — fix outline hitam RemovR (4 lapis) |
| `engine/ContentAwareFillEngine.kt` | DIROMBAK — pipeline Criminisi-first |
| `engine/BrushInpainter.kt` | DIROMBAK — pakai CustomPdeInpainter |
| `engine/NavierStokesInpainter.kt` | DIROMBAK — facade → CustomPdeInpainter |
| `view/CanvasView.kt` | PATCH — RemovR params + fallback Criminisi + import |

## Validasi
- `kotlinc` (Kotlin 2.0.21, android-34 + opencv 4.5.3.0 classpath) atas seluruh
  file yang berubah + dependensinya: **0 error, 0 warning**.
- 13 unit-test logika (dilasi mask, solver eikonal FMM, prioritas Criminisi,
  skenario fringe alpha outline hitam, blend write-back): **13/13 PASS**.
- Verifikasi referensi silang: semua call site lama kompatibel, tidak ada
  referensi `Photo.inpaint`/`Photo.INPAINT_*` tersisa di path inpaint.
- Keseimbangan kurung & struktur semua file yang disentuh: OK.
