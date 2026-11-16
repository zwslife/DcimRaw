package com.dcimgallery.raw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcimgallery.raw.core.tone.ToneParams
import kotlin.math.abs
import kotlin.math.floor

internal val EditorAccent = StudioColors.Accent
private val EditorMuted = StudioColors.Muted

internal data class EditTool(val label: String, val symbol: StudioSymbol)

internal val ToneTools = listOf(
    EditTool("光线", StudioSymbol.Sun), EditTool("色彩", StudioSymbol.Palette),
    EditTool("混色", StudioSymbol.CircleDot), EditTool("效果", StudioSymbol.Sparkles),
    EditTool("分级", StudioSymbol.Aperture), EditTool("细节", StudioSymbol.Focus),
    EditTool("曲线", StudioSymbol.Curve),
)
internal val MaskToolEntry = EditTool("蒙版", StudioSymbol.Scan)
internal val CropToolEntry = EditTool("旋转裁切", StudioSymbol.Frame)
internal val AdaptiveLookToolEntry = EditTool("预设", StudioSymbol.Layers2)
internal val PortraitToolEntry = EditTool("人像", StudioSymbol.Focus)
private val ToolMenuOrder = listOf(0, 6, 1, 2, 3, 4, 5)

@Composable
internal fun PorcelainPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        .background(Color.White).padding(top = 8.dp), content = content)
}

