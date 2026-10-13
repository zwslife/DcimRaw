package com.dcimgallery.raw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.core.tone.MaskLayer
import com.dcimgallery.raw.core.tone.MaskTool
import com.dcimgallery.raw.core.tone.ToneParams
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun MaskPanel(
    layers: List<MaskLayer>, selectedId: String?, onSelect: (String?) -> Unit,
    onChange: (List<MaskLayer>) -> Unit,
    tool: MaskTool, onTool: (MaskTool) -> Unit,
    subtract: Boolean, onSubtract: (Boolean) -> Unit,
    radius: Float, onRadius: (Float) -> Unit,
    feather: Float, onFeather: (Float) -> Unit,
    opacity: Float, onOpacity: (Float) -> Unit,
    componentIndex: Int, onComponent: (Int) -> Unit,
    adjusting: Boolean, onAdjusting: (Boolean) -> Unit,
    onToneDragging: (Boolean) -> Unit,
    showOverlay: Boolean, onOverlay: (Boolean) -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = layers.firstOrNull { it.id == selectedId }
    var showNew by remember { mutableStateOf(false) }
    var showRanges by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var control by remember { mutableStateOf("羽化") }
    var rename by remember { mutableStateOf<String?>(null) }
    val accent = StudioColors.Pine
    fun replace(layer: MaskLayer) = onChange(layers.map { if (it.id == layer.id) layer else it })
    fun create(chosen: MaskTool) {
        val layer = MaskLayer(name = "蒙版 ${layers.size + 1}")
        onChange(layers + layer); onSelect(layer.id); onTool(chosen)
        onSubtract(false); onComponent(-1); onAdjusting(false); onOverlay(true)
        showNew = false
    }
    fun updatePart(value: Float, kind: Int) {
        when (kind) { 0 -> onRadius(value); 1 -> onFeather(value); else -> onOpacity(value) }
        if (selected != null && componentIndex in selected.components.indices) {
            val parts = selected.components.toMutableList()
            parts[componentIndex] = when (kind) {
                0 -> parts[componentIndex].copy(radius = value)
                1 -> parts[componentIndex].copy(feather = value)
                else -> parts[componentIndex].copy(opacity = value)
            }
            replace(selected.copy(components = parts))
        }
    }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        .background(Color.White).navigationBarsPadding()) {
        StudioPanelHeader("蒙版", onClose)
        if (layers.isEmpty()) {
            Text("选择工具，创建第一个蒙版", color = StudioColors.Muted,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MaskTool.entries.forEach { item -> StudioTool(maskSymbol(item), item.label, { create(item) }) }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    StudioIconButton(StudioSymbol.Plus, "新建蒙版", { showNew = true })
                    DropdownMenu(showNew, { showNew = false }) {
                        MaskTool.entries.forEach { item -> DropdownMenuItem(text = { Text(item.label) },
                            leadingIcon = { StudioIcon(maskSymbol(item)) }, onClick = { create(item) }) }
                    }
                }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    layers.forEach { layer ->
                        StudioChoice(layer.name + if (layer.enabled) "" else " · 隐藏",
                            { onSelect(layer.id); onComponent(-1) },
                            symbol = layer.components.firstOrNull()?.tool?.let(::maskSymbol) ?: StudioSymbol.Scan,
                            selected = layer.id == selectedId)
                    }
                }
                if (selected != null) Box {
                    StudioIconButton(StudioSymbol.Ellipsis, "蒙版选项", { showMore = true })
                    DropdownMenu(showMore, { showMore = false }) {
                        DropdownMenuItem(text = { Text(if (selected.inverted) "取消反转范围" else "反转范围") },
                            leadingIcon = { StudioIcon(StudioSymbol.FlipHorizontal) }, onClick = {
                                replace(selected.copy(inverted = !selected.inverted)); showMore = false
                            })
                        DropdownMenuItem(text = { Text(if (selected.enabled) "隐藏效果" else "显示效果") },
                            onClick = { replace(selected.copy(enabled = !selected.enabled)); showMore = false })
                        DropdownMenuItem(text = { Text("重命名") }, onClick = { rename = selected.name; showMore = false })
                        DropdownMenuItem(text = { Text("复制蒙版") }, leadingIcon = { StudioIcon(StudioSymbol.Copy) }, onClick = {
                            val copy = selected.copy(id = UUID.randomUUID().toString(), name = selected.name + " 副本")
                            onChange(layers + copy); onSelect(copy.id); onComponent(-1); showMore = false
                        })
                        DropdownMenuItem(text = { Text("撤销最近范围") }, enabled = selected.components.isNotEmpty(),
                            leadingIcon = { StudioIcon(StudioSymbol.RotateCcw) }, onClick = {
                                replace(selected.copy(components = selected.components.dropLast(1))); onComponent(-1); showMore = false
                            })
                        DropdownMenuItem(text = { Text("操作说明") }, leadingIcon = { StudioIcon(StudioSymbol.Info) },
                            onClick = { showHelp = true; showMore = false })
                        DropdownMenuItem(text = { Text("删除蒙版", color = Color(0xFFBA1A1A)) },
                            leadingIcon = { StudioIcon(StudioSymbol.Trash2, tint = Color(0xFFBA1A1A)) }, onClick = {
                                val remaining = layers.filterNot { it.id == selected.id }
                                onChange(remaining); onSelect(remaining.lastOrNull()?.id); onComponent(-1); showMore = false
                            })
                    }
                }
            }
        }
        if (selected != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StudioChoice("范围", { onAdjusting(false); onOverlay(true) }, Modifier.weight(1f), StudioSymbol.Scan, selected = !adjusting)
                StudioChoice("调色", { onAdjusting(true); onOverlay(false) }, Modifier.weight(1f), StudioSymbol.Palette, selected = adjusting)
                StudioIconButton(StudioSymbol.Layers2, if (showOverlay) "隐藏选区叠加" else "显示选区叠加",
                    { onOverlay(!showOverlay) }, selected = showOverlay)
            }
            if (adjusting) {
                TonePanel(photoName = selected.name, params = selected.tone,
                    onParamsChange = { replace(selected.copy(tone = it)) },
                    onReset = { replace(selected.copy(tone = ToneParams())) }, onClose = onClose,
                    compactHeader = true, onDraggingChange = onToneDragging)
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        StudioChoice("${tool.label} · ${if (componentIndex in selected.components.indices) "范围 ${componentIndex + 1}" else "新范围"}",
                            { showRanges = true }, symbol = maskSymbol(tool), expandable = true)
                        DropdownMenu(showRanges, { showRanges = false }) {
                            DropdownMenuItem(text = { Text("新建范围") }, enabled = false, onClick = {})
                            MaskTool.entries.forEach { item ->
                                DropdownMenuItem(text = { Text(item.label) }, leadingIcon = { StudioIcon(maskSymbol(item)) }, onClick = {
                                    onTool(item); onComponent(-1); showRanges = false; onOverlay(true)
                                })
                            }
                            if (selected.components.isNotEmpty()) {
                                DropdownMenuItem(text = { Text("编辑已有范围") }, enabled = false, onClick = {})
                                selected.components.forEachIndexed { index, part ->
                                    DropdownMenuItem(text = { Text("${part.tool.label} ${index + 1} · ${if (part.subtract) "减去" else "添加"}") },
                                        leadingIcon = { StudioIcon(maskSymbol(part.tool)) }, onClick = {
                                            onTool(part.tool); onSubtract(part.subtract); onComponent(index)
                                            onRadius(part.radius); onFeather(part.feather); onOpacity(part.opacity)
                                            showRanges = false; onOverlay(true)
                                        })
                                }
                            }
                        }
                    }
                    StudioChoice("添加", { onSubtract(false); onComponent(-1) }, symbol = StudioSymbol.Plus, selected = !subtract)
                    StudioChoice("减去", { onSubtract(true); onComponent(-1) }, symbol = StudioSymbol.Minus, selected = subtract)
                }
                val radial = selected.components.getOrNull(componentIndex)?.takeIf { it.tool == MaskTool.RADIAL }
                val controls = buildList {
                    if (tool == MaskTool.BRUSH) add("大小")
                    add("羽化"); add("不透明度")
                    if (radial != null) add("旋转")
                }
                val activeControl = control.takeIf { it in controls } ?: "羽化"
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    controls.forEach { name -> StudioChoice(name, { control = name }, selected = activeControl == name) }
                }
                when (activeControl) {
                    "大小" -> MaskSlider("大小", radius, 0.005f..0.25f) { updatePart(it, 0) }
                    "不透明度" -> MaskSlider("不透明度", opacity, 0.05f..1f) { updatePart(it, 2) }
                    "旋转" -> if (radial != null) MaskSlider("旋转", radial.rotation / 180f, -1f..1f, "${radial.rotation.roundToInt()}°") { value ->
                        replace(selected.copy(components = selected.components.mapIndexed { i, part ->
                            if (i == componentIndex) part.copy(rotation = value * 180f) else part
                        }))
                    }
                    else -> MaskSlider("羽化", feather, 0f..1f) { updatePart(it, 1) }
                }
            }
        }
    }
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false }, title = { Text("蒙版操作") },
        text = { Text("先选蒙版，再进入范围或调色。\n\n范围菜单可新建画笔、线性或径向范围，也可选中已有范围继续编辑。添加与减去用于绘制新范围。\n\n单指编辑范围，双指缩放和移动照片；径向蒙版的绿色手柄用于旋转。") },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } })
    rename?.let { text ->
        AlertDialog(onDismissRequest = { rename = null }, title = { Text("重命名蒙版") },
            text = { OutlinedTextField(value = text, onValueChange = { rename = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                if (selected != null && text.isNotBlank()) replace(selected.copy(name = text.trim()))
                rename = null
            }) { Text("保存") } }, dismissButton = { TextButton(onClick = { rename = null }) { Text("取消") } })
    }
}

@Composable
private fun MaskSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, displayValue: String? = null, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = StudioColors.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(66.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(displayValue ?: "${(value * 100).roundToInt()}", color = StudioColors.Ink, style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
    }
}

private fun maskSymbol(tool: MaskTool): StudioSymbol = when (tool) {
    MaskTool.BRUSH -> StudioSymbol.Brush
    MaskTool.LINEAR -> StudioSymbol.RectangleHorizontal
    MaskTool.RADIAL -> StudioSymbol.CircleDashed
}
