package com.vasiliastyper.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vasiliastyper.engine.ProjectRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF0A0C0F)
private val Panel = Color(0xFF13171C)
private val Raised = Color(0xFF1A2129)
private val Line = Color(0xFF2A333C)
private val Ice = Color(0xFFEBF1F4)
private val Muted = Color(0xFF8B98A2)
private val Accent = Color(0xFF4FD6B8)
private val AccentDim = Color(0xFF17423A)
private val Blue = Color(0xFF7FB4F0)

// Refined type scale: tight-tracking display, clearer hierarchy via Medium/SemiBold.
private val StudioTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.6).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 27.sp,
        letterSpacing = (-0.4).sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.6.sp
    )
)

@Composable
fun VasiliasStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Blue,
            background = Ink,
            surface = Panel,
            surfaceVariant = Raised,
            onBackground = Ice,
            onSurface = Ice,
            outline = Line
        ),
        typography = StudioTypography,
        content = content
    )
}

private enum class HomeTab(val label: String) { STUDIO("Studio"), PROJECTS("Projects"), LEARN("Learn") }
private enum class Glyph { PLUS, IMAGE, FOLDER, SPARK, SEARCH, SETTINGS, MORE, ARROW, CLOCK, LAYERS }

@Composable
fun StudioHome(
    projects: List<ProjectRecord>,
    onNewProject: () -> Unit,
    onOpenImage: () -> Unit,
    onOpenProject: (ProjectRecord) -> Unit,
    onProjectInfo: (ProjectRecord) -> Unit,
    onProjects: () -> Unit,
    onLearn: () -> Unit,
    onAi: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit
) {
    VasiliasStudioTheme {
        var tab by remember { mutableStateOf(HomeTab.STUDIO) }
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                StudioHeader(
                    projectCount = projects.size,
                    onSearch = onSearch,
                    onSettings = onSettings
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp)
                ) {
                    Text("CREATE & EDIT", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.8.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Your mobile\ntypesetting room.", color = Ice, fontSize = 31.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(18.dp))
                    PrimaryAction(onNewProject)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CompactAction("Import image", "JPG, PNG, WEBP", Glyph.IMAGE, Blue, Modifier.weight(1f), onOpenImage)
                        CompactAction("AI workspace", "OCR & translate", Glyph.SPARK, Accent, Modifier.weight(1f), onAi)
                    }
                    Spacer(Modifier.height(24.dp))
                    SectionHeader("RECENT WORK", if (projects.isEmpty()) "EMPTY" else "${projects.size} FILES", onProjects)
                    Spacer(Modifier.height(11.dp))
                    if (projects.isEmpty()) {
                        EmptyProjectCard(onNewProject)
                    } else {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            contentPadding = PaddingValues(end = 18.dp)
                        ) {
                            items(projects.take(8), key = { it.id }) { project ->
                                ProjectCard(project, { onOpenProject(project) }, { onProjectInfo(project) })
                            }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    WorkflowStrip(onLearn)
                }
                HomeDock(tab) {
                    tab = it
                    when (it) {
                        HomeTab.STUDIO -> Unit
                        HomeTab.PROJECTS -> onProjects()
                        HomeTab.LEARN -> onLearn()
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioHeader(projectCount: Int, onSearch: () -> Unit, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(39.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Accent),
            contentAlignment = Alignment.Center
        ) {
            Text("VT", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp)
        }
        Column(Modifier.padding(start = 11.dp).weight(1f)) {
            Text("VASILIAS", color = Ice, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            Text("TYPE STUDIO  ·  $projectCount PROJECTS", color = Muted, fontSize = 9.sp, letterSpacing = 0.7.sp)
        }
        CircleButton(Glyph.SEARCH, onSearch)
        Spacer(Modifier.width(7.dp))
        CircleButton(Glyph.SETTINGS, onSettings)
    }
}

@Composable
private fun PrimaryAction(onClick: () -> Unit) {
    Surface(
        Modifier
            .fillMaxWidth()
            .height(78.dp)
            .clip(RoundedCornerShape(21.dp))
            .clickable(onClick = onClick),
        color = Accent
    ) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(43.dp).clip(CircleShape).background(Ink.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                StudioGlyph(Glyph.PLUS, Ink, Modifier.size(22.dp))
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text("New canvas", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("Start a clean typesetting session", color = Ink.copy(alpha = .66f), fontSize = 11.sp)
            }
            StudioGlyph(Glyph.ARROW, Ink, Modifier.size(22.dp))
        }
    }
}

@Composable
private fun CompactAction(title: String, subtitle: String, glyph: Glyph, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier
            .height(88.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .border(1.dp, Line, RoundedCornerShape(18.dp)),
        color = Panel
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            StudioGlyph(glyph, tint, Modifier.size(21.dp))
            Column {
                Text(title, color = Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, badge: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
        Text(badge, color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onClick))
    }
}

@Composable
private fun ProjectCard(project: ProjectRecord, onClick: () -> Unit, onInfo: () -> Unit) {
    Surface(
        Modifier
            .width(174.dp)
            .height(171.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .border(1.dp, Line, RoundedCornerShape(18.dp)),
        color = Panel
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(97.dp)
                    .background(Color(0xFF202A31))
                    .padding(12.dp)
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val step = 14.dp.toPx()
                    var x = -size.height
                    while (x < size.width) {
                        drawLine(Color.White.copy(alpha = .035f), Offset(x, size.height), Offset(x + size.height, 0f), 1f)
                        x += step
                    }
                    drawRect(Accent.copy(alpha = .14f), size = size.copy(width = size.width * .58f, height = size.height * .68f), topLeft = Offset(size.width * .21f, size.height * .16f))
                }
                Surface(Modifier.align(Alignment.TopEnd).size(28.dp).clickable(onClick = onInfo), shape = CircleShape, color = Ink.copy(alpha = .75f)) {
                    Box(contentAlignment = Alignment.Center) { StudioGlyph(Glyph.MORE, Ice, Modifier.size(15.dp)) }
                }
                Text("${project.width} × ${project.height}", color = Ice.copy(alpha = .76f), fontSize = 9.sp, modifier = Modifier.align(Alignment.BottomStart))
            }
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Text(project.name, color = Ice, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val date = remember(project.lastEditedAtMs) { SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(project.lastEditedAtMs)) }
                Text(date, color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun EmptyProjectCard(onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Line, RoundedCornerShape(18.dp))
            .background(Panel)
            .clickable(onClick = onClick)
            .padding(18.dp)
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(AccentDim), contentAlignment = Alignment.Center) {
                StudioGlyph(Glyph.LAYERS, Accent, Modifier.size(23.dp))
            }
            Column(Modifier.padding(start = 14.dp)) {
                Text("No canvas yet", color = Ice, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Create a project and it will appear here.", color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun WorkflowStrip(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Raised)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StudioGlyph(Glyph.SPARK, Blue, Modifier.size(21.dp))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text("Fast workflow: Clean → OCR → Type", color = Ice, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text("Open the 3-minute guided workflow", color = Muted, fontSize = 9.sp)
        }
        StudioGlyph(Glyph.ARROW, Muted, Modifier.size(18.dp))
    }
}

@Composable
private fun HomeDock(selected: HomeTab, onSelect: (HomeTab) -> Unit) {
    Surface(color = Panel, tonalElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(67.dp)
                .border(1.dp, Line)
                .padding(horizontal = 24.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            HomeTab.entries.forEach { tab ->
                val active = tab == selected
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(13.dp))
                        .clickable { onSelect(tab) }
                        .background(if (active) AccentDim else Color.Transparent),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    StudioGlyph(when (tab) { HomeTab.STUDIO -> Glyph.SPARK; HomeTab.PROJECTS -> Glyph.FOLDER; HomeTab.LEARN -> Glyph.LAYERS }, if (active) Accent else Muted, Modifier.size(18.dp))
                    Text(tab.label, color = if (active) Accent else Muted, fontSize = 9.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun CircleButton(glyph: Glyph, onClick: () -> Unit) {
    Box(
        Modifier
            .size(39.dp)
            .clip(CircleShape)
            .background(Panel)
            .border(1.dp, Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { StudioGlyph(glyph, Muted, Modifier.size(18.dp)) }
}

@Composable
private fun StudioGlyph(type: Glyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = (w * .085f).coerceAtLeast(1.5f), cap = StrokeCap.Round)
        when (type) {
            Glyph.PLUS -> { drawLine(color, Offset(w*.2f,h*.5f), Offset(w*.8f,h*.5f), stroke.width, StrokeCap.Round); drawLine(color, Offset(w*.5f,h*.2f), Offset(w*.5f,h*.8f), stroke.width, StrokeCap.Round) }
            Glyph.IMAGE -> { drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(w*.12f), style = stroke); drawCircle(color, w*.08f, Offset(w*.68f,h*.31f)); val p=Path().apply{moveTo(w*.12f,h*.78f);lineTo(w*.38f,h*.5f);lineTo(w*.55f,h*.65f);lineTo(w*.73f,h*.43f);lineTo(w*.9f,h*.67f)};drawPath(p,color,style=stroke) }
            Glyph.FOLDER -> { val p=Path().apply{moveTo(w*.08f,h*.28f);lineTo(w*.39f,h*.28f);lineTo(w*.48f,h*.39f);lineTo(w*.92f,h*.39f);lineTo(w*.85f,h*.79f);lineTo(w*.15f,h*.79f);close()};drawPath(p,color,style=stroke) }
            Glyph.SPARK -> { val p=Path().apply{moveTo(w*.5f,0f);lineTo(w*.6f,h*.39f);lineTo(w,h*.5f);lineTo(w*.6f,h*.61f);lineTo(w*.5f,h);lineTo(w*.4f,h*.61f);lineTo(0f,h*.5f);lineTo(w*.4f,h*.39f);close()};drawPath(p,color) }
            Glyph.SEARCH -> { drawCircle(color,w*.28f,Offset(w*.43f,h*.43f),style=stroke);drawLine(color,Offset(w*.64f,h*.64f),Offset(w*.88f,h*.88f),stroke.width,StrokeCap.Round) }
            Glyph.SETTINGS -> { drawCircle(color,w*.3f,Offset(w*.5f,h*.5f),style=stroke);drawCircle(color,w*.08f,Offset(w*.5f,h*.5f)); repeat(8){i->val a=Math.PI*2*i/8;drawLine(color,Offset((w*.5+w*.35*kotlin.math.cos(a)).toFloat(),(h*.5+h*.35*kotlin.math.sin(a)).toFloat()),Offset((w*.5+w*.46*kotlin.math.cos(a)).toFloat(),(h*.5+h*.46*kotlin.math.sin(a)).toFloat()),stroke.width,StrokeCap.Round)} }
            Glyph.MORE -> repeat(3){ drawCircle(color,w*.075f,Offset(w*(.25f+it*.25f),h*.5f)) }
            Glyph.ARROW -> { drawLine(color,Offset(w*.18f,h*.5f),Offset(w*.82f,h*.5f),stroke.width,StrokeCap.Round);drawLine(color,Offset(w*.58f,h*.26f),Offset(w*.82f,h*.5f),stroke.width,StrokeCap.Round);drawLine(color,Offset(w*.58f,h*.74f),Offset(w*.82f,h*.5f),stroke.width,StrokeCap.Round) }
            Glyph.CLOCK -> { drawCircle(color,w*.42f,Offset(w*.5f,h*.5f),style=stroke);drawLine(color,Offset(w*.5f,h*.5f),Offset(w*.5f,h*.26f),stroke.width,StrokeCap.Round);drawLine(color,Offset(w*.5f,h*.5f),Offset(w*.68f,h*.62f),stroke.width,StrokeCap.Round) }
            Glyph.LAYERS -> repeat(3){ i -> val y=h*(.25f+i*.24f);drawLine(color,Offset(w*.15f,y),Offset(w*.85f,y),stroke.width,StrokeCap.Round) }
        }
    }
}
