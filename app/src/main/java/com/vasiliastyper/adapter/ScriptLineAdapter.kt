package com.vasiliastyper.adapter

  import android.view.LayoutInflater
  import android.view.View
  import android.view.ViewGroup
  import androidx.recyclerview.widget.RecyclerView
  import com.vasiliastyper.databinding.ItemScriptLineBinding
  import com.vasiliastyper.engine.ScriptLine

  class ScriptLineAdapter(
      private val lines: MutableList<ScriptLine>,
      private val onUse: (ScriptLine, Int) -> Unit,
      private val onEdit: ((ScriptLine, Int) -> Unit)? = null,
      private val onToggleSelect: ((Int) -> Unit)? = null
  ) : RecyclerView.Adapter<ScriptLineAdapter.VH>() {

      // v2.0: multi-select set
      val selected = mutableSetOf<Int>()
      var multiSelectMode = false

      inner class VH(val b: ItemScriptLineBinding) : RecyclerView.ViewHolder(b.root)

      override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
          val b = ItemScriptLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
          return VH(b)
      }

      override fun onBindViewHolder(h: VH, position: Int) {
          val line = lines[position]
          h.b.tvLineNumber.text = (position + 1).toString()
          h.b.tvScriptLine.text = line.text
          h.b.tvScriptSource.text = line.sourceText.orEmpty()
          h.b.tvScriptSource.visibility = if (line.sourceText.isNullOrBlank()) View.GONE else View.VISIBLE
          h.b.scriptColumnDivider.visibility = h.b.tvScriptSource.visibility
          h.b.tvUsedMark.text   = if (line.used) "✓" else ""
          h.b.root.alpha        = if (line.used) 0.5f else 1.0f

          // Preserve the rounded card treatment while showing multi-selection.
          h.b.root.setBackgroundResource(
              if (selected.contains(position)) com.vasiliastyper.R.drawable.script_line_selected_bg
              else com.vasiliastyper.R.drawable.script_line_bg
          )

          h.b.btnUseLine.setOnClickListener {
              val current = h.bindingAdapterPosition
              if (current != RecyclerView.NO_POSITION) onUse(lines[current], current)
          }
          h.b.btnEditLine.setOnClickListener {
              val current = h.bindingAdapterPosition
              if (current != RecyclerView.NO_POSITION) onEdit?.invoke(lines[current], current)
          }
          h.b.root.setOnClickListener {
              val current = h.bindingAdapterPosition
              if (current == RecyclerView.NO_POSITION) return@setOnClickListener
              if (multiSelectMode) {
                  if (selected.contains(current)) selected.remove(current)
                  else selected.add(current)
                  notifyItemChanged(current)
                  onToggleSelect?.invoke(current)
              } else {
                  onUse(lines[current], current)
              }
          }
          h.b.root.setOnLongClickListener {
              val current = h.bindingAdapterPosition
              if (current != RecyclerView.NO_POSITION && !multiSelectMode) {
                  multiSelectMode = true
                  selected.add(current)
                  notifyDataSetChanged()
                  onToggleSelect?.invoke(current)
                  true
              } else false
          }
      }

      override fun getItemCount() = lines.size

      fun resetUsed() { lines.forEach { it.used = false }; notifyDataSetChanged() }
      fun markUsed(index: Int) { lines.getOrNull(index)?.used = true; notifyItemChanged(index) }

      fun markSelectedAsUsed() {
          for (idx in selected) lines.getOrNull(idx)?.used = true
          notifyDataSetChanged()
      }

      fun getSelectedTexts(): List<String> = selected.sorted().mapNotNull { lines.getOrNull(it)?.text }

      fun clearSelection() { selected.clear(); multiSelectMode = false; notifyDataSetChanged() }
  }
  