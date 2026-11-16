package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
    onEnsureEngine: () -> Unit, onRenderFull: () -> Unit,
    canUndo: Boolean, canRedo: Boolean, onHistory: (Boolean) -> Unit,
    onGesture: (Boolean) -> Unit, canPaste: Boolean, onCopy: () -> Unit,
    onPaste: () -> Unit, onReset: () -> Unit) {
    var tool by rememberSaveable(photo.uri) { mutableIntStateOf(0) }
    var section by rememberSaveable(photo.uri) { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var comparing by remember(photo.uri, tool) { mutableStateOf(false) }
    fun pick(entry: EditTool) {
        when (entry) {
            MaskToolEntry -> tool = 1
            CropToolEntry -> tool = 2
            AdaptiveLookToolEntry -> tool = 3
            PortraitToolEntry -> tool = 4
            else -> {
                section = ToneTools.indexOf(entry)
                tool = 0
            }
        }
    }
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
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                if (tool == 2) {
                    val (w, h) = geometry.outputSize(decoded.width, decoded.height)
                    Text("${w} × ${h}", color = StudioColors.Muted, fontSize = 12.sp, maxLines = 1)
                }
            }
            StudioIconButton(StudioSymbol.Undo2, "撤回上一步", { onHistory(false) }, enabled = canUndo && !busy)
            StudioIconButton(StudioSymbol.Redo2, "重做上一步", { onHistory(true) }, enabled = canRedo && !busy)
            Box {
                StudioIconButton(StudioSymbol.Ellipsis, "编辑操作", { more = true }, enabled = !busy)
                DropdownMenu(more, { more = false }, containerColor = Color.White, shape = RoundedCornerShape(18.dp)) {
                    StudioChoice("复制调色参数", { more = false; onCopy() }, symbol = StudioSymbol.Copy)
                    if (canPaste) StudioChoice("粘贴调色参数", { more = false; onPaste() }, symbol = StudioSymbol.Paste)
                    StudioChoice("复位本次全部调整", { more = false; onReset() }, symbol = StudioSymbol.RotateCcw)
                }
            }
            StudioIconButton(StudioSymbol.Check, "完成", { onRenderFull(); onFinish(true) }, filled = true, enabled = !busy)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val curve = tool == 0 && section == 6
            val ratio = when {
                curve -> .56f
                tool == 1 -> .46f
                else -> .40f
            }
            val panelHeight = maxOf(maxHeight * ratio, minOf(152.dp, maxHeight * .58f))
            if (tool == 2) CropEditorContent(photo.uri, frame, geometry, onGeometry,
                sourceWidth = decoded.width, sourceHeight = decoded.height, onPick = ::pick, onGesture = onGesture)
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
                        PhotoGeometryViewport(if (comparing) decoded else frame, geometry, photo.displayName, Modifier.fillMaxSize()) {
                            if (tool == 1 && !comparing) MaskOverlay(selected, component, maskTool, false,
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
                    CompareButton(photo.uri, comparing, { comparing = it },
                        Modifier.align(Alignment.BottomEnd))
                }
                Box(Modifier.fillMaxWidth().height(panelHeight)) {
                    when (tool) {
                        3 -> AdaptiveLookPanel(decoded, geometry, tone, busy, onTone, ::pick)
                        4 -> PortraitLightPanel(frame, geometry, layers, busy, onApply = {
                            selectedId = it.last().id
                            component = -1
                            onMasks(it)
                        }, onPick = ::pick)
                        1 -> MaskPanel(layers, selectedId, { selectedId = it; component = -1 }, onMasks,
                            maskTool, { maskTool = it }, radius, { radius = it },
                            feather, { feather = it }, opacity, { opacity = it }, { component = it },
                            { adjusting = it }, onGesture, { overlay = it },
                            thumbnail = frame, onPick = ::pick)
                        else -> TonePanel(photo.uri, section, tone, onTone, onGesture, ::pick)
                    }
                }
            }
        }
    }
}
