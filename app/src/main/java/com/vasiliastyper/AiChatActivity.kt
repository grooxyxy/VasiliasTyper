package com.vasiliastyper

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.vasiliastyper.engine.AiChatClient
import com.vasiliastyper.engine.AiChatProvider
import com.vasiliastyper.engine.AiChatSettings
import com.vasiliastyper.engine.AiConversationMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Dedicated AI workspace: two text-chat providers plus Agnes 2.5 Flash canvas vision. */
class AiChatActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_IMAGE_PATH = "ai_image_path"
        const val EXTRA_IMAGE_SCOPE = "ai_image_scope"
    }

    private lateinit var messagesContainer: LinearLayout
    private lateinit var messagesScroll: ScrollView
    private lateinit var promptInput: EditText
    private lateinit var sendButton: TextView
    private lateinit var visionButton: TextView
    private lateinit var modelButton: TextView
    private lateinit var progress: ProgressBar
    private var provider = AiChatProvider.GPT_35
    private var attachedBitmap: Bitmap? = null
    private val visibleTranscript = mutableListOf<Pair<String, String>>()
    private val conversation = mutableListOf(
        AiConversationMessage("system", "You are an assistant for a professional webtoon and manga typesetting workspace. Be concise and practical.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildPage())
        loadAttachment()
        addBubble("AI Studio siap. GPT-3.5 Turbo dan Claude terhubung melalui Nexray tanpa API key. Gunakan Agnes Vision untuk memahami kanvas atau area pilihan.", false)
    }

    override fun onDestroy() {
        attachedBitmap?.takeUnless { it.isRecycled }?.recycle()
        attachedBitmap = null
        super.onDestroy()
    }

    private fun buildPage(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#09090D"))
        setPadding(dp(14), dp(10), dp(14), dp(12))

        addView(LinearLayout(this@AiChatActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button("‹") { finish() }, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(LinearLayout(this@AiChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), 0, dp(8), 0)
                addView(label("AI Chat & Canvas Vision", 19f, "#F2F2F4", true))
                addView(label("GPT & Claude via Nexray GET API", 10f, "#9895A4"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(button("Salin Semua") { copyAllMessages() }, LinearLayout.LayoutParams(dp(82), dp(42)))
            addView(button("Vision Key") { showSettings() }, LinearLayout.LayoutParams(dp(82), dp(42)).apply {
                marginStart = dp(6)
            })
        })

        addView(LinearLayout(this@AiChatActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded("#15131C", 14, "#3C3151")
            setPadding(dp(12), dp(10), dp(12), dp(10))
            addView(label("Cara pakai", 12f, "#DCCBFF", true))
            addView(label("1. Pilih model  •  2. Ketik pertanyaan  •  3. Tekan Kirim", 11f, "#F2F2F4"))
            addView(label("Untuk gambar, buka dari editor lalu tekan Analisis Kanvas. Tombol Salin menyalin satu pesan; tekan lama teks untuk memilih bagian tertentu.", 10f, "#9895A4"))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
            bottomMargin = dp(4)
        })

        addView(LinearLayout(this@AiChatActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            modelButton = button("Model: ${provider.displayName}") { chooseModel() }
            addView(modelButton, LinearLayout.LayoutParams(0, dp(44), 1f))
            visionButton = button("Analisis Kanvas") { sendVision() }
            addView(visionButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)).apply { topMargin = dp(4) })

        val attachmentCard = LinearLayout(this@AiChatActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded("#18171E", 14, "#35313F")
            setPadding(dp(10), dp(8), dp(12), dp(8))
            val preview = ImageView(this@AiChatActivity).apply {
                id = View.generateViewId()
                tag = "attachment_preview"
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.parseColor("#24232C"))
            }
            addView(preview, LinearLayout.LayoutParams(dp(58), dp(58)))
            val scope = intent.getStringExtra(EXTRA_IMAGE_SCOPE) ?: "No canvas attachment"
            addView(LinearLayout(this@AiChatActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
                addView(label("Lampiran kanvas", 13f, "#F2F2F4", true))
                addView(label(scope, 11f, "#9895A4"))
                addView(label("Hanya dikirim saat Analisis Kanvas ditekan", 10f, "#777382"))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        addView(attachmentCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(76)).apply { bottomMargin = dp(8) })

        messagesContainer = LinearLayout(this@AiChatActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        messagesScroll = ScrollView(this@AiChatActivity).apply {
            isFillViewport = true
            addView(messagesContainer)
        }
        addView(messagesScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        progress = ProgressBar(this@AiChatActivity).apply { visibility = View.GONE }
        addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        addView(LinearLayout(this@AiChatActivity).apply {
            gravity = Gravity.BOTTOM
            promptInput = EditText(this@AiChatActivity).apply {
                hint = "Tanyakan terjemahan, SFX, layout, atau kanvas…"
                setHintTextColor(Color.parseColor("#777382"))
                setTextColor(Color.WHITE)
                textSize = 14f
                minLines = 1
                maxLines = 5
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_NONE
                background = rounded("#18171E", 16, "#35313F")
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }
            addView(promptInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sendButton = button("Kirim") { sendChat() }
            addView(sendButton, LinearLayout.LayoutParams(dp(72), dp(50)).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    private fun loadAttachment() {
        val path = intent.getStringExtra(EXTRA_IMAGE_PATH) ?: return
        lifecycleScope.launch {
            attachedBitmap = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
            val preview = findViewByTag<ImageView>(findViewById<ViewGroup>(android.R.id.content), "attachment_preview")
            preview?.setImageBitmap(attachedBitmap)
            visionButton.isEnabled = attachedBitmap != null
            if (attachedBitmap == null) Toast.makeText(this@AiChatActivity, "Canvas attachment could not be loaded", Toast.LENGTH_LONG).show()
            runCatching { File(path).delete() }
        }
    }

    private fun chooseModel() {
        val options = AiChatProvider.entries.toTypedArray()
        AlertDialog.Builder(this).setTitle("Text chat model")
            .setSingleChoiceItems(options.map { it.displayName }.toTypedArray(), provider.ordinal) { dialog, index ->
                provider = options[index]
                modelButton.text = "Model: ${provider.displayName}"
                dialog.dismiss()
            }.show()
    }

    private fun sendChat() {
        val prompt = promptInput.text.toString().trim()
        if (prompt.isBlank()) return
        val userMessage = AiConversationMessage("user", prompt)
        conversation += userMessage
        promptInput.text.clear()
        addBubble(prompt, true)
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { AiChatClient.chat(this@AiChatActivity, provider, conversation) } }
            setBusy(false)
            result.onSuccess { answer ->
                conversation += AiConversationMessage("assistant", answer)
                addBubble(answer, false)
            }.onFailure { showError(it) }
        }
    }

    private fun sendVision() {
        val bitmap = attachedBitmap
        if (bitmap == null || bitmap.isRecycled) {
            Toast.makeText(this, "Open AI Chat from the editor to attach a canvas.", Toast.LENGTH_LONG).show()
            return
        }
        val prompt = promptInput.text.toString().trim().ifBlank {
            "Analyze this webtoon canvas. Identify visible text, language, layout, speech bubbles, sound effects, and any typesetting issues. Give practical recommendations."
        }
        promptInput.text.clear()
        addBubble("[Agnes 2.5 Flash Vision] $prompt", true)
        setBusy(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AiChatClient.understandImage(this@AiChatActivity, prompt, bitmap) }
            }
            setBusy(false)
            result.onSuccess { addBubble(it, false) }.onFailure { showError(it) }
        }
    }

    private fun showSettings() {
        val keys = AiChatSettings.load(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), 0, dp(24), 0)
        }
        fun secret(hint: String, value: String) = EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        val agnes = secret("Agnes API key", keys.agnes)
        panel.addView(label("GPT-3.5 Turbo dan Claude tidak memerlukan API key karena memakai endpoint GET Nexray. Key di bawah hanya untuk Agnes Vision.", 11f, "#9895A4"))
        panel.addView(agnes)
        AlertDialog.Builder(this).setTitle("Agnes Vision key").setView(panel)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                AiChatSettings.save(this, keys.openAi, keys.claude, agnes.text.toString())
                Toast.makeText(this, "Agnes Vision key saved", Toast.LENGTH_SHORT).show()
            }.show()
    }

    private fun addBubble(text: String, fromUser: Boolean) {
        val role = if (fromUser) "Anda" else "AI"
        visibleTranscript += role to text
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            background = rounded(
                if (fromUser) "#5B35C8" else "#18171E",
                16,
                if (fromUser) "#9B82EE" else "#3D3948"
            )

            addView(LinearLayout(this@AiChatActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(label(role, 11f, if (fromUser) "#F1EAFF" else "#BDA5F4", true),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val copy = label("Salin", 11f, "#F1EAFF", true).apply {
                    gravity = Gravity.CENTER
                    setPadding(dp(10), dp(5), dp(10), dp(5))
                    background = rounded(if (fromUser) "#704DD0" else "#292532", 10, "#66587B")
                    contentDescription = "Salin pesan $role"
                    setOnClickListener { copyMessage(text) }
                }
                addView(copy, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)))
            })

            addView(label(text, 14f, "#F2F2F4").apply {
                setPadding(0, dp(5), 0, 0)
                setTextIsSelectable(true)
                contentDescription = "Pesan dari $role. Tekan lama lalu geser penanda untuk menyalin teks pilihan."
            })
        }
        messagesContainer.addView(card, LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (fromUser) Gravity.END else Gravity.START
            topMargin = dp(8)
        })
        messagesScroll.post { messagesScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyMessage(text: String) {
        copyText(text, "Pesan disalin")
    }

    private fun copyAllMessages() {
        val text = visibleTranscript.joinToString("\n\n") { (role, content) -> "$role:\n$content" }
        copyText(text, "Semua percakapan disalin")
    }

    private fun copyText(text: String, confirmation: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Belum ada teks untuk disalin", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Chat VasiliasTyper", text))
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
    }

    private fun setBusy(busy: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        sendButton.isEnabled = !busy
        visionButton.isEnabled = !busy && attachedBitmap != null
        modelButton.isEnabled = !busy
    }

    private fun showError(error: Throwable) {
        val message = error.message?.take(600) ?: "Unknown AI error"
        addBubble("Request failed: $message", false)
    }

    private fun button(text: String, action: () -> Unit) = label(text, 12f, "#E9DDFF", true).apply {
        gravity = Gravity.CENTER
        background = rounded("#24212D", 12, "#4E3A70")
        setOnClickListener { action() }
    }

    private fun label(text: String, size: Float, color: String, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun rounded(color: String, radius: Int, stroke: String? = null) = GradientDrawable().apply {
        setColor(Color.parseColor(color)); cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), Color.parseColor(it)) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun <T : View> findViewByTag(root: ViewGroup, tag: String): T? {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            if (child.tag == tag) @Suppress("UNCHECKED_CAST") return child as? T
            if (child is ViewGroup) findViewByTag<T>(child, tag)?.let { return it }
        }
        return null
    }
}
