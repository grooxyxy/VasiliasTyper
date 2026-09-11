# VasiliasTyper — v11.3 Style Manager & Preset SFX Rombak Total

Tanggal: 2026-08-13
Status build: BUILD SUCCESSFUL (processDebugResources + compileDebugKotlin, EXIT 0)
Lingkup: UI + fitur Style Manager & Preset Browser SFX Webtoon.

## Diagnosis akar masalah crash
1. filterStylesInDialog membaca spinnerFilterFolder.selectedItem sebelum adapter
   spinner terpasang -> null, lalu cast adapter tanpa pengaman -> force close saat
   dialog dibuka dalam kondisi tertentu.
2. updateList() dipanggil saat adapter sedang diganti ke adapter preset (atau null)
   -> refresh style merusak daftar preset di tengah render (Window/RecyclerView crash).
3. Preview bitmap RecyclerView + notifyDataSetChanged saat scroll cepat ->
   race "Canvas: trying to use a recycled bitmap" dan risiko OOM.
4. Double-tap tombol "Pakai" saat bottom sheet sedang dismiss -> dua apply berjalan
   bersamaan (crash intermiten di perangkat lambat).
5. onDelete memakai styles.indexOf(style) yang bisa -1 bila list berubah antara
   bind dan tap.

## Rombakan Fitur (hardening anti-crash)
- filterStylesInDialog ditulis ulang: null-safe penuh (adapter + spinner + query),
  hanya menyentuh adapter bila benar StyleAdapter, dan mendukung filter teks.
- StyleAdapter.bindStyle: tombol Pakai di-nonaktifkan 400ms setelah tap untuk
  mencegah double-apply saat transisi sheet.
- onDelete kini memakai indexOfFirst berpasangan (name+folder) dengan hasil -1
  yang aman, menggantikan indexOf yang rapuh.
- loadPreviewAsync menambahkan verifikasi isAttachedToWindow + runCatching pada
  setImageBitmap di UI thread (mencegah crash recycled bitmap).
- Header folder memakai drawable tema + ripple via TypedValue.resolveAttribute
  (aman sebelum attach), menggantikan hex hardcoded.

## Fitur baru
- Live search: etStyleSearch memfilter nama/preview/kategori secara langsung.
- Empty state: tvStyleEmpty menampilkan tampilan "kosong" yang didesain, bukan
  panel kosong. Muncul juga saat data style gagal dibaca.

## Rombakan UI
- dialog_style_manager.xml: header lebih tegas (letter-spacing -0.01, subtitle
  lebih lega), tombol preset memakai aksen utama, search bar baru, empty state baru.
  RecyclerView dipertinggi 340->360dp, overScrollMode=never.
- item_style.xml: beralih ke MaterialCardView (konsisten dengan kartu lain),
  tombol Pakai aksen + teks gelap, tombol hapus tint bahaya, desc dibatasi 2 baris.
- Drawable diselaraskan ke palet Luma Deck: style_item_bg, style_preview_frame,
  dan style_folder_header_bg (baru) kini memakai @color tema (bukan hex lama).

## Catatan
- Semua ID view lama dipertahankan -> binding & pemanggil lain tidak rusak.
- Tidak ada perubahan pada logika penyimpanan (StyleManager/WebtoonSfxPresets).
- Build terverifikasi: EXIT 0 tanpa error baru (warning deprecated sudah ada sebelumnya).
