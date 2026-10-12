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
    showOverlay: Boolean, onOverlay: (Boolean) -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = layers.firstOrNull { it.id == selectedId }
    var showNew by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    val accent = Color(0xFF237A46)
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
    Column(modifier.fillMaxWidth().background(Color.White).navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("蒙版", color = Color(0xFF202124), style = MaterialTheme.typography.titleSmall)
            Box {
                TextButton(onClick = { showNew = true }) { Text("＋新建") }
                DropdownMenu(expanded = showNew, onDismissRequest = { showNew = false }) {
                    MaskTool.entries.forEach { item ->
                        DropdownMenuItem(text = { Text(item.label) }, onClick = { create(item) })
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onOverlay(!showOverlay) }) { Text(if (showOverlay) "叠加：开" else "叠加：关") }
            TextButton(onClick = onClose) { Text("完成", color = accent) }
        }
        if (layers.isEmpty()) {
            Text("新建蒙版，选择需要局部调整的区域", color = Color(0xFF62656B),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MaskTool.entries.forEach { item -> TextButton(onClick = { create(item) }) { Text(item.label) } }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                layers.forEach { layer ->
                    Text(
                        text = (if (layer.enabled) "◉ " else "○ ") + layer.name,
                        color = if (layer.id == selectedId) accent else Color(0xFF62656B),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.background(Color(0xFFF2F3F5), RoundedCornerShape(10.dp))
                            .border(1.dp, if (layer.id == selectedId) accent else Color.Transparent, RoundedCornerShape(10.dp))
                            .clickable { onSelect(layer.id); onComponent(-1) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }
        if (selected != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAdjusting(false) }) { Text("范围", color = if (!adjusting) accent else Color.Gray) }
                TextButton(onClick = { onAdjusting(true); onOverlay(false) }) { Text("调色", color = if (adjusting) accent else Color.Gray) }
                TextButton(onClick = { replace(selected.copy(inverted = !selected.inverted)) }) {
                    Text("反转", color = if (selected.inverted) accent else Color.Gray)
                }
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { showMore = true }) { Text("更多") }
                    DropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                        DropdownMenuItem(text = { Text(if (selected.enabled) "隐藏效果" else "显示效果") },
                            onClick = { replace(selected.copy(enabled = !selected.enabled)); showMore = false })
                        DropdownMenuItem(text = { Text("重命名") }, onClick = { rename = selected.name; showMore = false })
                        DropdownMenuItem(text = { Text("复制蒙版") }, onClick = {
                            val copy = selected.copy(id = UUID.randomUUID().toString(), name = selected.name + " 副本")
                            onChange(layers + copy); onSelect(copy.id); onComponent(-1); showMore = false
                        })
                        DropdownMenuItem(text = { Text("删除蒙版", color = Color(0xFFBA1A1A)) }, onClick = {
                            val remaining = layers.filterNot { it.id == selected.id }
                            onChange(remaining); onSelect(remaining.lastOrNull()?.id); onComponent(-1); showMore = false
                        })
                    }
                }
            }
            if (adjusting) {
                TonePanel(photoName = selected.name, params = selected.tone,
                    onParamsChange = { replace(selected.copy(tone = it)) },
                    onReset = { replace(selected.copy(tone = ToneParams())) }, onClose = onClose, compactHeader = true)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    MaskTool.entries.forEach { item ->
                        TextButton(onClick = { onTool(item); onComponent(-1) }) {
                            Text(item.label, color = if (tool == item) accent else Color.Gray)
                        }
                    }
                    TextButton(onClick = { onSubtract(false); onComponent(-1) }) { Text("＋添加", color = if (!subtract) accent else Color.Gray) }
                    TextButton(onClick = { onSubtract(true); onComponent(-1) }) { Text("－减去", color = if (subtract) accent else Color.Gray) }
                }
                if (selected.components.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                        selected.components.forEachIndexed { index, part ->
                            TextButton(onClick = {
                                onTool(part.tool); onSubtract(part.subtract); onComponent(index)
                                onRadius(part.radius); onFeather(part.feather); onOpacity(part.opacity)
                            }) { Text("${if (part.subtract) "−" else "+"}${part.tool.label} ${index + 1}",
                                color = if (index == componentIndex) accent else Color.Gray,
                                style = MaterialTheme.typography.labelSmall) }
                        }
                        TextButton(onClick = {
                            replace(selected.copy(components = selected.components.dropLast(1))); onComponent(-1)
                        }) { Text("撤销范围") }
                    }
                }
                if (tool == MaskTool.BRUSH) MaskSlider("大小", radius, 0.005f..0.25f) { updatePart(it, 0) }
                MaskSlider("羽化", feather, 0f..1f) { updatePart(it, 1) }
                MaskSlider("不透明度", opacity, 0.05f..1f) { updatePart(it, 2) }
                val radial = selected.components.getOrNull(componentIndex)?.takeIf { it.tool == MaskTool.RADIAL }
                if (radial != null) {
                    MaskSlider("旋转", radial.rotation / 180f, -1f..1f, "${radial.rotation.roundToInt()}°") { value ->
                        replace(selected.copy(components = selected.components.mapIndexed { i, part ->
                            if (i == componentIndex) part.copy(rotation = value * 180f) else part
                        }))
                    }
                }
                Text(when (tool) {
                    MaskTool.BRUSH -> "单指涂抹，双指缩放移动；“减去”擦除选区"
                    MaskTool.RADIAL -> "绿色手柄旋转，白色控制点移动或缩放；也可用旋转滑杆"
                    else -> "拖动创建，白色控制点修改；双指缩放移动"
                },
                    color = Color(0xFF62656B), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
        }
    }
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
        Text(label, color = Color(0xFF62656B), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(66.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text(displayValue ?: "${(value * 100).roundToInt()}", color = Color(0xFF202124), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(44.dp))
    }
}
