package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.core.tone.HistogramData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

@Composable
fun HistogramOverlay(
    photoKey: String?, bitmap: Bitmap?,
    modifier: Modifier = Modifier,
) {
    val latestBitmap by rememberUpdatedState(bitmap)
    // One background calculation at a time; keep publishing while the dial is moving.
    // The 10 Hz limit prevents analysis from competing with image rendering at 60 Hz.
    val histogram by produceState<HistogramData?>(null, photoKey) {
        value = null
        snapshotFlow { latestBitmap }.conflate().collect { image ->
            value = if (image == null || image.isRecycled) null else withContext(Dispatchers.Default) {
                val coroutine = currentCoroutineContext()
                try {
                    HistogramData.sample(image.width, image.height) { y, row ->
                        coroutine.ensureActive()
                        image.getPixels(row, 0, image.width, 0, y, image.width, 1)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: IllegalArgumentException) { null }
                catch (_: IllegalStateException) { null }
            }
            delay(100)
        }
    }
    HistogramCard(histogram, modifier)
}

@Composable
private fun HistogramCard(data: HistogramData?, modifier: Modifier = Modifier) {
    val channels = remember(data) { data?.let { listOf(it.luminance, it.red, it.green, it.blue).map(HistogramData::smooth) } }
    val peak = channels?.maxOf { it.maxOrNull() ?: 0f }?.coerceAtLeast(1f) ?: 1f
    Column(modifier.width(144.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .32f))
        .padding(8.dp)
        .semantics { contentDescription = "整张照片的亮度和红绿蓝直方图，左侧为暗部，右侧为亮部" }) {
        if (data == null) {
            Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                Text("读取中…", color = StudioColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Canvas(Modifier.fillMaxWidth().height(46.dp)) {
                val baseline = size.height - 1.dp.toPx()
                for (i in 1..3) {
                    val x = size.width * i / 4f
                    drawLine(Color.White.copy(alpha = .25f), Offset(x, 0f), Offset(x, baseline), 1.dp.toPx())
                }
                fun channel(bins: FloatArray, color: Color, fill: Float) {
                    val path = Path()
                    fun y(i: Int) = baseline - sqrt(bins[i] / peak) * baseline
                    path.moveTo(0f, y(0))
                    for (i in 1..255) {
                        val x = size.width * i / 255f
                        val mid = size.width * (i - .5f) / 255f
                        path.cubicTo(mid, y(i - 1), mid, y(i), x, y(i))
                    }
                    drawPath(path, color, style = Stroke(1.dp.toPx()))
                    path.lineTo(size.width, baseline); path.lineTo(0f, baseline); path.close()
                    drawPath(path, color.copy(alpha = fill))
                }
                channels?.let {
                    channel(it[0], Color(0xFF737981), 0.1f)
                    channel(it[1], Color(0xFFC84343), 0.08f)
                    channel(it[2], Color(0xFF28834D), 0.08f)
                    channel(it[3], Color(0xFF367AC7), 0.08f)
                }
                drawLine(Color(0xFFB7BCC3).copy(alpha = .4f), Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0", color = StudioColors.Muted, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("亮度" to Color(0xFF737981), "R" to Color(0xFFC84343),
                    "G" to Color(0xFF28834D), "B" to Color(0xFF367AC7)).forEach { (label, color) ->
                    Text(label, color = color, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("255", color = StudioColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
