package com.vasiliastyper.adapter

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vasiliastyper.R
import com.vasiliastyper.model.OcrResult

/**
 * OCR result editor. Both SOURCE and TRANSLATION deliberately remain normal
 * EditTexts so users can type, edit, select, and paste text manually.
 */
class OcrResultAdapter(
    val items: MutableList<OcrResult>,
    private val onDel: (Int) -> Unit,
    private val onRet: (Int) -> Unit,
    private val onCopyOriginal: (Int) -> Unit,
    private val onCopyTranslation: (Int) -> Unit
) : RecyclerView.Adapter<OcrResultAdapter.VH>() {

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val indexLabel: TextView = view.findViewById(R.id.tvOcrIndex)
        val languageLabel: TextView = view.findViewById(R.id.tvOcrLang)
        val originalEditor: EditText = view.findViewById(R.id.etOcrOriginal)
        val translationEditor: EditText = view.findViewById(R.id.etOcrTranslated)
        val copyOriginalButton: ImageButton = view.findViewById(R.id.btnCopyOcrOriginal)
        val copyTranslationButton: ImageButton = view.findViewById(R.id.btnCopyOcrTranslated)
        val retranslateButton: ImageButton = view.findViewById(R.id.btnRetranslate)
        val deleteButton: ImageButton = view.findViewById(R.id.btnDeleteOcr)

        var boundItem: OcrResult? = null
        var bindingFields = false

        init {
            originalEditor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!bindingFields) boundItem?.originalText = s?.toString().orEmpty()
                }
            })
            translationEditor.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!bindingFields) boundItem?.translatedText = s?.toString().orEmpty()
                }
            })
            copyOriginalButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onCopyOriginal(position)
            }
            copyTranslationButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onCopyTranslation(position)
            }
            retranslateButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onRet(position)
            }
            deleteButton.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onDel(position)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_ocr_result, parent, false)
    )

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.boundItem = item
        holder.bindingFields = true
        val region = item.regionRect
        holder.indexLabel.text = if (region == null) {
            "Region ${position + 1}"
        } else {
            "Region ${position + 1} • ${region.left.toInt()},${region.top.toInt()}  ${region.width().toInt()}×${region.height().toInt()}"
        }
        holder.languageLabel.text = item.sourceLang.uppercase()
        holder.copyOriginalButton.isEnabled = item.originalText.isNotBlank()
        holder.copyOriginalButton.alpha = if (item.originalText.isNotBlank()) 1f else 0.35f
        val canCopyTranslation = !item.translatedText.isNullOrBlank()
        holder.copyTranslationButton.isEnabled = canCopyTranslation
        holder.copyTranslationButton.alpha = if (canCopyTranslation) 1f else 0.35f
        if (holder.originalEditor.text.toString() != item.originalText) {
            holder.originalEditor.setText(item.originalText)
        }
        val translation = item.translatedText.orEmpty()
        if (holder.translationEditor.text.toString() != translation) {
            holder.translationEditor.setText(translation)
        }
        holder.bindingFields = false
    }

    override fun onViewRecycled(holder: VH) {
        holder.boundItem = null
        super.onViewRecycled(holder)
    }
}
