package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.min

private val CropPresets = listOf("自由" to 0f, "原图" to -1f, "1:1" to 1f,
    "4:3" to 4f / 3f, "3:4" to 3f / 4f, "3:2" to 3f / 2f, "2:3" to 2f / 3f,
    "16:9" to 16f / 9f, "9:16" to 9f / 16f, "4:5" to 4f / 5f, "5:4" to 5f / 4f)

@Composable
internal fun CropEditorContent(photoKey: String, bitmap: Bitmap, geometry: PhotoGeometry,
    onChange: (PhotoGeometry) -> Unit, modifier: Modifier = Modifier,
    sourceWidth: Int = bitmap.width, sourceHeight: Int = bitmap.height) {
    val draft = geometry.normalized()
    var aspect by remember(photoKey) {
        val frame = draft.frame(sourceWidth, sourceHeight)
        val ratio = frame.width * draft.crop.width / (frame.height * draft.crop.height)
        mutableFloatStateOf(if (draft.crop == CropRect()) -1f else
            CropPresets.firstOrNull { it.second > 0f && abs(it.second - ratio) < .001f }?.second ?: 0f)
    }
    val latest by rememberUpdatedState(draft)
    Column(modifier.fillMaxSize().background(StudioColors.Canvas)) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                val frame = draft.frame(sourceWidth, sourceHeight)
                val fit = min(maxWidth.value / frame.width, maxHeight.value / frame.height)
                val width = (frame.width * fit).dp
                val height = (frame.height * fit).dp
                Box(Modifier.size(width, height)) {
                    PhotoGeometryViewport(bitmap, draft.copy(crop = CropRect()), "裁切预览", Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize().semantics {
                        contentDescription = "裁切框，拖动四角或边缘调整大小，拖动框内移动位置"
                    }.pointerInput(photoKey, draft.quarterTurns, draft.straighten, aspect) {
                        var start = Offset.Zero
                        var original = CropRect()
                        var handle = -1
                        val radius = 24.dp.toPx()
                        detectDragGestures(onDragStart = { at ->
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
                                onChange(latest.copy(crop = original.dragged(handle,
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
        Column(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                StudioChoice("向左 90°", {
                    onChange(draft.rotatedBy(-1)); if (aspect > 0f) aspect = 1f / aspect
                }, symbol = StudioSymbol.RotateCcw)
                StudioChoice("向右 90°", {
                    onChange(draft.rotatedBy(1)); if (aspect > 0f) aspect = 1f / aspect
                }, symbol = StudioSymbol.RotateCw)
                StudioIconButton(StudioSymbol.RotateCcw, "复位裁切与旋转", {
                    aspect = -1f; onChange(PhotoGeometry())
                })
            }
            ParameterSlider("自由旋转", String.format(Locale.US, "%.1f°", draft.straighten),
                draft.straighten, -45f..45f, 0f, "$photoKey:straighten",
                onChange = { onChange(draft.copy(straighten = it)) }, labelWidth = 112.dp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CropPresets.forEach { (name, ratio) ->
                    CropRatioCard(name, ratio, abs(aspect - ratio) < .0001f) {
                        aspect = ratio
                        val frame = draft.frame(sourceWidth, sourceHeight)
                        onChange(draft.copy(crop = when {
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

@Composable
private fun CropRatioCard(name: String, ratio: Float, active: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val tint = if (active) StudioColors.Pine else StudioColors.Ink
    Column(Modifier.size(72.dp, 78.dp).clip(shape)
        .background(if (active) StudioColors.Tint else StudioColors.Canvas)
        .then(if (active) Modifier.border(2.dp, StudioColors.Pine, shape) else Modifier)
        .clickable(onClick = onClick).semantics {
            selected = active; contentDescription = "$name 裁切比例"
        }.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        Canvas(Modifier.size(32.dp)) {
            val r = if (ratio > 0f) ratio else 1.5f
            val w = if (r >= 1f) size.width * .8f else size.height * .8f * r
            val h = w / r
            val at = Offset((size.width - w) / 2f, (size.height - h) / 2f)
            if (ratio < 0f) drawRect(tint.copy(alpha = .5f), at + Offset(4.dp.toPx(), -4.dp.toPx()),
                Size(w, h), style = Stroke(1.5.dp.toPx()))
            drawRect(tint, at, Size(w, h), style = Stroke(1.7.dp.toPx(),
                pathEffect = if (ratio == 0f) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null))
        }
        Text(name, fontSize = 12.sp, color = tint)
    }
}
