package com.dcimgallery.raw.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.core.tone.ColorGrade
import com.dcimgallery.raw.core.tone.ToneParams

@Composable
internal fun TonePanel(photoName: String, section: Int, params: ToneParams,
    onParamsChange: (ToneParams) -> Unit, onGesture: (Boolean) -> Unit,
    onPick: (EditTool) -> Unit) {
    var channel by rememberSaveable(photoName) { mutableIntStateOf(0) }
    var grade by rememberSaveable(photoName) { mutableIntStateOf(1) }
    val scroll = rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }
    PorcelainPanel {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            EditorToolPicker(ToneTools[section], onPick)
            Spacer(Modifier.weight(1f))
            when (section) {
                6 -> EditorColorTabs(channel, { channel = it }, Modifier.widthIn(max = 176.dp)
                    .fillMaxWidth(.7f), curves = true)
                4 -> {
                    GradeTabs(grade, { grade = it }, Modifier.weight(4f, fill = false))
                    EditorAction(StudioSymbol.RotateCcw, "复位当前分级", {
                        val grading = List(4) { index ->
                            if (index == grade) ColorGrade()
                            else params.grading.getOrElse(index) { ColorGrade() }
                        }
                        onParamsChange(params.copy(grading = grading))
                    })
                }
            }
        }
        Column(Modifier.weight(1f).then(if (section == 6) Modifier else Modifier.verticalScroll(scroll))
            .padding(horizontal = 16.dp, vertical = 4.dp)) {
            ToneRows(section, params, onParamsChange, photoName, onGesture, channel, grade)
        }
    }
}

@Composable
internal fun ToneRows(section: Int, params: ToneParams, onChange: (ToneParams) -> Unit,
    scopeKey: String, onGesture: (Boolean) -> Unit, channel: Int = 0, grade: Int = 1) {
    var color by rememberSaveable(scopeKey) { mutableIntStateOf(0) }
    if (section == 6) {
        CurveEditor(params, onChange, scopeKey, channel, onGesture)
        return
    }
    if (section == 2) EditorColorTabs(color, { color = it }, Modifier.fillMaxWidth())
    if (section == 4) {
        toneControls(4, color, grade, params.blackWhite).forEach { control ->
            ToneControlRow(control, params, onChange, "$scopeKey:4:$grade:" + control.id, onGesture)
        }
    } else {
        val controls = toneControls(section, color, 0, params.blackWhite)
        val ordered = if (section == 1) controls.sortedBy { if (it.id == "饱和度") 1 else 0 } else controls
        ordered.forEach { control ->
            val label = when {
                section == 2 -> if (control.id == "hsl_s") "饱和度" else control.label.substringBefore('·')
                control.id == "锐化蒙版" -> "边缘蒙版"
                control.id == "暗角高光" -> "高光保护"
                else -> control.label.replace("·", "")
            }
            ToneControlRow(control.copy(label = label), params, onChange,
                "$scopeKey:$section:$color:${control.id}", onGesture)
        }
    }
}

@Composable
private fun ToneControlRow(control: ChipDef, params: ToneParams, onChange: (ToneParams) -> Unit,
    key: String, onGesture: (Boolean) -> Unit) {
    val current by rememberUpdatedState(params)
    ParameterSlider(control.label, control.format(control.get(params)),
        control.get(params), control.range, control.default, key,
        onChange = { onChange(control.set(current, it)) }, onGesture = onGesture)
}
