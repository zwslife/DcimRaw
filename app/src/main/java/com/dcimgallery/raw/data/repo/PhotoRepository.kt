package com.dcimgallery.raw.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.dcimgallery.raw.GalleryApp
import com.dcimgallery.raw.data.db.PhotoEntity
import com.dcimgallery.raw.data.scan.LocalMediaScanner
import com.dcimgallery.raw.data.scan.MediaScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 照片索引仓库：合并本机相册（MediaStore）与外接盘 CR3（SAF）两个来源。 */
class PhotoRepository(private val app: GalleryApp) {

    private val dao get() = app.db.photoDao()
    private val externalScanner = MediaScanner(app)
    private val localScanner = LocalMediaScanner(app)

    val photos: Flow<List<PhotoEntity>> = dao.observeAll()

    /**
     * 分批增量更新；仅在两个来源均成功遍历后清理消失的记录。
     * @param treeUri 外接盘目录（null 表示未授权）
     * @param includeLocal 是否包含本机相册
     */
    suspend fun rescan(
        treeUri: Uri?,
        includeLocal: Boolean,
        onProgress: (found: Int) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        updateIndex(dao.getAll(), PhotoEntity::uri, dao::upsertAll, prune = { missing ->
            app.db.withTransaction {
                missing.chunked(400).forEach { dao.deleteByUris(it) }
                app.db.photoFormatDao().deleteOrphans()
            }
        }, onProgress = onProgress) { publish ->
            if (includeLocal) localScanner.scan(publish)
            if (treeUri != null) externalScanner.scan(treeUri) { batch ->
                publish(batch.map { PhotoEntity(it.uri.toString(), it.name, it.size,
                    it.lastModified, it.lastModified, it.folder, "external") })
            }
        }
    }
}
