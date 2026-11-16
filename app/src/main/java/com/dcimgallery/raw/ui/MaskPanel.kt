package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.dcimgallery.raw.core.tone.*
import java.util.UUID
import kotlin.math.roundToInt

@Composable
internal fun MaskPanel(
    layers: List<MaskLayer>, selectedId: String?, onSelect: (String?) -> Unit,
    onChange: (List<MaskLayer>) -> Unit,
    tool: MaskTool, onTool: (MaskTool) -> Unit,
    radius: Float, onRadius: (Float) -> Unit,
    feather: Float, onFeather: (Float) -> Unit,
    opacity: Float, onOpacity: (Float) -> Unit,
    onComponent: (Int) -> Unit,
    onAdjusting: (Boolean) -> Unit,
    onToneDragging: (Boolean) -> Unit,
    onOverlay: (Boolean) -> Unit,
    thumbnail: Bitmap? = null, onPick: (EditTool) -> Unit,
) {
    val selected = layers.firstOrNull { it.id == selectedId }
    val sections = remember { mutableStateMapOf<String, Int>() }
    val section = sections[selectedId]?.takeIf { it in -1..5 } ?: -1
    var dialog by remember { mutableStateOf<String?>(null) }
    var rename by rememberSaveable { mutableStateOf("") }
    val scroll = rememberScrollState()
    var grade by rememberSaveable(selectedId) { mutableIntStateOf(1) }
    LaunchedEffect(selectedId, section) {
        scroll.scrollTo(0)
        onAdjusting(section != -1); onOverlay(section == -1)
    }
    LaunchedEffect(selectedId, selected?.components) {
        val part = selected?.components?.firstOrNull()
        onComponent(if (part == null) -1 else 0)
        if (part != null) { onTool(part.tool); onRadius(part.radius); onFeather(part.feather); onOpacity(part.opacity) }
    }
    fun replace(layer: MaskLayer) = onChange(layers.map { if (it.id == layer.id) layer else it })
    fun create(chosen: MaskTool) {
        val points = when (chosen) {
            MaskTool.BRUSH -> emptyList()
            MaskTool.LINEAR -> listOf(MaskPoint(.5f, .25f), MaskPoint(.5f, .75f))
            MaskTool.RADIAL -> listOf(MaskPoint(.5f, .5f), MaskPoint(.72f, .8f))
        }
        val layer = MaskLayer(name = "蒙版 ${layers.size + 1}", components = listOf(
            MaskComponent(chosen, points, radius = radius, feather = feather, opacity = opacity)))
        onChange(layers + layer); onSelect(layer.id); onTool(chosen); onComponent(0)
        sections[layer.id] = -1; dialog = null
    }
    fun partChange(transform: (MaskComponent) -> MaskComponent) {
        if (selected != null && selected.components.isNotEmpty()) {
            replace(selected.copy(components = listOf(transform(selected.components.first()))))
        }
    }
    Box(Modifier.fillMaxSize()) {
        PorcelainPanel {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                EditorToolPicker(MaskToolEntry, onPick)
                Spacer(Modifier.width(4.dp))
                val maskScroll = rememberScrollState()
                LaunchedEffect(layers.lastOrNull()?.id) { maskScroll.scrollTo(maskScroll.maxValue) }
                Row(Modifier.weight(1f).horizontalScroll(maskScroll),
                    horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                    layers.forEach { layer ->
                        MaskThumbnail(layer, layer.id == selectedId, thumbnail) { onSelect(layer.id) }
                    }
                    EditorAction(StudioSymbol.Plus, "新建蒙版", { dialog = "create" })
                }
                if (selected != null) {
                    EditorAction(StudioSymbol.Ellipsis, "蒙版选项", { dialog = "actions" })
                    EditorAction(if (section == -1) StudioSymbol.Settings else StudioSymbol.Scan,
                        if (section == -1) "调整蒙版" else "编辑蒙版范围",
                        { sections[selected.id] = if (section == -1) 0 else -1 })
                }
            }
            if (selected != null && section != -1) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    EditorToolPicker(ToneTools[section], { sections[selected.id] = ToneTools.indexOf(it) }, maskOnly = true)
                    if (section == 4) {
                        Spacer(Modifier.weight(1f))
                        GradeTabs(grade, { grade = it }, Modifier.weight(3f))
                    }
                }
            }
            Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 4.dp)) {
                if (selected != null && section == -1) {
                    val part = selected.components.firstOrNull()
                    if (part != null) {
                        if (part.tool == MaskTool.BRUSH) ParameterSlider("大小", "${(part.radius * 100).roundToInt()}%",
                            part.radius, .005f.. .25f, .06f, "${selected.id}:radius",
                            { partChange { p -> p.copy(radius = it) } }, onToneDragging)
                        ParameterSlider("羽化", "${(part.feather * 100).roundToInt()}%", part.feather, 0f..1f, .7f,
                            "${selected.id}:feather", { partChange { p -> p.copy(feather = it) } }, onToneDragging)
                        ParameterSlider("不透明度", "${(part.opacity * 100).roundToInt()}%", part.opacity, .05f..1f, 1f,
                            "${selected.id}:opacity", { partChange { p -> p.copy(opacity = it) } }, onToneDragging)
                        if (part.tool == MaskTool.RADIAL) ParameterSlider("旋转", "${part.rotation.roundToInt()}°",
                            part.rotation, -180f..180f, 0f, "${selected.id}:rotation",
                            { partChange { p -> p.copy(rotation = it) } }, onToneDragging)
                    }
                } else if (selected != null) ToneRows(section, selected.tone, { replace(selected.copy(tone = it)) }, selected.id, onToneDragging, grade = grade)
            }
        }
        // A custom white floating sheet stays inside the editor, with no platform menu styling.
        if (dialog == "actions" || dialog == "create") {
            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .08f)).clickable { dialog = null })
            Column(Modifier.align(Alignment.BottomCenter).padding(12.dp).fillMaxWidth()
                .clip(RoundedCornerShape(20.dp)).background(Color.White)
                .verticalScroll(rememberScrollState()).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (dialog == "create") "新建蒙版" else selected?.name.orEmpty(), Modifier.weight(1f), color = StudioColors.Ink, fontSize = 14.sp)
                    EditorAction(StudioSymbol.X, "关闭菜单", { dialog = null })
                }
                if (dialog == "create") {
                    MaskTool.entries.forEach { item -> MenuAction(item.label, maskSymbol(item)) { create(item) } }
                } else if (selected != null) {
                    MenuAction(if (selected.enabled) "隐藏效果" else "显示效果", StudioSymbol.CircleOff) { replace(selected.copy(enabled = !selected.enabled)); dialog = null }
                    MenuAction("重命名", StudioSymbol.Pencil) { rename = selected.name; dialog = "rename" }
                    MenuAction("复制蒙版", StudioSymbol.Copy) {
                        val copy = selected.copy(id = UUID.randomUUID().toString(), name = selected.name + " 副本")
                        onChange(layers + copy); sections[copy.id] = section; onSelect(copy.id); dialog = null
                    }
                    MenuAction(if (selected.inverted) "取消反转范围" else "反转范围", StudioSymbol.FlipHorizontal) { replace(selected.copy(inverted = !selected.inverted)); dialog = null }
                    MenuAction("删除蒙版", StudioSymbol.Trash2, danger = true) {
                        val remaining = layers.filterNot { it.id == selected.id }
                        onChange(remaining); onSelect(remaining.lastOrNull()?.id); dialog = null
                    }
                }
            }
        }
    }
    if (dialog == "rename" && selected != null) Dialog(onDismissRequest = { dialog = null }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White).padding(20.dp)) {
            Text("重命名蒙版", fontSize = 16.sp, color = StudioColors.Ink)
            Spacer(Modifier.height(16.dp))
            BasicTextField(rename, { rename = it }, singleLine = true, textStyle = TextStyle(color = StudioColors.Ink, fontSize = 16.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(StudioColors.Canvas).padding(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                MenuAction("取消", StudioSymbol.X) { dialog = null }
                MenuAction("保存", StudioSymbol.Check) { if (rename.isNotBlank()) replace(selected.copy(name = rename.trim())); dialog = null }
            }
        }
    }
}

