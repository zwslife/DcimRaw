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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
    onDraggingChange: (Boolean) -> Unit = {},
) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var hslIndex by rememberSaveable { mutableIntStateOf(0) }
    var gradeIndex by rememberSaveable { mutableIntStateOf(0) }
    var showSections by remember { mutableStateOf(false) }
    var parameterMenu by remember { mutableStateOf<String?>(null) }
    var showGrade by remember { mutableStateOf(false) }
    var activeChip by rememberSaveable { mutableStateOf("曝光") }

    val safeSection = section.coerceIn(0, 5)
    val safeHslIndex = hslIndex.coerceIn(0, ToneParams.HSL_COUNT - 1)
    val safeGradeIndex = gradeIndex.coerceIn(0, 3)
    val chips = toneControls(safeSection, safeHslIndex, safeGradeIndex, params.blackWhite)
    val active = chips.firstOrNull { it.id == activeChip } ?: chips.first()
    val accent = StudioColors.Pine
    val sections = listOf("光线", "色彩", "混色", "效果", "分级", "细节")
    val sectionIcons = listOf(StudioSymbol.Sun, StudioSymbol.Palette, StudioSymbol.CircleDot,
        StudioSymbol.Sparkles, StudioSymbol.Aperture, StudioSymbol.Focus)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
        .background(Color.White).then(if (compactHeader) Modifier else Modifier.navigationBarsPadding())) {
        // Scope first, category in a menu, then parameters. Only one editing level is expanded.
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                StudioChoice(if (compactHeader) sections[safeSection] else "全局 · ${sections[safeSection]}",
                    { showSections = true }, symbol = sectionIcons[safeSection], selected = true, expandable = true)
                DropdownMenu(showSections, { showSections = false }) {
                    sections.forEachIndexed { index, name ->
                        DropdownMenuItem(text = { Text(name, color = if (index == safeSection) accent else StudioColors.Ink) },
                            leadingIcon = { StudioIcon(sectionIcons[index]) }, onClick = {
                                section = index
                                activeChip = toneControls(index, safeHslIndex, safeGradeIndex, params.blackWhite).first().id
                                showSections = false
                            })
                    }
                }
            }
            StudioIconButton(StudioSymbol.RotateCcw, if (compactHeader) "复位局部调色" else "复位全局调色", onReset)
            if (!compactHeader) StudioIconButton(StudioSymbol.Check, "完成调色", onClose, filled = true)
        }
        if (safeSection == 2) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                ToneParams.HSL_COLORS.forEachIndexed { index, color ->
                    Box(Modifier.weight(1f).height(40.dp).clip(CircleShape).clickable {
                        hslIndex = index
                        activeChip = toneControls(safeSection, index, safeGradeIndex, params.blackWhite).first().id
                    }, contentAlignment = Alignment.Center) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(Color(color))
                            .border(if (index == safeHslIndex) 2.dp else 0.dp, accent, CircleShape))
                    }
                }
            }
        }

        // 参数卡片（横向滚动）
        val chipScrollState = rememberScrollState()
        LaunchedEffect(safeSection) { chipScrollState.scrollTo(0) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(chipScrollState)
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (safeSection == 1) StudioChoice(if (params.blackWhite) "黑白" else "彩色",
                { onParamsChange(params.copy(blackWhite = !params.blackWhite)) }, symbol = StudioSymbol.CircleOff,
                selected = params.blackWhite)
            if (safeSection == 4) Box {
                StudioChoice(listOf("阴影", "中间调", "高光", "全局")[safeGradeIndex],
                    { showGrade = true }, symbol = StudioSymbol.Aperture, selected = true, expandable = true)
                DropdownMenu(showGrade, { showGrade = false }) {
                    listOf("阴影", "中间调", "高光", "全局").forEachIndexed { i, name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            gradeIndex = i; activeChip = "grade_hue"; showGrade = false
                        })
                    }
                }
            }
            val advancedGroups = if (safeSection == 3) setOf("暗角", "颗粒") else emptySet()
            val visibleChips = chips.filter { chip -> advancedGroups.none { chip.id.startsWith(it) && chip.id != it } }
            visibleChips.forEach { chip ->
                if (chip.id in advancedGroups) Box {
                    val groupActive = active.id.startsWith(chip.id)
                    StudioChoice(if (groupActive) active.label else chip.label, { parameterMenu = chip.id },
                        symbol = parameterSymbol(chip.label), selected = groupActive, expandable = true)
                    DropdownMenu(parameterMenu == chip.id, { parameterMenu = null }) {
                        chips.filter { it.id.startsWith(chip.id) }.forEach { child ->
                            DropdownMenuItem(text = { Text(if (child.id == chip.id) "强度" else child.label.substringAfter('·')) }, onClick = {
                                activeChip = child.id; parameterMenu = null
                            })
                        }
                    }
                } else {
                    val label = when (safeSection) { 2 -> chip.label.substringBefore('·'); 5 -> chip.label.substringAfter('·'); else -> chip.label }
                    StudioChoice(label, { activeChip = chip.id }, symbol = parameterSymbol(chip.label), selected = chip.id == active.id)
                }
            }
        }

        // 当前值
        Text(
            text = active.format(active.get(params)) + if (active.id == "曝光") " EV" else "",
            fontSize = 20.sp,
            color = if (active.get(params) != active.default) accent else StudioColors.Ink,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        // 拨盘
        DialSlider(
            controlKey = "$safeSection:${active.id}:$safeHslIndex:$safeGradeIndex",
            value = active.get(params), range = active.range, defaultValue = active.default,
            accent = accent, onChange = { onParamsChange(active.set(params, it)) },
            onDraggingChange = onDraggingChange,
        )


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

// ---- 拨盘 ----

@Composable
private fun DialSlider(
    controlKey: String,
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
    DisposableEffect(controlKey) {
        onDispose { currentOnDraggingChange(false) }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 20.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .pointerInput(controlKey, range) {
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
                .pointerInput(controlKey, range) {
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
