package com.dcimgallery.raw.data.scan

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import com.dcimgallery.raw.data.db.PhotoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 本机相册扫描器：经 MediaStore 读取相机目录图片。
 * 覆盖 DCIM（含各品牌 Camera 子目录：佳能/小米/华为/三星/vivo/OPPO 均落于此）
 * 与 Pictures（截图、保存图）。按拍摄时间倒序。
 */
class LocalMediaScanner(private val context: Context) {

    suspend fun scan(onBatch: suspend (List<PhotoEntity>) -> Unit): Int = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA,
        )
        val selection =
            "(${MediaStore.Images.Media.DATA} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?)"
        val selectionArgs = arrayOf(
            "/storage/emulated/0/DCIM/%",
            "/storage/emulated/0/Pictures/%",
        )
        var count = 0
        val batches = ScanBatcher(onBatch)
        val result = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC",
        ) ?: error("无法读取系统相册")
        result.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val modifiedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                val id = cursor.getLong(idCol)
                val path = cursor.getString(dataCol).orEmpty()
                val taken = cursor.getLong(takenCol).takeIf { it > 0 }
                    ?: cursor.getLong(addedCol) * 1000
                batches.add(PhotoEntity(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id,
                    ).toString(),
                    displayName = cursor.getString(nameCol).orEmpty(),
                    sizeBytes = cursor.getLong(sizeCol),
                    lastModified = cursor.getLong(modifiedCol) * 1000,
                    takenAt = taken,
                    folder = path.substringBeforeLast('/').substringAfterLast('/'),
                    source = "local",
                ))
                count++
            }
        }
        batches.flush()
        count
    }
}