@Composable
private fun MenuAction(label: String, icon: StudioSymbol, danger: Boolean = false, action: () -> Unit) {
    Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = action).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val tint = if (danger) Color(0xFFBA1A1A) else EditorAccent
        StudioIcon(icon, Modifier.size(18.dp), tint = tint)
        Text(label, color = if (danger) tint else StudioColors.Ink, fontSize = 13.sp)
    }
}

@Composable
private fun MaskThumbnail(layer: MaskLayer, selected: Boolean, photo: Bitmap?, onSelect: () -> Unit) {
    Column(Modifier.width(48.dp).heightIn(min = 44.dp)
        .studioPressable(selected = selected, onClick = onSelect),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(30.dp, 26.dp).clip(RoundedCornerShape(6.dp)).background(StudioColors.Ink)) {
            if (photo != null && !photo.isRecycled) Image(photo.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)))
            Canvas(Modifier.fillMaxSize()) {
                val tint = Color.White.copy(alpha = if (layer.enabled) .7f else .2f)
                when (layer.components.firstOrNull()?.tool) {
                    MaskTool.RADIAL -> rotate(layer.components.first().rotation) { drawOval(tint, Offset(size.width * .3f, size.height * .12f), Size(size.width * .4f, size.height * .76f)) }
                    MaskTool.LINEAR -> drawRect(tint, size = Size(size.width, size.height * .45f))
                    else -> drawOval(tint, Offset(size.width * .12f, size.height * .4f), Size(size.width * .76f, size.height * .4f))
                }
            }
            if (selected) Box(Modifier.align(Alignment.BottomEnd).size(12.dp)
                .background(StudioColors.Accent, RoundedCornerShape(topStart = 4.dp)),
                contentAlignment = Alignment.Center) {
                StudioIcon(StudioSymbol.Check, Modifier.size(10.dp), tint = Color.White)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(layer.name, fontSize = 11.sp, color = if (selected) EditorAccent else StudioColors.Muted,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 3.dp))
    }
}

private fun maskSymbol(tool: MaskTool) = when (tool) {
    MaskTool.BRUSH -> StudioSymbol.Brush
    MaskTool.LINEAR -> StudioSymbol.RectangleHorizontal
    MaskTool.RADIAL -> StudioSymbol.CircleDashed
}
