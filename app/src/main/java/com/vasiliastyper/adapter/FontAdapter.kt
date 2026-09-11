package com.vasiliastyper.adapter

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.vasiliastyper.R

class FontAdapter(
    private val fonts: List<FontItem>,
    private val onFontSelected: (FontItem) -> Unit
) : RecyclerView.Adapter<FontAdapter.FontVH>() {

    private var query = ""
    private var folder = ALL_FOLDERS
    private var filteredFonts = fonts.toMutableList()
    var selectedIndex = -1

    inner class FontVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FontVH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return FontVH(view)
    }

    override fun onBindViewHolder(holder: FontVH, position: Int) {
        val item = filteredFonts[position]
        holder.tvName.text = item.displayName
        holder.tvName.typeface = item.typeface
        holder.tvName.setTextColor(
            if (position == selectedIndex) android.graphics.Color.parseColor("#4D9BF0")
            else android.graphics.Color.parseColor("#CCCCCC")
        )
        holder.itemView.setOnClickListener {
            selectedIndex = position
            notifyDataSetChanged()
            onFontSelected(item)
        }
    }

    override fun getItemCount() = filteredFonts.size

    fun filter(query: String) {
        this.query = query.trim()
        applyFilters()
    }

    fun filterFolder(folder: String) {
        this.folder = folder
        applyFilters()
    }

    private fun applyFilters() {
        filteredFonts = fonts.filter { item ->
            (folder == ALL_FOLDERS || item.folder.equals(folder, ignoreCase = true)) &&
                (query.isEmpty() || item.displayName.contains(query, ignoreCase = true) ||
                    item.folder.contains(query, ignoreCase = true))
        }.toMutableList()
        selectedIndex = -1
        notifyDataSetChanged()
    }

    companion object {
        const val ALL_FOLDERS = "All Folders"
    }
}
