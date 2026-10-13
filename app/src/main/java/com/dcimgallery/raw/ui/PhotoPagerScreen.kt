package com.dcimgallery.raw.ui

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.contentDescription
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dcimgallery.raw.core.coil.Cr3Image
import com.dcimgallery.raw.core.exif.ExifInfo
import com.dcimgallery.raw.core.tone.ToneParams
import com.dcimgallery.raw.core.tone.MaskLayer
import com.dcimgallery.raw.core.tone.MaskTool
import com.dcimgallery.raw.data.db.PhotoEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * 全屏照片查看（仿微信沉浸式交互）。
 *
 * 手势分发原则：单指未缩放不消费事件（横滑还给 Pager，纵滑走关闭）；
 * 双指捏合或已缩放时才接管全部手势。单击切换顶/底栏渐隐渐出。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoPagerScreen(
    photos: List<PhotoEntity>,
    initialIndex: Int,
    decodedMap: Map<String, Bitmap>,
    exporting: Boolean,
    toneParams: Map<String, ToneParams>,
    tonePreview: Pair<String, Bitmap>?,
    toneFull: Pair<String, Bitmap>?,
    masks: Map<String, List<MaskLayer>>,
    onMasksChange: (String, List<MaskLayer>) -> Unit,
    canPasteEdits: Boolean,
    onCopyEdits: (String) -> Unit,
    onPasteEdits: (String) -> Unit,
    onResetEdits: (String) -> Unit,
    loadExif: suspend (PhotoEntity) -> ExifInfo?,
    onEnsureDecoded: (PhotoEntity) -> Unit,
    onExport: (PhotoEntity) -> Unit,
    onDelete: (PhotoEntity) -> Unit,
    onToneChange: (String, ToneParams) -> Unit,
    onEnsureToneEngine: (PhotoEntity) -> Unit,
    onTonePanelDone: (PhotoEntity) -> Unit,
    onPageChanged: (String?) -> Unit,
    onClose: () -> Unit,
) {
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, (photos.size - 1).coerceAtLeast(0)),
        pageCount = { photos.size },
    )
    val scope = rememberCoroutineScope()
    val gestureState = remember { PagerGestureState() }
    var showMore by remember { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var tonePanelHeight by remember { mutableIntStateOf(0) }
    var dockHeight by remember { mutableIntStateOf(0) }
    var infoHeight by remember { mutableIntStateOf(0) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showTone by rememberSaveable { mutableStateOf(false) }
    var showMasks by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm by rememberSaveable { mutableStateOf(false) }
    var uiVisible by rememberSaveable { mutableStateOf(true) }
    val context = LocalContext.current
    val viewerPreferences = remember(context) { context.getSharedPreferences("photo-viewer", Context.MODE_PRIVATE) }
    var showHistogram by rememberSaveable { mutableStateOf(viewerPreferences.getBoolean("histogram-visible", true)) }
    fun toggleHistogram() {
        showHistogram = !showHistogram
        viewerPreferences.edit().putBoolean("histogram-visible", showHistogram).apply()
    }
    var toolbarHeight by remember { mutableIntStateOf(0) }
    val fallbackBitmaps = remember { mutableStateMapOf<String, Bitmap>() }

    val current = photos.getOrNull(pagerState.currentPage)
    var comparingOriginal by remember(current?.uri) { mutableStateOf(false) }
    val isCr3 = current?.source == "external"
    val usbPhotos by produceState(emptySet<String>(), photos) { value = StorageOrigin.usbPhotos(context, photos) }
    val currentMasks = current?.let { masks[it.uri] }.orEmpty()
    var selectedMaskId by rememberSaveable(current?.uri) { mutableStateOf<String?>(null) }
    var maskTool by rememberSaveable { mutableStateOf(MaskTool.BRUSH) }
    var maskSubtract by rememberSaveable { mutableStateOf(false) }
    var maskRadius by rememberSaveable { mutableFloatStateOf(0.06f) }
    var maskFeather by rememberSaveable { mutableFloatStateOf(0.7f) }
    var maskOpacity by rememberSaveable { mutableFloatStateOf(1f) }
    var maskComponent by rememberSaveable(current?.uri) { mutableIntStateOf(-1) }
    var maskAdjusting by rememberSaveable { mutableStateOf(false) }
    var maskOverlay by rememberSaveable { mutableStateOf(true) }
    var maskPanelHeight by remember { mutableIntStateOf(0) }
    var toneDragging by remember { mutableStateOf(false) }
    val maskPanelDp = with(LocalDensity.current) { maskPanelHeight.toDp() }
    val selectedMask = currentMasks.firstOrNull { it.id == selectedMaskId }
    fun finishMasks() { showMasks = false; gestureState.reset(); current?.let(onTonePanelDone) }
    BackHandler {
        when { showMasks -> finishMasks(); showExport -> showExport = false; showInfo -> showInfo = false
            showTone -> { showTone = false; current?.let(onTonePanelDone) }; else -> onClose() }
    }
    LaunchedEffect(current?.uri, currentMasks.map { it.id }) {
        if (currentMasks.none { it.id == selectedMaskId }) selectedMaskId = currentMasks.lastOrNull()?.id
    }

    LaunchedEffect(Unit) { gestureState.attach(scope) }
    LaunchedEffect(pagerState.currentPage, photos.size) {
        gestureState.reset()
        current?.let {
            onPageChanged(it.uri)
            onEnsureDecoded(it)
        }
    }
    // 调色会话：打开面板或已有调色参数且解码就绪时建立
    val curDecodedReady = current != null && decodedMap.containsKey(current.uri)
    val hasCurrentEdits = current?.let { !(toneParams[it.uri] ?: ToneParams()).isNeutral } == true ||
        currentMasks.any { it.enabled && !it.tone.isNeutral }
    // Use the same edited frame as the photo, excluding the colored mask overlay.
    val histogramBitmap = current?.let { photo ->
        val decoded = decodedMap[photo.uri]
        (if (hasCurrentEdits && !comparingOriginal) toneFull?.takeIf { it.first == photo.uri }?.second
            ?: tonePreview?.takeIf { it.first == photo.uri }?.second ?: decoded else decoded)
            ?: fallbackBitmaps[photo.uri]
    }
    LaunchedEffect(showTone, showMasks, current?.uri, curDecodedReady, hasCurrentEdits) {
        val photo = current ?: return@LaunchedEffect
        val hasTone = !(toneParams[photo.uri] ?: ToneParams()).isNeutral
        if (showTone || showMasks || hasTone || currentMasks.any { it.enabled && !it.tone.isNeutral }) onEnsureToneEngine(photo)
    }
    // 缩放查看时，拖动调色期间继续用低分辨率实时帧；手势与拨盘停下后再补全尺寸画面。
    val zoomedForDetail = gestureState.isZoomed
    val touchingForDetail = gestureState.isTouching
    val flingingForDetail = gestureState.isFlinging
    val currentToneForDetail = current?.let { toneParams[it.uri] }
    val currentPreviewForDetail = tonePreview?.takeIf { it.first == current?.uri }?.second
    LaunchedEffect(current?.uri, showTone, showMasks, zoomedForDetail, touchingForDetail,
        flingingForDetail, toneDragging, currentToneForDetail, currentMasks, curDecodedReady,
        currentPreviewForDetail) {
        if (current != null && curDecodedReady && (showTone || showMasks) && zoomedForDetail &&
            !touchingForDetail && !flingingForDetail && !toneDragging) {
            delay(240)
            onTonePanelDone(current)
        }
    }

    val viewH = gestureState.viewSize.height.toFloat().coerceAtLeast(1f)
    val dragProgress = (abs(gestureState.dragY) / (viewH * 0.6f)).coerceIn(0f, 1f)

    BoxWithConstraints(
        Modifier
            .fillMaxSize(),
    ) {
        val panelHeightLimit = maxHeight * .5f
        val editorPanelHeight = maxHeight * .4f
        // 背景层：下滑时淡出
        Box(Modifier.fillMaxSize().background(StudioColors.Canvas.copy(alpha = 1f - dragProgress * 0.9f)))

        // 内容层：下滑跟随位移 + 轻微缩小；未缩放时探测垂直关闭手势
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = gestureState.dragY
                    val shrink = 1f - dragProgress * 0.15f
                    scaleX = shrink
                    scaleY = shrink
                }
                .pointerInput(gestureState.isZoomed, showMasks) {
                    if (!gestureState.isZoomed && !showMasks) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (gestureState.onGestureEnd()) onClose() },
                            onDragCancel = { gestureState.onGestureEnd() },
                        ) { _, amount -> gestureState.onCloseDrag(amount) }
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                userScrollEnabled = !showMasks,
            ) { page ->
                val photo = photos[page]
                val isCurrent = page == pagerState.currentPage
                val isExternalPhoto = photo.source == "external"
                val previewRequest = remember(photo.uri, isExternalPhoto, context) {
                    ImageRequest.Builder(context)
                        .data(if (isExternalPhoto) Cr3Image(Uri.parse(photo.uri)) else Uri.parse(photo.uri))
                        .allowHardware(false)
                        .build()
                }
                DisposableEffect(photo.uri) {
                    onDispose { fallbackBitmaps.remove(photo.uri) }
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(with(LocalDensity.current) {
                            PaddingValues(top = if (uiVisible || showMasks) toolbarHeight.toDp() else 0.dp,
                                bottom = when { showMasks -> maskPanelDp; showTone -> tonePanelHeight.toDp()
                                    showInfo -> infoHeight.toDp(); uiVisible && isCr3 -> dockHeight.toDp(); else -> 0.dp })
                        })
                        .onSizeChanged { if (isCurrent) gestureState.viewSize = it }
                        // 图片与蒙版在同一个 graphicsLayer 内缩放；由父层在 Initial pass
                        // 统一识别双指手势，单指交给蒙版绘制或未缩放时的 Pager。
                        .pointerInput(showMasks) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                gestureState.onTouchStart()
                                try {
                                    val first = awaitPointerEvent(PointerEventPass.Initial)
                                    if (first.changes.none { it.pressed }) return@awaitEachGesture
                                    if (!showMasks && first.changes.count { it.pressed } < 2 && !gestureState.isZoomed) {
                                        return@awaitEachGesture
                                    }
                                    val prevPositions = HashMap<PointerId, Offset>()
                                    first.changes.forEach { prevPositions[it.id] = it.position }
                                    var trackedCount = first.changes.count { it.pressed }
                                    var velocityTracker = VelocityTracker()
                                    var movedWhileZoomed = false
                                    velocityTracker.addPosition(first.changes.first().uptimeMillis,
                                        first.changes.filter { it.pressed }.map { it.position }.centroid())
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        val pressed = event.changes.filter { it.pressed }
                                        if (pressed.isEmpty()) {
                                            if (movedWhileZoomed) {
                                                val released = event.changes.filter { it.previousPressed }
                                                if (trackedCount == 1 && released.size == 1) {
                                                    velocityTracker.addPosition(released[0].uptimeMillis, released[0].position)
                                                }
                                                val velocity = velocityTracker.calculateVelocity()
                                                gestureState.fling(Offset(velocity.x, velocity.y))
                                            }
                                            break
                                        }
                                        val positions = pressed.map { it.position }
                                        val centroid = positions.centroid()
                                        if (pressed.size != trackedCount) {
                                            trackedCount = pressed.size
                                            velocityTracker = VelocityTracker()
                                            movedWhileZoomed = false
                                            velocityTracker.addPosition(pressed[0].uptimeMillis, centroid)
                                        }
                                        var zoom = 1f
                                        if (pressed.size >= 2) {
                                            val prevD0 = prevPositions[pressed[0].id]
                                            val prevD1 = prevPositions[pressed[1].id]
                                            if (prevD0 != null && prevD1 != null) {
                                                val prevDist = (prevD0 - prevD1).getDistance()
                                                val curDist =
                                                    (pressed[0].position - pressed[1].position).getDistance()
                                                if (prevDist > 0f && curDist > 0f) {
                                                    zoom = curDist / prevDist
                                                }
                                            }
                                        }
                                        val prevList = pressed.mapNotNull { prevPositions[it.id] }
                                        val pan = if (prevList.size == pressed.size) {
                                            centroid - prevList.centroid()
                                        } else {
                                            Offset.Zero
                                        }
                                        if ((pressed.size >= 2 || !showMasks && gestureState.isZoomed) &&
                                            (zoom != 1f || pan != Offset.Zero)) {
                                            gestureState.onTransform(centroid, pan, zoom)
                                            if (gestureState.isZoomed && pan != Offset.Zero) {
                                                velocityTracker.addPosition(pressed[0].uptimeMillis, centroid)
                                                movedWhileZoomed = true
                                            }
                                            pressed.forEach { it.consume() }
                                        }
                                        prevPositions.clear()
                                        pressed.forEach { prevPositions[it.id] = it.position }
                                    }
                                } finally {
                                    gestureState.onTouchEnd()
                                }
                            }
                        }
                        .pointerInput(showMasks) {
                            if (showMasks) return@pointerInput
                            detectTapGestures(
                                onTap = {
                                    // 面板打开时优先关面板（调色面板关闭触发全尺寸定稿渲染）
                                    if (showTone || showInfo) {
                                        if (showTone) current?.let(onTonePanelDone)
                                        showTone = false
                                        showInfo = false
                                    } else {
                                        uiVisible = !uiVisible
                                    }
                                },
                                onDoubleTap = { point -> gestureState.onDoubleTap(point) },
                            )
                        }
                        .graphicsLayer {
                            if (isCurrent) {
                                transformOrigin = TransformOrigin(0f, 0f)
                                translationX = gestureState.offset.x
                                translationY = gestureState.offset.y
                                scaleX = gestureState.scale
                                scaleY = gestureState.scale
                            }
                        },
                ) {
                    val tone = toneParams[photo.uri] ?: ToneParams()
                    // 底层：内嵌预览（始终在位）
                    AsyncImage(
                        model = previewRequest,
                        onSuccess = { state ->
                            (state.result.drawable as? BitmapDrawable)?.bitmap?.let { fallbackBitmaps[photo.uri] = it }
                        },
                        contentDescription = photo.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 显示优先级：全尺寸定稿 > 调色预览小图（有调色时）> 解码原图
                    val decoded = decodedMap[photo.uri]
                    val fullBmp = toneFull?.takeIf { it.first == photo.uri }?.second
                    val prevBmp = tonePreview?.takeIf { it.first == photo.uri }?.second
                    val hasLocalTone = masks[photo.uri].orEmpty().any { it.enabled && !it.tone.isNeutral }
                    val displayBitmap = when {
                        isCurrent && comparingOriginal -> decoded
                        tone.isNeutral && !hasLocalTone -> decoded
                        isCurrent && (showTone || showMasks) -> fullBmp ?: prevBmp ?: decoded
                        else -> fullBmp ?: prevBmp ?: decoded
                    }
                    if (showMasks && isCurrent && decoded != null) {
                        MaskOverlay(
                            layer = selectedMask, componentIndex = maskComponent, tool = maskTool,
                            subtract = maskSubtract, radius = maskRadius, feather = maskFeather,
                            opacity = maskOpacity, showOverlay = maskOverlay && !comparingOriginal,
                            imageWidth = decoded.width, imageHeight = decoded.height,
                            editable = !maskAdjusting && !comparingOriginal,
                            onCommit = { index, part ->
                                val layer = selectedMask
                                if (layer != null) {
                                    val parts = if (part.tool == MaskTool.BRUSH && index < 0) {
                                        // Brush strokes share a range; retain their individual paths for rasterization.
                                        val previous = layer.components.firstOrNull()
                                        if (previous?.tool == MaskTool.BRUSH && previous.points.isNotEmpty()) {
                                            listOf(previous.copy(points = previous.points + part.points,
                                                strokeStarts = previous.strokeStarts + previous.points.size))
                                        } else listOf(part)
                                    } else listOf(part)
                                    onMasksChange(photo.uri, currentMasks.map { if (it.id == layer.id) layer.copy(components = parts) else it })
                                    maskComponent = 0
                                }
                            }, modifier = Modifier.fillMaxSize().zIndex(1f),
                        )
                    }
                    AnimatedVisibility(
                        visible = displayBitmap != null,
                        enter = fadeIn(animationSpec = tween(320)),
                        exit = fadeOut(),
                    ) {
                        if (displayBitmap != null) {
                            Image(
                                bitmap = displayBitmap.asImageBitmap(),
                                contentDescription = photo.displayName,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }

            // 顶栏：圆形返回 + 标题 + 圆形信息（单击图片渐隐渐出）
            AnimatedVisibility(
                visible = uiVisible || showMasks,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { toolbarHeight = it.height }
                        .background(Color.White)
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StudioIconButton(StudioSymbol.ArrowLeft, "返回", { if (showMasks) finishMasks() else onClose() })
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = current?.displayName.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = StudioColors.Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${if (isCr3) "RAW · " else ""}${pagerState.currentPage + 1} / ${photos.size}",
                            style = MaterialTheme.typography.bodySmall,
                            color = StudioColors.Muted,
                        )
                    }
                    val config = LocalViewConfiguration.current
                    val compactConfig = remember(config) { object : ViewConfiguration by config {
                        override val minimumTouchTargetSize = DpSize(32.dp, 32.dp)
                    } }
                    CompositionLocalProvider(LocalViewConfiguration provides compactConfig) {
                        HistogramIconButton(shown = showHistogram, onClick = ::toggleHistogram, modifier = Modifier.size(32.dp))
                        Spacer(Modifier.width(4.dp))
                        StudioIconButton(StudioSymbol.Info, "信息", {
                            showInfo = !showInfo
                            if (showInfo) { showTone = false; if (showMasks) finishMasks() }
                        }, modifier = Modifier.size(32.dp), selected = showInfo)
                    }
                    Box {
                        StudioIconButton(StudioSymbol.Ellipsis, "更多", { showMore = true })
                        DropdownMenu(showMore, { showMore = false }) {
                            if (isCr3) {
                                DropdownMenuItem(text = { Text("复制调色参数") }, leadingIcon = { StudioIcon(StudioSymbol.Copy) },
                                    onClick = { current?.let { onCopyEdits(it.uri) }; showMore = false })
                                DropdownMenuItem(text = { Text("粘贴调色参数") }, leadingIcon = { StudioIcon(StudioSymbol.Paste) },
                                    enabled = canPasteEdits,
                                    onClick = { current?.let { onPasteEdits(it.uri) }; showMore = false })
                                DropdownMenuItem(text = { Text("复位全部调色") }, leadingIcon = { StudioIcon(StudioSymbol.RotateCcw) },
                                    onClick = { current?.let { onResetEdits(it.uri) }; showMore = false })
                                DropdownMenuItem(text = { Text("删除原片", color = Color(0xFFBA1A1A)) },
                                    leadingIcon = { StudioIcon(StudioSymbol.Trash2, tint = Color(0xFFBA1A1A)) },
                                    onClick = { showMore = false; showDeleteConfirm = true })
                            } else DropdownMenuItem(text = { Text("照片信息") }, onClick = { showMore = false; showInfo = true })
                        }
                    }
                }
            }

            // 底栏：调色 / 导出 / 删除动作胶囊（面板打开时收起）
            AnimatedVisibility(
                visible = uiVisible && isCr3 && !showInfo && !showTone && !showMasks,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(Modifier.fillMaxWidth().onSizeChanged { dockHeight = it.height }
                    .background(Color.White).navigationBarsPadding().padding(horizontal = 36.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    StudioTool(StudioSymbol.Palette, "调色", { showTone = true; showInfo = false })
                    StudioTool(StudioSymbol.Scan, "蒙版", {
                        gestureState.reset(); showMasks = true; showTone = false; showInfo = false
                        maskAdjusting = false; maskOverlay = true; maskComponent = -1
                    }, enabled = curDecodedReady)
                    StudioTool(StudioSymbol.ArrowUpFromLine, if (exporting) "导出中…" else "导出",
                        { showExport = true }, enabled = current != null && !exporting)
                }
            }
        }

        if (curDecodedReady && !showInfo && (uiVisible || showTone || showMasks)) {
            val bottom = with(LocalDensity.current) {
                when { showMasks -> maskPanelHeight.toDp(); showTone -> tonePanelHeight.toDp(); isCr3 -> dockHeight.toDp(); else -> 0.dp }
            }
            DisposableEffect(current?.uri) { onDispose { comparingOriginal = false } }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottom + 12.dp)
                .size(36.dp).clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = .8f))
                .semantics { contentDescription = "长按对比未调色原图"; stateDescription = if (comparingOriginal) "原图" else "调色后" }
                .pointerInput(current?.uri) {
                    detectTapGestures(onPress = {
                        comparingOriginal = true
                        try { tryAwaitRelease() } finally { comparingOriginal = false }
                    })
                }, contentAlignment = Alignment.Center) {
                StudioIcon(StudioSymbol.Contrast, Modifier.size(20.dp), tint = StudioColors.Ink)
            }
        }

        // Fixed in screen coordinates, so zooming/panning the image does not move the chart.
        if (showHistogram && (uiVisible || showMasks) && (toolbarHeight > 0 || showMasks)) {
            val topInset = with(LocalDensity.current) { toolbarHeight.toDp() }
            HistogramOverlay(
                photoKey = current?.uri, bitmap = histogramBitmap,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = topInset)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        // 调色面板
        if (showTone && current != null) {
            val tone = toneParams[current.uri] ?: ToneParams()
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(editorPanelHeight)
                .onSizeChanged { tonePanelHeight = it.height }) {
                TonePanel(
                    photoName = current.displayName,
                    params = tone,
                    onParamsChange = { onToneChange(current.uri, it) },
                    onReset = { onResetEdits(current.uri) },
                    onDraggingChange = { toneDragging = it },
                    onClose = {
                        showTone = false
                        current?.let(onTonePanelDone)
                    },
                )
            }
        }

        if (showMasks && current != null) {
            MaskPanel(
                layers = currentMasks, selectedId = selectedMaskId,
                onSelect = { selectedMaskId = it; maskComponent = -1 },
                onChange = { onMasksChange(current.uri, it) },
                tool = maskTool, onTool = { maskTool = it },
                subtract = maskSubtract, onSubtract = { maskSubtract = it },
                radius = maskRadius, onRadius = { maskRadius = it },
                feather = maskFeather, onFeather = { maskFeather = it },
                opacity = maskOpacity, onOpacity = { maskOpacity = it },
                componentIndex = maskComponent, onComponent = { maskComponent = it },
                adjusting = maskAdjusting, onAdjusting = { maskAdjusting = it },
                onToneDragging = { toneDragging = it },
                showOverlay = maskOverlay, onOverlay = { maskOverlay = it }, onClose = ::finishMasks,
                modifier = Modifier.align(Alignment.BottomCenter).height(editorPanelHeight)
                    .onSizeChanged { maskPanelHeight = it.height },
                thumbnail = histogramBitmap,
            )
        }

        // EXIF 信息面板
        if (showInfo && current != null) {
            val exif by produceState<ExifInfo?>(null, current.uri) {
                value = loadExif(current)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .heightIn(max = panelHeightLimit).onSizeChanged { infoHeight = it.height }
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(Color.White)
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("照片信息", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    StudioIconButton(StudioSymbol.X, "关闭信息", { showInfo = false })
                }
                Text(current.displayName, style = MaterialTheme.typography.titleSmall, color = StudioColors.Ink)
                Text(
                    text = buildString {
                        if (current.folder.isNotEmpty()) append(current.folder).append(" · ")
                        append(String.format(Locale.US, "%.1f MB", current.sizeBytes / 1048576f))
                        if (current.uri in usbPhotos) append(" · OTG 存储")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = StudioColors.Muted,
                )
                Spacer(Modifier.height(12.dp))
                val info = exif
                if (info == null) {
                    Text("读取中…", style = MaterialTheme.typography.bodyMedium, color = StudioColors.Muted)
                } else {
                    ExifRow("拍摄时间", info.dateTimeText)
                    ExifRow("相机", info.camera)
                    ExifRow("镜头", info.lens)
                    ExifRow("光圈", info.aperture)
                    ExifRow("快门", info.exposure)
                    ExifRow("感光度", info.iso)
                    ExifRow("焦距", info.focal)
                    ExifRow("闪光灯", info.flash)
                    ExifRow("白平衡", info.whiteBalance)
                    ExifRow(
                        "分辨率",
                        if (info.imageWidth > 0) "${info.imageWidth} × ${info.imageHeight}" else null,
                    )
                }
            }
        }

        if (showExport && current != null) {
            AlertDialog(onDismissRequest = { showExport = false }, containerColor = Color.White,
                icon = { StudioIcon(StudioSymbol.ArrowUpFromLine, tint = StudioColors.Pine) },
                title = { Text("导出照片") },
                text = {
                    Column {
                        Text(current.displayName, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(20.dp))
                        ExifRow("格式", "JPEG")
                        ExifRow("尺寸", "原始尺寸")
                        ExifRow("调整", "全局调色与蒙版")
                    }
                },
                confirmButton = { Button(onClick = { showExport = false; onExport(current) }) { Text("导出到相册") } },
                dismissButton = { TextButton(onClick = { showExport = false }) { Text("取消") } })
        }

        // 删除二次确认
        if (showDeleteConfirm && current != null) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("删除原片") },
                text = {
                    Text(
                        "将永久删除 ${current.displayName}" +
                            "（${String.format(Locale.US, "%.1f", current.sizeBytes / 1048576f)} MB）。\n\n" +
                            "此操作不可恢复，相机的原始 RAW 文件将被彻底移除。",
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            onDelete(current)
                        },
                    ) {
                        Text("永久删除", color = Color(0xFFBA1A1A))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
                },
            )
        }
    }
}

@Composable
private fun HistogramIconButton(shown: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    StudioIconButton(StudioSymbol.ChartNoAxesColumn, if (shown) "隐藏直方图" else "显示直方图", onClick,
        modifier = modifier.semantics { stateDescription = if (shown) "已开启" else "已关闭" }, selected = shown)
}

@Composable
private fun ExifRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioColors.Muted,
            modifier = Modifier.fillMaxWidth(0.28f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = StudioColors.Ink,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun formatTaken(ts: Long): String {
    if (ts <= 0) return ""
    return Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy/M/d HH:mm"))
}

/** 多点质心。 */
private fun List<Offset>.centroid(): Offset {
    var x = 0f
    var y = 0f
    forEach {
        x += it.x
        y += it.y
    }
    return Offset(x / size, y / size)
}
