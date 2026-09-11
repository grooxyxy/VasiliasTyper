package com.vasiliastyper.adapter

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import com.vasiliastyper.R
import com.vasiliastyper.databinding.ItemLayerBinding
import com.vasiliastyper.databinding.ItemLayerSectionBinding
import com.vasiliastyper.model.CanvasStackItem
import com.vasiliastyper.model.CanvasStackType
import com.vasiliastyper.model.ImageElement
import com.vasiliastyper.model.Layer
import com.vasiliastyper.model.TextElement

/** One unrestricted layer stack shared by pixel, image and text elements. */
class LayerAdapter(
    private val layers: MutableList<Layer>,
    private val textElements: MutableList<TextElement> = mutableListOf(),
    private val imageElements: MutableList<ImageElement> = mutableListOf(),
    private val stackOrder: MutableList<CanvasStackItem>,
    private var textLayerVisible: Boolean = true,
    private val onTextLayerVisibilityToggle: ((Boolean) -> Unit)? = null,
    private var imageLayerVisible: Boolean = true,
    private val onImageLayerVisibilityToggle: ((Boolean) -> Unit)? = null,
    private val onLayerClick: (Int) -> Unit,
    private val onVisibilityToggle: (Int) -> Unit,
    private val onLockToggle: (Int) -> Unit,
    private val onDuplicate: (Int) -> Unit = {},
    private val onRename: (Int, String) -> Unit = { _, _ -> },
    private val onMergeDown: (Int) -> Unit = {},
    private val onTextElementClick: (Int) -> Unit = {},
    private val onImageElementClick: (Int) -> Unit = {},
    private val onStackChanged: () -> Unit = {},
    private val onStartDrag: (RecyclerView.ViewHolder) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var selectedIndex: Int = 0
    var selectedTextIndex: Int = -1
    var selectedImageIndex: Int = -1

    private sealed class Item {
        data class Header(val count: Int) : Item()
        data class StackRow(val ref: CanvasStackItem, val stackIndex: Int) : Item()
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ROW = 1
        private const val COL_TEXT = 0xFF4D9BF0.toInt()
        private const val COL_IMAGE = 0xFF26A69A.toInt()
        private const val COL_LAYER = 0xFF607D8B.toInt()
        private const val COL_BG = 0xFFE65100.toInt()
        private const val COL_ACTIVE_BG = 0xFF0D47A1.toInt()
        private const val COL_ACTIVE_STRIPE = 0xFFFFD600.toInt()
        private const val COL_INACTIVE_BG = 0xFF2B2B2B.toInt()
    }

    private fun buildItems(): List<Item> {
        val rows = mutableListOf<Item>(Item.Header(stackOrder.size))
        for (index in stackOrder.indices.reversed()) {
            rows += Item.StackRow(stackOrder[index], index)
        }
        return rows
    }

    inner class HeaderVH(val binding: ItemLayerSectionBinding) : RecyclerView.ViewHolder(binding.root)
    inner class RowVH(val binding: ItemLayerBinding) : RecyclerView.ViewHolder(binding.root)

    override fun getItemViewType(position: Int): Int =
        if (buildItems()[position] is Item.Header) TYPE_HEADER else TYPE_ROW

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderVH(ItemLayerSectionBinding.inflate(inflater, parent, false))
        } else {
            RowVH(ItemLayerBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = buildItems()[position]) {
            is Item.Header -> bindHeader(holder as HeaderVH, item)
            is Item.StackRow -> bindRow(holder as RowVH, item)
        }
    }

    override fun getItemCount(): Int = stackOrder.size + 1

    fun refresh() = notifyDataSetChanged()

    fun selectedStackItem(): CanvasStackItem? = when {
        selectedTextIndex in textElements.indices ->
            CanvasStackItem(CanvasStackType.TEXT, textElements[selectedTextIndex].id)
        selectedImageIndex in imageElements.indices ->
            CanvasStackItem(CanvasStackType.IMAGE, imageElements[selectedImageIndex].id)
        selectedIndex in layers.indices ->
            CanvasStackItem(CanvasStackType.PIXEL, layers[selectedIndex].id)
        else -> null
    }

    fun clearElementSelection() {
        selectedTextIndex = -1
        selectedImageIndex = -1
    }

    fun stackIndexAt(adapterPosition: Int): Int =
        (buildItems().getOrNull(adapterPosition) as? Item.StackRow)?.stackIndex ?: -1

    fun isBackgroundAt(adapterPosition: Int): Boolean {
        val stackIndex = stackIndexAt(adapterPosition)
        if (stackIndex < 0) return false
        val ref = stackOrder[stackIndex]
        return ref.type == CanvasStackType.PIXEL && ref.id == layers.firstOrNull()?.id
    }

    fun moveCanvasItem(fromStackIndex: Int, toStackIndex: Int): Boolean {
        if (fromStackIndex !in stackOrder.indices || toStackIndex !in stackOrder.indices) return false
        val backgroundId = layers.firstOrNull()?.id
        val moving = stackOrder[fromStackIndex]
        val target = stackOrder[toStackIndex]
        if (moving.type == CanvasStackType.PIXEL && moving.id == backgroundId) return false
        if (target.type == CanvasStackType.PIXEL && target.id == backgroundId) return false
        if (fromStackIndex == toStackIndex) return true
        stackOrder.add(toStackIndex, stackOrder.removeAt(fromStackIndex))
        notifyDataSetChanged()
        onStackChanged()
        return true
    }

    fun moveSelected(up: Boolean): Boolean {
        val selectedRef = when {
            selectedTextIndex in textElements.indices -> {
                CanvasStackItem(CanvasStackType.TEXT, textElements[selectedTextIndex].id)
            }
            selectedImageIndex in imageElements.indices -> {
                CanvasStackItem(CanvasStackType.IMAGE, imageElements[selectedImageIndex].id)
            }
            selectedIndex in layers.indices -> {
                CanvasStackItem(CanvasStackType.PIXEL, layers[selectedIndex].id)
            }
            else -> return false
        }
        val from = stackOrder.indexOf(selectedRef)
        val to = from + if (up) 1 else -1
        return moveCanvasItem(from, to)
    }

    private fun bindHeader(holder: HeaderVH, item: Item.Header) = with(holder.binding) {
        sectionTitle.text = "CANVAS STACK · DRAG BEBAS"
        sectionCount.text = item.count.toString()
        sectionStripe.setBackgroundColor(COL_LAYER)
        sectionCount.setBackgroundColor(COL_LAYER)
    }

    private fun bindRow(holder: RowVH, item: Item.StackRow) {
        when (item.ref.type) {
            CanvasStackType.PIXEL -> bindPixel(holder, item)
            CanvasStackType.IMAGE -> bindImage(holder, item)
            CanvasStackType.TEXT -> bindText(holder, item)
        }
    }

    private fun prepareCommon(holder: RowVH, item: Item.StackRow, isBackground: Boolean) = with(holder.binding) {
        layoutMoveOrder.visibility = View.GONE
        dragHandle.visibility = if (isBackground) View.GONE else View.VISIBLE
        dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onStartDrag(holder)
                true
            } else {
                false
            }
        }
    }

    private fun bindText(holder: RowVH, item: Item.StackRow) {
        val index = textElements.indexOfFirst { it.id == item.ref.id }
        val element = textElements.getOrNull(index) ?: return
        val active = index == selectedTextIndex
        prepareCommon(holder, item, false)
        with(holder.binding) {
            layerTypeStripe.setBackgroundColor(if (active) COL_ACTIVE_STRIPE else COL_TEXT)
            root.setBackgroundColor(if (active) COL_ACTIVE_BG else Color.parseColor("#0D1520"))
            layerName.text = element.name.ifBlank {
                element.text.take(28).replace('\n', ' ').ifBlank { "Text ${index + 1}" }
            }
            layerName.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            layerSubLabel.text = "T · ${element.fontSize.toInt()}sp · z${item.stackIndex}"
            layerSubLabel.visibility = View.VISIBLE
            layerThumb.setImageDrawable(null)
            layerThumb.setBackgroundColor(element.color)
            btnLayerLock.visibility = View.VISIBLE
            btnLayerLock.setColorFilter(if (element.isLocked) COL_TEXT else Color.parseColor("#555555"))
            btnLayerLock.setOnClickListener {
                element.isLocked = !element.isLocked
                notifyDataSetChanged()
                onStackChanged()
            }
            btnMergeDown.visibility = View.GONE
            btnLayerVisible.setImageResource(
                if (element.isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off
            )
            btnLayerVisible.setOnClickListener {
                element.isVisible = !element.isVisible
                notifyDataSetChanged()
                onTextLayerVisibilityToggle?.invoke(textElements.any { it.isVisible })
                onStackChanged()
            }
            root.setOnClickListener {
                selectedTextIndex = index
                selectedImageIndex = -1
                notifyDataSetChanged()
                onTextElementClick(index)
            }
            layerName.setOnLongClickListener {
                showElementRenameDialog(it, element.name) { name ->
                    element.name = name
                    notifyDataSetChanged()
                    onStackChanged()
                }
                true
            }
        }
    }

    private fun bindImage(holder: RowVH, item: Item.StackRow) {
        val index = imageElements.indexOfFirst { it.id == item.ref.id }
        val image = imageElements.getOrNull(index) ?: return
        val active = index == selectedImageIndex
        prepareCommon(holder, item, false)
        with(holder.binding) {
            layerTypeStripe.setBackgroundColor(if (active) COL_ACTIVE_STRIPE else COL_IMAGE)
            root.setBackgroundColor(if (active) COL_ACTIVE_BG else Color.parseColor("#0A1A1A"))
            layerName.text = image.name.ifBlank { "Image ${index + 1}" }
            layerName.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            layerSubLabel.text = "Img · ${image.width.toInt()}×${image.height.toInt()} · z${item.stackIndex}"
            layerSubLabel.visibility = View.VISIBLE
            if (!image.bitmap.isRecycled) layerThumb.setImageBitmap(image.bitmap)
            btnLayerLock.visibility = View.VISIBLE
            btnLayerLock.setColorFilter(if (image.isLocked) COL_TEXT else Color.parseColor("#555555"))
            btnLayerLock.setOnClickListener {
                image.isLocked = !image.isLocked
                notifyDataSetChanged()
                onStackChanged()
            }
            btnMergeDown.visibility = View.GONE
            btnLayerVisible.setImageResource(
                if (image.isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off
            )
            btnLayerVisible.setOnClickListener {
                image.isVisible = !image.isVisible
                notifyDataSetChanged()
                onImageLayerVisibilityToggle?.invoke(imageElements.any { it.isVisible })
                onStackChanged()
            }
            root.setOnClickListener {
                selectedImageIndex = index
                selectedTextIndex = -1
                notifyDataSetChanged()
                onImageElementClick(index)
            }
            layerName.setOnLongClickListener {
                showElementRenameDialog(it, image.name) { name ->
                    image.name = name
                    notifyDataSetChanged()
                    onStackChanged()
                }
                true
            }
        }
    }

    private fun showElementRenameDialog(anchor: View, currentName: String, onSave: (String) -> Unit) {
        val editor = EditText(anchor.context).apply {
            setText(currentName)
            setPadding(40, 16, 40, 16)
            selectAll()
        }
        AlertDialog.Builder(anchor.context)
            .setTitle("Rename Layer")
            .setView(editor)
            .setPositiveButton("Rename") { _, _ ->
                onSave(editor.text.toString().trim().ifBlank { currentName })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun bindPixel(holder: RowVH, item: Item.StackRow) {
        val index = layers.indexOfFirst { it.id == item.ref.id }
        val layer = layers.getOrNull(index) ?: return
        val background = index == 0
        val active = index == selectedIndex && selectedTextIndex < 0 && selectedImageIndex < 0
        prepareCommon(holder, item, background)
        with(holder.binding) {
            layerTypeStripe.setBackgroundColor(
                if (active) COL_ACTIVE_STRIPE else if (background) COL_BG else COL_LAYER
            )
            root.setBackgroundColor(if (active) COL_ACTIVE_BG else COL_INACTIVE_BG)
            layerName.text = layer.name
            layerName.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            val maskStatus = buildList {
                if (layer.isClippingMask) add("clip")
                if (layer.isAlphaMaskEnabled && layer.hasAlphaMask) add("α")
                if (layer.folderId != null) add("folder")
            }.joinToString(" · ")
            layerSubLabel.text = buildString {
                append("${if (background) "BG" else "px"} · ${layer.opacity}% · z${item.stackIndex}")
                if (maskStatus.isNotEmpty()) append(" · $maskStatus")
            }
            layerSubLabel.visibility = View.VISIBLE
            if (!layer.bitmap.isRecycled) layerThumb.setImageBitmap(layer.bitmap)
            btnLayerLock.visibility = View.VISIBLE
            btnLayerLock.setColorFilter(if (layer.isLocked) COL_TEXT else Color.parseColor("#555555"))
            btnLayerVisible.setImageResource(
                if (layer.isVisible) R.drawable.ic_eye else R.drawable.ic_eye_off
            )
            btnMergeDown.visibility = if (index > 0) View.VISIBLE else View.GONE
            btnMergeDown.setOnClickListener { view ->
                AlertDialog.Builder(view.context)
                    .setTitle("Gabung Layer")
                    .setMessage("Gabung layer dengan pixel layer di bawahnya?")
                    .setPositiveButton("Gabung") { _, _ -> onMergeDown(index) }
                    .setNegativeButton("Batal", null)
                    .show()
            }
            root.setOnClickListener {
                selectedIndex = index
                selectedTextIndex = -1
                selectedImageIndex = -1
                notifyDataSetChanged()
                onLayerClick(index)
            }
            btnLayerVisible.setOnClickListener { onVisibilityToggle(index) }
            btnLayerLock.setOnClickListener { onLockToggle(index) }
            layerName.setOnLongClickListener {
                val editor = EditText(it.context).apply {
                    setText(layer.name)
                    setPadding(40, 16, 40, 16)
                }
                AlertDialog.Builder(it.context)
                    .setTitle("Rename Layer")
                    .setView(editor)
                    .setPositiveButton("Rename") { _, _ ->
                        val name = editor.text.toString().ifBlank { layer.name }
                        layer.name = name
                        notifyDataSetChanged()
                        onRename(index, name)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
        }
    }
}
