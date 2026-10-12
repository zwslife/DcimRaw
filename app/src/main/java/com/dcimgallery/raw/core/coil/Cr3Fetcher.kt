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
import com.dcimgallery.raw.core.cr3.Cr3Parser
import com.dcimgallery.raw.core.cr3.extractFullJpegPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/** Coil 数据模型：一张待展示的 CR3 照片。 */
data class Cr3Image(val uri: Uri)

/**
 * Coil Fetcher：把 CR3 文件的内嵌 JPEG 预览变成可解码的图片来源。
 * 首次访问执行真实提取并写入磁盘缓存，之后直接命中缓存文件。
 */
class Cr3Fetcher(
    private val context: Context,
    private val data: Cr3Image,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val cacheFile = PreviewCache.fileFor(data.uri.toString())
        if (!cacheFile.exists() || cacheFile.length() == 0L) {
            val bytes = withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(data.uri, "r")?.use { pfd ->
                    Cr3Parser.open(pfd).extractFullJpegPreview()
                }
            } ?: throw IllegalStateException("无法从 CR3 提取内嵌预览: ${data.uri}")
            withContext(Dispatchers.IO) { PreviewCache.store(cacheFile, bytes) }
        }
        return SourceResult(
            source = ImageSource(cacheFile.toOkioPath(), fileSystem = okio.FileSystem.SYSTEM),
            mimeType = "image/jpeg",
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Cr3Image> {
        override fun create(data: Cr3Image, options: Options, imageLoader: ImageLoader): Fetcher =
            Cr3Fetcher(context, data)
    }
}
