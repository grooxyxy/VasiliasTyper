package com.vasiliastyper.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasiliastyper.engine.DEFAULT_PROJECT_FOLDER
import com.vasiliastyper.engine.ProjectRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LibraryInk = Color(0xFF0A0C0F)
private val LibraryPanel = Color(0xFF13171C)
private val LibraryRaised = Color(0xFF1A2129)
private val LibraryLine = Color(0xFF2A333C)
private val LibraryText = Color(0xFFEBF1F4)
private val LibraryMuted = Color(0xFF8B98A2)
private val LibraryAccent = Color(0xFF4FD6B8)

@Composable
fun StudioLibrary(
    records: List<ProjectRecord>,
    folders: List<String>,
    selectedFolder: String?,
    query: String,
    onQuery: (String) -> Unit,
    onFolder: (String?) -> Unit,
    onBack: () -> Unit,
    onCreateFolder: () -> Unit,
    onRenameFolder: () -> Unit,
    onOpen: (ProjectRecord) -> Unit,
    onActions: (ProjectRecord) -> Unit,
    selectedProjectIds: Set<String>,
    onToggleSelection: (ProjectRecord) -> Unit,
    onSelectAllVisible: (List<ProjectRecord>) -> Unit,
    onDeleteSelected: () -> Unit,
    onClearSelection: () -> Unit
) {
    VasiliasStudioTheme {
        val visible = records.filter { record ->
            val inFolder = selectedFolder == null ||
                (selectedFolder == DEFAULT_PROJECT_FOLDER && record.folder == DEFAULT_PROJECT_FOLDER) ||
                record.folder == selectedFolder
            inFolder && (query.isBlank() || record.name.contains(query, ignoreCase = true))
        }
        Surface(Modifier.fillMaxSize(), color = LibraryInk) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LibraryIconButton(if (selectedProjectIds.isEmpty()) "‹" else "×") {
                        if (selectedProjectIds.isEmpty()) onBack() else onClearSelection()
                    }
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(
                            if (selectedProjectIds.isEmpty()) "Project library" else "${selectedProjectIds.size} dipilih",
                            color = LibraryText,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (selectedProjectIds.isEmpty()) "${records.size} projects  ·  ${folders.size} collections"
                            else "Pilih proyek lain atau hapus sekaligus",
                            color = LibraryMuted,
                            fontSize = 10.sp
                        )
                    }
                    if (selectedProjectIds.isEmpty()) {
                        Surface(
                            Modifier.height(39.dp).clickable(onClick = onCreateFolder),
                            shape = RoundedCornerShape(12.dp), color = LibraryAccent
                        ) { Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { Text("+ Collection", color = LibraryInk, fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
                    } else {
                        Text(
                            "PILIH SEMUA",
                            color = LibraryAccent,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp).clickable { onSelectAllVisible(visible) }
                        )
                        Surface(
                            Modifier.height(39.dp).clickable(onClick = onDeleteSelected),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF5A2529)
                        ) { Box(Modifier.padding(horizontal = 13.dp), contentAlignment = Alignment.Center) { Text("Hapus", color = Color(0xFFFFB4B8), fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
                    }
                }
                SearchField(query, onQuery)
                Spacer(Modifier.height(16.dp))
                Text("COLLECTIONS", color = LibraryMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
                Spacer(Modifier.height(9.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderChip("All projects", selectedFolder == null) { onFolder(null) }
                    FolderChip("Unsorted", selectedFolder == DEFAULT_PROJECT_FOLDER) { onFolder(DEFAULT_PROJECT_FOLDER) }
                    folders.forEach { folder -> FolderChip(folder, selectedFolder == folder) { onFolder(folder) } }
                    if (selectedFolder != null && selectedFolder != DEFAULT_PROJECT_FOLDER) {
                        FolderChip("Rename", false, onRenameFolder)
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("FILES", color = LibraryMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp, modifier = Modifier.weight(1f))
                    Text("${visible.size} SHOWN", color = LibraryAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(9.dp))
                if (visible.isEmpty()) {
                    LibraryEmpty(Modifier.weight(1f))
                } else {
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(visible, key = { it.id }) { project ->
                            LibraryProjectRow(
                                project = project,
                                selected = project.id in selectedProjectIds,
                                selectionMode = selectedProjectIds.isNotEmpty(),
                                onOpen = { onOpen(project) },
                                onActions = { onActions(project) },
                                onToggleSelection = { onToggleSelection(project) }
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValue: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(LibraryPanel, RoundedCornerShape(16.dp)).border(1.dp, LibraryLine, RoundedCornerShape(16.dp)).padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(18.dp)) { drawCircle(LibraryMuted, size.width * .3f, Offset(size.width*.42f,size.height*.42f), style = Stroke(1.8.dp.toPx())); drawLine(LibraryMuted, Offset(size.width*.64f,size.height*.64f), Offset(size.width*.88f,size.height*.88f), 1.8.dp.toPx(), StrokeCap.Round) }
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = TextStyle(color = LibraryText, fontSize = 13.sp),
            modifier = Modifier.padding(start = 12.dp).weight(1f),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text("Search by project name", color = LibraryMuted, fontSize = 13.sp); inner() } }
        )
        if (value.isNotEmpty()) Text("CLEAR", color = LibraryAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onValue("") })
    }
}

@Composable
private fun FolderChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        Modifier.height(38.dp).clickable(onClick = onClick).border(1.dp, if (selected) LibraryAccent else LibraryLine, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp), color = if (selected) Color(0xFF17423A) else LibraryPanel
    ) { Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) { Text(label, color = if (selected) LibraryAccent else LibraryMuted, fontSize = 11.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) } }
}

@Composable
private fun LibraryProjectRow(
    project: ProjectRecord,
    selected: Boolean,
    selectionMode: Boolean,
    onOpen: () -> Unit,
    onActions: () -> Unit,
    onToggleSelection: () -> Unit
) {
    val rowShape = RoundedCornerShape(17.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .height(82.dp)
            .background(if (selected) Color(0xFF17423A) else LibraryPanel, rowShape)
            .border(1.dp, if (selected) LibraryAccent else LibraryLine, rowShape)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelection() else onOpen() },
                onLongClick = onToggleSelection
            )
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(58.dp).background(LibraryRaised, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Text(project.name.take(1).uppercase(Locale.getDefault()).ifBlank { "V" }, color = LibraryAccent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 13.dp).weight(1f)) {
            Text(project.name, color = LibraryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${project.width} × ${project.height}  ·  ${project.folder}", color = LibraryMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(SimpleDateFormat("d MMM yyyy · HH:mm", Locale.getDefault()).format(Date(project.lastEditedAtMs)), color = LibraryMuted.copy(alpha = .72f), fontSize = 8.sp)
        }
        if (selectionMode) {
            Box(
                Modifier.size(28.dp).background(if (selected) LibraryAccent else LibraryRaised, CircleShape)
                    .border(1.dp, if (selected) LibraryAccent else LibraryLine, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Text("✓", color = LibraryInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            LibraryIconButton("•••", onActions)
        }
    }
}

@Composable
private fun LibraryEmpty(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(58.dp).background(LibraryPanel, CircleShape).border(1.dp, LibraryLine, CircleShape), contentAlignment = Alignment.Center) { Text("□", color = LibraryAccent, fontSize = 24.sp) }
            Spacer(Modifier.height(12.dp))
            Text("No projects found", color = LibraryText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text("Try another collection or search term.", color = LibraryMuted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun LibraryIconButton(text: String, onClick: () -> Unit) {
    Box(Modifier.size(39.dp).background(LibraryPanel, CircleShape).border(1.dp, LibraryLine, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, color = LibraryText, fontSize = if (text == "•••") 11.sp else 24.sp, fontWeight = FontWeight.SemiBold)
    }
}
