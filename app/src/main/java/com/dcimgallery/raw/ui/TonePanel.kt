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
import androidx.compose.ui.unit.sp
import com.dcimgallery.raw.core.tone.HslAdjust
import com.dcimgallery.raw.core.tone.ColorGrade
import com.dcimgallery.raw.core.tone.ToneParams
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lr 风格调色面板（拨盘式紧凑坞）。
 * 参考系统相册编辑交互：分区标签 + 参数卡片 + 单参数拨盘 + HSL 色点，
 * 分组横向滚动，保留固定中央指针与白色底部控制区。
 */
@Composable
fun TonePanel(
    photoName: String,
    params: ToneParams,
    onParamsChange: (ToneParams) -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
    compactHeader: Boolean = false,
    onMasks: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onDraggingChange: (Boolean) -> Unit = {},
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var hslIndex by rememberSaveable { mutableIntStateOf(0) }
    var gradeIndex by rememberSaveable { mutableIntStateOf(0) }
    var activeChip by rememberSaveable { mutableStateOf("曝光") }

    val chips = rememberChips(section, hslIndex, gradeIndex, params.blackWhite)
    val active = chips.firstOrNull { it.id == activeChip } ?: chips.first()
    val accent = StudioColors.Pine
    val sections = listOf("光线", "色彩", "混色", "效果", "分级", "细节")
    val sectionIcons = listOf(StudioSymbol.Sun, StudioSymbol.Palette, StudioSymbol.CircleDot,
        StudioSymbol.Sparkles, StudioSymbol.Aperture, StudioSymbol.Focus)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        .background(Color.White).then(if (compactHeader) Modifier else Modifier.navigationBarsPadding())) {
        if (!compactHeader) StudioPanelHeader("${sections[section]} · 全局调整", onClose, onReset)
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("局部调整", Modifier.weight(1f).padding(start = 20.dp), style = MaterialTheme.typography.labelMedium)
            StudioIconButton(StudioSymbol.RotateCcw, "复位局部调色", onReset)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            sections.forEachIndexed { index, name ->
                StudioTool(sectionIcons[index], name, {
                    section = index
                    activeChip = rememberChips(index, hslIndex, gradeIndex, params.blackWhite).first().id
                }, modifier = Modifier.weight(1f), selected = section == index)
            }
        }
        if (section == 1) {
            TextButton(onClick = { onParamsChange(params.copy(blackWhite = !params.blackWhite)) },
                modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(if (params.blackWhite) "黑白：开 · 点击切回彩色" else "黑白：关 · 点击转为黑白", color = accent)
            }
        }
        if (section == 4) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("阴影", "中间调", "高光", "全局").forEachIndexed { i, name ->
                    TextButton(onClick = { gradeIndex = i; activeChip = "grade_hue" }) {
                        Text(name, color = if (gradeIndex == i) accent else StudioColors.Muted)
                    }
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
                StudioTool(parameterSymbol(chip.label), chip.label, { activeChip = chip.id },
                    selected = selected, primary = true, modifier = Modifier.width(62.dp))

            }
        }

        // 当前值
        Text(
            text = "${active.label}  ${active.format(active.get(params))}",
            fontSize = 20.sp,
            color = if (active.get(params) != active.default) accent else StudioColors.Ink,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // 拨盘
        key(active.id, hslIndex, gradeIndex) {
            DialSlider(
                value = active.get(params),
                range = active.range,
                defaultValue = active.default,
                accent = accent,
                onChange = { onParamsChange(active.set(params, it)) },
                onDraggingChange = onDraggingChange,
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
                                activeChip = rememberChips(section, index, gradeIndex, params.blackWhite).first().id
                            },
                    )
                }
            }
        }


        if (!compactHeader && onMasks != null && onExport != null) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StudioTool(StudioSymbol.Palette, "调色", {}, selected = true, primary = true)
                StudioTool(StudioSymbol.Scan, "蒙版", onMasks)
                StudioTool(StudioSymbol.ArrowUpFromLine, "导出", onExport)
            }
        }

    }
}

private fun parameterSymbol(label: String): StudioSymbol = when (label) {
    "曝光" -> StudioSymbol.Sun
    "对比度" -> StudioSymbol.Contrast
    "高光" -> StudioSymbol.SunMedium
    "阴影" -> StudioSymbol.Eclipse
    "白色", "黑色" -> StudioSymbol.Circle
    "色温" -> StudioSymbol.Thermometer
    "色调", "色相" -> StudioSymbol.Pipette
    "饱和度" -> StudioSymbol.Droplets
    "自然饱和度" -> StudioSymbol.Flower2
    "清晰度", "纹理" -> StudioSymbol.Grip
    "去除薄雾" -> StudioSymbol.SunDim
    "暗角" -> StudioSymbol.Frame
    "锐化" -> StudioSymbol.Focus
    "颗粒" -> StudioSymbol.CircleDot
    else -> StudioSymbol.Scale
}

// ---- 参数定义 ----

private data class ChipDef(
    val id: String,
    val label: String,
    val range: ClosedFloatingPointRange<Float>,
    val format: (Float) -> String,
    val get: (ToneParams) -> Float,
    val set: (ToneParams, Float) -> ToneParams,
    val default: Float = 0f,
)

