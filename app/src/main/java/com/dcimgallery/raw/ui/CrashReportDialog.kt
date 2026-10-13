package com.dcimgallery.raw.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dcimgallery.raw.CrashRecord

@Composable
fun CrashReportDialog() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashRecord.read(context)) }
    var copied by remember { mutableStateOf(false) }
    val text = report ?: return
    fun dismiss() { CrashRecord.clear(context); report = null }
    AlertDialog(onDismissRequest = ::dismiss,
        title = { Text("上次运行发生错误") },
        text = { Column {
            Text("可复制下面的错误信息，用于定位闪退原因。记录仅保存在本机。")
            SelectionContainer {
                Text(text, Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton = { TextButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("DcimRaw 崩溃记录", text))
            copied = true
        }) { Text(if (copied) "已复制" else "复制错误信息") } },
        dismissButton = { TextButton(onClick = ::dismiss) { Text("关闭") } })
}
