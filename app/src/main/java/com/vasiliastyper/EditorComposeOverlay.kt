package com.vasiliastyper

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal enum class ComposeLayerKind { PIXEL, IMAGE, TEXT, FOLDER }

internal data class ComposeLayerItem(
    val id: String,
    val name: String,
    val kind: ComposeLayerKind,
    val thumbnail: Bitmap? = null,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val opacity: Int = 100,
    val blendMode: String = "Normal",
    val selected: Boolean = false,
    val clippingMask: Boolean = false,
    val alphaMaskEnabled: Boolean = false,
    val hasAlphaMask: Boolean = false,
    val folderId: String? = null,
    val expanded: Boolean = true,
    val canMoveToFolder: Boolean = false
)

internal data class BrushUiState(
    val visible: Boolean = false,
    val expanded: Boolean = false,
    val toolName: String = "Pen",
    val size: Float = 20f,
    val opacity: Float = 100f,
    val hardness: Float = 90f,
    val stabilizer: Float = 72f,
    val forceFade: Float = 22f,
    val spacing: Float = 12f,
    val flow: Float = 100f
)

internal data class ToolItem(
    val id: String,
    val label: String,
    val category: String,
    val vectorType: VectorToolType
)

internal enum class VectorToolType {
    BRUSH, TEXT, TEXT_SHAPER, MOVE_CANVAS, MOVE_ELEMENT, RECT_SELECT, FREE_SELECT, MAGIC_WAND,
    REMOVR, CLEAN_BUBBLE, TRANSLATE, OCR, MASK, SCRIPT, VASTYPE, AI_CHAT, WATERMARK, UNWATERMARK, MORE
}

