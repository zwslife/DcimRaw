package com.dcimgallery.raw.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.core.tone.ToneParams

/** Porcelain controls: categories above a vertically scrolling parameter list. */
@Composable
fun TonePanel(photoName: String, params: ToneParams, onParamsChange: (ToneParams) -> Unit,
    onReset: () -> Unit, onClose: () -> Unit, compactHeader: Boolean = false,
    onDraggingChange: (Boolean) -> Unit = {}) {
    var section by rememberSaveable(photoName) { mutableIntStateOf(0) }
    val history = remember(photoName) { mutableStateListOf<ToneParams>() }
    var before by remember(photoName) { mutableStateOf<ToneParams?>(null) }
    val current by rememberUpdatedState(params)
    val scroll = rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }
    PorcelainPanel {
        PorcelainHeader(onUndo = {
            if (history.isNotEmpty()) onParamsChange(history.removeAt(history.lastIndex))
        }, onDone = onClose, canUndo = history.isNotEmpty())
        EditorTabs(section, { section = it })
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 22.dp, vertical = 12.dp)) {
            ToneRows(section, params, { next ->
                before?.takeIf { it != next }?.let { history.add(it); before = null }
                onParamsChange(next)
            }, photoName, onGesture = { active ->
                if (active) before = current else before = null
                onDraggingChange(active)
            })
        }
    }
}

@Composable
internal fun ToneRows(section: Int, params: ToneParams, onChange: (ToneParams) -> Unit,
    scopeKey: String, onGesture: (Boolean) -> Unit) {
    var color by rememberSaveable(scopeKey) { mutableIntStateOf(0) }
    if (section == 2) EditorColorTabs(color, { color = it })
    if (section == 4) {
        listOf("阴影", "中间调", "高光", "全局").forEachIndexed { grade, name ->
            toneControls(4, color, grade, params.blackWhite).take(3).forEach { control ->
                ToneControlRow(control.copy(label = name + control.label), params, onChange,
                    "$scopeKey:4:$grade:${control.id}", onGesture)
            }
        }
        toneControls(4, color, 0, params.blackWhite).drop(3).forEach { control ->
            ToneControlRow(control.copy(label = "分级" + control.label), params, onChange,
                "$scopeKey:4:${control.id}", onGesture)
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
    ParameterSlider(control.label, control.format(control.get(params)) + if (control.id == "曝光") " EV" else "",
        control.get(params), control.range, control.default, key,
        onChange = { onChange(control.set(current, it)) }, onGesture = onGesture)
}
