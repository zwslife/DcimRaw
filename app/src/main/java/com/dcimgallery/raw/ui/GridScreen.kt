package com.dcimgallery.raw.ui

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

/** 主界面：按拍摄日期分组的照片网格（本机 + 外接混合，外接带来源角标）。 */
@Composable
fun GridScreen(
    items: List<GridItem>,
    photoCount: Int,
    gridState: LazyGridState,
    scanning: Boolean,
    scanFound: Int,
    driveReadable: Boolean,
    onRefresh: () -> Unit,
    onChangeDir: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("CR3 相册", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = when {
                        scanning -> "正在扫描… 已发现 $scanFound 张"
                        else -> "$photoCount 张"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!driveReadable) {
                Text(
                    "外接盘未就绪",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFE57373),
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
            TextButton(onClick = onRefresh) { Text("刷新") }
            TextButton(onClick = onChangeDir) { Text("换目录") }
        }

        when {
            items.isEmpty() && !scanning -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "没有找到照片\n如需浏览外接硬盘，请连接后点「换目录」授权",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                state = gridState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items.forEach { gridItem ->
                    when (gridItem) {
                        is GridItem.Header -> item(
                            key = "header_${gridItem.date}",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "header",
                        ) {
                            Text(
                                text = gridItem.label,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        is GridItem.PhotoItem -> item(
                            key = gridItem.photo.uri,
                            contentType = "photo",
                        ) {
                            GridCell(
                                photo = gridItem.photo,
                                onClick = { onOpen(gridItem.globalIndex) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GridCell(photo: PhotoEntity, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color(0xFF1A1A1C))
            .clickable(onClick = onClick),
    ) {
        val isExternal = photo.source == "external"
        AsyncImage(
            model = if (isExternal) Cr3Image(Uri.parse(photo.uri)) else Uri.parse(photo.uri),
            contentDescription = photo.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (isExternal) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_sd_card),
                    contentDescription = "外接存储",
                    tint = Color.White,
                    modifier = Modifier.size(11.dp),
                )
            }
        }
    }
}
