package com.dcimgallery.raw.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.shadow
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.isActive
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.dcimgallery.raw.GridItem
import com.dcimgallery.raw.R
import com.dcimgallery.raw.core.coil.Cr3Image
import com.dcimgallery.raw.data.db.PhotoEntity
import com.dcimgallery.raw.core.raw.PhotoFileType
import com.dcimgallery.raw.core.raw.PhotoFormatProbe

/** White four-column gallery with stable URI selection and shared delete confirmation. */
@Composable
fun GridScreen(
    items: List<GridItem>, photoCount: Int, gridState: LazyGridState,
    scanning: Boolean, scanFound: Int, driveReadable: Boolean,
    onRefresh: () -> Unit, onChangeDir: () -> Unit, onOpen: (String) -> Unit,
    showCr3Only: Boolean, onShowCr3Only: (Boolean) -> Unit,
    selecting: Boolean, selectedUris: Set<String>, deleting: Boolean,
    onBeginSelection: (String?) -> Unit, onToggleSelection: (String) -> Unit,
    onSelectAll: () -> Unit, onCancelSelection: () -> Unit, onDeleteSelected: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val openPrivacy = LocalOpenPrivacyPolicy.current
    val photos = androidx.compose.runtime.remember(items) { items.filterIsInstance<GridItem.PhotoItem>().map { it.photo } }
    val usb by androidx.compose.runtime.produceState(emptySet<String>(), photos) {
        value = StorageOrigin.usbPhotos(context, photos)
    }
    BackHandler(selecting) { if (!deleting) onCancelSelection() }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selecting) "已选择 ${selectedUris.size} 张" else "照片", Modifier.weight(1f),
                style = if (selecting) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
                color = StudioColors.Ink)
            if (selecting) {
                Text("全选", color = StudioColors.Pine, fontSize = 14.sp,
                    modifier = Modifier.clickable(enabled = !deleting, onClick = onSelectAll).padding(12.dp))
                StudioIconButton(StudioSymbol.X, "取消多选", onCancelSelection, enabled = !deleting)
            } else {
                RefreshButton(scanning, onRefresh)
                StudioIconButton(StudioSymbol.FolderOpen, "选择目录", onChangeDir)
                StudioIconButton(StudioSymbol.SquareCheck, "选择照片", { onBeginSelection(null) }, enabled = photoCount > 0)
                GallerySettings(showCr3Only, onShowCr3Only, openPrivacy)
            }
        }
        if (items.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StudioIcon(StudioSymbol.Images, Modifier.size(44.dp), tint = StudioColors.Muted)
                Text(if (scanning) "正在查找照片…" else if (showCr3Only) "当前没有可显示的 CR3 照片" else "选择目录，开始浏览照片", Modifier.padding(top = 16.dp), color = StudioColors.Muted)
            }
        } else LazyVerticalGrid(columns = GridCells.Fixed(4), state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(count = items.size, key = { index -> when (val item = items[index]) {
                is GridItem.Header -> "header_${item.date}"
                is GridItem.PhotoItem -> item.photo.uri
            } }, contentType = { index -> if (items[index] is GridItem.Header) "date" else "photo" },
                span = { index -> GridItemSpan(if (items[index] is GridItem.Header) maxLineSpan else 1) }) { index ->
                val item = items[index]
                when (item) {
                    is GridItem.Header -> {
                        Text(item.label, Modifier.padding(horizontal = 14.dp, vertical = 12.dp), style = MaterialTheme.typography.labelMedium, color = StudioColors.Muted)
                    }
                    is GridItem.PhotoItem -> {
                        GridCell(item.photo, item.photo.uri in usb, selecting, item.photo.uri in selectedUris, !deleting,
                            onClick = { if (selecting) onToggleSelection(item.photo.uri) else onOpen(item.photo.uri) },
                            onLongClick = { onBeginSelection(item.photo.uri) })
                    }
                }
            }
        }
        if (selecting) Row(Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (selectedUris.isEmpty()) "选择要删除的照片" else "已选择 ${selectedUris.size} 张", Modifier.weight(1f),
                color = StudioColors.Muted, fontSize = 13.sp)
            Row(Modifier.clip(RoundedCornerShape(12.dp))
                .background(DeleteRed.copy(alpha = if (selectedUris.isEmpty()) .35f else 1f))
                .clickable(enabled = selectedUris.isNotEmpty() && !deleting, onClick = onDeleteSelected)
                .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                StudioIcon(StudioSymbol.Trash2, Modifier.size(18.dp), tint = Color.White)
                Text(if (deleting) "删除中…" else "删除", Modifier.padding(start = 7.dp), color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun GallerySettings(cr3Only: Boolean, onCr3Only: (Boolean) -> Unit, onPrivacy: () -> Unit) {
    var opened by remember { mutableStateOf(false) }
    val top = with(LocalDensity.current) { 48.dp.roundToPx() }
    Box {
        StudioIconButton(StudioSymbol.Settings, "图库设置", { opened = true }, selected = cr3Only)
        if (opened) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, top),
            onDismissRequest = { opened = false }, properties = PopupProperties(focusable = true)) {
            Column(Modifier.width(240.dp).shadow(8.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp)).background(Color.White).border(1.dp, StudioColors.Line, RoundedCornerShape(16.dp))
                .padding(vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().toggleable(value = cr3Only, role = Role.Switch, onValueChange = onCr3Only)
                    .padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("只显示 CR3", Modifier.weight(1f), color = StudioColors.Ink, style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.width(38.dp).height(23.dp).clip(CircleShape)
                        .background(if (cr3Only) StudioColors.Pine else StudioColors.Line).padding(3.dp)) {
                        Box(Modifier.size(17.dp).align(if (cr3Only) Alignment.CenterEnd else Alignment.CenterStart)
                            .clip(CircleShape).background(Color.White))
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(StudioColors.Line))
                Row(Modifier.fillMaxWidth().clickable { opened = false; onPrivacy() }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StudioIcon(StudioSymbol.Info, Modifier.size(18.dp), tint = StudioColors.Muted)
                    Text("隐私政策", Modifier.padding(start = 10.dp), color = StudioColors.Ink, style = MaterialTheme.typography.bodyMedium)
                }
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
    Box(Modifier.size(44.dp).clip(CircleShape).clickable(enabled = !scanning, onClick = onRefresh)
        .semantics { stateDescription = if (scanning) "正在刷新" else "刷新完成" },
        contentAlignment = Alignment.Center) {
        StudioIcon(StudioSymbol.RefreshCw, Modifier.graphicsLayer { rotationZ = angle.value }, label = "刷新")
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun GridCell(photo: PhotoEntity, usb: Boolean, selecting: Boolean, chosen: Boolean, enabled: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(context, photo.uri, photo.sizeBytes, photo.lastModified, photo.source) {
        ImageRequest.Builder(context).data(if (photo.source == "external")
            Cr3Image(Uri.parse(photo.uri), photo.sizeBytes, photo.lastModified) else Uri.parse(photo.uri))
            .crossfade(false).build()
    }
    val fileType by androidx.compose.runtime.produceState(PhotoFileType.UNKNOWN, photo.uri, photo.sizeBytes, photo.lastModified) {
        value = PhotoFormatProbe.read(context, photo.uri, photo.sizeBytes, photo.lastModified)
    }
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(2.dp))
        .background(StudioColors.Canvas).combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
        .semantics { selected = selecting && chosen }) {
        AsyncImage(model = request, contentDescription = photo.displayName,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (fileType == PhotoFileType.CR3) Text("RAW", Modifier.align(Alignment.BottomStart).padding(4.dp)
            .clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = .9f)).padding(horizontal = 4.dp, vertical = 1.dp),
            fontSize = 8.sp, color = StudioColors.Ink)
        if (usb) Box(Modifier.align(Alignment.BottomEnd).padding(4.dp).size(20.dp).clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = .9f)), contentAlignment = Alignment.Center) {
            StudioIcon(StudioSymbol.HardDrive, Modifier.size(13.dp), label = "OTG 存储")
        }
        if (selecting) {
            if (chosen) Box(Modifier.fillMaxSize().background(StudioColors.Pine.copy(alpha = .13f))
                .border(2.dp, StudioColors.Pine))
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp).clip(CircleShape)
                .background(if (chosen) StudioColors.Pine else Color.White.copy(alpha = .9f))
                .border(1.dp, if (chosen) StudioColors.Pine else Color(0xFFBAC4BB), CircleShape), contentAlignment = Alignment.Center) {
                if (chosen) StudioIcon(StudioSymbol.Check, Modifier.size(13.dp), tint = Color.White)
            }
        }
    }
}
