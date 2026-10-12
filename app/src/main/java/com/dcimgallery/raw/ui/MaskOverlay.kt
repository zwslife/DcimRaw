package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.core.tone.MaskComponent
import com.dcimgallery.raw.core.tone.MaskLayer
import com.dcimgallery.raw.core.tone.MaskPoint
import com.dcimgallery.raw.core.tone.MaskRasterizer
import com.dcimgallery.raw.core.tone.MaskTool
import com.dcimgallery.raw.core.tone.MaskViewport
import com.dcimgallery.raw.core.tone.rotateMaskPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt

/** 编辑坐标与 ContentScale.Fit 对齐；黑边不能落笔，绘制时接管单指，避免误翻页。 */
@Composable
fun MaskOverlay(
    layer: MaskLayer?, componentIndex: Int, tool: MaskTool, subtract: Boolean,
    radius: Float, feather: Float, opacity: Float, showOverlay: Boolean,
    imageWidth: Int, imageHeight: Int, onCommit: (Int, MaskComponent) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
) {
    val latestLayer by rememberUpdatedState(layer)
    val commit by rememberUpdatedState(onCommit)
    var draft by remember(layer?.id) { mutableStateOf<MaskComponent?>(null) }
    val overlay by produceState<Bitmap?>(null, layer?.id, layer?.components, layer?.inverted, imageWidth, imageHeight) {
        value = withContext(Dispatchers.Default) {
            layer?.let {
                val scale = (512f / maxOf(imageWidth, imageHeight)).coerceAtMost(1f)
                val w = (imageWidth * scale).roundToInt().coerceAtLeast(1)
                val h = (imageHeight * scale).roundToInt().coerceAtLeast(1)
                val alpha = MaskRasterizer.rasterize(it, w, h)
                val pixels = IntArray(alpha.size) { i ->
                    (((alpha[i].toInt() and 255) * 0.42f).toInt() shl 24) or 0x00FF3B45
                }
                Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            }
        }
    }
    Canvas(modifier.pointerInput(layer?.id, componentIndex, tool, subtract, radius, feather, opacity, imageWidth, imageHeight, editable) {
        if (!editable) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val viewport = MaskViewport.fit(size.width.toFloat(), size.height.toFloat(), imageWidth, imageHeight)
            if (latestLayer == null) return@awaitEachGesture
            val first = viewport.normalize(down.position.x, down.position.y)
            val existing = latestLayer?.components?.getOrNull(componentIndex)?.takeIf { it.tool == tool && tool != MaskTool.BRUSH }
            fun screen(p: MaskPoint) = Offset(viewport.left + p.x * viewport.width, viewport.top + p.y * viewport.height)
            val handleRadius = 28.dp.toPx()
            val hit = if (existing != null && existing.points.size >= 2) {
                val center = existing.points.first()
                val a = screen(center)
                val b = screen(if (tool == MaskTool.RADIAL) rotateMaskPoint(existing.points.last(), center, existing.rotation, viewport.width, viewport.height) else existing.points.last())
                val knob = screen(rotateMaskPoint(MaskPoint(center.x, center.y - abs(existing.points.last().y - center.y) - 24.dp.toPx() / viewport.height), center, existing.rotation, viewport.width, viewport.height))
                when {
                    tool == MaskTool.RADIAL && (down.position - knob).getDistance() < handleRadius -> 3
                    (down.position - a).getDistance() < handleRadius -> 0
                    (down.position - b).getDistance() < handleRadius -> 1
                    (down.position - (a + b) / 2f).getDistance() < handleRadius -> 2
                    else -> -1
                }
            } else -1
            if (hit < 0 && !viewport.contains(down.position.x, down.position.y)) return@awaitEachGesture
            down.consume()
            val target = if (hit >= 0) componentIndex else -1
            val base = if (hit >= 0) existing!! else MaskComponent(tool, listOf(first), subtract, radius, feather, opacity)
            draft = base
            var cancelled = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } > 1) {
                        cancelled = true
                        draft = null
                        event.changes.forEach { it.consume() }
                        continue
                    }
                    if (cancelled) {
                        event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) break
                        continue
                    }
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val point = viewport.normalize(change.position.x, change.position.y)
                    change.consume()
                    draft = when {
                        hit == 3 -> {
                            val center = screen(base.points.first())
                            val start = down.position - center; val end = change.position - center
                            val delta = Math.toDegrees((atan2(end.y, end.x) - atan2(start.y, start.x)).toDouble()).toFloat()
                            base.copy(rotation = ((base.rotation + delta + 540f) % 360f) - 180f)
                        }
                        hit == 0 && tool == MaskTool.RADIAL -> {
                            val dx = point.x - first.x; val dy = point.y - first.y
                            base.copy(points = base.points.map { MaskPoint(it.x + dx, it.y + dy) })
                        }
                        hit == 0 -> base.copy(points = listOf(point, base.points.last()))
                        hit == 1 -> base.copy(points = listOf(base.points.first(),
                            if (tool == MaskTool.RADIAL) rotateMaskPoint(point, base.points.first(), -base.rotation, viewport.width, viewport.height) else point))
                        hit == 2 -> {
                            val dx = point.x - first.x; val dy = point.y - first.y
                            base.copy(points = base.points.map { MaskPoint(it.x + dx, it.y + dy) })
                        }
                        tool == MaskTool.BRUSH -> {
                            val previous = draft!!.points.last()
                            if ((screen(point) - screen(previous)).getDistance() >= 2f) {
                                draft!!.copy(points = draft!!.points + point)
                            } else draft
                        }
                        else -> base.copy(points = listOf(first, point))
                    }
                    if (!change.pressed) break
                }
                val part = draft
                if (!cancelled && part != null && (part.tool == MaskTool.BRUSH || part.points.size >= 2)) commit(target, part)
            } finally {
                draft = null
            }
        }
    }) {
        val v = MaskViewport.fit(size.width, size.height, imageWidth, imageHeight)
        fun screen(p: MaskPoint) = Offset(v.left + p.x * v.width, v.top + p.y * v.height)
        clipRect(v.left, v.top, v.left + v.width, v.top + v.height) {
            if (showOverlay) overlay?.let {
                drawImage(it.asImageBitmap(), dstOffset = IntOffset(v.left.roundToInt(), v.top.roundToInt()),
                    dstSize = IntSize(v.width.roundToInt().coerceAtLeast(1), v.height.roundToInt().coerceAtLeast(1)))
            }
            draft?.let { part ->
                val color = if (part.subtract) Color.White.copy(alpha = 0.5f) else Color(0x88FF3B45)
                if (part.tool == MaskTool.BRUSH) {
                    val r = part.radius * min(v.width, v.height)
                    val path = Path().apply {
                        val first = screen(part.points.first()); moveTo(first.x, first.y)
                        part.points.drop(1).forEach { val p = screen(it); lineTo(p.x, p.y) }
                    }
                    if (part.points.size == 1) drawCircle(color, r, screen(part.points.first()))
                    else drawPath(path, color, style = Stroke(r * 2, cap = StrokeCap.Round))
                    drawCircle(Color.White, r, screen(part.points.last()), style = Stroke(1.dp.toPx()))
                } else if (part.points.size >= 2 && showOverlay) {
                    val a = screen(part.points.first()); val b = screen(part.points.last())
                    if (part.tool == MaskTool.LINEAR && (a - b).getDistance() > 1f) {
                        drawRect(Brush.linearGradient(listOf(color, Color.Transparent), a, b),
                            topLeft = Offset(v.left, v.top), size = Size(v.width, v.height))
                    } else if (part.tool == MaskTool.RADIAL) {
                        val rx = abs(b.x - a.x).coerceAtLeast(1f); val ry = abs(b.y - a.y).coerceAtLeast(1f)
                        rotate(part.rotation, a) { drawOval(color, Offset(a.x - rx, a.y - ry), Size(rx * 2, ry * 2)) }
                    }
                }
            }
        }
        // 控制点可以位于照片边缘之外，仍可在留白区域拖动。
        val part = draft ?: layer?.components?.getOrNull(componentIndex)
        if (editable && part != null && part.tool != MaskTool.BRUSH && part.points.size >= 2) {
            val center = part.points.first()
            val a = screen(center); val rawB = screen(part.points.last())
            val b = screen(if (part.tool == MaskTool.RADIAL) rotateMaskPoint(part.points.last(), center, part.rotation, v.width, v.height) else part.points.last())
            if (part.tool == MaskTool.RADIAL) {
                val rx = abs(rawB.x - a.x); val ry = abs(rawB.y - a.y)
                rotate(part.rotation, a) {
                    drawOval(Color.White, Offset(a.x - rx, a.y - ry), Size(rx * 2, ry * 2), style = Stroke(1.dp.toPx()))
                    val edge = Offset(a.x, a.y - ry)
                    val knob = edge - Offset(0f, 24.dp.toPx())
                    drawLine(Color.White, edge, knob, 2.dp.toPx())
                    drawCircle(Color.White, 10.dp.toPx(), knob)
                    drawCircle(Color(0xFF237A46), 8.dp.toPx(), knob)
                    drawArc(Color.White, 25f, 280f, false, knob - Offset(4.dp.toPx(), 4.dp.toPx()), Size(8.dp.toPx(), 8.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                }
            }
            drawLine(Color.White, a, b, 1.dp.toPx())
            listOf(a, b, (a + b) / 2f).forEach { p ->
                drawCircle(Color.Black.copy(alpha = 0.6f), 7.dp.toPx(), p)
                drawCircle(Color.White, 5.dp.toPx(), p, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