@Composable
internal fun EditorComposeOverlay(
    layersOpen: Boolean,
    layerItems: List<ComposeLayerItem>,
    brush: BrushUiState,
    onToggleLayers: () -> Unit,
    onAddLayer: () -> Unit,
    onAddFolder: () -> Unit,
    onSelectLayer: (ComposeLayerItem) -> Unit,
    onRenameLayer: (ComposeLayerItem, String) -> Unit,
    onToggleClippingMask: (ComposeLayerItem) -> Unit,
    onToggleAlphaMask: (ComposeLayerItem) -> Unit,
    onRemoveAlphaMask: (ComposeLayerItem) -> Unit,
    onToggleFolderExpanded: (ComposeLayerItem) -> Unit,
    onToggleLayerFolder: (ComposeLayerItem) -> Unit,
    onToggleLayerVisibility: (ComposeLayerItem) -> Unit,
    onToggleLayerLock: (ComposeLayerItem) -> Unit,
    onLayerOpacity: (ComposeLayerItem, Int) -> Unit,
    onLayerBlendMode: (ComposeLayerItem, String) -> Unit,
    onMoveLayer: (Boolean) -> Unit,
    onDuplicateLayer: () -> Unit,
    onMergeLayerDown: () -> Unit,
    onFlattenLayers: () -> Unit,
    onDeleteLayer: () -> Unit,
    onToggleBrushExpanded: () -> Unit,
    onBrushSize: (Float) -> Unit,
    onBrushOpacity: (Float) -> Unit,
    onBrushHardness: (Float) -> Unit,
    onBrushStabilizer: (Float) -> Unit,
    onBrushForceFade: (Float) -> Unit,
    onBrushSpacing: (Float) -> Unit,
    onBrushFlow: (Float) -> Unit,
    // IbisPaint-style Tool Picker Bottom Sheet State & Callbacks
    toolPickerOpen: Boolean = false,
    selectedToolId: String = "toolBrush",
    onToggleToolPicker: () -> Unit = {},
    onSelectToolById: (String) -> Unit = {},
    // Quick Action Bar Triggers
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {}
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF4FD6B8),
            surface = Color(0xFF13171C),
            surfaceVariant = Color(0xFF1A2129),
            onSurface = Color(0xFFEBF1F4),
            outline = Color(0xFF2A333C)
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds()
        ) {
            AnimatedVisibility(
                visible = brush.visible,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                BrushSettingsBar(
                    state = brush,
                    onToggleExpanded = onToggleBrushExpanded,
                    onSize = onBrushSize,
                    onOpacity = onBrushOpacity,
                    onHardness = onBrushHardness,
                    onStabilizer = onBrushStabilizer,
                    onForceFade = onBrushForceFade,
                    onSpacing = onBrushSpacing,
                    onFlow = onBrushFlow
                )
            }

            // Floating Layer Panel Container
            val panelWidth = (maxWidth - 16.dp).coerceAtLeast(220.dp)
            val panelHeight = minOf(520.dp, maxHeight * 0.72f).coerceAtLeast(280.dp)
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 66.dp),
                horizontalAlignment = Alignment.End
            ) {
                AnimatedVisibility(
                    visible = layersOpen,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom)
                ) {
                    LayerPanel(
                        panelWidth = panelWidth,
                        panelHeight = panelHeight,
                        items = layerItems,
                        onClose = onToggleLayers,
                        onAdd = onAddLayer,
                        onAddFolder = onAddFolder,
                        onSelect = onSelectLayer,
                        onRename = onRenameLayer,
                        onToggleClippingMask = onToggleClippingMask,
                        onToggleAlphaMask = onToggleAlphaMask,
                        onRemoveAlphaMask = onRemoveAlphaMask,
                        onToggleFolderExpanded = onToggleFolderExpanded,
                        onToggleLayerFolder = onToggleLayerFolder,
                        onToggleVisibility = onToggleLayerVisibility,
                        onToggleLock = onToggleLayerLock,
                        onOpacity = onLayerOpacity,
                        onBlendMode = onLayerBlendMode,
                        onMove = onMoveLayer,
                        onDuplicate = onDuplicateLayer,
                        onMergeDown = onMergeLayerDown,
                        onFlatten = onFlattenLayers,
                        onDelete = onDeleteLayer
                    )
                }
                if (layersOpen) Spacer(Modifier.height(8.dp))
                LayerToggleButton(
                    expanded = layersOpen,
                    onClick = onToggleLayers
                )
            }

            // Clean Mobile Bottom Navigation Tool Bar
            val quickTools = listOf(
                ToolItem("toolBrush", "Kuas", "LUKIS", VectorToolType.BRUSH),
                ToolItem("toolText", "Teks", "LUKIS", VectorToolType.TEXT),
                ToolItem("toolFreeSelect", "Lasso", "SELEKSI", VectorToolType.FREE_SELECT),
                ToolItem("toolMagicWand", "Wand", "SELEKSI", VectorToolType.MAGIC_WAND),
                ToolItem("toolRemovR", "RemovR", "CLEANUP", VectorToolType.REMOVR),
                ToolItem("toolBubbleClean", "Clean", "CLEANUP", VectorToolType.CLEAN_BUBBLE),
                ToolItem("toolBubbleTranslate", "Terjemah", "MANGA", VectorToolType.TRANSLATE),
                ToolItem("toolOcrPanel", "OCR", "MANGA", VectorToolType.OCR),
                ToolItem("toolAiChat", "AI Studio", "AI", VectorToolType.AI_CHAT)
            )

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(60.dp),
                color = Color(0xFF13171C),
                tonalElevation = 12.dp,
                shadowElevation = 16.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Scrollable Quick Tool Buttons
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        quickTools.forEach { tool ->
                            val isSelected = tool.id == selectedToolId
                            val iconColor = if (isSelected) Color(0xFF4FD6B8) else Color(0xFFEBF1F4)
                            Surface(
                                modifier = Modifier
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectToolById(tool.id) }
                                    .border(
                                        1.dp,
                                        if (isSelected) Color(0xFF4FD6B8) else Color.Transparent,
                                        RoundedCornerShape(10.dp)
                                    ),
                                color = if (isSelected) Color(0xFF1E3A34) else Color(0xFF1A2129)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CustomToolIcon(
                                        type = tool.vectorType,
                                        tint = iconColor,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Text(
                                        text = tool.label,
                                        color = if (isSelected) Color(0xFF4FD6B8) else Color(0xFFEBF1F4),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                }
                            }
                        }

                        // "Lainnya..." Button for full tool grid sheet
                        Surface(
                            modifier = Modifier
                                .height(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onToggleToolPicker)
                                .border(1.dp, Color(0xFF35444D), RoundedCornerShape(10.dp)),
                            color = Color(0xFF222B35)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CustomToolIcon(
                                    type = VectorToolType.MORE,
                                    tint = Color(0xFF4FD6B8),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Lainnya",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(Modifier.width(6.dp))

                    // Undo & Redo Quick Triggers
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onUndo, modifier = Modifier.size(38.dp)) {
                            LayerGlyph(LayerGlyphType.MOVE_DOWN, Color(0xFFEBF1F4), Modifier.size(18.dp))
                        }
                        IconButton(onClick = onRedo, modifier = Modifier.size(38.dp)) {
                            LayerGlyph(LayerGlyphType.MOVE_UP, Color(0xFFEBF1F4), Modifier.size(18.dp))
                        }
                    }
                }
            }

            // IbisPaint Tool Picker Bottom Sheet Dialog Grid (for additional tools)
            if (toolPickerOpen) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 60.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    IbisToolPickerSheet(
                        selectedToolId = selectedToolId,
                        onDismiss = onToggleToolPicker,
                        onSelectTool = { toolId ->
                            onSelectToolById(toolId)
                            onToggleToolPicker()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CustomToolIcon(
    type: VectorToolType,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (minOf(w, h) * 0.1f).coerceAtLeast(1.8f)
        fun line(a: Offset, b: Offset) = drawLine(tint, a, b, stroke, StrokeCap.Round)

        when (type) {
            VectorToolType.BRUSH -> {
                line(Offset(w * 0.2f, h * 0.8f), Offset(w * 0.7f, h * 0.3f))
                line(Offset(w * 0.7f, h * 0.3f), Offset(w * 0.85f, h * 0.15f))
                drawCircle(tint, radius = stroke * 1.2f, center = Offset(w * 0.18f, h * 0.82f))
            }
            VectorToolType.TEXT -> {
                line(Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.2f))
                line(Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.85f))
                line(Offset(w * 0.35f, h * 0.85f), Offset(w * 0.65f, h * 0.85f))
            }
            VectorToolType.TEXT_SHAPER -> {
                line(Offset(w * 0.2f, h * 0.25f), Offset(w * 0.8f, h * 0.25f))
                line(Offset(w * 0.5f, h * 0.25f), Offset(w * 0.5f, h * 0.8f))
                drawCircle(tint, radius = stroke * 1.1f, center = Offset(w * 0.82f, h * 0.18f))
            }
            VectorToolType.MOVE_CANVAS -> {
                line(Offset(w * 0.2f, h * 0.5f), Offset(w * 0.8f, h * 0.5f))
                line(Offset(w * 0.5f, h * 0.2f), Offset(w * 0.5f, h * 0.8f))
            }
            VectorToolType.MOVE_ELEMENT -> {
                drawCircle(tint, radius = w * 0.25f, center = Offset(w / 2f, h / 2f), style = Stroke(stroke))
                drawCircle(tint, radius = stroke * 0.8f, center = Offset(w / 2f, h / 2f))
            }
            VectorToolType.RECT_SELECT -> {
                drawRoundRect(tint, Offset(w * 0.18f, h * 0.18f), Size(w * 0.64f, h * 0.64f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = Stroke(stroke))
            }
            VectorToolType.FREE_SELECT -> {
                line(Offset(w * 0.2f, h * 0.3f), Offset(w * 0.5f, h * 0.15f))
                line(Offset(w * 0.5f, h * 0.15f), Offset(w * 0.8f, h * 0.4f))
                line(Offset(w * 0.8f, h * 0.4f), Offset(w * 0.6f, h * 0.85f))
                line(Offset(w * 0.6f, h * 0.85f), Offset(w * 0.2f, h * 0.3f))
            }
            VectorToolType.MAGIC_WAND -> {
                line(Offset(w * 0.2f, h * 0.8f), Offset(w * 0.65f, h * 0.35f))
                drawCircle(tint, radius = stroke * 1.2f, center = Offset(w * 0.8f, h * 0.2f))
            }
            VectorToolType.REMOVR -> {
                line(Offset(w * 0.2f, h * 0.7f), Offset(w * 0.7f, h * 0.2f))
                drawRect(tint, Offset(w * 0.15f, h * 0.65f), Size(w * 0.3f, h * 0.2f), style = Stroke(stroke))
            }
            VectorToolType.CLEAN_BUBBLE -> {
                drawCircle(tint, radius = w * 0.32f, center = Offset(w * 0.45f, h * 0.45f), style = Stroke(stroke))
                drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.72f, h * 0.28f), style = Stroke(stroke))
            }
            VectorToolType.TRANSLATE -> {
                drawCircle(tint, radius = w * 0.36f, center = Offset(w / 2f, h / 2f), style = Stroke(stroke))
                line(Offset(w * 0.14f, h * 0.5f), Offset(w * 0.86f, h * 0.5f))
            }
            VectorToolType.OCR -> {
                drawRoundRect(tint, Offset(w * 0.15f, h * 0.15f), Size(w * 0.7f, h * 0.7f), androidx.compose.ui.geometry.CornerRadius(w * 0.08f), style = Stroke(stroke))
                line(Offset(w * 0.3f, h * 0.4f), Offset(w * 0.7f, h * 0.4f))
                line(Offset(w * 0.3f, h * 0.6f), Offset(w * 0.6f, h * 0.6f))
            }
            VectorToolType.MASK -> {
                drawRoundRect(tint, Offset(w * 0.2f, h * 0.25f), Size(w * 0.6f, h * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.25f), style = Stroke(stroke))
            }
            VectorToolType.SCRIPT -> {
                line(Offset(w * 0.25f, h * 0.2f), Offset(w * 0.75f, h * 0.2f))
                line(Offset(w * 0.25f, h * 0.5f), Offset(w * 0.75f, h * 0.5f))
                line(Offset(w * 0.25f, h * 0.8f), Offset(w * 0.55f, h * 0.8f))
            }
            VectorToolType.VASTYPE -> {
                line(Offset(w * 0.2f, h * 0.8f), Offset(w * 0.5f, h * 0.2f))
                line(Offset(w * 0.5f, h * 0.2f), Offset(w * 0.8f, h * 0.8f))
                line(Offset(w * 0.32f, h * 0.6f), Offset(w * 0.68f, h * 0.6f))
            }
            VectorToolType.AI_CHAT -> {
                drawRoundRect(tint, Offset(w * 0.15f, h * 0.2f), Size(w * 0.7f, h * 0.5f), androidx.compose.ui.geometry.CornerRadius(w * 0.12f), style = Stroke(stroke))
                line(Offset(w * 0.3f, h * 0.7f), Offset(w * 0.2f, h * 0.88f))
            }
            VectorToolType.WATERMARK -> {
                drawCircle(tint, radius = w * 0.28f, center = Offset(w / 2f, h * 0.55f), style = Stroke(stroke))
                line(Offset(w / 2f, h * 0.15f), Offset(w / 2f, h * 0.27f))
            }
            VectorToolType.UNWATERMARK -> {
                drawCircle(tint, radius = w * 0.28f, center = Offset(w / 2f, h * 0.55f), style = Stroke(stroke))
                line(Offset(w * 0.2f, h * 0.2f), Offset(w * 0.8f, h * 0.8f))
            }
            VectorToolType.MORE -> {
                listOf(0.25f, 0.5f, 0.75f).forEach { y ->
                    drawCircle(tint, radius = stroke * 0.75f, center = Offset(w / 2f, h * y))
                }
            }
        }
    }
}

