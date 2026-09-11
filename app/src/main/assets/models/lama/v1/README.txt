LaMa Original (DIBUNDLE VIA CI — JANGAN COMMIT .onnx)

File:
  lama-fp32.onnx (~200MB, single-file)

Sumber asli (Apache-2.0):
  https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx
Ref kontrak:
  https://huggingface.co/sapienkit/LaMa-ONNX
  image [1,3,512,512] RGB /255, mask [1,1,512,512] 1=erase,
  output [1,3,512,512] RGB [0,255].

Cara menyediakan (dipilih otomatis saat build):
  a) Otomatis CI (disarankan): task `bundleLamaModel` mengunduh dari HF
     default lalu membundle ke APK. Validasi >50MB menolak HTML/error.
     Override: -PLAMA_MODEL_PATH=/lokal/lama-fp32.onnx atau
     -PLAMA_MODEL_URL=https://.../lama_fp32.onnx (secret LAMA_MODEL_URL).
  b) Manual: letakkan file dengan nama persis di folder ini.

Gambar 720x16000+ diproses per tile 512 + overlap 64 via TilingEngine,
sehingga memori tetap kecil. Bila model hilang, fallback OpenCV tanpa crash.
