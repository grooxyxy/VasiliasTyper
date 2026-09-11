package com.vasiliastyper.adapter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.vasiliastyper.databinding.ItemStyleBinding
import com.vasiliastyper.engine.FontResolver
import com.vasiliastyper.model.TextStyle
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Folder-aware style list. Every folder is an expandable row, so Style Manager
 * and the editor's Load flow remain tidy even with hundreds of presets.
 */
class StyleAdapter(
    private var styles: MutableList<TextStyle>,
    private val onApply: (TextStyle) -> Unit,
    private val onDelete: (TextStyle, Int) -> Unit,
    private val allowDelete: Boolean = true,
    private val initiallyCollapsed: Boolean = true
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private sealed class Row {
        data class Folder(val name: String, val count: Int, val collapsed: Boolean) : Row()
        data class Style(val value: TextStyle) : Row()
    }

    private val collapsedFolders = linkedSetOf<String>().apply {
        if (initiallyCollapsed) styles.mapTo(this) { folderKey(it.folder) }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val previewTypefaceCache = object : LinkedHashMap<String, android.graphics.Typeface>(24, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, android.graphics.Typeface>?
        ): Boolean = size > 24
    }
    private val previewBitmapCache = object : LinkedHashMap<String, Bitmap>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 20
    }
    private var previewExecutor: ExecutorService = newPreviewExecutor()
    private var previewGeneration = 0
    private var isAttached = false
    private var groupedStyles: Map<String, List<TextStyle>> = buildGroups(styles)
    private var rows: List<Row> = buildRows()

    private class StyleVH(val binding: ItemStyleBinding) : RecyclerView.ViewHolder(binding.root)
    private class FolderVH(val label: TextView) : RecyclerView.ViewHolder(label)

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Folder -> VIEW_FOLDER
        is Row.Style -> VIEW_STYLE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        if (viewType == VIEW_FOLDER) {
            val context = parent.context
            val density = parent.resources.displayMetrics.density
            val horizontal = (14 * density).toInt()
            val vertical = (11 * density).toInt()
            val label = TextView(context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    leftMargin = (2 * density).toInt()
                    rightMargin = (2 * density).toInt()
                    topMargin = (5 * density).toInt()
                    bottomMargin = (3 * density).toInt()
                }
                gravity = Gravity.CENTER_VERTICAL
                setPadding(horizontal, vertical, horizontal, vertical)
                // Theme-driven colours + ripple replace the previous hardcoded dark hex
                // values, so folder headers now match the rest of Luma Deck and give
                // visible press feedback instead of a flat, unresponsive strip.
                setTextColor(ContextCompat.getColor(context, com.vasiliastyper.R.color.ps_text_bright))
                textSize = 13f
                setBackgroundResource(com.vasiliastyper.R.drawable.style_folder_header_bg)
                // Theme-aware ripple. resolveAttribute is safe pre-attach (unlike reading
                // styled attributes from a detached view) and never returns a stale id.
                val outValue = android.util.TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)) {
                    foreground = ContextCompat.getDrawable(context, outValue.resourceId)
                }
                isClickable = true
                isFocusable = true
            }
            return FolderVH(label)
        }
        return StyleVH(ItemStyleBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Folder -> bindFolder(holder as FolderVH, row)
            is Row.Style -> bindStyle(holder as StyleVH, row.value)
        }
    }

    override fun getItemCount(): Int = rows.size

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is StyleVH) releasePreview(holder.binding)
        super.onViewRecycled(holder)
    }

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        isAttached = true
        if (previewExecutor.isShutdown) previewExecutor = newPreviewExecutor()
        super.onAttachedToRecyclerView(recyclerView)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        isAttached = false
        previewGeneration++
        previewExecutor.shutdownNow()
        for (index in 0 until recyclerView.childCount) {
            val holder = recyclerView.getChildViewHolder(recyclerView.getChildAt(index))
            if (holder is StyleVH) releasePreview(holder.binding)
        }
        synchronized(previewTypefaceCache) { previewTypefaceCache.clear() }
        synchronized(previewBitmapCache) { previewBitmapCache.clear() }
        super.onDetachedFromRecyclerView(recyclerView)
    }

    fun updateList(newStyles: MutableList<TextStyle>) {
        val wasEmpty = styles.isEmpty()
        styles = newStyles
        groupedStyles = buildGroups(styles)
        previewGeneration++
        synchronized(previewBitmapCache) { previewBitmapCache.clear() }
        if (initiallyCollapsed && wasEmpty) {
            groupedStyles.keys.mapTo(collapsedFolders, ::folderKey)
        }
        rows = buildRows()
        notifyDataSetChanged()
    }

    fun expandAll() {
        collapsedFolders.clear()
        rows = buildRows()
        notifyDataSetChanged()
    }

    fun collapseAll() {
        collapsedFolders.clear()
        groupedStyles.keys.mapTo(collapsedFolders, ::folderKey)
        rows = buildRows()
        notifyDataSetChanged()
    }

    private fun bindFolder(holder: FolderVH, folder: Row.Folder) {
        holder.label.text = buildString {
            append(if (folder.collapsed) "▸  " else "▾  ")
            append(folder.name)
            append("   ·   ")
            append(folder.count)
        }
        holder.label.contentDescription = if (folder.collapsed) {
            "Buka folder ${folder.name}, ${folder.count} style"
        } else {
            "Tutup folder ${folder.name}, ${folder.count} style"
        }
        holder.label.setOnClickListener {
            val key = folderKey(folder.name)
            if (!collapsedFolders.add(key)) collapsedFolders.remove(key)
            rows = buildRows()
            notifyDataSetChanged()
        }
    }

    private fun bindStyle(holder: StyleVH, style: TextStyle) {
        val binding = holder.binding
        releasePreview(binding)
        binding.tvStyleName.text = style.name

        val effectLabel = when (style.effect) {
            "OUTLINE" -> "Outline"
            "SHADOW" -> "Shadow"
            "OUTLINE_SHADOW" -> "Out+Shd"
            "GRADIENT" -> "Gradient"
            "TEXTURE" -> "Texture"
            "WARP" -> "Warp"
            else -> "None"
        }
        val extras = buildString {
            if (style.enableOutline) append("+Outline ")
            if (style.enableShadow) append("+Shadow ")
            if (style.enableGradient) append("+Gradient ")
            if (style.enableTexture) append("+Texture ")
        }.trim()
        val category = style.presetCategory.takeIf { it.isNotBlank() }?.let { "$it · " }.orEmpty()
        val editHint = style.presetDescription.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        binding.tvStyleDesc.text = category + "${style.fontSize.toInt()}sp" +
            (if (style.isBold) " Bold" else "") +
            (if (style.isItalic) " Italic" else "") +
            " · $effectLabel" +
            (if (extras.isNotEmpty()) " · $extras" else "") +
            " · ${style.align.lowercase()}" + editHint

        // Font resolution and bitmap drawing must never run in RecyclerView binding.
        // Doing it here blocked folder expansion and could trigger ANR/OOM when many
        // SFX presets became visible at once. A request token prevents a recycled row
        // from receiving the bitmap generated for its previous style.
        loadPreviewAsync(binding, style)
        binding.btnApplyStyle.setOnClickListener {
            // Disable briefly to stop a rapid double-tap from firing two applies while
            // the manager bottom sheet is mid-dismiss (a known crash source).
            if (binding.btnApplyStyle.isEnabled) {
                binding.btnApplyStyle.isEnabled = false
                onApply(style)
                binding.btnApplyStyle.postDelayed({ binding.btnApplyStyle.isEnabled = true }, 400)
            }
        }
        binding.btnDeleteStyle.visibility = if (allowDelete) View.VISIBLE else View.GONE
        binding.btnDeleteStyle.setOnClickListener(
            if (allowDelete) View.OnClickListener {
                // indexOf can return -1 if the list changed between bind and tap; guard it.
                val index = styles.indexOfFirst { it.name == style.name && it.folder == style.folder }
                onDelete(style, index)
            } else null
        )
    }

    private fun loadPreviewAsync(binding: ItemStyleBinding, style: TextStyle) {
        // The renderer receives an immutable-by-convention snapshot. Applying or
        // editing the same style while its preview is queued can otherwise mutate
        // gradient data from another thread.
        val previewStyle = style.safeCopyForApply()
        val key = previewKey(previewStyle)
        binding.ivStylePreview.tag = key
        binding.ivStylePreview.setImageDrawable(null)
        synchronized(previewBitmapCache) { previewBitmapCache[key] }?.let {
            binding.ivStylePreview.setImageBitmap(it)
            return
        }
        if (!isAttached) return
        val generation = previewGeneration
        try {
            previewExecutor.execute {
                if (Thread.currentThread().isInterrupted) return@execute
                val preview = try {
                    buildPreview(binding.root.context.applicationContext, previewStyle)
                } catch (_: Throwable) {
                    // A damaged custom font or memory pressure must not close the panel.
                    null
                }
                if (preview == null) return@execute
                if (Thread.currentThread().isInterrupted || !isAttached || generation != previewGeneration) {
                    // The bitmap has never reached RenderThread, so it is safe to release.
                    preview.recycle()
                    return@execute
                }
                synchronized(previewBitmapCache) { previewBitmapCache[key] = preview }
                mainHandler.post {
                    // Re-verify everything on the UI thread: the row may have been
                    // recycled or the adapter detached while the bitmap was decoding.
                    val stillValid = isAttached &&
                        generation == previewGeneration &&
                        binding.ivStylePreview.tag == key &&
                        binding.root.isAttachedToWindow
                    if (stillValid) {
                        runCatching { binding.ivStylePreview.setImageBitmap(preview) }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // The adapter was detached while RecyclerView was binding. No UI action needed.
        }
    }

    private fun releasePreview(binding: ItemStyleBinding) {
        // Detach only. Explicit recycle can race Android's RenderThread and crash with
        // "Canvas: trying to use a recycled bitmap" while a row is being animated.
        binding.ivStylePreview.tag = null
        binding.ivStylePreview.setImageDrawable(null)
    }

    private fun buildGroups(source: List<TextStyle>): Map<String, List<TextStyle>> =
        source.groupBy { it.folder.trim().ifBlank { "Default" } }
            .toList()
            .sortedWith(
                compareBy<Pair<String, List<TextStyle>>> {
                    if (it.first.equals("Default", ignoreCase = true)) 0 else 1
                }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.first }
            )
            .associate { (folder, values) -> folder to values.sortedBy { it.name.lowercase() } }

    private fun buildRows(): List<Row> = buildList {
        for ((folder, values) in groupedStyles) {
            val collapsed = folderKey(folder) in collapsedFolders
            add(Row.Folder(folder, values.size, collapsed))
            if (!collapsed) values.forEach { add(Row.Style(it)) }
        }
    }

    private fun previewKey(style: TextStyle): String = buildString {
        append(style.name); append('|'); append(style.fontName); append('|')
        append(style.previewText); append('|'); append(style.color); append('|')
        append(style.isBold); append('|'); append(style.isItalic); append('|')
        append(style.effect); append('|'); append(style.outlineWidth); append('|')
        append(style.outlineColor); append('|'); append(style.shadowColor); append('|')
        append(style.gradientColors.hashCode()); append('|'); append(style.gradientAngle)
    }

    private fun newPreviewExecutor(): ExecutorService = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        // Enough room for the complete bundled preset set, while remaining bounded.
        ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "style-preview").apply { priority = Thread.NORM_PRIORITY - 1 } },
        ThreadPoolExecutor.AbortPolicy()
    )

    private fun folderKey(folder: String): String = folder.trim().ifBlank { "Default" }.lowercase()

    private fun buildPreview(context: Context, style: TextStyle): Bitmap {
        val width = 280
        val height = 104
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#1A1A1A"))
        val sample = style.previewText.trim().ifBlank { "Ag" }.take(18)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = height * 0.58f
            typeface = synchronized(previewTypefaceCache) {
                previewTypefaceCache.getOrPut(style.fontName) {
                    // Resolve only this row's font. Building the complete merged bank here
                    // decoded every custom font during preset expansion and could OOM/ANR.
                    FontResolver.resolveStyleTypeface(context, style.fontName)
                }
            }
            isFakeBoldText = style.isBold
            textSkewX = if (style.isItalic) -0.22f else 0f
        }
        val availableWidth = width - 24f
        val measured = paint.measureText(sample).coerceAtLeast(1f)
        if (measured > availableWidth) paint.textSize *= availableWidth / measured
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2f
        val startX = ((width - paint.measureText(sample)) / 2f).coerceAtLeast(6f)

        val hasShadow = style.effect == "SHADOW" || style.effect == "OUTLINE_SHADOW" || style.enableShadow
        if (hasShadow) {
            paint.setShadowLayer(
                style.shadowRadius.coerceIn(1f, 12f),
                style.shadowDx.coerceIn(-8f, 8f),
                style.shadowDy.coerceIn(-8f, 8f),
                applyAlpha(style.shadowColor, style.shadowOpacity)
            )
        }
        val hasOutline = style.effect == "OUTLINE" || style.effect == "OUTLINE_SHADOW" || style.enableOutline
        if (hasOutline) {
            val outline = Paint(paint).apply {
                color = applyAlpha(style.outlineColor, style.outlineOpacity)
                this.style = Paint.Style.STROKE
                strokeWidth = style.outlineWidth.coerceIn(1f, 6f) * 2f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                clearShadowLayer()
            }
            canvas.drawText(sample, startX, baseline, outline)
        }
        paint.clearShadowLayer()

        val useGradient = style.enableGradient || style.effect == "GRADIENT"
        val useTexture = style.enableTexture || style.effect == "TEXTURE"
        val gradientShader = if (useGradient) {
            val colors = style.gradientColors.takeIf { it.size >= 2 }?.toIntArray()
                ?: intArrayOf(style.gradientStartColor, style.gradientEndColor)
            val radians = Math.toRadians(style.gradientAngle.toDouble())
            val dx = kotlin.math.cos(radians).toFloat()
            val dy = kotlin.math.sin(radians).toFloat()
            val centerX = width / 2f
            val centerY = height / 2f
            val radius = abs(dx) * width / 2f + abs(dy) * height / 2f
            LinearGradient(
                centerX - dx * radius,
                centerY - dy * radius,
                centerX + dx * radius,
                centerY + dy * radius,
                colors,
                null,
                Shader.TileMode.CLAMP
            )
        } else null
        var textureBitmap: Bitmap? = null
        val textureShader = if (useTexture) {
            val texture = Bitmap.createBitmap(8, 8, Bitmap.Config.RGB_565).apply {
                eraseColor(Color.WHITE)
                setPixel(0, 0, Color.LTGRAY)
                setPixel(4, 4, Color.LTGRAY)
            }
            textureBitmap = texture
            BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        } else null
        paint.shader = when {
            gradientShader != null && textureShader != null ->
                ComposeShader(gradientShader, textureShader, PorterDuff.Mode.MULTIPLY)
            gradientShader != null -> gradientShader
            textureShader != null -> textureShader
            else -> null
        }
        if (!useGradient && !useTexture) paint.color = style.color
        canvas.drawText(sample, startX, baseline, paint)
        paint.shader = null
        textureBitmap?.recycle()
        return bitmap
    }

    private fun applyAlpha(color: Int, opacity: Int): Int {
        val alpha = (Color.alpha(color) * opacity / 100f).toInt().coerceIn(0, 255)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }

    private companion object {
        const val VIEW_FOLDER = 0
        const val VIEW_STYLE = 1
    }
}