@Composable
private fun IbisToolPickerSheet(
    selectedToolId: String,
    onDismiss: () -> Unit,
    onSelectTool: (String) -> Unit
) {
    val tools = listOf(
        ToolItem("toolBrush", "Brush", "LUKIS", VectorToolType.BRUSH),
        ToolItem("toolText", "Teks", "LUKIS", VectorToolType.TEXT),
        ToolItem("toolTextShaper", "Text Shaper", "LUKIS", VectorToolType.TEXT_SHAPER),
        ToolItem("toolMove", "Geser Canvas", "NAVIGASI", VectorToolType.MOVE_CANVAS),
        ToolItem("toolMoveElement", "Geser Elemen", "NAVIGASI", VectorToolType.MOVE_ELEMENT),
        ToolItem("toolRectSelect", "Seleksi Kotak", "SELEKSI", VectorToolType.RECT_SELECT),
        ToolItem("toolFreeSelect", "Lasso", "SELEKSI", VectorToolType.FREE_SELECT),
        ToolItem("toolMagicWand", "Tongkat Sihir", "SELEKSI", VectorToolType.MAGIC_WAND),
        ToolItem("toolRemovR", "RemovR", "CLEANUP", VectorToolType.REMOVR),
        ToolItem("toolBubbleClean", "Clean Bubble", "CLEANUP", VectorToolType.CLEAN_BUBBLE),
        ToolItem("toolBubbleTranslate", "Terjemah", "MANGA", VectorToolType.TRANSLATE),
        ToolItem("toolOcrPanel", "OCR Studio", "MANGA", VectorToolType.OCR),
        ToolItem("toolMask", "Auto Mask", "MANGA", VectorToolType.MASK),
        ToolItem("toolScript", "Script Workspace", "MANGA", VectorToolType.SCRIPT),
        ToolItem("toolVasType", "VasType", "MANGA", VectorToolType.VASTYPE),
        ToolItem("toolAiChat", "AI Studio", "AI", VectorToolType.AI_CHAT),
        ToolItem("toolWatermark", "Watermark", "UTILITY", VectorToolType.WATERMARK),
        ToolItem("toolUnwatermark", "Unwatermark", "UTILITY", VectorToolType.UNWATERMARK)
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF13171C))
                .border(1.dp, Color(0xFF2A333C), RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "PILIH ALAT",
                    color = Color(0xFF4FD6B8),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
                IconButton(onClick = onDismiss) {
                    LayerGlyph(LayerGlyphType.CLOSE, Color(0xFFB8B5C2), Modifier.size(20.dp))
                }
            }

            HorizontalDivider(color = Color(0xFF2A333C), modifier = Modifier.padding(vertical = 12.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val grouped = tools.groupBy { it.category }
                grouped.forEach { (category, items) ->
                    item {
                        Text(
                            text = category,
                            color = Color(0xFF8F8B99),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items.forEach { tool ->
                                val isSelected = tool.id == selectedToolId
                                val iconColor = if (isSelected) Color(0xFF4FD6B8) else Color(0xFFEBF1F4)
                                Surface(
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(72.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSelectTool(tool.id) }
                                        .border(
                                            1.dp,
                                            if (isSelected) Color(0xFF4FD6B8) else Color(0xFF2A333C),
                                            RoundedCornerShape(12.dp)
                                        ),
                                    color = if (isSelected) Color(0xFF1E3A34) else Color(0xFF1A2129)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        CustomToolIcon(
                                            type = tool.vectorType,
                                            tint = iconColor,
                                            modifier = Modifier.size(22.dp)
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            tool.label,
                                            color = if (isSelected) Color(0xFF4FD6B8) else Color(0xFFEBF1F4),
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayerToggleButton(
    expanded: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .height(44.dp)
            .widthIn(min = 96.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                color = if (expanded) Color(0xFF4FD6B8) else Color(0xFF35444D),
                shape = RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (expanded) Color(0xFF2A9C85) else Color(0xFF1A2129),
        tonalElevation = 8.dp,
        shadowElevation = 10.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LayerGlyph(
                glyph = LayerGlyphType.LAYERS,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Layer",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            LayerGlyph(
                glyph = if (expanded) LayerGlyphType.CHEVRON_DOWN else LayerGlyphType.CHEVRON_UP,
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun LayerPanel(
    panelWidth: Dp,
    panelHeight: Dp,
    items: List<ComposeLayerItem>,
    onClose: () -> Unit,
    onAdd: () -> Unit,
    onAddFolder: () -> Unit,
    onSelect: (ComposeLayerItem) -> Unit,
    onRename: (ComposeLayerItem, String) -> Unit,
    onToggleClippingMask: (ComposeLayerItem) -> Unit,
    onToggleAlphaMask: (ComposeLayerItem) -> Unit,
    onRemoveAlphaMask: (ComposeLayerItem) -> Unit,
    onToggleFolderExpanded: (ComposeLayerItem) -> Unit,
    onToggleLayerFolder: (ComposeLayerItem) -> Unit,
    onToggleVisibility: (ComposeLayerItem) -> Unit,
    onToggleLock: (ComposeLayerItem) -> Unit,
    onOpacity: (ComposeLayerItem, Int) -> Unit,
    onBlendMode: (ComposeLayerItem, String) -> Unit,
    onMove: (Boolean) -> Unit,
    onDuplicate: () -> Unit,
    onMergeDown: () -> Unit,
    onFlatten: () -> Unit,
    onDelete: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier
            .width(panelWidth)
            .height(panelHeight),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF0E1316))
                .border(1.dp, Color(0xFF2A333C), RoundedCornerShape(24.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .padding(start = 18.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "LAYER STACK",
                    modifier = Modifier.weight(1f),
                    color = Color(0xFFF4F2F8),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    letterSpacing = 1.6.sp
                )
                IconButton(onClick = onAdd) {
                    LayerGlyph(LayerGlyphType.ADD, Color(0xFF4FD6B8), Modifier.size(22.dp))
                }
                IconButton(onClick = onAddFolder) {
                    LayerGlyph(LayerGlyphType.FOLDER, Color(0xFFFBBF24), Modifier.size(22.dp))
                }
                IconButton(onClick = onClose) {
                    LayerGlyph(LayerGlyphType.CLOSE, Color(0xFFB8B5C2), Modifier.size(20.dp))
                }
            }
            HorizontalDivider(color = Color(0xFF2A333C))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 120.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    LayerRow(
                        item = item,
                        onSelect = {
                            if (item.kind == ComposeLayerKind.FOLDER) onToggleFolderExpanded(item)
                            else onSelect(item)
                        },
                        onRename = { onRename(item, it) },
                        onToggleClippingMask = { onToggleClippingMask(item) },
                        onToggleAlphaMask = { onToggleAlphaMask(item) },
                        onRemoveAlphaMask = { onRemoveAlphaMask(item) },
                        onToggleLayerFolder = { onToggleLayerFolder(item) },
                        onToggleVisibility = { onToggleVisibility(item) },
                        onToggleLock = { onToggleLock(item) },
                        onOpacity = { onOpacity(item, it) },
                        onBlendMode = { onBlendMode(item, it) },
                        onDelete = {
                            if (item.kind != ComposeLayerKind.FOLDER) {
                                onSelect(item)
                                onDelete()
                            }
                        }
                    )
                }
            }
            HorizontalDivider(color = Color(0xFF2A333C))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
                    .padding(horizontal = 5.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LayerAction(LayerGlyphType.MOVE_UP, "Naik") { onMove(true) }
                LayerAction(LayerGlyphType.MOVE_DOWN, "Turun") { onMove(false) }
                LayerAction(LayerGlyphType.DUPLICATE, "Duplikat", onClick = onDuplicate)
                LayerAction(LayerGlyphType.MERGE_DOWN, "Gabung", onClick = onMergeDown)
                LayerAction(LayerGlyphType.FLATTEN, "Satukan", onClick = onFlatten)
                LayerAction(LayerGlyphType.DELETE, "Hapus", danger = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun LayerRow(
    item: ComposeLayerItem,
    onSelect: () -> Unit,
    onRename: (String) -> Unit,
    onToggleClippingMask: () -> Unit,
    onToggleAlphaMask: () -> Unit,
    onRemoveAlphaMask: () -> Unit,
    onToggleLayerFolder: () -> Unit,
    onToggleVisibility: () -> Unit,
    onToggleLock: () -> Unit,
    onOpacity: (Int) -> Unit,
    onBlendMode: (String) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameValue by remember(item.id, item.name) { mutableStateOf(item.name) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (item.selected) Color(0xFF1D345A) else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = if (item.folderId != null) 24.dp else 8.dp, end = 8.dp, top = 7.dp, bottom = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onToggleVisibility, modifier = Modifier.size(40.dp)) {
                LayerGlyph(
                    if (item.visible) LayerGlyphType.EYE else LayerGlyphType.EYE_OFF,
                    if (item.visible) Color(0xFFE5E2EA) else Color(0xFF716E7B),
                    Modifier.size(20.dp)
                )
            }
            LayerThumbnail(item)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, color = Color(0xFFF2F0F5), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                val status = when {
                    item.kind == ComposeLayerKind.FOLDER -> if (item.expanded) "Folder terbuka" else "Folder tertutup"
                    item.clippingMask && item.alphaMaskEnabled -> "Clipping · Alpha Mask"
                    item.clippingMask -> "Clipping Mask"
                    item.alphaMaskEnabled -> "Alpha Mask"
                    else -> item.blendMode
                }
                Text(status, color = Color(0xFF9D99A8), fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                    LayerGlyph(
                        if (item.locked) LayerGlyphType.LOCK else LayerGlyphType.MORE,
                        if (item.locked) Color(0xFF4FD6B8) else Color(0xFFB8BDC7),
                        Modifier.size(18.dp)
                    )
                }
                Text("${item.opacity}%", color = Color(0xFF9D99A8), fontSize = 11.sp)
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menuOpen = false
                        renameValue = item.name
                        renameOpen = true
                    })
                    if (item.kind != ComposeLayerKind.FOLDER) {
                        DropdownMenuItem(text = { Text(if (item.locked) "Buka kunci" else "Kunci") }, onClick = {
                            menuOpen = false
                            onToggleLock()
                        })
                    }
                    if (item.kind == ComposeLayerKind.PIXEL) {
                        DropdownMenuItem(text = { Text(if (item.clippingMask) "Lepas Clipping Mask" else "Jadikan Clipping Mask") }, onClick = {
                            menuOpen = false
                            onToggleClippingMask()
                        })
                        DropdownMenuItem(text = { Text(if (item.alphaMaskEnabled) "Nonaktifkan Alpha Mask" else "Aktifkan Alpha Mask") }, onClick = {
                            menuOpen = false
                            onToggleAlphaMask()
                        })
                        if (item.hasAlphaMask) DropdownMenuItem(text = { Text("Hapus Alpha Mask") }, onClick = {
                            menuOpen = false
                            onRemoveAlphaMask()
                        })
                        if (item.folderId != null || item.canMoveToFolder) DropdownMenuItem(
                            text = { Text(if (item.folderId != null) "Keluarkan dari Folder" else "Masukkan ke Folder Terakhir") },
                            onClick = {
                                menuOpen = false
                                onToggleLayerFolder()
                            }
                        )
                    }
                    if (item.kind != ComposeLayerKind.FOLDER) listOf("Normal", "Multiply", "Screen", "Overlay").forEach { mode ->
                        DropdownMenuItem(text = { Text(mode) }, onClick = {
                            menuOpen = false
                            onBlendMode(mode)
                        })
                    }
                    if (item.kind != ComposeLayerKind.FOLDER) {
                        DropdownMenuItem(
                            text = { Text("Hapus layer", color = Color(0xFFF87171)) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }
        }
        if (item.selected && item.kind != ComposeLayerKind.FOLDER) {
            Slider(
                value = item.opacity.toFloat(),
                onValueChange = { onOpacity(it.roundToInt()) },
                valueRange = 0f..100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .padding(start = 54.dp)
            )
        }
    }
    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text(if (item.kind == ComposeLayerKind.FOLDER) "Rename Folder" else "Rename Layer") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { renameValue = it },
                    singleLine = true,
                    label = { Text("Nama") }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val safeName = renameValue.trim()
                    if (safeName.isNotEmpty()) onRename(safeName)
                    renameOpen = false
                }) { Text("Simpan") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun LayerThumbnail(item: ComposeLayerItem) {
    Surface(
        modifier = Modifier.size(42.dp),
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF272630)
    ) {
        when {
            item.thumbnail != null && !item.thumbnail.isRecycled -> Image(
                bitmap = item.thumbnail.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            item.kind == ComposeLayerKind.TEXT -> Box(contentAlignment = Alignment.Center) {
                Text("T", color = Color(0xFFE9E5F2), fontSize = 23.sp, fontFamily = FontFamily.Serif)
            }
            item.kind == ComposeLayerKind.FOLDER -> Box(contentAlignment = Alignment.Center) {
                LayerGlyph(LayerGlyphType.FOLDER, Color(0xFFFBBF24), Modifier.size(22.dp))
            }
            else -> Box(
                modifier = Modifier.background(Color(0xFF22212A)),
                contentAlignment = Alignment.Center
            ) {
                LayerGlyph(LayerGlyphType.LAYERS, Color(0xFF9D99A8), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun LayerAction(
    glyph: LayerGlyphType,
    description: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.width(49.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LayerGlyph(
                glyph,
                if (danger) Color(0xFFF87171) else Color(0xFFD6D2DE),
                Modifier.size(20.dp)
            )
            Text(
                description,
                color = if (danger) Color(0xFFF87171) else Color(0xFF9D99A8),
                fontSize = 8.sp,
                maxLines = 1
            )
        }
    }
}

private enum class LayerGlyphType {
    ADD, CLOSE, LAYERS, EYE, EYE_OFF, LOCK, MORE, CHEVRON_UP, CHEVRON_DOWN,
    MOVE_UP, MOVE_DOWN, DUPLICATE, MERGE_DOWN, FLATTEN, DELETE, FOLDER
}

@Composable
private fun LayerGlyph(
    glyph: LayerGlyphType,
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (minOf(w, h) * 0.095f).coerceAtLeast(1.5f)
        fun line(a: Offset, b: Offset) = drawLine(tint, a, b, stroke, StrokeCap.Round)
        fun layer(top: Float, left: Float = w * 0.18f, right: Float = w * 0.82f) {
            line(Offset(left, top), Offset(w / 2f, top + h * 0.13f))
            line(Offset(w / 2f, top + h * 0.13f), Offset(right, top))
        }
        when (glyph) {
            LayerGlyphType.ADD -> {
                line(Offset(w * 0.18f, h / 2f), Offset(w * 0.82f, h / 2f))
                line(Offset(w / 2f, h * 0.18f), Offset(w / 2f, h * 0.82f))
            }
            LayerGlyphType.CLOSE -> {
                line(Offset(w * 0.22f, h * 0.22f), Offset(w * 0.78f, h * 0.78f))
                line(Offset(w * 0.78f, h * 0.22f), Offset(w * 0.22f, h * 0.78f))
            }
            LayerGlyphType.LAYERS -> {
                layer(h * 0.22f)
                layer(h * 0.42f)
                layer(h * 0.62f)
            }
            LayerGlyphType.EYE, LayerGlyphType.EYE_OFF -> {
                val eyePath = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.08f, h * 0.50f)
                    quadraticTo(w * 0.50f, h * 0.08f, w * 0.92f, h * 0.50f)
                    quadraticTo(w * 0.50f, h * 0.92f, w * 0.08f, h * 0.50f)
                    close()
                }
                drawPath(eyePath, tint, style = Stroke(stroke))
                drawCircle(tint, radius = w * 0.12f, center = Offset(w / 2f, h / 2f))
                if (glyph == LayerGlyphType.EYE_OFF) {
                    line(Offset(w * 0.12f, h * 0.12f), Offset(w * 0.88f, h * 0.88f))
                }
            }
            LayerGlyphType.LOCK -> {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.22f, h * 0.45f),
                    size = androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.43f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = Stroke(stroke)
                )
                drawArc(
                    color = tint,
                    startAngle = 180f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(w * 0.31f, h * 0.12f),
                    size = androidx.compose.ui.geometry.Size(w * 0.38f, h * 0.55f),
                    style = Stroke(stroke)
                )
            }
            LayerGlyphType.MORE -> {
                listOf(0.25f, 0.5f, 0.75f).forEach { y ->
                    drawCircle(tint, radius = stroke * 0.72f, center = Offset(w / 2f, h * y))
                }
            }
            LayerGlyphType.CHEVRON_UP, LayerGlyphType.CHEVRON_DOWN -> {
                val pointsUp = glyph == LayerGlyphType.CHEVRON_UP
                val edgeY = if (pointsUp) h * 0.68f else h * 0.32f
                val middleY = if (pointsUp) h * 0.32f else h * 0.68f
                line(Offset(w * 0.18f, edgeY), Offset(w * 0.50f, middleY))
                line(Offset(w * 0.50f, middleY), Offset(w * 0.82f, edgeY))
            }
            LayerGlyphType.MOVE_UP, LayerGlyphType.MOVE_DOWN -> {
                val up = glyph == LayerGlyphType.MOVE_UP
                val headY = if (up) h * 0.20f else h * 0.80f
                val tailY = if (up) h * 0.80f else h * 0.20f
                line(Offset(w / 2f, tailY), Offset(w / 2f, headY))
                val wingY = if (up) h * 0.43f else h * 0.57f
                line(Offset(w / 2f, headY), Offset(w * 0.28f, wingY))
                line(Offset(w / 2f, headY), Offset(w * 0.72f, wingY))
            }
            LayerGlyphType.DUPLICATE -> {
                drawRect(tint, Offset(w * 0.30f, h * 0.16f), androidx.compose.ui.geometry.Size(w * 0.54f, h * 0.54f), style = Stroke(stroke))
                drawRect(tint, Offset(w * 0.14f, h * 0.32f), androidx.compose.ui.geometry.Size(w * 0.54f, h * 0.54f), style = Stroke(stroke))
            }
            LayerGlyphType.MERGE_DOWN -> {
                line(Offset(w * 0.25f, h * 0.18f), Offset(w * 0.25f, h * 0.48f))
                line(Offset(w * 0.75f, h * 0.18f), Offset(w * 0.75f, h * 0.48f))
                line(Offset(w * 0.25f, h * 0.48f), Offset(w / 2f, h * 0.70f))
                line(Offset(w * 0.75f, h * 0.48f), Offset(w / 2f, h * 0.70f))
                line(Offset(w * 0.25f, h * 0.86f), Offset(w * 0.75f, h * 0.86f))
            }
            LayerGlyphType.FLATTEN -> {
                line(Offset(w * 0.17f, h * 0.28f), Offset(w * 0.83f, h * 0.28f))
                line(Offset(w * 0.23f, h * 0.50f), Offset(w * 0.77f, h * 0.50f))
                line(Offset(w * 0.30f, h * 0.72f), Offset(w * 0.70f, h * 0.72f))
            }
            LayerGlyphType.FOLDER -> {
                drawRoundRect(
                    tint,
                    Offset(w * 0.12f, h * 0.30f),
                    androidx.compose.ui.geometry.Size(w * 0.76f, h * 0.52f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = Stroke(stroke)
                )
                line(Offset(w * 0.18f, h * 0.30f), Offset(w * 0.38f, h * 0.16f))
                line(Offset(w * 0.38f, h * 0.16f), Offset(w * 0.58f, h * 0.30f))
            }
            LayerGlyphType.DELETE -> {
                drawRoundRect(
                    tint,
                    Offset(w * 0.28f, h * 0.30f),
                    androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.55f),
                    androidx.compose.ui.geometry.CornerRadius(w * 0.05f),
                    style = Stroke(stroke)
                )
                line(Offset(w * 0.20f, h * 0.24f), Offset(w * 0.80f, h * 0.24f))
                line(Offset(w * 0.38f, h * 0.14f), Offset(w * 0.62f, h * 0.14f))
            }
        }
    }
}

private fun safeBrushValue(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    fallback: Float
): Float = if (value.isFinite()) value.coerceIn(range.start, range.endInclusive) else fallback

@Composable
private fun BrushSettingsBar(
    state: BrushUiState,
    onToggleExpanded: () -> Unit,
    onSize: (Float) -> Unit,
    onOpacity: (Float) -> Unit,
    onHardness: (Float) -> Unit,
    onStabilizer: (Float) -> Unit,
    onForceFade: (Float) -> Unit,
    onSpacing: (Float) -> Unit,
    onFlow: (Float) -> Unit
) {
    val size = safeBrushValue(state.size, 1f..201f, 20f)
    val opacity = safeBrushValue(state.opacity, 0f..100f, 100f)
    val hardness = safeBrushValue(state.hardness, 0f..100f, 90f)
    val stabilizer = safeBrushValue(state.stabilizer, 0f..100f, 72f)
    val forceFade = safeBrushValue(state.forceFade, 0f..100f, 22f)
    val spacing = safeBrushValue(state.spacing, 1f..100f, 12f)
    val flow = safeBrushValue(state.flow, 1f..100f, 100f)
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .background(Color(0xFF15181D))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Kuas", color = Color(0xFFF2F0F5), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Text(state.toolName, color = Color(0xFFA7A3B1), fontSize = 12.sp)
                Spacer(Modifier.width(14.dp))
                CompactSlider("Ukuran", "${size.roundToInt()} px", size, 1f..201f, 168.dp, onSize)
                Spacer(Modifier.width(14.dp))
                CompactSlider("Opasitas", "${opacity.roundToInt()}%", opacity, 0f..100f, 168.dp, onOpacity)
                TextButton(onClick = onToggleExpanded) { Text(if (state.expanded) "Tutup" else "Lainnya") }
            }
            AnimatedVisibility(visible = state.expanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactSlider("Hardness", "${hardness.roundToInt()}%", hardness, 0f..100f, 145.dp, onHardness)
                    Spacer(Modifier.width(10.dp))
                    CompactSlider("Force fade", "${forceFade.roundToInt()}%", forceFade, 0f..100f, 145.dp, onForceFade)
                    Spacer(Modifier.width(10.dp))
                    CompactSlider("Stabilizer", "${stabilizer.roundToInt()}%", stabilizer, 0f..100f, 145.dp, onStabilizer)
                    Spacer(Modifier.width(10.dp))
                    CompactSlider("Spacing", "${spacing.roundToInt()}%", spacing, 1f..100f, 145.dp, onSpacing)
                    Spacer(Modifier.width(10.dp))
                    CompactSlider("Flow", "${flow.roundToInt()}%", flow, 1f..100f, 145.dp, onFlow)
                }
            }
        }
    }
}

@Composable
private fun CompactSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    width: androidx.compose.ui.unit.Dp,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.width(width)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color(0xFFB8B5C2), fontSize = 10.sp)
            Text(valueLabel, color = Color(0xFF8F8B99), fontSize = 10.sp)
        }
        Slider(
            value = safeBrushValue(value, range, range.start),
            onValueChange = { changed -> if (changed.isFinite()) onChange(changed) },
            valueRange = range,
            modifier = Modifier.height(22.dp)
        )
    }
}
