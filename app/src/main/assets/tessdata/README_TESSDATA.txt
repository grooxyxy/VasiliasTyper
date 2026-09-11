======================================================
  Tesseract 4.x Language Data Files — Setup Guide
======================================================

REQUIRED FOR OCR-GUIDED BUBBLE CLEANING
----------------------------------------
Place at least ONE of the following files in this folder:
  app/src/main/assets/tessdata/

  jpn.traineddata           ← Japanese horizontal (recommended for manga)
  jpn_vert.traineddata      ← Japanese vertical
  eng.traineddata           ← English

If BOTH jpn AND eng are present, the engine uses "jpn+eng" mode.

DOWNLOAD LINKS (tessdata_fast — small, LSTM optimised)
-------------------------------------------------------
  https://github.com/tesseract-ocr/tessdata_fast

Direct links:
  jpn      https://github.com/tesseract-ocr/tessdata_fast/raw/main/jpn.traineddata
  jpn_vert https://github.com/tesseract-ocr/tessdata_fast/raw/main/jpn_vert.traineddata
  eng      https://github.com/tesseract-ocr/tessdata_fast/raw/main/eng.traineddata

Typical file sizes (fast variants):
  jpn.traineddata      ~ 1.0 MB
  jpn_vert.traineddata ~ 1.0 MB
  eng.traineddata      ~ 4.0 MB

RUNTIME COPY (optional)
-----------------------
If you include the files in assets/, call once at startup:
  OcrEngine.copyFromAssets(context, "jpn", "eng")

This copies them to context.filesDir/tessdata/ (required by Tesseract).

FALLBACK
--------
If no tessdata files are present, BubbleCleaner automatically falls back
to the original algorithmic expansion (same quality as v5.0). No crash.
