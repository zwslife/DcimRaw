package com.dcimgallery.raw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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

internal val EditorPine = Color(0xFF28644D)
private val EditorMuted = Color(0xFF859087)

@Composable
internal fun PorcelainPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
        .background(Color.White).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(9.dp), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.size(26.dp, 3.dp).clip(CircleShape).background(Color(0xFFDFE6DC)))
        }
        content()
    }
}

@Composable
internal fun PorcelainHeader(onUndo: () -> Unit, onDone: () -> Unit, canUndo: Boolean,
    onRedo: () -> Unit, canRedo: Boolean) {
    Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        EditorAction(StudioSymbol.Undo2, "撤回上一次调整", onUndo, enabled = canUndo)
        EditorAction(StudioSymbol.Redo2, "重做上一次调整", onRedo, enabled = canRedo)
        EditorAction(StudioSymbol.Check, "确认调整", onDone, filled = true)
    }
}

@Composable
internal fun EditorAction(symbol: StudioSymbol, label: String, onClick: () -> Unit,
    filled: Boolean = false, enabled: Boolean = true) {
    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(27.dp).clip(RoundedCornerShape(9.dp))
            .background(if (filled) EditorPine else Color.Transparent), contentAlignment = Alignment.Center) {
            StudioIcon(symbol, Modifier.size(15.dp), label,
                if (filled) Color.White else EditorMuted.copy(alpha = if (enabled) 1f else .4f))
        }
    }
}

@Composable
internal fun EditorTabs(selected: Int, onSelect: (Int) -> Unit, includeRange: Boolean = false) {
    val labels = listOf("光线", "色彩", "混色", "效果", "分级", "细节", "曲线")
    val icons = listOf(StudioSymbol.Sun, StudioSymbol.Palette, StudioSymbol.CircleDot,
        StudioSymbol.Sparkles, StudioSymbol.Aperture, StudioSymbol.Focus, StudioSymbol.Curve)
    val scroll = rememberScrollState()
    Column {
        Row(Modifier.fillMaxWidth().height(48.dp).horizontalScroll(scroll).padding(horizontal = 12.dp)) {
            val indices = (if (includeRange) listOf(-1) else emptyList()) + listOf(0, 6, 1, 2, 3, 4, 5)
            indices.forEach { index ->
                val active = index == selected
                val tint = if (active) EditorPine else EditorMuted
                Box(Modifier.height(48.dp).widthIn(min = 52.dp).clickable { onSelect(index) }
                    .semantics { this.selected = active }, contentAlignment = Alignment.Center) {
                    Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        StudioIcon(if (index == -1) StudioSymbol.Scan else icons[index], Modifier.size(15.dp), tint = tint)
                        Text(if (index == -1) "范围" else labels[index], color = tint, fontSize = 12.sp)
                    }
                    if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                        .size(18.dp, 2.dp).clip(CircleShape).background(EditorPine))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFE4E9E3)))
    }
}

@Composable
internal fun EditorColorTabs(selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        ToneParams.HSL_COLORS.forEachIndexed { index, color ->
            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { onSelect(index) }
                .semantics { this.selected = selected == index },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(30.dp).then(if (selected == index) Modifier.border(1.5.dp, EditorPine, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(Color(color)))
                }
                Spacer(Modifier.height(5.dp))
                Text(ToneParams.HSL_NAMES[index], fontSize = 11.sp, color = if (selected == index) EditorPine else EditorMuted)
            }
        }
    }
}

/** Signed controls start at zero; other controls start at the left end of the track. */
@Composable
internal fun ParameterSlider(label: String, display: String, value: Float, range: ClosedFloatingPointRange<Float>,
    default: Float, controlKey: String, onChange: (Float) -> Unit, onGesture: (Boolean) -> Unit = {}) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val gesture by rememberUpdatedState(onGesture)
    DisposableEffect(controlKey) { onDispose { gesture(false) } }
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(80.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = Color(0xFF6C7A6E), fontSize = 12.sp, modifier = Modifier.widthIn(max = 64.dp).weight(1f, fill = false))
            Text(display, color = if (abs(value - default) > .0001f) EditorPine else EditorMuted,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
        }
        Spacer(Modifier.width(2.dp))
        Canvas(Modifier.weight(1f).height(46.dp)
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
            drawLine(Color(0xFFE9ECE8), Offset(inset, y), Offset(size.width - inset, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFF4C8166), Offset(origin, y), Offset(thumb, y), 4.dp.toPx(), StrokeCap.Round)
            drawLine(Color(0xFFA3AEA3), Offset(origin, y - 4.dp.toPx()), Offset(origin, y + 4.dp.toPx()), 1.dp.toPx())
            for (i in 4 downTo 1) drawCircle(Color(0xFF24382E).copy(alpha = .014f), (10 + i).dp.toPx(), Offset(thumb, y + 2.dp.toPx()))
            drawCircle(Color(0xFFE9EDE8), 10.5.dp.toPx(), Offset(thumb, y))
            drawCircle(Color.White, 10.dp.toPx(), Offset(thumb, y))
        }
    }
}
