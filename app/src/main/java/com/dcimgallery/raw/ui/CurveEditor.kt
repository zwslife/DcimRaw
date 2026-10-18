package com.dcimgallery.raw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcimgallery.raw.core.tone.CurvePoint
import com.dcimgallery.raw.core.tone.ToneCurve
import com.dcimgallery.raw.core.tone.ToneParams
import kotlin.math.roundToInt

private val CurvePine = Color(0xFF28644D)

@Composable
internal fun CurveEditor(params: ToneParams, onChange: (ToneParams) -> Unit,
    scopeKey: String, onGesture: (Boolean) -> Unit) {
    var channel by rememberSaveable(scopeKey) { mutableIntStateOf(0) }
    var selectedPoint by remember(scopeKey, channel) { mutableIntStateOf(-1) }
    val current by rememberUpdatedState(params)
    val change by rememberUpdatedState(onChange)
    val gesture by rememberUpdatedState(onGesture)
    val curve = params.curves.getOrElse(channel) { ToneCurve() }
    val names = listOf("RGB", "红", "绿", "蓝")
    val colors = listOf(CurvePine, Color(0xFFE56B67), Color(0xFF56A174), Color(0xFF558DDD))
    val tint = colors[channel]
    val samples = remember(curve) { curve.lut() }
    fun points() = current.curves.getOrElse(channel) { ToneCurve() }.points
    fun update(next: List<CurvePoint>) {
        val curves = List(4) { current.curves.getOrElse(it) { ToneCurve() } }.toMutableList()
        curves[channel] = ToneCurve(next)
        change(current.copy(curves = curves))
    }
    fun nearest(at: Offset, width: Float, height: Float, inset: Float, radius: Float): Int {
        return points().indices.minByOrNull { index ->
            val p = points()[index]
            (Offset(inset + p.x * width, inset + (1f - p.y) * height) - at).getDistance()
        }?.takeIf { index ->
            val p = points()[index]
            (Offset(inset + p.x * width, inset + (1f - p.y) * height) - at).getDistance() <= radius
        } ?: -1
    }
    fun selectOrAdd(at: Offset, width: Float, height: Float, inset: Float, radius: Float) {
        val found = nearest(at, width, height, inset, radius)
        if (found >= 0) { selectedPoint = found; return }
        val x = ((at.x - inset) / width).coerceIn(.008f, .992f)
        val p = points()
        if (p.size >= 16 || p.any { kotlin.math.abs(it.x - x) < .008f }) { selectedPoint = -1; return }
        val next = (p + CurvePoint(x, (1f - (at.y - inset) / height).coerceIn(0f, 1f))).sortedBy { it.x }
        selectedPoint = next.indexOfFirst { it.x == x }
        update(next)
    }
    DisposableEffect(scopeKey, channel) { onDispose { gesture(false) } }
    Column {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            names.forEachIndexed { index, name ->
                Box(Modifier.padding(end = 6.dp).clip(CircleShape)
                    .background(if (channel == index) colors[index].copy(alpha = .12f) else Color.Transparent)
                    .clickable { channel = index }.semantics { selected = channel == index }
                    .padding(horizontal = 12.dp, vertical = 7.dp), contentAlignment = Alignment.Center) {
                    Text(name, color = colors[index], fontSize = 12.sp)
                }
            }
            Spacer(Modifier.weight(1f))
            Text("复位", fontSize = 12.sp, color = CurvePine,
                modifier = Modifier.clip(CircleShape).clickable {
                    gesture(true); update(ToneCurve().points); selectedPoint = -1; gesture(false)
                }.padding(10.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(176.dp).clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFF6F8F5))
            .semantics { contentDescription = "${names[channel]}曲线，点按添加控制点，拖动调整，双击删除中间点" }
            .pointerInput(scopeKey, channel) {
                val inset = 14.dp.toPx()
                detectDragGestures(onDragStart = { at ->
                    gesture(true)
                    selectOrAdd(at, size.width - inset*2, size.height - inset*2, inset, 24.dp.toPx())
                }, onDragEnd = { gesture(false) }, onDragCancel = { gesture(false) }) { event, _ ->
                    event.consume()
                    val p = points(); val index = selectedPoint
                    if (index in p.indices) {
                        val x = when (index) {
                            0 -> 0f
                            p.lastIndex -> 1f
                            else -> ((event.position.x - inset) / (size.width - inset*2))
                                .coerceIn(p[index - 1].x + .004f, p[index + 1].x - .004f)
                        }
                        val y = (1f - (event.position.y - inset) / (size.height - inset*2)).coerceIn(0f, 1f)
                        update(p.toMutableList().also { it[index] = CurvePoint(x, y) })
                    }
                }
            }.pointerInput(scopeKey, channel) {
                val inset = 14.dp.toPx()
                detectTapGestures(onTap = { at ->
                    gesture(true)
                    selectOrAdd(at, size.width - inset*2, size.height - inset*2, inset, 24.dp.toPx())
                    gesture(false)
                }, onDoubleTap = { at ->
                    val index = nearest(at, size.width - inset*2, size.height - inset*2, inset, 24.dp.toPx())
                    if (index > 0 && index < points().lastIndex) {
                        gesture(true); update(points().filterIndexed { i, _ -> i != index })
                        selectedPoint = -1; gesture(false)
                    }
                })
            }) {
            val inset = 14.dp.toPx(); val w = size.width - inset*2; val h = size.height - inset*2
            fun position(x: Float, y: Float) = Offset(inset + x*w, inset + (1f - y)*h)
            repeat(5) { i ->
                val f = i/4f; val grid = Color(0xFFE0E6DF)
                drawLine(grid, position(f, 0f), position(f, 1f), 1.dp.toPx())
                drawLine(grid, position(0f, f), position(1f, f), 1.dp.toPx())
            }
            drawLine(Color(0xFFCBD4CA), position(0f, 0f), position(1f, 1f), 1.dp.toPx())
            val path = Path()
            samples.forEachIndexed { i, y ->
                val p = position(i/255f, y/255f)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, tint, style = Stroke(2.dp.toPx()))
            curve.points.forEachIndexed { i, p ->
                val center = position(p.x, p.y)
                if (i == selectedPoint) drawCircle(tint.copy(alpha = .14f), 11.dp.toPx(), center)
                drawCircle(Color.White, 5.dp.toPx(), center)
                drawCircle(tint, 5.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            }
        }
        val point = curve.points.getOrNull(selectedPoint)
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (point == null) "点按添加 · 拖动调整 · 双击删除" else
                "输入 ${(point.x*255).roundToInt()} · 输出 ${(point.y*255).roundToInt()}",
                fontSize = 10.sp, color = Color(0xFF859087))
            Spacer(Modifier.weight(1f))
            if (selectedPoint > 0 && selectedPoint < curve.points.lastIndex) {
                Text("删除点", color = CurvePine, fontSize = 11.sp, modifier = Modifier.clickable {
                    gesture(true); update(points().filterIndexed { i, _ -> i != selectedPoint })
                    selectedPoint = -1; gesture(false)
                }.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}
