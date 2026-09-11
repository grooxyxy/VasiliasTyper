# VasiliasTyper Aurora Studio — HiPaint Pro Layout v6.2

## Ringkasan
Rombak **layout & tata letak** (bukan sekadar palet warna) agar lebih nyaman di HP kentang, mengikuti pola HiPaint/Procreate:
chrome tipis, kanvas maksimal, opsi tool di zona jempol.

## Perubahan layout utama
1. **Top bar 40dp** — menu File/Edit/Kanvas/Layer/Efek/View/More ringkas.
2. **Tab strip 30dp** — hemat vertikal.
3. **Options bar dipindah ke BAWAH kanvas** (thumb zone) — perbaikan kenyamanan terbesar.
4. **Left rail 48dp icon-only** — label teks dihilangkan, tool 36dp, lebih banyak tool terlihat tanpa scroll panjang.
5. **Right rail 48dp icon-only** — shortcut Layer/Mask/Script/Style/Compare tetap.
6. **Handle sidebar 18dp** menempel di tepi rail (margin sinkron 48dp di MainActivity).
7. **Status dock 28dp** — Naskah/OCR/Mask tetap.
8. **Bottom panels** (Script/OCR/Mask/UNWM) lebih padat, header 34dp, chip action compact.
9. **Home hub** charcoal flat + footer dock 2 baris agar tidak mepet di layar sempit.
10. **Recent cards** lebih pendek (80dp) agar lebih banyak item terlihat.

## Keamanan fungsional
- **185/185 view ID** di `activity_main.xml` dipertahankan 1:1.
- Home IDs (`cardNewProject`, `cardOpenImage`, `rvRecentProjects`, `emptyState`, `btnClearHistory`, `btnTelegram`, `btnDiscord`, `footerLayout`) aman.
- Dialog IDs (export/font/layers/script/span/style/text editor) **tidak dihapus**.
- Engine, OCR, Mask, Script, UNWM, Bubble tools, CanvasView **tidak disentuh logikanya**.
- Hanya sinkron margin sidebar 52→48 di `MainActivity.kt`.

## Optimasi device kentang
- Chrome vertikal berkurang ~30–40dp → kanvas lebih luas.
- OverScrollMode never di scroll chrome (kurangi overdraw).
- Icon-only rail mengurangi view hierarchy (hapus TextView label per tool).
- Panel bottom full-bleed (tanpa margin 4dp) hemat space & hit target lebih nyaman.
- Tidak ada dependency baru, tidak ada minify change.

## File diubah
- `app/src/main/res/layout/activity_main.xml`
- `app/src/main/res/layout/activity_home.xml`
- `app/src/main/res/layout/item_recent_project.xml`
- `app/src/main/res/layout/dialog_layers.xml` (teks header saja)
- `app/src/main/res/values/colors.xml`
- `app/src/main/res/values/styles.xml`
- `app/src/main/res/values/dimens.xml`
- `app/src/main/res/values/strings.xml`
- `app/src/main/res/drawable/*` (chrome/home/tool)
- `app/src/main/java/com/vasiliastyper/MainActivity.kt` (margin sidebar 48dp)