private fun rememberChips(section: Int, hslIndex: Int, gradeIndex: Int, blackWhite: Boolean): List<ChipDef> {
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
            ChipDef("自然饱和度", "自然饱和度", -1f..1f, ::pct, { it.vibrance }, { p, v -> p.copy(vibrance = v) }),
        )
        3 -> listOf(
            ChipDef("纹理", "纹理", -1f..1f, ::pct, { it.texture }, { p, v -> p.copy(texture = v) }),
            ChipDef("清晰度", "清晰度", -1f..1f, ::pct, { it.clarity }, { p, v -> p.copy(clarity = v) }),
            ChipDef("去朦胧", "去朦胧", -1f..1f, ::pct, { it.dehaze }, { p, v -> p.copy(dehaze = v) }),
            ChipDef("暗角", "暗角", -1f..1f, ::pct, { it.vignette }, { p, v -> p.copy(vignette = v) }),
            ChipDef("暗角中点", "暗角·中点", 0f..1f, ::unsignedPct, { it.vignetteMidpoint }, { p, v -> p.copy(vignetteMidpoint = v) }, 0.5f),
            ChipDef("暗角羽化", "暗角·羽化", 0f..1f, ::unsignedPct, { it.vignetteFeather }, { p, v -> p.copy(vignetteFeather = v) }, 0.5f),
            ChipDef("暗角圆度", "暗角·圆度", -1f..1f, ::pct, { it.vignetteRoundness }, { p, v -> p.copy(vignetteRoundness = v) }),
            ChipDef("暗角高光", "暗角·高光保护", 0f..1f, ::unsignedPct, { it.vignetteHighlights }, { p, v -> p.copy(vignetteHighlights = v) }),
            ChipDef("颗粒", "颗粒", 0f..1f, ::unsignedPct, { it.grain }, { p, v -> p.copy(grain = v) }),
            ChipDef("颗粒大小", "颗粒·大小", 0f..1f, ::unsignedPct, { it.grainSize }, { p, v -> p.copy(grainSize = v) }, 0.25f),
            ChipDef("颗粒粗糙度", "颗粒·粗糙度", 0f..1f, ::unsignedPct, { it.grainRoughness }, { p, v -> p.copy(grainRoughness = v) }, 0.5f),
        )
        4 -> {
            fun get(p: ToneParams) = p.grading.getOrElse(gradeIndex) { ColorGrade() }
            fun update(p: ToneParams, grade: ColorGrade) = p.copy(grading = List(4) {
                if (it == gradeIndex) grade else p.grading.getOrElse(it) { ColorGrade() }
            })
            listOf(
                ChipDef("grade_hue", "色相", 0f..1f, { "${(it * 360).roundToInt()}°" }, { get(it).hue },
                    { p, v -> update(p, get(p).copy(hue = v)) }),
                ChipDef("grade_sat", "饱和度", 0f..1f, ::unsignedPct, { get(it).saturation },
                    { p, v -> update(p, get(p).copy(saturation = v)) }),
                ChipDef("grade_lum", "明度", -1f..1f, ::pct, { get(it).luminance },
                    { p, v -> update(p, get(p).copy(luminance = v)) }),
                ChipDef("grade_balance", "平衡", -1f..1f, ::pct, { it.gradingBalance }, { p, v -> p.copy(gradingBalance = v) }),
                ChipDef("grade_blending", "混合", 0f..1f, ::unsignedPct, { it.gradingBlending }, { p, v -> p.copy(gradingBlending = v) }, 0.5f),
            )
        }
        5 -> listOf(
            ChipDef("锐化", "锐化·强度", 0f..1f, ::unsignedPct, { it.sharpening }, { p, v -> p.copy(sharpening = v) }),
            ChipDef("锐化半径", "锐化·半径", 0.5f..3f, { String.format(Locale.US, "%.1f px", it) }, { it.sharpeningRadius }, { p, v -> p.copy(sharpeningRadius = v) }, 1f),
            ChipDef("锐化细节", "锐化·细节", 0f..1f, ::unsignedPct, { it.sharpeningDetail }, { p, v -> p.copy(sharpeningDetail = v) }, 0.25f),
            ChipDef("锐化蒙版", "锐化·边缘蒙版", 0f..1f, ::unsignedPct, { it.sharpeningMasking }, { p, v -> p.copy(sharpeningMasking = v) }),
        )
        else -> {
            val name = ToneParams.HSL_NAMES[hslIndex]
            if (blackWhite) listOf(ChipDef("bw_mix", "黑白明度·$name", -1f..1f, ::pct,
                { it.bwMix.getOrElse(hslIndex) { 0f } }, { p, v -> p.copy(bwMix = List(ToneParams.HSL_COUNT) {
                    if (it == hslIndex) v else p.bwMix.getOrElse(it) { 0f }
                }) })) else listOf(
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
    defaultValue: Float,
    accent: Color,
    onChange: (Float) -> Unit,
    onDraggingChange: (Boolean) -> Unit,
) {
    val currentValue by rememberUpdatedState(value)
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnDraggingChange by rememberUpdatedState(onDraggingChange)
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
                        onDragStart = { dragValue = currentValue; currentOnDraggingChange(true) },
                        onDragEnd = { currentOnDraggingChange(false) },
                        onDragCancel = { currentOnDraggingChange(false) },
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
                    detectTapGestures(onDoubleTap = { currentOnChange(defaultValue) })
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

private fun unsignedPct(v: Float): String = (v * 100).roundToInt().toString()
