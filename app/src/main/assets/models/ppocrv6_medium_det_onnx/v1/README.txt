PP-OCRv6 MEDIUM DETECTOR

Unduh model resmi:
  https://huggingface.co/PaddlePaddle/PP-OCRv6_medium_det_onnx/resolve/main/inference.onnx

Letakkan model ONNX detector dengan nama tepat:
  inference.onnx

di folder ini sehingga path lengkapnya:
  app/src/main/assets/models/ppocrv6_medium_det_onnx/v1/inference.onnx

Model harus berupa text detection/DBNet export ONNX dengan input float32 NCHW
[1,3,H,W] (dimensi dinamis, kelipatan 32) dan output probability map teks.
Jangan menaruh model recognition/classification pada folder ini.

Saat aplikasi pertama kali menjalankan opsi PP-OCRv6 Medium, asset disalin ke:
  <internal filesDir>/models/ppocrv6_medium_det_onnx/v1/inference.onnx

Ukuran minimum yang diterima runtime: 5 MB.
