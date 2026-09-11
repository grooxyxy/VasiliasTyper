COMIC TEXT DETECTOR beta-0.2.1

Unduh model resmi:
  https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.2.1/comictextdetector.pt.onnx

Letakkan dengan nama tepat:
  comictextdetector.pt.onnx

di folder ini sehingga path lengkapnya:
  app/src/main/assets/models/comictextdetector/v1/comictextdetector.pt.onnx

Sumber implementasi/model:
  https://github.com/dmMaze/comic-text-detector
  https://github.com/zyddnys/manga-image-translator/releases/tag/beta-0.2.1

Runtime hanya memakai output ONNX `seg` (pixel-level text segmentation), bukan
output blok/bubble. Asset resmi berukuran sekitar 94.7 MB. Saat pertama dipakai,
asset disalin ke:
  <internal filesDir>/models/comictextdetector/v1/comictextdetector.pt.onnx

Ukuran minimum yang diterima runtime: 70 MB.
