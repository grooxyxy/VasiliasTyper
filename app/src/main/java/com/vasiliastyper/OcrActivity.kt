package com.vasiliastyper

  import android.content.Context
  import android.graphics.Bitmap
  import android.graphics.BitmapFactory
  import android.graphics.RectF
  import android.net.Uri
  import android.os.Bundle
  import android.text.Editable
  import android.text.InputType
  import android.text.TextWatcher
  import android.view.Gravity
  import android.view.View
  import android.view.ViewGroup
  import android.widget.*
  import androidx.activity.result.contract.ActivityResultContracts
  import androidx.appcompat.app.AlertDialog
  import androidx.appcompat.app.AppCompatActivity
  import com.vasiliastyper.engine.GeminiOcrTranslation
  import com.vasiliastyper.engine.GeminiSettings
  import com.vasiliastyper.engine.MlKitOcrEngine
  import com.vasiliastyper.engine.NexrayAiClient
  import com.vasiliastyper.engine.TranslationManager
  import com.vasiliastyper.model.OcrResult
  import com.vasiliastyper.model.OcrSelectionRect
  import com.vasiliastyper.view.SelectionOverlayView

  class OcrActivity : AppCompatActivity() {
      private lateinit var ivPage: ImageView
      private lateinit var overlay: SelectionOverlayView
      private lateinit var spSrc: Spinner
      private lateinit var spTgt: Spinner
      private lateinit var spAiProvider: Spinner
      private lateinit var btnPick: Button
      private lateinit var btnRun: Button
      private lateinit var btnAdd: Button
      private lateinit var btnUndo: Button
      private lateinit var btnClear: Button
      private lateinit var pb: ProgressBar
      private lateinit var tvHint: TextView
      private lateinit var tabStrip: LinearLayout
      private lateinit var tvTabInfo: TextView
      private lateinit var etOriginal: EditText
      private lateinit var etTranslated: EditText
      private lateinit var btnRetranslate: Button
      private lateinit var btnCopyOriginal: Button
      private lateinit var btnCopyTranslated: Button
      private lateinit var btnDeleteCurrent: Button
      private lateinit var tvEmpty: TextView
      private lateinit var btnGeminiKey: Button
      private lateinit var tvGeminiStatus: TextView

      private var bitmap: Bitmap? = null
      private val results = mutableListOf<OcrResult>()
      private var selectedIndex = -1
      private var suppressFieldUpdates = false

      private val srcCodes = arrayOf("auto", "en", "zh", "ko")
      private val tgtCodes = arrayOf("id", "en")

      private enum class AiMode {
          OFFLINE, GEMINI, GPT_35_TURBO, CLAUDE
      }

      private fun selectedAiMode(): AiMode = AiMode.entries
          .getOrElse(spAiProvider.selectedItemPosition) { AiMode.OFFLINE }

      private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
          uri ?: return@registerForActivityResult
          loadImage(uri)
      }

      override fun onCreate(b: Bundle?) {
          super.onCreate(b)
          setContentView(R.layout.activity_ocr)

          ivPage           = findViewById(R.id.ivPagePreview)
          overlay          = findViewById(R.id.selectionOverlay)
          spSrc            = findViewById(R.id.spinnerLangSrc)
          spTgt            = findViewById(R.id.spinnerLangTgt)
          spAiProvider     = findViewById(R.id.spinnerAiProvider)
          btnPick          = findViewById(R.id.btnPickImage)
          btnRun           = findViewById(R.id.btnRunOcr)
          btnAdd           = findViewById(R.id.btnAddToScript)
          btnUndo          = findViewById(R.id.btnUndo)
          btnClear         = findViewById(R.id.btnClearAll)
          pb               = findViewById(R.id.progressBarOcr)
          tvHint           = findViewById(R.id.tvSelectionHint)
          tabStrip         = findViewById(R.id.ocrTabStrip)
          tvTabInfo        = findViewById(R.id.tvOcrTabInfo)
          etOriginal       = findViewById(R.id.etOcrOriginal)
          etTranslated     = findViewById(R.id.etOcrTranslated)
          btnRetranslate   = findViewById(R.id.btnRetranslateOcr)
          btnCopyOriginal  = findViewById(R.id.btnCopyOriginalOcr)
          btnCopyTranslated = findViewById(R.id.btnCopyTranslatedOcr)
          btnDeleteCurrent = findViewById(R.id.btnDeleteCurrentOcr)
          tvEmpty          = findViewById(R.id.tvOcrEmpty)
          btnGeminiKey     = findViewById(R.id.btnGeminiApiKey)
          tvGeminiStatus   = findViewById(R.id.tvGeminiStatus)

          spSrc.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
              arrayOf("Auto", "EN", "ZH", "KO"))
          spTgt.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
              arrayOf("ID", "EN"))
          spAiProvider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
              arrayOf("Offline / MyMemory", "Gemini 3.1 Flash-Lite", "GPT-3.5 Turbo (Nexray)", "Claude (Nexray)"))
          spAiProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
              override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                  updateGeminiStatusLabel()
              }
              override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
          }

          overlay.listener = object : SelectionOverlayView.Listener {
              override fun onChanged(rects: List<OcrSelectionRect>) = Unit
          }

          etOriginal.addTextChangedListener(object : TextWatcher {
              override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
              override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
              override fun afterTextChanged(s: Editable?) {
                  if (suppressFieldUpdates) return
                  results.getOrNull(selectedIndex)?.originalText = s?.toString().orEmpty()
                  refreshTabLabel(selectedIndex)
              }
          })
          etTranslated.addTextChangedListener(object : TextWatcher {
              override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
              override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
              override fun afterTextChanged(s: Editable?) {
                  if (suppressFieldUpdates) return
                  results.getOrNull(selectedIndex)?.translatedText = s?.toString().orEmpty()
              }
          })

          btnPick.setOnClickListener { pick.launch("image/*") }
          btnRun.setOnClickListener { runOcr() }
          btnUndo.setOnClickListener { overlay.undoLast() }
          btnClear.setOnClickListener {
              overlay.clearAll(); results.clear(); selectedIndex = -1
              refreshTabs(); bindSelectedResult(); tvHint.visibility = View.VISIBLE
          }
          btnAdd.setOnClickListener { finish2() }
          btnRetranslate.setOnClickListener { if (selectedIndex >= 0) retranslate(selectedIndex) }
          btnCopyOriginal.setOnClickListener { copySelectedText(translated = false) }
          btnCopyTranslated.setOnClickListener { copySelectedText(translated = true) }
          btnDeleteCurrent.setOnClickListener {
              val pos = selectedIndex
              if (pos in results.indices) {
                  results.removeAt(pos)
                  selectedIndex = if (results.isEmpty()) -1 else pos.coerceAtMost(results.lastIndex)
                  refreshTabs(); bindSelectedResult()
              }
          }
          btnGeminiKey.setOnClickListener { showGeminiKeyDialog() }

          updateGeminiStatusLabel()
          ivPage.post { updateBounds() }
          refreshTabs()
          bindSelectedResult()
      }

      // ─── Gemini API Key ───────────────────────────────────────────────────────

      private fun getGeminiApiKey(): String = GeminiSettings.getApiKey(this).orEmpty()

      private fun saveGeminiApiKey(key: String) {
          if (key.isBlank()) GeminiSettings.clear(this) else GeminiSettings.setApiKey(this, key)
          updateGeminiStatusLabel()
      }

      private fun updateGeminiStatusLabel() {
          when (selectedAiMode()) {
              AiMode.OFFLINE -> {
                  tvGeminiStatus.text = "OCR ML Kit · terjemahan MyMemory"
                  tvGeminiStatus.setTextColor(0xFF888888.toInt())
              }
              AiMode.GPT_35_TURBO -> {
                  tvGeminiStatus.text = "OCR ML Kit · terjemahan GPT-3.5 Turbo via Nexray"
                  tvGeminiStatus.setTextColor(0xFF66BB6A.toInt())
              }
              AiMode.CLAUDE -> {
                  tvGeminiStatus.text = "OCR ML Kit · terjemahan Claude via Nexray"
                  tvGeminiStatus.setTextColor(0xFF66BB6A.toInt())
              }
              AiMode.GEMINI -> {
                  val key = getGeminiApiKey()
                  if (key.isNotBlank()) {
                      val masked = if (key.length > 8) key.take(4) + "..." + key.takeLast(4) else "****"
                      tvGeminiStatus.text = "Gemini OCR + terjemahan aktif ($masked)"
                      tvGeminiStatus.setTextColor(0xFF66BB6A.toInt())
                  } else {
                      tvGeminiStatus.text = "Gemini belum memiliki API key · fallback offline"
                      tvGeminiStatus.setTextColor(0xFFF59E0B.toInt())
                  }
              }
          }
      }

      private fun showGeminiKeyDialog() {
          val currentKey = getGeminiApiKey()

          val container = LinearLayout(this)
          container.orientation = LinearLayout.VERTICAL
          container.setPadding(48, 32, 48, 16)

          val tvInfo = TextView(this)
          tvInfo.text = "Masukkan Gemini API Key untuk OCR dan terjemahan berbasis AI.\n\n" +
              "Dapatkan key gratis di: aistudio.google.com\n\n" +
              "Kosongkan untuk pakai mode offline (ML Kit + MyMemory)."
          tvInfo.textSize = 12f
          tvInfo.setTextColor(0xFFAAAAAA.toInt())

          val space = View(this)
          space.layoutParams = LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT, 20)

          val etKey = EditText(this)
          etKey.hint = "AIza..."
          etKey.setText(currentKey)
          etKey.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
          etKey.textSize = 13f
          etKey.setTextColor(0xFFFFFFFF.toInt())
          etKey.setHintTextColor(0xFF555555.toInt())
          etKey.setBackgroundColor(0xFF1A1A1A.toInt())
          etKey.setPadding(16, 12, 16, 12)

          container.addView(tvInfo)
          container.addView(space)
          container.addView(etKey)

          AlertDialog.Builder(this)
              .setTitle("Gemini API Key")
              .setView(container)
              .setPositiveButton("Simpan") { _, _ ->
                  val k = etKey.text.toString().trim()
                  saveGeminiApiKey(k)
                  Toast.makeText(this,
                      if (k.isNotBlank()) "API key disimpan. Gemini AI aktif!"
                      else "Key dihapus. Mode offline aktif.",
                      Toast.LENGTH_SHORT).show()
              }
              .setNegativeButton("Batal", null)
              .setNeutralButton("Hapus Key") { _, _ ->
                  saveGeminiApiKey("")
                  Toast.makeText(this, "Key dihapus. Mode offline aktif.", Toast.LENGTH_SHORT).show()
              }
              .show()
      }

      // ─── Image ────────────────────────────────────────────────────────────────

      private fun loadImage(uri: Uri) {
          val st = contentResolver.openInputStream(uri) ?: return
          bitmap = BitmapFactory.decodeStream(st); st.close()
          ivPage.setImageBitmap(bitmap)
          overlay.clearAll(); results.clear(); selectedIndex = -1
          refreshTabs(); bindSelectedResult()
          overlay.selectionEnabled = true
          tvHint.visibility = View.VISIBLE
          ivPage.post { updateBounds() }
      }

      private fun updateBounds() {
          val bmp = bitmap ?: return
          val vw = ivPage.width.toFloat();  val vh = ivPage.height.toFloat()
          val bw = bmp.width.toFloat();     val bh = bmp.height.toFloat()
          val sc = minOf(vw / bw, vh / bh)
          val sw = bw * sc; val sh = bh * sc
          val lx = (vw - sw) / 2f; val ty = (vh - sh) / 2f
          overlay.imageBounds = RectF(lx, ty, lx + sw, ty + sh)
          overlay.imgW = bw; overlay.imgH = bh
      }

      // ─── OCR ─────────────────────────────────────────────────────────────────

      private fun runOcr() {
          val bmp = bitmap ?: run {
              Toast.makeText(this, "Pilih gambar dulu", Toast.LENGTH_SHORT).show(); return
          }
          val sels = overlay.selections.toList()
          if (sels.isEmpty()) { runFull(bmp); return }

          val geminiKey = getGeminiApiKey().takeIf { selectedAiMode() == AiMode.GEMINI }.orEmpty()
          val src = srcCodes[spSrc.selectedItemPosition]
          val tgt = tgtCodes[spTgt.selectedItemPosition]
          pb.visibility = View.VISIBLE; btnRun.isEnabled = false
          val total = sels.size; var done = 0

          fun onOneDone() {
              done++
              if (done >= total) {
                  pb.visibility = View.GONE; btnRun.isEnabled = true
                  selectedIndex = results.lastIndex.coerceAtLeast(0)
                  refreshTabs(); bindSelectedResult()
              }
          }

          for (sel in sels) {
              val x = sel.imageRect.left.toInt().coerceIn(0, bmp.width - 1)
              val y = sel.imageRect.top.toInt().coerceIn(0, bmp.height - 1)
              val w = sel.imageRect.width().toInt().coerceIn(1, bmp.width - x)
              val h = sel.imageRect.height().toInt().coerceIn(1, bmp.height - y)
              val crop = if (w > 0 && h > 0) Bitmap.createBitmap(bmp, x, y, w, h) else bmp

              if (geminiKey.isNotBlank()) {
                  GeminiOcrTranslation.recognizeAndTranslate(
                      crop, src, tgt, geminiKey,
                      object : GeminiOcrTranslation.OcrCallback {
                          override fun onSuccess(originalText: String, translatedText: String, detectedLang: String) {
                              if (crop !== bmp) crop.recycle()
                              results.add(OcrResult(
                                  normalizeOcrText(originalText),
                                  detectedLang,
                                  normalizeOcrText(translatedText),
                                  tgt,
                                  sel.index
                              ))
                              refreshTabs(); bindSelectedResult(); onOneDone()
                          }
                          override fun onFailure(error: String) {
                              Toast.makeText(this@OcrActivity,
                                  "Gemini gagal, pakai offline...", Toast.LENGTH_SHORT).show()
                              fallbackMlKit(crop, bmp, src, tgt, sel.index) { onOneDone() }
                          }
                      })
              } else {
                  fallbackMlKit(crop, bmp, src, tgt, sel.index) { onOneDone() }
              }
          }
      }

      private fun fallbackMlKit(
          crop: Bitmap, bmp: Bitmap, src: String, tgt: String,
          selIndex: Int, onDone: () -> Unit
      ) {
          MlKitOcrEngine.recognize(crop, null, src, object : MlKitOcrEngine.OcrCallback {
              override fun onSuccess(text: String, lang: String) {
                  val normalized = normalizeOcrText(text)
                  if (crop !== bmp) crop.recycle()
                  val r = OcrResult(normalized, lang, null, tgt, selIndex)
                  results.add(r)
                  val pos = results.lastIndex
                  refreshTabs(); bindSelectedResult()
                  trans(pos, r); onDone()
              }
              override fun onFailure(e: Exception) {
                  if (crop !== bmp) crop.recycle()
                  Toast.makeText(this@OcrActivity, "OCR gagal", Toast.LENGTH_SHORT).show()
                  onDone()
              }
          })
      }

      private fun runFull(bmp: Bitmap) {
          val geminiKey = getGeminiApiKey().takeIf { selectedAiMode() == AiMode.GEMINI }.orEmpty()
          val src = srcCodes[spSrc.selectedItemPosition]
          val tgt = tgtCodes[spTgt.selectedItemPosition]
          pb.visibility = View.VISIBLE; btnRun.isEnabled = false

          if (geminiKey.isNotBlank()) {
              GeminiOcrTranslation.recognizeAndTranslate(
                  bmp, src, tgt, geminiKey,
                  object : GeminiOcrTranslation.OcrCallback {
                      override fun onSuccess(originalText: String, translatedText: String, detectedLang: String) {
                          pb.visibility = View.GONE; btnRun.isEnabled = true
                          if (originalText.isBlank()) {
                              Toast.makeText(this@OcrActivity,
                                  "Tidak ada teks", Toast.LENGTH_SHORT).show(); return
                          }
                          results.clear()
                          results.add(OcrResult(
                              normalizeOcrText(originalText),
                              detectedLang,
                              normalizeOcrText(translatedText),
                              tgt
                          ))
                          selectedIndex = 0; refreshTabs(); bindSelectedResult()
                      }
                      override fun onFailure(error: String) {
                          Toast.makeText(this@OcrActivity,
                              "Gemini gagal, pakai offline...", Toast.LENGTH_SHORT).show()
                          runFullOffline(bmp, src, tgt)
                      }
                  })
          } else {
              runFullOffline(bmp, src, tgt)
          }
      }

      private fun runFullOffline(bmp: Bitmap, src: String, tgt: String) {
          MlKitOcrEngine.recognize(bmp, null, src, object : MlKitOcrEngine.OcrCallback {
              override fun onSuccess(text: String, lang: String) {
                  pb.visibility = View.GONE; btnRun.isEnabled = true
                  val normalized = normalizeOcrText(text)
                  if (normalized.isBlank()) {
                      Toast.makeText(this@OcrActivity, "Tidak ada teks", Toast.LENGTH_SHORT).show()
                      return
                  }
                  results.clear()
                  val r = OcrResult(normalized, lang, null, tgt)
                  results.add(r); selectedIndex = 0
                  refreshTabs(); bindSelectedResult(); trans(0, r)
              }
              override fun onFailure(e: Exception) {
                  pb.visibility = View.GONE; btnRun.isEnabled = true
                  Toast.makeText(this@OcrActivity, "OCR gagal: " + e.message, Toast.LENGTH_SHORT).show()
              }
          })
      }

      // ─── Translation fallback ─────────────────────────────────────────────────

      private fun trans(pos: Int, res: OcrResult, onFinished: () -> Unit = {}) {
          fun success(value: String) {
              res.translatedText = normalizeOcrText(value)
              if (pos == selectedIndex) bindSelectedResult()
              refreshTabLabel(pos)
              onFinished()
          }
          fun failure(message: String) {
              res.translatedText = "[Gagal: $message]"
              if (pos == selectedIndex) bindSelectedResult()
              refreshTabLabel(pos)
              onFinished()
          }

          val nexrayProvider = when (selectedAiMode()) {
              AiMode.GPT_35_TURBO -> NexrayAiClient.Provider.GPT_35_TURBO
              AiMode.CLAUDE -> NexrayAiClient.Provider.CLAUDE
              else -> null
          }
          if (nexrayProvider != null) {
              NexrayAiClient.translate(
                  res.originalText,
                  res.sourceLang,
                  res.targetLang,
                  nexrayProvider,
                  object : NexrayAiClient.TranslationCallback {
                      override fun onSuccess(translatedText: String) = success(translatedText)
                      override fun onFailure(error: String) = failure(error)
                  }
              )
              return
          }

          TranslationManager.translate(res.originalText, res.sourceLang, res.targetLang,
              object : TranslationManager.Cb {
                  override fun onSuccess(r: String) = success(r)
                  override fun onFailure(m: String) = failure(m)
              })
      }

      private fun retranslate(pos: Int) {
          val res = results.getOrNull(pos) ?: return
          res.targetLang = tgtCodes[spTgt.selectedItemPosition]
          val key = getGeminiApiKey()
          if (selectedAiMode() != AiMode.GEMINI || key.isBlank()) {
              btnRetranslate.isEnabled = false
              trans(pos, res) { btnRetranslate.isEnabled = true }
              return
          }
          btnRetranslate.isEnabled = false
          GeminiOcrTranslation.translateText(
              res.originalText,
              res.sourceLang,
              res.targetLang,
              key,
              object : GeminiOcrTranslation.TranslationCallback {
                  override fun onSuccess(translatedText: String) {
                      res.translatedText = normalizeOcrText(translatedText)
                      btnRetranslate.isEnabled = true
                      if (pos == selectedIndex) bindSelectedResult()
                      refreshTabLabel(pos)
                  }

                  override fun onFailure(error: String) {
                      Toast.makeText(this@OcrActivity, "Gemini gagal, pakai fallback", Toast.LENGTH_SHORT).show()
                      trans(pos, res) { btnRetranslate.isEnabled = true }
                  }
              }
          )
      }

      // ─── Tab UI ───────────────────────────────────────────────────────────────

      private fun refreshTabs() {
          tabStrip.removeAllViews()
          tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
          for ((index, res) in results.withIndex()) {
              val tab = TextView(this).apply {
                  layoutParams = LinearLayout.LayoutParams(
                      ViewGroup.LayoutParams.WRAP_CONTENT,
                      ViewGroup.LayoutParams.MATCH_PARENT).apply { rightMargin = 8 }
                  setPadding(28, 10, 28, 10); textSize = 12f; gravity = Gravity.CENTER
                  text = tabLabel(index, res)
                  setBackgroundResource(R.drawable.studio_chip_bg)
                  setTextColor(if (index == selectedIndex) 0xFFFFFFFF.toInt() else 0xFFCCCCCC.toInt())
                  setOnClickListener { selectTab(index) }
              }
              if (index == selectedIndex) tab.setBackgroundColor(0xFF4D9BF0.toInt())
              tabStrip.addView(tab)
          }
      }

      private fun refreshTabLabel(index: Int) {
          val child = tabStrip.getChildAt(index) as? TextView ?: return
          child.text = tabLabel(index, results.getOrNull(index))
      }

      private fun tabLabel(index: Int, res: OcrResult?): String {
          val head = res?.originalText?.lineSequence()?.firstOrNull()?.trim().orEmpty()
          val short = if (head.isBlank()) "Area " + (index + 1) else head.take(10)
          return (index + 1).toString() + ". " + short
      }

      private fun selectTab(index: Int) {
          if (index !in results.indices) return
          selectedIndex = index; refreshTabs(); bindSelectedResult()
      }

      private fun bindSelectedResult() {
          val res = results.getOrNull(selectedIndex)
          suppressFieldUpdates = true
          if (res == null) {
              tvTabInfo.text = "Belum ada tab OCR"
              etOriginal.setText(""); etTranslated.setText("")
              etOriginal.isEnabled = false; etTranslated.isEnabled = false
              btnRetranslate.isEnabled = false; btnDeleteCurrent.isEnabled = false
              btnCopyOriginal.isEnabled = false; btnCopyTranslated.isEnabled = false
              tvEmpty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
          } else {
              tvTabInfo.text = "Tab " + (selectedIndex + 1) + " / " + results.size +
                  " · " + res.sourceLang.uppercase() + " → " + res.targetLang.uppercase()
              etOriginal.isEnabled = true; etTranslated.isEnabled = true
              btnRetranslate.isEnabled = true; btnDeleteCurrent.isEnabled = true
              btnCopyOriginal.isEnabled = res.originalText.isNotBlank()
              btnCopyTranslated.isEnabled = !res.translatedText.isNullOrBlank()
              etOriginal.setText(res.originalText)
              etTranslated.setText(res.translatedText.orEmpty())
              tvEmpty.visibility = View.GONE
          }
          suppressFieldUpdates = false
      }

      private fun normalizeOcrText(raw: String): String = raw
          .lineSequence()
          .joinToString("\n") { line -> line.replace(Regex("[\\t ]+"), " ").trim() }
          .trim()

      private fun copySelectedText(translated: Boolean) {
          val result = results.getOrNull(selectedIndex) ?: return
          val text = if (translated) result.translatedText.orEmpty() else result.originalText
          if (text.isBlank()) {
              Toast.makeText(this, "Teks belum tersedia", Toast.LENGTH_SHORT).show()
              return
          }
          val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
          val label = if (translated) "Terjemahan OCR" else "Teks asli OCR"
          clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
          Toast.makeText(this, "$label disalin", Toast.LENGTH_SHORT).show()
      }

      private fun finish2() {
          val lines = ArrayList<String>()
          for (r in results)
              lines.add(normalizeOcrText(r.translatedText?.takeIf { it.isNotBlank() } ?: r.originalText))
          if (lines.isEmpty()) {
              Toast.makeText(this, "Tidak ada hasil OCR", Toast.LENGTH_SHORT).show(); return
          }
          val d = android.content.Intent()
          d.putStringArrayListExtra("ocr_lines", lines)
          setResult(RESULT_OK, d); finish()
      }
  }
  