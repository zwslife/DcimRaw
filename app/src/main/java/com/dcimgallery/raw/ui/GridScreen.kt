package com.dcimgallery.raw.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dcimgallery.raw.GridItem
import com.dcimgallery.raw.core.coil.GalleryThumbnail
import com.dcimgallery.raw.core.coil.hasRawName
import com.dcimgallery.raw.core.raw.PhotoFileType
import com.dcimgallery.raw.core.raw.PhotoFormatProbe
import com.dcimgallery.raw.data.db.PhotoEntity
import kotlinx.coroutines.isActive

/** White four-column gallery with stable URI selection and shared delete confirmation. */
@Composable
fun GridScreen(
    items: List<GridItem>, photoCount: Int, gridState: LazyGridState,
    scanning: Boolean,
    onRefresh: () -> Unit, onChangeDir: () -> Unit, onOpen: (String) -> Unit,
    localSource: Boolean, directoryGranted: Boolean, directoryReadable: Boolean,
    onLocal: () -> Unit, onDirectory: () -> Unit,
    selecting: Boolean, selectedUris: Set<String>, deleting: Boolean,
    onBeginSelection: (String?) -> Unit, onToggleSelection: (String) -> Unit,
    onDragSelection: (Set<String>) -> Unit,
    onSelectAll: () -> Unit, onCancelSelection: () -> Unit, onDeleteSelected: () -> Unit,
    mediaManagementGranted: Boolean, onManageMedia: (() -> Unit)?,
) {
    val context = LocalContext.current
    val openPrivacy = LocalOpenPrivacyPolicy.current
    val usb by produceState(emptySet<String>(), items, localSource) {
        value = if (localSource) emptySet() else StorageOrigin.usbPhotos(context,
            items.filterIsInstance<GridItem.PhotoItem>().map { it.photo })
    }
    val dragSelection = remember(items) { GalleryDragSelectionState(items) }
    SideEffect { dragSelection.acknowledge(selectedUris) }
    val cancelSelection = {
        dragSelection.cancel()
        onCancelSelection()
    }
    BackHandler(selecting) { if (!deleting) cancelSelection() }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) {
                GallerySelectionHeader(dragSelection, selectedUris, deleting,
                    onSelectAll, onDeleteSelected, cancelSelection)
            } else {
                Text("照片", Modifier.weight(1f), fontSize = 34.sp, fontWeight = FontWeight.Bold,
                    letterSpacing = (-.5).sp, color = StudioColors.Ink)
                GallerySourceMenu(localSource, directoryGranted, onLocal, onDirectory, onChangeDir)
                StudioIconButton(StudioSymbol.SquareCheck, "选择照片", { onBeginSelection(null) }, enabled = photoCount > 0)
                GallerySettings(scanning, onRefresh, openPrivacy, mediaManagementGranted, onManageMedia)
            }
        }
        if (items.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StudioIcon(StudioSymbol.Images, Modifier.size(44.dp), tint = StudioColors.Muted)
                Text(if (scanning) "正在查找照片…" else if (!localSource && !directoryReadable) "授权目录暂不可读取" else "当前来源没有照片", Modifier.padding(top = 16.dp), color = StudioColors.Muted)
            }
        } else BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val edge = with(LocalDensity.current) {
                GalleryThumbnail.bucket(((maxWidth - 10.dp) / 4).roundToPx())
            }
            PrefetchGalleryThumbnails(gridState, items, edge)
            LazyVerticalGrid(columns = GridCells.Fixed(4), state = gridState,
                userScrollEnabled = !selecting,
                modifier = Modifier.fillMaxSize()
                    .galleryDragSelection(gridState, dragSelection, selecting, deleting, selectedUris,
                        { onBeginSelection(null) }, onDragSelection),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(count = items.size, key = { index -> when (val item = items[index]) {
                    is GridItem.Header -> "header_${item.date}"
                    is GridItem.PhotoItem -> item.photo.uri
                } }, contentType = { index -> if (items[index] is GridItem.Header) "date" else "photo" },
                    span = { index -> GridItemSpan(if (items[index] is GridItem.Header) maxLineSpan else 1) }) { index ->
                    val item = items[index]
                    when (item) {
                        is GridItem.Header -> {
                            Text(item.label, Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = StudioColors.Ink)
                        }
                        is GridItem.PhotoItem -> {
                            val uri = item.photo.uri
                            val chosen by remember(dragSelection, index, uri, selectedUris) {
                                derivedStateOf(structuralEqualityPolicy()) {
                                    dragSelection.isSelected(index, uri, selectedUris)
                                }
                            }
                            GridCell(item.photo, edge, uri in usb, selecting, chosen, !deleting,
                                onClick = { if (selecting) onToggleSelection(item.photo.uri) else onOpen(item.photo.uri) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.GallerySelectionHeader(
    dragSelection: GalleryDragSelectionState,
    selectedUris: Set<String>,
    deleting: Boolean,
    onSelectAll: () -> Unit,
    onDeleteSelected: () -> Unit,
    onCancelSelection: () -> Unit,
) {
    val count = dragSelection.count(selectedUris)
    val actionsEnabled = !deleting && !dragSelection.dragging
    Text("已选择 $count 张", Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-.5).sp, color = StudioColors.Ink)
    Text("全选", color = StudioColors.Accent, fontSize = 14.sp,
        modifier = Modifier.clickable(enabled = actionsEnabled, onClick = onSelectAll).padding(12.dp))
    EditorAction(StudioSymbol.Trash2, "删除选中照片", onDeleteSelected,
        enabled = actionsEnabled && count != 0, tint = DeleteRed)
    StudioIconButton(StudioSymbol.X, "取消多选", onCancelSelection, enabled = !deleting)
}

@Composable
private fun GallerySourceMenu(local: Boolean, directoryGranted: Boolean, onLocal: () -> Unit,
    onDirectory: () -> Unit, onChangeDir: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        StudioIconButton(if (local) StudioSymbol.Images else StudioSymbol.FolderOpen,
            if (local) "照片来源，当前系统相册" else "照片来源，当前授权目录", { open = true })
        DropdownMenu(open, { open = false }, containerColor = Color.White, shape = RoundedCornerShape(18.dp)) {
            StudioChoice("系统相册", { open = false; onLocal() }, symbol = StudioSymbol.Images, selected = local)
            if (directoryGranted) StudioChoice("授权目录", { open = false; onDirectory() },
                symbol = StudioSymbol.FolderOpen, selected = !local)
            StudioChoice(if (directoryGranted) "更换授权目录" else "添加目录",
                { open = false; onChangeDir() }, symbol = StudioSymbol.Plus)
        }
    }
}

@Composable
private fun GallerySettings(scanning: Boolean, onRefresh: () -> Unit, onPrivacy: () -> Unit,
    mediaManagementGranted: Boolean, onManageMedia: (() -> Unit)?) {
    var opened by remember { mutableStateOf(false) }
    Box {
        if (scanning) RefreshButton(scanning) { opened = true }
        else StudioIconButton(StudioSymbol.Ellipsis, "图库选项", { opened = true })
        DropdownMenu(opened, { opened = false }, containerColor = Color.White,
            shape = RoundedCornerShape(18.dp), modifier = Modifier.width(224.dp)) {
                StudioChoice("刷新照片", { opened = false; onRefresh() }, symbol = StudioSymbol.RefreshCw)
                if (onManageMedia != null) Column(Modifier.fillMaxWidth().clickable {
                    opened = false; onManageMedia()
                }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Text("系统删除授权", color = StudioColors.Ink, style = MaterialTheme.typography.bodyMedium)
                    Text(if (mediaManagementGranted) "已授权 · 无需逐次系统确认" else "一次授权，免去逐次系统确认",
                        Modifier.padding(top = 4.dp), color = StudioColors.Muted, fontSize = 11.sp)
                }
                Row(Modifier.fillMaxWidth().clickable { opened = false; onPrivacy() }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StudioIcon(StudioSymbol.Info, Modifier.size(18.dp), tint = StudioColors.Muted)
                    Text("隐私政策", Modifier.padding(start = 10.dp), color = StudioColors.Ink, style = MaterialTheme.typography.bodyMedium)
                }
        }
    }
}

@Composable
private fun RefreshButton(scanning: Boolean, onRefresh: () -> Unit) {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(scanning) {
        angle.snapTo(0f)
        if (scanning) while (isActive) {
            angle.animateTo(360f, tween(durationMillis = 900, easing = LinearEasing))
            angle.snapTo(0f)
        }
    }
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onRefresh)
        .semantics { stateDescription = if (scanning) "正在刷新" else "刷新完成" },
        contentAlignment = Alignment.Center) {
        StudioIcon(StudioSymbol.RefreshCw, Modifier.graphicsLayer { rotationZ = angle.value }, label = "刷新")
    }
}

@Composable
private fun GridCell(photo: PhotoEntity, edge: Int, usb: Boolean, selecting: Boolean, chosen: Boolean, enabled: Boolean,
    onClick: () -> Unit) {
    val context = LocalContext.current
    val request = remember(context, photo, edge) {
        GalleryThumbnail(photo, edge).request(context)
    }
    var formatFromThumbnail by remember(photo) {
        mutableStateOf(PhotoFormatProbe.cached(photo.uri, photo.sizeBytes, photo.lastModified))
    }
    val fileType by produceState(PhotoFileType.UNKNOWN, photo) {
        if (photo.hasRawName()) value = PhotoFormatProbe.read(context, photo.uri, photo.sizeBytes, photo.lastModified)
    }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp))
        .background(StudioColors.Canvas).clickable(enabled = enabled, onClick = onClick)
        .semantics { selected = selecting && chosen }) {
        AsyncImage(model = request, contentDescription = photo.displayName,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            onSuccess = { formatFromThumbnail = PhotoFormatProbe.cached(photo.uri, photo.sizeBytes, photo.lastModified) })
        if (fileType == PhotoFileType.CR3 || formatFromThumbnail == PhotoFileType.CR3) Text("RAW", Modifier.align(Alignment.BottomStart).padding(4.dp)
            .clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .9f)).padding(horizontal = 4.dp, vertical = 1.dp),
            fontSize = 8.sp, color = StudioColors.Ink)
        if (usb) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp).clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = .9f)), contentAlignment = Alignment.Center) {
            StudioIcon(StudioSymbol.HardDrive, Modifier.size(13.dp), label = "OTG 存储")
        }
        if (selecting) {
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape)
                .background(if (chosen) StudioColors.Accent else Color.White.copy(alpha = .9f))
                .border(1.dp, if (chosen) StudioColors.Accent else Color(0xFFBAC4BB), CircleShape), contentAlignment = Alignment.Center) {
                if (chosen) StudioIcon(StudioSymbol.Check, Modifier.size(13.dp), tint = Color.White)
            }
        }
    }
}
