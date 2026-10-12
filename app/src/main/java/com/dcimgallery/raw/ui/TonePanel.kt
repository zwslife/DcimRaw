package com.dcimgallery.raw.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.core.tone.HslAdjust
import com.dcimgallery.raw.core.tone.ToneParams
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lr 风格调色面板（拨盘式紧凑坞）。
 * 参考系统相册编辑交互：分区标签 + 参数卡片 + 单参数拨盘 + HSL 色点，
 * 控制区只占底部约四分之一，图片始终完整可见。
 */
@Composable
fun TonePanel(
    photoName: String,
    params: ToneParams,
    onParamsChange: (ToneParams) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    compactHeader: Boolean = false,
) {
    var section by rememberSaveable { mutableIntStateOf(0) } // 0曝光 1色彩 2HSL
    var hslIndex by rememberSaveable { mutableIntStateOf(0) }
    var activeChip by rememberSaveable { mutableStateOf("曝光") }

    val chips = rememberChips(section, hslIndex)
    val active = chips.firstOrNull { it.id == activeChip } ?: chips.first()
    val accent = Color(0xFF237A46)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .navigationBarsPadding(),
    ) {
        // 头部：分区标签 + 复位/完成
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        ) {
            listOf("曝光", "色彩", "HSL").forEachIndexed { index, name ->
                TextButton(
                    onClick = {
                        section = index
                        activeChip = rememberChips(index, hslIndex).first().id
                    },
                ) {
                    Text(
                        text = name,
                        color = if (section == index) accent else Color(0xFF62656B),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (!compactHeader) Text(
                text = photoName,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF6E6E73),
                maxLines = 1,
            )
            TextButton(onClick = onReset) { Text("复位") }
            if (!compactHeader) TextButton(onClick = onClose) { Text("完成", color = accent) }
        }

        // 当前值
        Text(
            text = "${active.label}  ${active.format(active.get(params))}",
            style = MaterialTheme.typography.titleSmall,
            color = if (active.get(params) != 0f) accent else Color(0xFF202124),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // 拨盘
        key(active.id, hslIndex) {
            DialSlider(
                value = active.get(params),
                range = active.range,
                accent = accent,
                onChange = { onParamsChange(active.set(params, it)) },
            )
        }

        // HSL 色点选择器
        if (section == 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ToneParams.HSL_COLORS.forEachIndexed { index, color ->
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color(color))
                            .border(
                                width = if (index == hslIndex) 2.5.dp else 0.dp,
                                color = accent,
                                shape = CircleShape,
                            )
                            .clickable {
                                hslIndex = index
                                activeChip = rememberChips(section, index).first().id
                            },
                    )
                }
            }
        }

        // 参数卡片（横向滚动）
        val chipScrollState = key(section) { rememberScrollState() }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScrollState)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips.forEach { chip ->
                val selected = chip.id == active.id
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color(0xFFE8F3EB) else Color(0xFFF5F6F7))
                        .border(
                            width = 1.dp,
                            color = if (selected) accent else Color(0xFFD9DCE1),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { activeChip = chip.id }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Text(
                        text = chip.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) accent else Color(0xFF62656B),
                    )
                    Text(
                        text = chip.format(chip.get(params)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chip.get(params) != 0f) Color(0xFF202124) else Color(0xFF6E6E73),
                    )
                }
            }
        }
    }
}

// ---- 参数定义 ----

private data class ChipDef(
    val id: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val format: (Float) -> String,
    val get: (ToneParams) -> Float,
    val set: (ToneParams, Float) -> ToneParams,
)

