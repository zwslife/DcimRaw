package com.dcimgallery.raw.data.repo

import android.net.Uri
import androidx.room.withTransaction
import com.dcimgallery.raw.GalleryApp
import com.dcimgallery.raw.data.db.PhotoEntity
import com.dcimgallery.raw.data.scan.LocalMediaScanner
import com.dcimgallery.raw.data.scan.MediaScanner
import kotlinx.coroutines.flow.Flow

/** 照片索引仓库：合并本机相册（MediaStore）与外接盘 CR3（SAF）两个来源。 */
class PhotoRepository(private val app: GalleryApp) {

    private val dao get() = app.db.photoDao()
    private val externalScanner = MediaScanner(app)
    private val localScanner = LocalMediaScanner(app)

    val photos: Flow<List<PhotoEntity>> = dao.observeAll()

    /**
     * 全量重建索引（事务性清表+写入）。
     * @param treeUri 外接盘目录（null 表示未授权）
     * @param includeLocal 是否包含本机相册
     */
    suspend fun rescan(
        treeUri: Uri?,
        includeLocal: Boolean,
        onProgress: (found: Int) -> Unit = {},
    ): Int {
        val local = if (includeLocal) localScanner.scan() else emptyList()
        val external = if (treeUri != null) {
            externalScanner.scan(treeUri) { found -> onProgress(local.size + found) }
        } else {
            emptyList()
        }
        val entities = local + external.map {
            PhotoEntity(
                uri = it.uri.toString(),
                displayName = it.name,
                sizeBytes = it.size,
                lastModified = it.lastModified,
                takenAt = it.lastModified,
                folder = it.folder,
                source = "external",
            )
        }
        app.db.withTransaction {
            dao.clear()
            dao.upsertAll(entities)
        }
        return entities.size
    }
}
