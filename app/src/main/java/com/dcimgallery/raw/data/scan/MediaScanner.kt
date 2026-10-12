package com.dcimgallery.raw.data.scan

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 外接盘目录树扫描器：递归遍历 SAF DocumentFile，收集所有 CR3 文件。
 * 纯增量友好：结果交给仓库层与 Room 比对 upsert。
 */
class MediaScanner(private val context: Context) {

    data class Found(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val folder: String,
    )

    suspend fun scan(treeUri: Uri, onProgress: (found: Int) -> Unit = {}): List<Found> =
        withContext(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri)
                ?: return@withContext emptyList()
            val out = ArrayList<Found>(1024)
            val stack = ArrayList<Pair<DocumentFile, String>>()
            stack.add(root to "")
            while (stack.isNotEmpty()) {
                val (dir, path) = stack.removeAt(stack.size - 1)
                val children = try {
                    dir.listFiles()
                } catch (e: Exception) {
                    continue
                }
                for (doc in children) {
                    val name = doc.name ?: continue
                    if (doc.isDirectory) {
                        stack.add(doc to if (path.isEmpty()) name else "$path/$name")
                        continue
                    }
                    if (name.endsWith(".cr3", ignoreCase = true)) {
                        out += Found(
                            uri = doc.uri,
                            name = name,
                            size = doc.length(),
                            lastModified = doc.lastModified(),
                            folder = path,
                        )
                        if (out.size % 50 == 0) onProgress(out.size)
                    }
                }
            }
            out
        }
}