@Composable
internal fun EditorToolPicker(tool: EditTool, onPick: (EditTool) -> Unit, maskOnly: Boolean = false,
    modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val choices = ToolMenuOrder.filter { !maskOnly || it != 6 }.map(ToneTools::get) +
        if (maskOnly) emptyList() else listOf(AdaptiveLookToolEntry, PortraitToolEntry, MaskToolEntry, CropToolEntry)
    Box(modifier) {
        Row(Modifier.height(44.dp).studioPressable { expanded = true }, verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.height(32.dp).background(StudioColors.Tint, CircleShape).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StudioIcon(tool.symbol, Modifier.size(14.dp), tint = EditorAccent)
                Text(tool.label, fontSize = 13.sp, maxLines = 1)
                StudioIcon(StudioSymbol.ChevronRight, Modifier.size(12.dp).rotate(90f), tint = EditorMuted)
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = Color.White,
            shape = RoundedCornerShape(18.dp), modifier = Modifier.width(248.dp)) {
            choices.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    pair.forEach { entry ->
                        StudioChoice(entry.label, {
                            expanded = false
                            onPick(entry)
                        }, Modifier.weight(1f), symbol = entry.symbol, selected = entry == tool)
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun EditorAction(symbol: StudioSymbol, label: String, onClick: () -> Unit,
    enabled: Boolean = true, tint: Color = EditorMuted) {
    Box(Modifier.size(44.dp).studioPressable(enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        StudioIcon(symbol, Modifier.size(18.dp), label, tint)
    }
}

private val GradeChoices = listOf(
    Triple("阴影", StudioSymbol.Moon, StudioSymbol.MoonActive),
    Triple("中间调", StudioSymbol.Contrast, StudioSymbol.ContrastActive),
    Triple("高光", StudioSymbol.Sun, StudioSymbol.SunActive),
    Triple("全局", StudioSymbol.Aperture, StudioSymbol.ApertureActive),
)

@Composable
internal fun GradeTabs(grade: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.widthIn(max = 176.dp)) {
        GradeChoices.forEachIndexed { index, (name, regular, active) ->
            val selected = index == grade
            Box(Modifier.weight(1f).height(44.dp).studioPressable(selected = selected) { onSelect(index) },
                contentAlignment = Alignment.Center) {
                StudioIcon(if (selected) active else regular, Modifier.size(19.dp), name,
                    if (selected) EditorAccent else EditorMuted)
            }
        }
    }
}

private val EditorSwatches = listOf(0xFFF34444, 0xFFFF900A, 0xFFF4CF20, 0xFF47A84A,
    0xFF00AEBE, 0xFF248CE3, 0xFF923CB1, 0xFFE42675).map { Color(it) }
private val CurveSwatches = listOf(null, EditorSwatches[0], EditorSwatches[3], EditorSwatches[5])

@Composable
internal fun EditorColorTabs(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier,
    curves: Boolean = false) {
    val names = if (curves) listOf("RGB", "红色", "绿色", "蓝色") else ToneParams.HSL_NAMES
    val colors = if (curves) CurveSwatches else EditorSwatches
    Row(modifier) {
        names.forEachIndexed { index, name ->
            val active = selected == index
            val color = colors[index]
            Box(Modifier.weight(1f).height(44.dp).studioPressable(selected = active) { onSelect(index) }
                .semantics { contentDescription = name }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(16.dp)) {
                    if (color != null) drawCircle(color)
                    else listOf(EditorSwatches[0], EditorSwatches[3], EditorSwatches[5]).forEachIndexed { i, ink ->
                        drawArc(ink, -150f + i * 120f, 120f, useCenter = true)
                    }
                }
                if (active) {
                    StudioIcon(StudioSymbol.Check, Modifier.offset(y = 0.7.dp).size(12.dp),
                        tint = Color.Black.copy(alpha = .35f))
                    StudioIcon(StudioSymbol.Check, Modifier.size(11.dp), tint = Color.White)
                }
            }
        }
    }
}

/** Signed controls start at zero; other controls start at the left end of the track. */
@Composable
internal fun ParameterSlider(label: String, display: String, value: Float, range: ClosedFloatingPointRange<Float>,
    default: Float, controlKey: String, onChange: (Float) -> Unit, onGesture: (Boolean) -> Unit = {},
    ruler: Boolean = false) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val gesture by rememberUpdatedState(onGesture)
    DisposableEffect(controlKey) { onDispose { gesture(false) } }
    Column(Modifier.fillMaxWidth().height(56.dp)) {
        Row(Modifier.fillMaxWidth().height(24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (ruler) Arrangement.Center else Arrangement.SpaceBetween) {
            if (!ruler) Text(label, color = StudioColors.Ink, fontSize = 13.sp, maxLines = 1)
            Text(display, color = if (abs(value - default) > .0001f) EditorAccent else EditorMuted,
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)
            .semantics {
                contentDescription = label
                stateDescription = display
                progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(range), range)
                setProgress { next -> gesture(true); change(next.coerceIn(range)); gesture(false); true }
            }
            .pointerInput(controlKey, range) {
                detectHorizontalDragGestures(onDragStart = { point ->
                    gesture(true)
                    change(editorSliderValue(point.x, size.width.toFloat(), 10.dp.toPx(), range))
                }, onDragEnd = { gesture(false) }, onDragCancel = { gesture(false) }) { event, _ ->
                    event.consume()
                    change(editorSliderValue(event.position.x, size.width.toFloat(), 10.dp.toPx(), range))
                }
            }.pointerInput(controlKey, range) {
                detectTapGestures(onTap = { point ->
                    gesture(true); change(editorSliderValue(point.x, size.width.toFloat(), 10.dp.toPx(), range)); gesture(false)
                }, onDoubleTap = { gesture(true); change(default); gesture(false) })
            }) {
            val inset = 10.dp.toPx()
            val span = (size.width - inset * 2).coerceAtLeast(1f)
            fun x(v: Float) = inset + (v - range.start) / (range.endInclusive - range.start) * span
            val y = size.height / 2
            val origin = x(if (range.start < 0f && range.endInclusive > 0f) 0f else range.start)
            val thumb = x(current.coerceIn(range))
            if (ruler) {
                val spacing = 8.dp.toPx()
                val center = size.width / 2
                val ticks = floor(span / (2 * spacing)).toInt()
                val shift = (current / 2f) * spacing
                for (index in -ticks..ticks) {
                    val x = center + index * spacing - shift % spacing
                    drawLine(StudioColors.Track, Offset(x, y - 5.dp.toPx()), Offset(x, y + 5.dp.toPx()), 1.dp.toPx())
                }
                drawLine(StudioColors.Accent, Offset(center, y - 10.dp.toPx()), Offset(center, y + 10.dp.toPx()), 1.5.dp.toPx())
                return@Canvas
            }
            drawLine(StudioColors.Track, Offset(inset, y), Offset(size.width - inset, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(StudioColors.Accent, Offset(origin, y), Offset(thumb, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(StudioColors.Muted.copy(alpha = .6f), Offset(origin, y - 4.dp.toPx()), Offset(origin, y + 4.dp.toPx()), 1.dp.toPx())
            for (i in 4 downTo 1) drawCircle(Color.Black.copy(alpha = .022f), (10 + i).dp.toPx(), Offset(thumb, y + 2.dp.toPx()))
            drawCircle(StudioColors.Line, 10.5.dp.toPx(), Offset(thumb, y))
            drawCircle(Color.White, 10.dp.toPx(), Offset(thumb, y))
        }
    }
}
