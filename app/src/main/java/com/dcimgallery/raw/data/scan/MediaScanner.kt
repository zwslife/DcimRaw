package com.dcimgallery.raw.data.scan

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 授权目录扫描器：每个目录用一次 SAF 查询批量取得属性，分批交给索引仓库。
 */
class MediaScanner(private val context: Context) {

    data class Found(
        val uri: Uri,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val folder: String,
    )

    suspend fun scan(treeUri: Uri, onBatch: suspend (List<Found>) -> Unit): Int =
        withContext(Dispatchers.IO) {
            var count = 0
            val batches = ScanBatcher(onBatch)
            val stack = ArrayList<Pair<String, String>>()
            val visited = HashSet<String>()
            stack.add(DocumentsContract.getTreeDocumentId(treeUri) to "")
            val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (stack.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val (documentId, path) = stack.removeAt(stack.size - 1)
                if (!visited.add(documentId)) continue
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
                // A single provider query per directory supplies every required property.
                // An unavailable subtree fails the scan, so reconciliation never deletes unseen files.
                val cursor = context.contentResolver.query(children, projection, null, null, null)
                    ?: error("无法读取授权目录")
                cursor.use {
                    val idColumn = it.getColumnIndexOrThrow(projection[0])
                    val nameColumn = it.getColumnIndexOrThrow(projection[1])
                    val mimeColumn = it.getColumnIndexOrThrow(projection[2])
                    val sizeColumn = it.getColumnIndexOrThrow(projection[3])
                    val modifiedColumn = it.getColumnIndexOrThrow(projection[4])
                    while (it.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        val id = it.getString(idColumn) ?: continue
                        val name = it.getString(nameColumn) ?: continue
                        if (it.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            stack.add(id to if (path.isEmpty()) name else "$path/$name")
                        } else if (name.endsWith(".cr3", ignoreCase = true)) {
                            batches.add(Found(DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name, it.getLong(sizeColumn), it.getLong(modifiedColumn), path))
                            count++
                        }
                    }
                    check(!it.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                        "目录仍在加载，请稍后刷新"
                    }
                }
            }
            batches.flush()
            count
        }
}
