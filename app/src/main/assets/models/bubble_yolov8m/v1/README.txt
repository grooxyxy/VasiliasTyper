BUBBLE DETECTOR YOLOv8m (MODEL UTAMA — TIDAK DISERTAKAN DALAM SOURCE)

File:
  comic-speech-bubble-detector.onnx (~99MB)

Sumber (Drive milik user, publik "Anyone with the link"):
  https://drive.google.com/file/d/13B42NV0mPPBzUUVsIvD4SvE3QXEaLOlv/view

Cara menyediakan model (pilih satu):
  a) Otomatis saat build (disarankan, dipakai GitHub Actions):
     task Gradle `bundleBubbleModel` mengunduh dari Drive default lalu
     membundle ke APK. Validasi ukuran >50MB menolak HTML/error-page.
     Override bila perlu:
       gradle :app:assembleDebug -PBUBBLE_MODEL_PATH=/lokal/model.onnx
       gradle :app:assembleDebug -PBUBBLE_MODEL_URL=https://.../bubble.onnx
  b) Manual: letakkan file dengan nama persis di folder ini.

Kontrak ONNX:
  input   float32 [1,3,640,640], RGB 0..1 (letterbox pad 114)
  output  float32 [1,6,8400] = cx,cy,w,h + 2 skor (mentah, tanpa sigmoid)
  conf 0.30, NMS IoU 0.45, tiling strip 1200/overlap 300 + NMS global.

Model lain (LaMa Manga, PP-OCR, ComicTextDetector) tetap dari Hugging Face
sesuai README tiap folder assets/models — tidak dibundle ke APK.

Bila model hilang/rusak/tidak kompatibel, detector mengembalikan daftar kosong
dan aplikasi memakai fallback lokal tanpa crash.
