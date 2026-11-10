package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import com.dcimgallery.raw.core.tone.*
import com.dcimgallery.raw.data.db.PhotoEntity

/** A single editing workspace. All tools share one draft, Cancel and Done. */
@Composable
internal fun PhotoEditorScreen(photo: PhotoEntity, decoded: Bitmap, frame: Bitmap,
    tone: ToneParams, layers: List<MaskLayer>, geometry: PhotoGeometry, busy: Boolean,
    onTone: (ToneParams) -> Unit, onMasks: (List<MaskLayer>) -> Unit,
    onGeometry: (PhotoGeometry) -> Unit, onFinish: (Boolean) -> Unit,
    onEnsureEngine: () -> Unit, onRenderFull: () -> Unit) {
    var tool by rememberSaveable(photo.uri) { mutableIntStateOf(0) }
    var curveSection by remember { mutableStateOf(false) }
    val gesture = remember { PagerGestureState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { gesture.attach(scope); onEnsureEngine() }
    LaunchedEffect(tool, geometry) { gesture.reset() }
    BackHandler { if (!busy) onFinish(false) }
    var selectedId by rememberSaveable(photo.uri) { mutableStateOf<String?>(null) }
    LaunchedEffect(layers.map { it.id }) {
        if (layers.none { it.id == selectedId }) selectedId = layers.lastOrNull()?.id
    }
    var maskTool by remember { mutableStateOf(MaskTool.BRUSH) }
    var subtract by remember { mutableStateOf(false) }
    var radius by remember { mutableFloatStateOf(.06f) }
    var feather by remember { mutableFloatStateOf(.7f) }
    var opacity by remember { mutableFloatStateOf(1f) }
    var component by remember { mutableIntStateOf(-1) }
    var adjusting by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(true) }
    val selected = layers.firstOrNull { it.id == selectedId }
    Column(Modifier.fillMaxSize().background(StudioColors.Canvas).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).background(Color.White).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            StudioIconButton(StudioSymbol.X, "撤销本次编辑并退出", { onFinish(false) }, enabled = !busy)
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text(photo.displayName, color = StudioColors.Ink, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                if (tool == 2) {
                    val (w, h) = geometry.outputSize(decoded.width, decoded.height)
                    Text("${w} × ${h}", color = StudioColors.Muted, fontSize = 11.sp)
                }
            }
            Text(if (busy) "保存中…" else "完成", color = Color.White, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(22.dp)).background(StudioColors.Pine)
                    .clickable(enabled = !busy) { onRenderFull(); onFinish(true) }
                    .padding(horizontal = 20.dp, vertical = 11.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val panelHeight = maxHeight * if (tool == 0 && curveSection) .65f else .5f
            if (tool == 2) CropEditorContent(photo.uri, frame, geometry, onGeometry,
                sourceWidth = decoded.width, sourceHeight = decoded.height)
            else Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f).padding(12.dp)
                    .onSizeChanged { gesture.viewSize = it }.clipToBounds()
                    .editorImageGestures(gesture, tool == 1)
                    .pointerInput(tool) {
                        if (tool != 1) detectTapGestures(onDoubleTap = { gesture.onDoubleTap(it) })
                    }) {
                    Box(Modifier.fillMaxSize().graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = gesture.scale; scaleY = gesture.scale
                        translationX = gesture.offset.x; translationY = gesture.offset.y
                    }) {
                        PhotoGeometryViewport(frame, geometry, photo.displayName, Modifier.fillMaxSize()) {
                            if (tool == 1) MaskOverlay(selected, component, maskTool, subtract,
                                radius, feather, opacity, overlay, decoded.width, decoded.height,
                                editable = !adjusting && !busy, onCommit = { index, part ->
                                    selected?.let { layer ->
                                        val previous = layer.components.firstOrNull()
                                        val parts = if (part.tool == MaskTool.BRUSH && index < 0 &&
                                            previous?.tool == MaskTool.BRUSH && previous.points.isNotEmpty())
                                            listOf(previous.copy(points = previous.points + part.points,
                                                strokeStarts = previous.strokeStarts + previous.points.size))
                                        else listOf(part)
                                        onMasks(layers.map { if (it.id == layer.id) it.copy(components = parts) else it })
                                        component = 0
                                    }
                                }, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().height(panelHeight)) {
                    if (tool == 0) TonePanel(photo.uri, tone, onTone, {}, {}, compactHeader = true,
                        onCurveSection = { curveSection = it })
                    else MaskPanel(layers, selectedId, { selectedId = it; component = -1 }, onMasks,
                        maskTool, { maskTool = it }, subtract, { subtract = it }, radius, { radius = it },
                        feather, { feather = it }, opacity, { opacity = it }, component, { component = it },
                        adjusting, { adjusting = it }, {}, overlay, { overlay = it }, {},
                        thumbnail = frame, embedded = true)
                }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color.White).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(StudioSymbol.Palette to "调色", StudioSymbol.Scan to "蒙版", StudioSymbol.Frame to "裁切旋转")
                .forEachIndexed { index, (icon, title) ->
                    StudioTool(icon, title, { tool = index }, selected = tool == index, enabled = !busy)
                }
        }
    }
}
