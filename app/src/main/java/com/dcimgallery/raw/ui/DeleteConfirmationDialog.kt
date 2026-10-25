package com.dcimgallery.raw.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

internal val DeleteRed = Color(0xFFD94C48)

@Composable
internal fun DeleteConfirmationDialog(count: Int, name: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(24.dp)) {
            StudioIcon(StudioSymbol.Trash2, Modifier.size(30.dp), tint = DeleteRed)
            Text(if (count == 1) "删除这张照片？" else "删除 $count 张照片？",
                Modifier.padding(top = 16.dp), color = StudioColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            if (count == 1 && name != null) Text(name, Modifier.padding(top = 10.dp),
                color = StudioColors.Muted, fontSize = 13.sp, maxLines = 2)
            Text("将删除设备上的原片，删除后无法在若图中恢复。系统可能还会请求删除授权。",
                Modifier.padding(top = 12.dp, bottom = 22.dp), color = StudioColors.Muted, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(StudioColors.Canvas).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
                    Text("取消", color = StudioColors.Ink, fontSize = 14.sp)
                }
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(DeleteRed).clickable(onClick = onConfirm), contentAlignment = Alignment.Center) {
                    Text("确认删除", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
internal fun MediaManagementDialog(onAuthorize: () -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Color.White).padding(24.dp)) {
            Text("免去重复系统授权", color = StudioColors.Ink, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Text("在接下来的系统设置中，允许若图管理媒体。授权后，删除系统相册照片无需每次再确认系统弹窗，若图内的删除二次确认仍会保留。\n\n此权限仅用于您主动确认的删除操作，可随时在系统设置中撤销。也可暂不授权，继续逐次确认。",
                Modifier.padding(top = 14.dp, bottom = 22.dp), color = StudioColors.Muted, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(StudioColors.Canvas).clickable(onClick = onSkip), contentAlignment = Alignment.Center) {
                    Text("暂不授权", color = StudioColors.Ink, fontSize = 14.sp)
                }
                Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(StudioColors.Pine).clickable(onClick = onAuthorize), contentAlignment = Alignment.Center) {
                    Text("前往授权", color = Color.White, fontSize = 14.sp)
                }
            }
        }
    }
}