private fun rememberChips(section: Int, hslIndex: Int): List<ChipDef> {
    return when (section) {
        0 -> listOf(
            ChipDef("曝光", "曝光", -2f..2f, ::signed, { it.exposure }, { p, v -> p.copy(exposure = v) }),
            ChipDef("对比度", "对比度", -1f..1f, ::pct, { it.contrast }, { p, v -> p.copy(contrast = v) }),
            ChipDef("高光", "高光", -1f..1f, ::pct, { it.highlights }, { p, v -> p.copy(highlights = v) }),
            ChipDef("阴影", "阴影", -1f..1f, ::pct, { it.shadows }, { p, v -> p.copy(shadows = v) }),
            ChipDef("白色", "白色", -1f..1f, ::pct, { it.whites }, { p, v -> p.copy(whites = v) }),
            ChipDef("黑色", "黑色", -1f..1f, ::pct, { it.blacks }, { p, v -> p.copy(blacks = v) }),
        )
        1 -> listOf(
            ChipDef("色温", "色温", -1f..1f, ::pct, { it.temperature }, { p, v -> p.copy(temperature = v) }),
            ChipDef("色调", "色调", -1f..1f, ::pct, { it.tint }, { p, v -> p.copy(tint = v) }),
            ChipDef("饱和度", "饱和度", -1f..1f, ::pct, { it.saturation }, { p, v -> p.copy(saturation = v) }),
        )
        else -> {
            val name = ToneParams.HSL_NAMES[hslIndex]
            listOf(
                ChipDef("hsl_h", "色相·$name", -1f..1f, ::pct,
                    { it.hsl.getOrElse(hslIndex) { HslAdjust() }.hue },
                    { p, v -> p.copy(hsl = p.hsl.replaced(hslIndex, p.hsl.getOrElse(hslIndex) { HslAdjust() }.copy(hue = v))) }),
                ChipDef("hsl_s", "饱和·$name", -1f..1f, ::pct,
                    { it.hsl.getOrElse(hslIndex) { HslAdjust() }.sat },
                    { p, v -> p.copy(hsl = p.hsl.replaced(hslIndex, p.hsl.getOrElse(hslIndex) { HslAdjust() }.copy(sat = v))) }),
                ChipDef("hsl_l", "明度·$name", -1f..1f, ::pct,
                    { it.hsl.getOrElse(hslIndex) { HslAdjust() }.lum },
                    { p, v -> p.copy(hsl = p.hsl.replaced(hslIndex, p.hsl.getOrElse(hslIndex) { HslAdjust() }.copy(lum = v))) }),
            )
        }
    }
}

private fun List<HslAdjust>.replaced(index: Int, value: HslAdjust): List<HslAdjust> =
    List(ToneParams.HSL_COUNT) { i -> if (i == index) value else getOrElse(i) { HslAdjust() } }

// ---- 拨盘 ----

@Composable
private fun DialSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color,
    onChange: (Float) -> Unit,
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 20.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .pointerInput(range) {
                    var dragValue = currentValue
                    detectHorizontalDragGestures(
                        onDragStart = { dragValue = currentValue },
                    ) { change, amount ->
                        change.consume()
                        // 刻度跟手移动，中央指针固定；向左拖动提高数值。
                        val span = range.endInclusive - range.start
                        dragValue = (dragValue - amount / size.width.coerceAtLeast(1) * span)
                            .coerceIn(range.start, range.endInclusive)
                        currentOnChange(dragValue)
                    }
                }
                .pointerInput(range) {
                    detectTapGestures(onDoubleTap = { currentOnChange(0f) })
                },
        ) {
            val midY = size.height * 0.55f
            val steps = 40
            val span = range.endInclusive - range.start
            val centerX = size.width / 2f
            for (i in 0..steps) {
                val tickValue = range.start + span * i / steps
                val x = centerX + (tickValue - value) / span * size.width
                val major = i % 5 == 0
                drawLine(
                    color = Color(0xFF92979F),
                    start = Offset(x, midY - (if (major) 18f else 9f)),
                    end = Offset(x, midY + (if (major) 18f else 9f)),
                    strokeWidth = if (major) 2f else 1f,
                )
            }
            // 零点参考线
            if (range.start < 0f && range.endInclusive > 0f) {
                val zx = centerX - value / span * size.width
                drawLine(Color(0xFFD9DCE1), Offset(zx, midY - 26f), Offset(zx, midY + 26f), 3f)
            }
            // 固定在中央的当前值指针；零刻度随拨盘移动。
            drawLine(accent, Offset(centerX, 4f), Offset(centerX, size.height), 4.5f)
        }
    }
}

private fun signed(v: Float): String =
    String.format(Locale.US, if (v >= 0) "+%.2f" else "%.2f", v)

private fun pct(v: Float): String =
    (v * 100).roundToInt().let { if (it > 0) "+$it" else "$it" }
