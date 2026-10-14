package com.dcimgallery.raw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 首次启动：引导授权本机相册（默认）与外接硬盘（可选）。 */
@Composable
fun PermissionScreen(
    onGrantLocal: () -> Unit,
    onPickDirectory: () -> Unit,
) {
    val openPrivacy = LocalOpenPrivacyPolicy.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StudioIcon(StudioSymbol.Images, modifier = Modifier.padding(bottom = 24.dp), tint = StudioColors.Pine)
        Text("照片图库", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(20.dp))
        Text(
            "默认读取本机相册目录（DCIM / Pictures）。\n\n" +
                "若要浏览外接硬盘中的佳能 CR3 原片，可再授权硬盘目录。" +
                "提示：vivo 需先在 设置 → 其他网络与连接 开启 OTG；" +
                "硬盘需为 exFAT 或 FAT32 格式（NTFS 无法识别）。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onGrantLocal) {
            StudioIcon(StudioSymbol.Images, tint = androidx.compose.ui.graphics.Color.White)
            Text("读取本机照片", Modifier.padding(start = 10.dp))
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onPickDirectory) {
            StudioIcon(StudioSymbol.FolderOpen)
            Text("选择照片目录", Modifier.padding(start = 10.dp))
        }
        Spacer(Modifier.height(12.dp))
        androidx.compose.material3.TextButton(onClick = openPrivacy) { Text("隐私政策") }
    }
}
