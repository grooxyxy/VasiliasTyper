============================================================
  LaMa Manga ONNX Dynamic — Model Setup
============================================================

MODEL
-----
Repository : https://huggingface.co/ogkalu/lama-manga-onnx-dynamic
File       : lama-manga-dynamic.onnx
License    : Apache-2.0
Size       : 206,291,843 bytes (~197 MiB)
SHA-256    : de31ffa5ba26916b8ea35319f6c12151ff9654d4261bccf0583a69bb095315f9

The model is intentionally not bundled in this source archive. Download it in
app or place it at ONE of these locations:

  a) app/src/main/assets/models/lama_manga/v1/lama-manga-dynamic.onnx
  b) app/src/main/assets/models/lama_manga/lama-manga-dynamic.onnx
  c) app/src/main/assets/lama-manga-dynamic.onnx
  d) filesDir/models/lama_manga/v1/lama-manga-dynamic.onnx

Direct download:
https://huggingface.co/ogkalu/lama-manga-onnx-dynamic/resolve/main/lama-manga-dynamic.onnx

VERIFIED ONNX CONTRACT
----------------------
Opset 18, dynamic H/W:
  image     float32 [batch,3,h,w] RGB [0,1]
  mask      float32 [batch,1,h,w] 1=inpaint, 0=keep
  inpainted float32 [batch,3,h,w] RGB [0,1]

The Android pipeline aligns dimensions to multiples of 8, limits inference to
768 px (512 px on low-memory devices), maps the model from disk, and writes only
the exact selected Region. If model loading or inference fails, the source image
is preserved outside the mask and the local OpenCV patch engine is used.
