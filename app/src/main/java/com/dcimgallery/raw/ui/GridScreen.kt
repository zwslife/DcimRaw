package com.dcimgallery.raw.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dcimgallery.raw.GridItem
import com.dcimgallery.raw.R
import com.dcimgallery.raw.core.coil.Cr3Image
import com.dcimgallery.raw.data.db.PhotoEntity
import com.dcimgallery.raw.core.raw.PhotoFileType
import com.dcimgallery.raw.core.raw.PhotoFormatProbe

/** 白瓷图库：按日期保留分组，第一张照片作为横幅。 */
@Composable
fun GridScreen(
    items: List<GridItem>, photoCount: Int, gridState: LazyGridState,
    scanning: Boolean, scanFound: Int, driveReadable: Boolean,
    onRefresh: () -> Unit, onChangeDir: () -> Unit, onOpen: (Int) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val openPrivacy = LocalOpenPrivacyPolicy.current
    val photos = androidx.compose.runtime.remember(items) { items.filterIsInstance<GridItem.PhotoItem>().map { it.photo } }
    val usb by androidx.compose.runtime.produceState(emptySet<String>(), photos) {
        value = StorageOrigin.usbPhotos(context, photos)
    }
    Column(Modifier.fillMaxSize().background(Color.White).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("照片图库", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, color = StudioColors.Ink)
            RefreshButton(scanning, onRefresh)
            StudioIconButton(StudioSymbol.FolderOpen, "选择目录", onChangeDir)
            StudioIconButton(StudioSymbol.Info, "隐私政策", openPrivacy)
        }
        if (items.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                StudioIcon(StudioSymbol.Images, Modifier.size(44.dp), tint = StudioColors.Muted)
                Text(if (scanning) "正在查找照片…" else "选择目录，开始浏览照片", Modifier.padding(top = 16.dp), color = StudioColors.Muted)
            }
        } else LazyVerticalGrid(columns = GridCells.Fixed(2), state = gridState,
            modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            var indexInGroup = 0
            items.forEach { item ->
                when (item) {
                    is GridItem.Header -> {
                        indexInGroup = 0
                        item(key = "header_${item.date}", span = { GridItemSpan(maxLineSpan) }) {
                            Text(item.label, Modifier.padding(top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = StudioColors.Muted)
                        }
                    }
                    is GridItem.PhotoItem -> {
                        val wide = indexInGroup++ == 0
                        item(key = item.photo.uri, span = { GridItemSpan(if (wide) 2 else 1) }) {
                            GridCell(item.photo, wide, item.photo.uri in usb) { onOpen(item.globalIndex) }
                        }
                    }
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
private fun GridCell(photo: PhotoEntity, wide: Boolean, usb: Boolean, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val fileType by androidx.compose.runtime.produceState(PhotoFileType.UNKNOWN, photo.uri, photo.sizeBytes, photo.lastModified) {
        value = PhotoFormatProbe.read(context, photo.uri, photo.sizeBytes, photo.lastModified)
    }
    Box(Modifier.aspectRatio(if (wide) 1.5f else 1f).clip(RoundedCornerShape(12.dp))
        .background(StudioColors.Canvas).clickable(onClick = onClick)) {
        val raw = photo.source == "external"
        AsyncImage(model = if (raw) Cr3Image(Uri.parse(photo.uri)) else Uri.parse(photo.uri), contentDescription = photo.displayName,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (fileType == PhotoFileType.CR3) Text("RAW", Modifier.align(Alignment.BottomStart).padding(10.dp)
            .clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = .9f)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = StudioColors.Ink)
        if (usb) Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).size(28.dp).clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = .9f)), contentAlignment = Alignment.Center) {
            StudioIcon(StudioSymbol.HardDrive, Modifier.size(18.dp), label = "OTG 存储")
        }
    }
}
