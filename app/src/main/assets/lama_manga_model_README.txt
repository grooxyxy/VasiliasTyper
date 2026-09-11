============================================================
  LaMa Original ONNX — Model Setup (bundled via CI)
============================================================

MODEL (ASLI, bukan manga)
-----
Repository : https://huggingface.co/Carve/LaMa-ONNX
File       : lama_fp32.onnx -> dibundle sebagai lama-fp32.onnx
License    : Apache-2.0
Size       : ~200 MiB single-file
Ref kontrak: https://huggingface.co/sapienkit/LaMa-ONNX (turunan Carve)

Model dibundle otomatis ke APK via task Gradle `bundleLamaModel`
(langsung di GitHub Action, tanpa download lokal):
  -PLAMA_MODEL_PATH=/lokal/lama-fp32.onnx
  -PLAMA_MODEL_URL=https://.../lama_fp32.onnx (atau secret LAMA_MODEL_URL)
  - default: https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx

Lokasi asset hasil bundle:
  a) app/src/main/assets/models/lama/v1/lama-fp32.onnx (utama)
  b) app/src/main/assets/models/lama/lama-fp32.onnx
  c) app/src/main/assets/lama-fp32.onnx
  d) filesDir/models/lama/v1/lama-fp32.onnx
Legacy manga tetap dibaca (migrasi):
  e) models/lama_manga/v1/lama-manga-dynamic.onnx

VERIFIED ONNX CONTRACT (fixed 512)
----------------------
Opset 17, fixed 512x512:
  image  float32 [1,3,512,512] RGB [0,1] (/255)
  mask   float32 [1,1,512,512] 1=erase, 0=keep
  output float32 [1,3,512,512] RGB [0,255] (otomatis /255 bila [0,1])

Pipeline Android me-resize tiap crop/tile ke 512, lalu resize balik
(bilinear) dan hanya menulis piksel dalam Region. Untuk 720x16000+
dipakai TilingEngine (tile 512 + overlap 64, center-write) sehingga
memori tetap ~1MB/tile. Bila model gagal, fallback ke OpenCV patch
tanpa mengubah piksel di luar mask.
