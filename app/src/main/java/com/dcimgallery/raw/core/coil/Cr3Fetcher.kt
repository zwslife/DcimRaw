package com.dcimgallery.raw.core.coil

import android.content.Context
import android.net.Uri
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.key.Keyer
import com.dcimgallery.raw.core.cr3.Cr3Parser
import com.dcimgallery.raw.core.cr3.extractFullJpegPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.Path.Companion.toOkioPath

/** Coil 数据模型：一张待展示的 CR3 照片。 */
data class Cr3Image(val uri: Uri, val sizeBytes: Long = 0, val lastModified: Long = 0) {
    val cacheKey: String get() = "$uri|$sizeBytes|$lastModified"
}

class Cr3ImageKeyer : Keyer<Cr3Image> {
    override fun key(data: Cr3Image, options: Options): String = "cr3-preview:${data.cacheKey}"
}

/**
 * Coil Fetcher：把 CR3 文件的内嵌 JPEG 预览变成可解码的图片来源。
 * 首次访问执行真实提取并写入磁盘缓存，之后直接命中缓存文件。
 */
class Cr3Fetcher(
    private val context: Context,
    private val data: Cr3Image,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val cacheFile = PreviewCache.fileFor(data.cacheKey)
        withContext(Dispatchers.IO) {
            locks[(data.cacheKey.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
                if (!cacheFile.exists() || cacheFile.length() == 0L) slots.withPermit {
                    currentCoroutineContext().ensureActive()
                    val bytes = context.contentResolver.openFileDescriptor(data.uri, "r")?.use { pfd ->
                        Cr3Parser.open(pfd).extractFullJpegPreview()
                    } ?: error("无法从 CR3 提取内嵌预览: ${data.uri}")
                    PreviewCache.store(cacheFile, bytes)
                }
            }
        }
        return SourceResult(
            source = ImageSource(cacheFile.toOkioPath(), fileSystem = okio.FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    private companion object {
        val slots = Semaphore(2)
        val locks = Array(32) { Mutex() }
    }

    class Factory(private val context: Context) : Fetcher.Factory<Cr3Image> {
        override fun create(data: Cr3Image, options: Options, imageLoader: ImageLoader): Fetcher =
            Cr3Fetcher(context, data)
    }
}
