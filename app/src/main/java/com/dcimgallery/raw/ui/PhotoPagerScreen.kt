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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.platform.LocalConfiguration
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
import com.dcimgallery.raw.core.raw.PhotoFileType
import com.dcimgallery.raw.core.raw.PhotoFormatProbe
import com.dcimgallery.raw.data.db.PhotoEntity
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import com.dcimgallery.raw.core.geometry.PhotoGeometryRenderer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    deleting: Boolean,
    editBusy: Boolean,
    onBeginEditing: (String) -> Unit,
    toneParams: Map<String, ToneParams>,
    tonePreview: Pair<String, Bitmap>?,
    toneFull: Pair<String, Bitmap>?,
    masks: Map<String, List<MaskLayer>>,
    geometry: Map<String, PhotoGeometry>,
    canPasteEdits: Boolean,
    onCopyEdits: (String) -> Unit,
    onPasteEdits: (String) -> Unit,
    onResetEdits: (String) -> Unit,
    loadExif: suspend (PhotoEntity) -> ExifInfo?,
    onEnsureDecoded: (PhotoEntity) -> Unit,
    onExport: (PhotoEntity) -> Unit,
    onExportEmbedded: (PhotoEntity) -> Unit,
    onDelete: (PhotoEntity) -> Unit,
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
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.screenWidthDp, configuration.screenHeightDp) {
        gestureState.reset()
    }
    var showMore by remember { mutableStateOf(false) }
    var showExport by rememberSaveable { mutableStateOf(false) }
    var exportEmbedded by rememberSaveable { mutableStateOf(false) }
    var dockHeight by remember { mutableIntStateOf(0) }
    var infoHeight by remember { mutableIntStateOf(0) }
    var showInfo by rememberSaveable { mutableStateOf(false) }
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
    val currentGeometry = geometry[current?.uri] ?: PhotoGeometry()
    val currentFileType by androidx.compose.runtime.key(current?.uri, current?.sizeBytes, current?.lastModified) {
        produceState(PhotoFileType.UNKNOWN) {
            value = current?.let { PhotoFormatProbe.read(context, it.uri, it.sizeBytes, it.lastModified) } ?: PhotoFileType.UNKNOWN
        }
    }
    var comparingOriginal by remember(current?.uri) { mutableStateOf(false) }
    LaunchedEffect(currentFileType) {
        if (currentFileType != PhotoFileType.CR3) exportEmbedded = false
    }
    val canEdit = current != null
    val usbPhotos by produceState(emptySet<String>(), photos) { value = StorageOrigin.usbPhotos(context, photos) }
    val currentMasks = current?.let { masks[it.uri] }.orEmpty()
    BackHandler { if (showExport) showExport = false else if (showInfo) showInfo = false else onClose() }

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
    val histogramSource = current?.let { photo ->
        val decoded = decodedMap[photo.uri]
        (if (hasCurrentEdits && !comparingOriginal) toneFull?.takeIf { it.first == photo.uri }?.second
            ?: tonePreview?.takeIf { it.first == photo.uri }?.second ?: decoded else decoded)
            ?: fallbackBitmaps[photo.uri]
    }
    val histogramBitmap by produceState<Bitmap?>(null, histogramSource, currentGeometry) {
        value = withContext(Dispatchers.Default) {
            histogramSource?.let { source ->
                if (currentGeometry.isIdentity) source else {
                    val scale = minOf(1f, 512f / maxOf(source.width, source.height))
                    val small = Bitmap.createScaledBitmap(source, maxOf(1, (source.width * scale).toInt()),
                        maxOf(1, (source.height * scale).toInt()), true)
                    PhotoGeometryRenderer.render(small, currentGeometry).also {
                        if (small !== source && small !== it) small.recycle()
                    }
                }
            }
        }
    }
    LaunchedEffect(current?.uri, curDecodedReady, hasCurrentEdits) {
        if (hasCurrentEdits) current?.let(onEnsureToneEngine)
    }
    val currentPreview = tonePreview?.takeIf { it.first == current?.uri }?.second
    LaunchedEffect(current?.uri, currentPreview) {
        if (currentPreview != null) current?.let(onTonePanelDone)
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
                .pointerInput(gestureState.isZoomed) {
                    if (!gestureState.isZoomed) {
                        detectVerticalDragGestures(
                            onDragEnd = { if (gestureState.onGestureEnd()) onClose() },
                            onDragCancel = { gestureState.onGestureEnd() },
                        ) { _, amount -> gestureState.onCloseDrag(amount) }
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().clipToBounds(),
                beyondViewportPageCount = 1,
            ) { page ->
                val photo = photos[page]
                val isCurrent = page == pagerState.currentPage
                val isExternalPhoto = photo.source == "external"
                val previewRequest = remember(photo.uri, photo.sizeBytes, photo.lastModified, isExternalPhoto, context) {
                    ImageRequest.Builder(context)
                        .data(if (isExternalPhoto) Cr3Image(Uri.parse(photo.uri), photo.sizeBytes, photo.lastModified) else Uri.parse(photo.uri))
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
                            // Chrome overlays fade without changing the image viewport.
                            PaddingValues(top = toolbarHeight.toDp(),
                                bottom = if (showInfo) infoHeight.toDp() else dockHeight.toDp())
                        })
                        .onSizeChanged { if (isCurrent) gestureState.viewSize = it }
                        .clipToBounds()
                        // 图片与蒙版在同一个 graphicsLayer 内缩放；由父层在 Initial pass
                        // 统一识别双指手势，单指交给蒙版绘制或未缩放时的 Pager。
                        .editorImageGestures(gestureState, false)
                        .pointerInput(showInfo) {
                            detectTapGestures(onTap = {
                                if (showInfo) showInfo = false else uiVisible = !uiVisible
                            }, onDoubleTap = { gestureState.onDoubleTap(it) })
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
                    // 显示优先级：全尺寸定稿 > 调色预览小图（有调色时）> 解码原图
                    val decoded = decodedMap[photo.uri]
                    val fullBmp = toneFull?.takeIf { it.first == photo.uri }?.second
                    val prevBmp = tonePreview?.takeIf { it.first == photo.uri }?.second
                    val hasLocalTone = masks[photo.uri].orEmpty().any { it.enabled && !it.tone.isNeutral }
                    val displayBitmap = when {
                        isCurrent && comparingOriginal -> decoded
                        tone.isNeutral && !hasLocalTone -> decoded
                        else -> fullBmp ?: prevBmp ?: decoded
                    }
                    // One photo layer: embedded JPEG and decoded RAW can have different
                    // orientation/aspect ratios. Keeping both exposes the JPEG in letterboxes.
                    val source = displayBitmap ?: fallbackBitmaps[photo.uri]
                    if (source != null) {
                        PhotoGeometryViewport(source, geometry[photo.uri] ?: PhotoGeometry(),
                            photo.displayName, Modifier.fillMaxSize())
                    } else {
                        AsyncImage(
                            model = previewRequest,
                            onSuccess = { state ->
                                (state.result.drawable as? BitmapDrawable)?.bitmap?.let { fallbackBitmaps[photo.uri] = it }
                            },
                            contentDescription = photo.displayName,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            // 顶栏：圆形返回 + 标题 + 圆形信息（单击图片渐隐渐出）
            AnimatedVisibility(
                visible = uiVisible,
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
                    StudioIconButton(StudioSymbol.ArrowLeft, "返回", onClose)
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
                            text = "${if (currentFileType == PhotoFileType.CR3) "RAW · " else ""}${pagerState.currentPage + 1} / ${photos.size}",
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

                        }, modifier = Modifier.size(32.dp), selected = showInfo)
                    }
                    Box {
                        StudioIconButton(StudioSymbol.Ellipsis, "更多", { showMore = true })
                        DropdownMenu(showMore, { showMore = false }) {
                            if (canEdit) {
                                DropdownMenuItem(text = { Text("复制调色参数") }, leadingIcon = { StudioIcon(StudioSymbol.Copy) },
                                    onClick = { current?.let { onCopyEdits(it.uri) }; showMore = false })
                                DropdownMenuItem(text = { Text("粘贴调色参数") }, leadingIcon = { StudioIcon(StudioSymbol.Paste) },
                                    enabled = canPasteEdits,
                                    onClick = { current?.let { onPasteEdits(it.uri) }; showMore = false })
                                DropdownMenuItem(text = { Text("复位全部调色") }, leadingIcon = { StudioIcon(StudioSymbol.RotateCcw) },
                                    onClick = { current?.let { onResetEdits(it.uri) }; showMore = false })
                                DropdownMenuItem(text = { Text("删除照片", color = DeleteRed) },
                                    leadingIcon = { StudioIcon(StudioSymbol.Trash2, tint = Color(0xFFBA1A1A)) },
                                    enabled = !deleting,
                                    onClick = { showMore = false; current?.let(onDelete) })
                            } else DropdownMenuItem(text = { Text("照片信息") }, onClick = { showMore = false; showInfo = true })
                        }
                    }
                }
            }

            // Geometry shares the photo's non-destructive editing and export flow.
            AnimatedVisibility(
                visible = uiVisible && canEdit && !showInfo,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(Modifier.fillMaxWidth().onSizeChanged { dockHeight = it.height }
                    .background(Color.White).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically) {
                        StudioTool(StudioSymbol.Pencil, "编辑", { current?.let { onBeginEditing(it.uri) } },
                            enabled = curDecodedReady && !deleting && !exporting && !editBusy)
                        StudioTool(StudioSymbol.Trash2, if (deleting) "删除中…" else "删除",
                            { current?.let(onDelete) },
                            enabled = current != null && !deleting && !exporting, tint = DeleteRed)
                        StudioTool(StudioSymbol.ArrowUpFromLine, if (exporting) "导出中…" else "导出",
                            { showExport = true }, enabled = current != null && !exporting && !deleting)
                    }
                }
            }
        }

        if (curDecodedReady && !showInfo && uiVisible) {
            val bottom = with(LocalDensity.current) {
                dockHeight.toDp()
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
        if (showHistogram && uiVisible && toolbarHeight > 0) {
            val topInset = with(LocalDensity.current) { toolbarHeight.toDp() }
            HistogramOverlay(
                photoKey = current?.uri, bitmap = histogramBitmap,
                modifier = Modifier.align(Alignment.TopEnd)
                    .padding(top = topInset)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
                        StudioChoice("调色后 JPEG", { exportEmbedded = false }, symbol = StudioSymbol.Palette, selected = !exportEmbedded)
                        if (currentFileType == PhotoFileType.CR3) StudioChoice("RAW 内嵌 JPEG", { exportEmbedded = true }, symbol = StudioSymbol.Images, selected = exportEmbedded)
                        Spacer(Modifier.height(12.dp))
                        ExifRow("格式", "JPEG")
                        val size = decodedMap[current.uri]?.let { currentGeometry.outputSize(it.width, it.height) }
                        ExifRow("尺寸", if (exportEmbedded) "机内预览尺寸" else size?.let { "${it.first} × ${it.second}" } ?: "原始尺寸")
                        ExifRow("调整", if (exportEmbedded) "不应用编辑" else "调色、蒙版、裁切与旋转")
                    }
                },
                confirmButton = { Button(onClick = {
                    showExport = false
                    if (exportEmbedded && currentFileType == PhotoFileType.CR3) onExportEmbedded(current) else onExport(current)
                }, enabled = !exporting) { Text("导出到相册") } },
                dismissButton = { TextButton(onClick = { showExport = false }) { Text("取消") } })
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
