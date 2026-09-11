package com.vasiliastyper.engine

  import android.content.Context
  import android.net.Uri
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.withContext
  import org.xmlpull.v1.XmlPullParser
  import org.xmlpull.v1.XmlPullParserFactory
  import java.io.BufferedReader
  import java.io.ByteArrayInputStream
  import java.io.InputStreamReader
  import java.nio.charset.Charset
  import java.nio.charset.StandardCharsets
  import java.util.zip.ZipInputStream

  data class ScriptLine(
      var text: String,
      var used: Boolean = false,
      /** Source/OCR text paired with [text] in structured OCR/TRANSLATE scripts. */
      var sourceText: String? = null
  )

  data class StructuredScript(
      val lines: List<ScriptLine>,
      val hasOcrPairs: Boolean,
      /** Baris yang dikategorikan dari bagian OCR/SOURCE. */
      val sourceLines: List<String> = emptyList(),
      /** Baris yang dikategorikan dari bagian TRANSLATE. */
      val translationLines: List<String> = emptyList()
  )

  object ScriptImporter {

      /**
       * Import biasa untuk tombol LOAD. Semua baris dipertahankan apa adanya agar
       * prefix Style Rules tetap dapat diproses saat teks dipakai. Import ini tidak
       * menafsirkan header OCR/SOURCE/TRANSLATE sebagai kategori.
       */
      suspend fun import(context: Context, uri: Uri): List<ScriptLine> =
          importPlain(context, uri)

      suspend fun importPlain(context: Context, uri: Uri): List<ScriptLine> = withContext(Dispatchers.IO) {
          when {
              isDocxDocument(context, uri) -> importDocx(context, uri)
              else                         -> importTxt(context, uri)
          }
      }

      /**
       * Imports either a normal script or a paired script with this shape:
       *
       * OCR
       * source line 1
       * source line 2
       * TRANSLATE
       * style-prefix: translation 1
       * style-prefix: translation 2
       *
       * Pairing is positional. Every leading prefix is deliberately kept on the
       * translation. VasType may use it for Style Rules, but must also render the
       * original prefix (for example `-`, `/`, `[`, or a custom code).
       */
      suspend fun importStructured(context: Context, uri: Uri): StructuredScript = withContext(Dispatchers.IO) {
          if (isDocxDocument(context, uri)) {
              parseOcrTranslationSections(importDocx(context, uri))
          } else {
              parseStructuredText(readTextContent(context, uri))
          }
      }

      /**
       * Import untuk alur VasType berbasis urutan region. File biasa tidak wajib
       * memiliki blok OCR/TRANSLATE: setiap paragraf/baris non-kosong menjadi satu
       * teks placement. Jika file memang terstruktur, sisi TRANSLATE tetap dipakai.
       */
      suspend fun importPlacement(context: Context, uri: Uri): StructuredScript = withContext(Dispatchers.IO) {
          val raw = if (isDocxDocument(context, uri)) {
              importDocx(context, uri).joinToString("\n") { it.text }
          } else {
              readTextContent(context, uri)
          }
          parsePlacementText(raw)
      }

      /**
       * Mem-parsing script biasa untuk placement top-to-bottom, left-to-right.
       * Style prefix tidak diubah agar Style Rules dapat membacanya saat apply.
       */
      fun parsePlacementText(rawText: String): StructuredScript {
          val structured = parseStructuredText(rawText)
          if (structured.hasOcrPairs && structured.lines.isNotEmpty()) return structured

          val lines = rawText
              .replace("\r\n", "\n")
              .replace('\r', '\n')
              .trimStart('\uFEFF')
              .lineSequence()
              .map { it.trim().trimStart('\uFEFF') }
              .filter { it.isNotEmpty() }
              .filterNot { value ->
                  val header = value.removeSuffix(":").removeSuffix("：").trim().uppercase()
                  header == "OCR" || header == "SOURCE" || header == "TRANSLATE" ||
                      header == "TRANSLATION" || header == "TARGET"
              }
              .map { ScriptLine(it) }
              .toList()
          return StructuredScript(
              lines = lines,
              hasOcrPairs = false,
              translationLines = lines.map { it.text }
          )
      }

      /**
       * Parse text pasted into VasType / TipeR.
       *
       * Besides the documented OCR ... TRANSLATE section format, this accepts
       * common spreadsheet exports (`source<TAB>translation`, CSV, `source => translation`)
       * so a file does not silently load as an unusable plain script.
       */
      fun parseStructuredText(rawText: String): StructuredScript {
          val normalized = rawText
              .replace("\r\n", "\n")
              .replace('\r', '\n')
              .trimStart('\uFEFF')
          val lines = normalized
              .lineSequence()
              .map { it.trim().trimStart('\uFEFF') }
              .filter { it.isNotEmpty() }
              .map { ScriptLine(it) }
              .toList()

          val sectioned = parseOcrTranslationSections(lines)
          if (sectioned.hasOcrPairs) return sectioned
          return parseDelimitedPairs(normalized) ?: sectioned
      }

      /**
       * v2.0: Import MULTIPLE files and merge them into one flat list.
       * Each file is imported separately; a separator line is inserted between files.
       */
      suspend fun importMultiple(context: Context, uris: List<Uri>): List<ScriptLine> = withContext(Dispatchers.IO) {
          val result = mutableListOf<ScriptLine>()
          for (uri in uris) {
              val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "Script 23371"
              if (result.isNotEmpty()) {
                  result.add(ScriptLine("── $fileName ──", used = false))
              }
              val lines = when {
                  isDocxDocument(context, uri) -> importDocx(context, uri)
                  else                         -> importTxt(context, uri)
              }
              result.addAll(lines)
          }
          result
      }

      private fun isDocxDocument(context: Context, uri: Uri): Boolean {
          val pathName = uri.lastPathSegment.orEmpty().lowercase()
          if (pathName.endsWith(".docx")) return true

          val mimeType = runCatching { context.contentResolver.getType(uri) }
              .getOrNull()
              .orEmpty()
              .lowercase()
          if (mimeType.contains("wordprocessingml") || mimeType.endsWith("/docx")) return true

          val displayName = runCatching {
              context.contentResolver.query(
                  uri,
                  arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                  null,
                  null,
                  null
              )?.use { cursor ->
                  if (cursor.moveToFirst()) cursor.getString(0) else null
              }
          }.getOrNull().orEmpty().lowercase()
          return displayName.endsWith(".docx")
      }

      private fun parseOcrTranslationSections(raw: List<ScriptLine>): StructuredScript {
          fun normalizedHeader(value: String): String = value
              .trim()
              .trimStart('\uFEFF')
              .removeSuffix(":")
              .removeSuffix("：")
              .replace(Regex("^[\\p{P}\\p{S}\\s]+|[\\p{P}\\p{S}\\s]+$"), "")
              .replace(Regex("\\s+"), "")
              .uppercase()

          fun cleanSectionEntry(value: String): String = value
              .trim()
              .trimStart('\uFEFF')
              // Prefix is content, not disposable list metadata. Keep it verbatim
              // so Style Rules can inspect it and VasType can place it on canvas.
              // This includes `-`, `/`, `[`, bullets, numbering, and custom codes.
              .trimEnd()

          fun isSourceHeader(value: String): Boolean = normalizedHeader(value).matches(
              Regex("(?:BEGIN)?(?:OCR(?:/SOURCE)?|OCRTEXT|OCRSOURCE|OCR/SOURCETEXT|SOURCE(?:CODE|TEXT|LANGUAGE)?|SOURCETEXT/CODE|ORIGINAL(?:TEXT)?|JAPANESE(?:\\(SOURCE\\))?)(?:SECTION)?")
          )
          fun isTranslationHeader(value: String): Boolean = normalizedHeader(value).matches(
              Regex("(?:BEGIN)?(?:TRANSLAT(?:E|ED|ION)|TRANSLATION/TARGET|TRANSLATIONTARGET|TERJEMAHAN|HASILTERJEMAHAN|TARGET(?:TEXT|LANGUAGE)?)(?:SECTION)?")
          )

          val ocrHeader = raw.indexOfFirst { isSourceHeader(it.text) }
          val translationHeader = raw.indexOfFirst { isTranslationHeader(it.text) }
          if (ocrHeader < 0 || translationHeader <= ocrHeader) {
              return StructuredScript(raw, hasOcrPairs = false)
          }

          val rawSources = raw.subList(ocrHeader + 1, translationHeader)
              .map { cleanSectionEntry(it.text) }
              .filter { it.isNotEmpty() }
          val translations = raw.drop(translationHeader + 1)
              .map { cleanSectionEntry(it.text) }
              .filter { it.isNotEmpty() }
          if (rawSources.isEmpty() || translations.isEmpty()) {
              return StructuredScript(raw, hasOcrPairs = false)
          }

          // OCR exports sometimes contain a display-only counter/value on its own
          // line even though the translation deliberately folds that value into the
          // previous sentence. Pairing the two sections by raw index then shifts all
          // following translations (the supplied script contains exactly this case:
          // "240,000,000,000"). Reconcile only the count surplus and only remove
          // unmistakable metadata-like lines; ordinary dialogue is never discarded.
          val sources = reconcileSourceArtifacts(rawSources, translations.size)

          // Only complete positional pairs are runnable. Keeping unmatched tail
          // entries would produce TRANSLATE lines with no OCR key and make the UI
          // report work that can never be matched on the canvas.
          val pairCount = minOf(sources.size, translations.size)
          val paired = (0 until pairCount).map { index ->
              ScriptLine(
                  text = translations[index],
                  sourceText = sources[index]
              )
          }
          return StructuredScript(
              lines = paired,
              hasOcrPairs = paired.any { !it.sourceText.isNullOrBlank() },
              sourceLines = sources,
              translationLines = translations
          )
      }

      private fun parseDelimitedPairs(rawText: String): StructuredScript? {
          val pairs = rawText.lineSequence().mapNotNull { original ->
              val line = original.trim().trimStart('\uFEFF')
              if (line.isBlank() || line.startsWith("#") || line.startsWith("//")) return@mapNotNull null

              val delimiter = when {
                  '\t' in line -> "\t"
                  "=>" in line -> "=>"
                  "|" in line -> "|"
                  else -> null
              }
              val columns = if (delimiter != null) {
                  line.split(delimiter, limit = 2)
              } else {
                  parseCsvColumns(line)
              }
              if (columns.size != 2) return@mapNotNull null
              val source = columns[0].trim().trim('"').trim()
              val translation = columns[1].trim().trim('"').trim()
              val looksLikeHeader = source.equals("OCR", true) ||
                  source.equals("SOURCE", true) ||
                  translation.startsWith("TRANSLAT", true) ||
                  translation.equals("TARGET", true)
              if (source.isBlank() || translation.isBlank() || looksLikeHeader) null
              else source to translation
          }.toList()

          if (pairs.isEmpty()) return null
          val lines = pairs.map { (source, translation) ->
              ScriptLine(text = translation, sourceText = source)
          }
          return StructuredScript(
              lines = lines,
              hasOcrPairs = true,
              sourceLines = pairs.map { it.first },
              translationLines = pairs.map { it.second }
          )
      }

      private fun parseCsvColumns(line: String): List<String> {
          if (',' !in line) return emptyList()
          val columns = mutableListOf<String>()
          val current = StringBuilder()
          var quoted = false
          var index = 0
          while (index < line.length) {
              val char = line[index]
              when {
                  char == '"' && quoted && index + 1 < line.length && line[index + 1] == '"' -> {
                      current.append('"')
                      index++
                  }
                  char == '"' -> quoted = !quoted
                  char == ',' && !quoted -> {
                      columns += current.toString()
                      current.clear()
                  }
                  else -> current.append(char)
              }
              index++
          }
          columns += current.toString()
          return if (columns.size == 2) columns else emptyList()
      }

      /**
       * Removes only surplus OCR artifacts that cannot represent a spoken line.
       * The routine is intentionally count-bounded: if both sections already have
       * equal length, numeric dialogue such as "911" remains a valid pair.
       */
      private fun reconcileSourceArtifacts(sources: List<String>, translationCount: Int): List<String> {
          var surplus = sources.size - translationCount
          if (surplus <= 0) return sources

          val out = sources.toMutableList()
          val candidates = out.indices.filter { index ->
              val value = out[index].trim()
              val letters = value.count { it.isLetter() }
              val digits = value.count { it.isDigit() }
              val metadataOnly = value.matches(
                  Regex("^(?:[\\p{Sc}]?\\s*)?[+-]?[0-9０-９][0-9０-９.,:_/\\-\\s]*(?:%|(?:\\s*(?:px|dpi|usd|krw|won|yen|¥|₩|\\$)))?$")
              )
              val hasSentenceNeighbour = listOfNotNull(
                  out.getOrNull(index - 1),
                  out.getOrNull(index + 1)
              ).any { neighbour -> neighbour.count { it.isLetter() } >= 3 }
              letters == 0 && digits >= 2 && metadataOnly && hasSentenceNeighbour
          }

          // Remove from the end so original indices stay valid. Never remove more
          // than the exact section-count difference.
          for (index in candidates.asReversed()) {
              if (surplus <= 0) break
              out.removeAt(index)
              surplus--
          }
          return out
      }

      // ── TXT ───────────────────────────────────────────────────────────────────

      private fun importTxt(context: Context, uri: Uri): List<ScriptLine> =
          readTextContent(context, uri)
              .replace("\r\n", "\n")
              .replace('\r', '\n')
              .lineSequence()
              .map { it.trim().trimStart('\uFEFF') }
              .filter { it.isNotEmpty() }
              .map { ScriptLine(it) }
              .toList()

      private fun readTextContent(context: Context, uri: Uri): String {
          val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
              ?: throw IllegalArgumentException("File tidak dapat dibuka")
          if (bytes.isEmpty()) return ""

          val (offset, charset) = detectTextEncoding(bytes)
          return ByteArrayInputStream(bytes, offset, bytes.size - offset).use { input ->
              InputStreamReader(input, charset).readText()
          }
      }

      /** Supports UTF-8, UTF-16 LE/BE and common legacy TXT exports. */
      private fun detectTextEncoding(bytes: ByteArray): Pair<Int, Charset> {
          if (bytes.size >= 3 &&
              bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
          ) return 3 to StandardCharsets.UTF_8
          if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
              return 2 to StandardCharsets.UTF_16LE
          }
          if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
              return 2 to StandardCharsets.UTF_16BE
          }

          val utf8 = StandardCharsets.UTF_8.newDecoder()
          return try {
              utf8.decode(java.nio.ByteBuffer.wrap(bytes))
              0 to StandardCharsets.UTF_8
          } catch (_: Throwable) {
              0 to Charset.forName("windows-1252")
          }
      }

      // ── DOCX ──────────────────────────────────────────────────────────────────

      private fun importDocx(context: Context, uri: Uri): List<ScriptLine> {
          val lines = mutableListOf<ScriptLine>()
          context.contentResolver.openInputStream(uri)?.use { fileStream ->
              val zip   = ZipInputStream(fileStream)
              var entry = zip.nextEntry
              while (entry != null) {
                  if (entry.name == "word/document.xml") {
                      lines.addAll(parseDocumentXml(zip))
                      break
                  }
                  entry = zip.nextEntry
              }
          }
          return lines
      }

      private fun parseDocumentXml(stream: ZipInputStream): List<ScriptLine> {
          val lines   = mutableListOf<ScriptLine>()
          val factory = XmlPullParserFactory.newInstance()
          factory.isNamespaceAware = true
          val parser  = factory.newPullParser()
          parser.setInput(stream, "UTF-8")

          var inParagraph = false
          val paraText    = StringBuilder()

          var eventType = parser.eventType
          while (eventType != XmlPullParser.END_DOCUMENT) {
              when (eventType) {
                  XmlPullParser.START_TAG -> {
                      if (parser.name == "p" && (parser.prefix == "w" || parser.namespace?.contains("wordprocessingml") == true)) {
                          inParagraph = true; paraText.clear()
                      }
                      if (parser.name == "t" && inParagraph) {
                          val space = parser.getAttributeValue("http://www.w3.org/XML/1998/namespace", "space")
                          val text  = parser.nextText()
                          if (space == "preserve") paraText.append(text)
                          else paraText.append(text.trim())
                      }
                  }
                  XmlPullParser.END_TAG -> {
                      if (parser.name == "p" && inParagraph) {
                          val trimmed = paraText.toString().trim()
                          if (trimmed.isNotEmpty()) lines.add(ScriptLine(trimmed))
                          inParagraph = false
                      }
                  }
              }
              eventType = parser.next()
          }
          return lines
      }
  }
  