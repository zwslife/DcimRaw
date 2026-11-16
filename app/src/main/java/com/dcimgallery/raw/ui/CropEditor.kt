package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcimgallery.raw.core.geometry.CropRect
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val CropPresets = listOf("自由" to 0f, "原图" to -1f, "1:1" to 1f,
    "4:3" to 4f / 3f, "3:4" to 3f / 4f, "3:2" to 3f / 2f, "2:3" to 2f / 3f,
    "16:9" to 16f / 9f, "9:16" to 9f / 16f, "4:5" to 4f / 5f, "5:4" to 5f / 4f)

@Composable
internal fun CropEditorContent(photoKey: String, bitmap: Bitmap, geometry: PhotoGeometry,
    onChange: (PhotoGeometry) -> Unit, modifier: Modifier = Modifier,
    sourceWidth: Int = bitmap.width, sourceHeight: Int = bitmap.height,
    onPick: (EditTool) -> Unit, onGesture: (Boolean) -> Unit) {
    val draft = geometry.normalized()
    val targetAngle = draft.quarterTurns * 90f
    val displayedAngle = remember(photoKey) { Animatable(targetAngle) }
    LaunchedEffect(targetAngle) {
        val angle = displayedAngle.value
        val remaining = nearestAngle(angle - targetAngle)
        if (remaining != 0f) {
            displayedAngle.animateTo(angle - remaining,
                tween(260, easing = CubicBezierEasing(.22f, .8f, .28f, 1f)))
        }
        displayedAngle.snapTo(targetAngle)
    }
    val rotationSettled by remember(displayedAngle, targetAngle) {
        derivedStateOf { abs(nearestAngle(displayedAngle.value - targetAngle)) < .01f }
    }
    fun inferredAspect(value: PhotoGeometry): Float {
        if (value.crop == CropRect()) return -1f
        val frame = value.frame(sourceWidth, sourceHeight)
        val ratio = frame.width * value.crop.width / (frame.height * value.crop.height)
        return CropPresets.firstOrNull { it.second > 0f && abs(it.second - ratio) < .001f }?.second ?: 0f
    }
    var aspect by remember(photoKey) { mutableFloatStateOf(inferredAspect(draft)) }
    var lastRequestedGeometry by remember(photoKey) { mutableStateOf(draft) }
    LaunchedEffect(draft) {
        if (draft != lastRequestedGeometry) aspect = inferredAspect(draft)
    }
    val latest by rememberUpdatedState(draft)
    val trackGesture by rememberUpdatedState(onGesture)
    val change by rememberUpdatedState<(PhotoGeometry) -> Unit> { next ->
        lastRequestedGeometry = next.normalized()
        onChange(next)
    }
    BoxWithConstraints(modifier.fillMaxSize().background(StudioColors.Canvas)) {
        val panelLimit = maxHeight * .65f
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(16.dp).clipToBounds(),
                contentAlignment = Alignment.Center) {
                val frame = draft.frame(sourceWidth, sourceHeight)
                val fit = min(maxWidth.value / frame.width, maxHeight.value / frame.height)
                val width = (frame.width * fit).dp
                val height = (frame.height * fit).dp
                val availableWidth = maxWidth.value
                val availableHeight = maxHeight.value
                Box(Modifier.size(width, height).graphicsLayer {
                    val offset = nearestAngle(displayedAngle.value - targetAngle)
                    val radians = Math.toRadians(offset.toDouble())
                    val cosine = abs(cos(radians)).toFloat()
                    val sine = abs(sin(radians)).toFloat()
                    val rotatedWidth = width.value * cosine + height.value * sine
                    val rotatedHeight = width.value * sine + height.value * cosine
                    val motionScale = min(availableWidth / rotatedWidth.coerceAtLeast(1f),
                        availableHeight / rotatedHeight.coerceAtLeast(1f))
                    rotationZ = offset
                    scaleX = motionScale
                    scaleY = motionScale
                }) {
                    PhotoGeometryViewport(bitmap, draft.copy(crop = CropRect()), "裁切预览", Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize().semantics {
                        contentDescription = "裁切框，拖动四角或边缘调整大小，拖动框内移动位置"
                    }.pointerInput(photoKey, draft.quarterTurns, draft.straighten, aspect, rotationSettled) {
                        if (!rotationSettled) return@pointerInput
                        var start = Offset.Zero
                        var original = CropRect()
                        var handle = -1
                        val radius = 24.dp.toPx()
                        detectDragGestures(onDragEnd = { trackGesture(false) },
                            onDragCancel = { trackGesture(false) }, onDragStart = { at ->
                            trackGesture(true)
                            start = at; original = latest.crop
                            val l = original.left * size.width; val r = original.right * size.width
                            val t = original.top * size.height; val b = original.bottom * size.height
                            val corners = listOf(Offset(l, t), Offset(r, t), Offset(r, b), Offset(l, b))
                            handle = corners.indices.minByOrNull { (at - corners[it]).getDistance() }
                                ?.takeIf { (at - corners[it]).getDistance() <= radius } ?: when {
                                at.y in t..b && abs(at.x - l) <= radius -> 4
                                at.x in l..r && abs(at.y - t) <= radius -> 5
                                at.y in t..b && abs(at.x - r) <= radius -> 6
                                at.x in l..r && abs(at.y - b) <= radius -> 7
                                at.x in l..r && at.y in t..b -> 8
                                else -> -1
                            }
                        }) { change, _ ->
                            if (handle >= 0) {
                                change.consume()
                                val ratio = when {
                                    aspect == 0f -> 0f
                                    aspect < 0f -> 1f
                                    else -> aspect / (frame.width / frame.height)
                                }
                                change(latest.copy(crop = original.dragged(handle,
                                    (change.position.x - start.x) / size.width,
                                    (change.position.y - start.y) / size.height, ratio)))
                            }
                        }
                    }) {
                        val crop = draft.crop
                        val l = crop.left * size.width; val r = crop.right * size.width
                        val t = crop.top * size.height; val b = crop.bottom * size.height
                        val shade = Color.Black.copy(alpha = .5f)
                        drawRect(shade, Offset.Zero, Size(size.width, t))
                        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                        drawRect(shade, Offset(0f, t), Size(l, b - t))
                        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))
                        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(1.dp.toPx()))
                        for (i in 1..2) {
                            val x = l + (r - l) * i / 3f; val y = t + (b - t) * i / 3f
                            drawLine(Color.White.copy(alpha = .55f), Offset(x, t), Offset(x, b), 1.dp.toPx())
                            drawLine(Color.White.copy(alpha = .55f), Offset(l, y), Offset(r, y), 1.dp.toPx())
                        }
                        val length = min(18.dp.toPx(), min(r - l, b - t) / 3f)
                        for ((x, sx) in listOf(l to 1f, r to -1f)) for ((y, sy) in listOf(t to 1f, b to -1f)) {
                            drawLine(Color.White, Offset(x, y), Offset(x + sx * length, y), 3.dp.toPx())
                            drawLine(Color.White, Offset(x, y), Offset(x, y + sy * length), 3.dp.toPx())
                        }
                    }
                }
            }
            Column(Modifier.fillMaxWidth().heightIn(max = panelLimit)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    EditorToolPicker(CropToolEntry, onPick)
                    Spacer(Modifier.weight(1f))
                    EditorAction(StudioSymbol.RotateCcw, "左转 90°", {
                        change(draft.rotatedBy(-1)); if (aspect > 0f) aspect = 1f / aspect
                    })
                    EditorAction(StudioSymbol.RotateCw, "右转 90°", {
                        change(draft.rotatedBy(1)); if (aspect > 0f) aspect = 1f / aspect
                    })
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ParameterSlider("自由旋转", String.format(Locale.US, "%.1f°", draft.straighten),
                        draft.straighten, -45f..45f, 0f, "$photoKey:straighten",
                        onChange = { change(latest.copy(straighten = it)) }, onGesture = onGesture, ruler = true)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CropPresets.forEach { (name, ratio) ->
                            CropRatioCard(name, ratio, abs(aspect - ratio) < .0001f) {
                                aspect = ratio
                                val frame = draft.frame(sourceWidth, sourceHeight)
                                change(draft.copy(crop = when {
                                    ratio == 0f -> draft.crop
                                    ratio < 0f -> CropRect()
                                    else -> CropRect().withAspect(ratio / (frame.width / frame.height))
                                }))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun nearestAngle(angle: Float): Float = ((angle + 180f) % 360f + 360f) % 360f - 180f

@Composable
private fun CropRatioCard(name: String, ratio: Float, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val tint = if (active) StudioColors.Accent else StudioColors.Ink
    Column(Modifier.size(52.dp).studioPressable(selected = active, onClick = onClick).clip(shape)
        .background(StudioColors.Tint)
        .semantics {
            selected = active; contentDescription = "$name 裁切比例"
        }.padding(5.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val r = if (ratio > 0f) ratio else 1.5f
                val w = if (r >= 1f) size.width * .8f else size.height * .8f * r
                val h = w / r
                val at = Offset((size.width - w) / 2f, (size.height - h) / 2f)
                if (ratio < 0f) drawRect(tint.copy(alpha = .5f), at + Offset(4.dp.toPx(), -4.dp.toPx()),
                    Size(w, h), style = Stroke(1.5.dp.toPx()))
                drawRect(tint, at, Size(w, h), style = Stroke(1.7.dp.toPx(),
                    pathEffect = if (ratio == 0f) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null))
            }
            if (active) StudioIcon(StudioSymbol.Check, Modifier.size(10.dp), tint = tint)
        }
        Text(name, fontSize = 11.sp, color = tint)
    }
}
