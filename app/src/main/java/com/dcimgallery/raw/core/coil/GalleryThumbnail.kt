package com.dcimgallery.raw.core.coil

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.util.Size
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import coil.decode.DataSource
import coil.disk.DiskCache
import coil.fetch.DrawableResult
import coil.fetch.Fetcher
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.Options
import coil.size.Precision
import coil.size.Scale
import com.dcimgallery.raw.core.cr3.Cr3Parser
import com.dcimgallery.raw.core.raw.PhotoFileType
import com.dcimgallery.raw.core.raw.PhotoFormatProbe
import com.dcimgallery.raw.data.db.PhotoEntity
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

internal fun PhotoEntity.hasRawName() = displayName.endsWith(".cr3", ignoreCase = true)

internal data class GalleryThumbnail(val photo: PhotoEntity, val edge: Int) {
    val cacheKey = "grid:v1:${photo.uri}|${photo.sizeBytes}|${photo.lastModified}|$edge"

    fun request(context: Context) = ImageRequest.Builder(context)
        .data(this)
        .size(edge)
        .scale(Scale.FILL)
        .memoryCacheKey(cacheKey)
        .placeholderMemoryCacheKey(cacheKey)
        .crossfade(false)
        .build()

    companion object {
        private val sizes = intArrayOf(128, 192, 256, 320, 384, 512, 640, 768)

        fun bucket(pixels: Int) = sizes.firstOrNull { it >= pixels } ?: sizes.last()

        @OptIn(ExperimentalCoilApi::class)
        fun removeFrom(cache: DiskCache, photo: PhotoEntity) {
            sizes.forEach { cache.remove(GalleryThumbnail(photo, it).cacheKey) }
        }
    }
}

/** Grid-sized assets use a separate cache from the full-screen camera previews. */
@OptIn(ExperimentalCoilApi::class)
internal class GalleryThumbnailFetcher private constructor(
    private val context: Context,
    private val model: GalleryThumbnail,
    private val loader: ImageLoader,
    private val cache: DiskCache,
) : Fetcher {
    private val photo = model.photo
    private val uri = Uri.parse(photo.uri)
    private val raw = Cr3Image(uri, photo.sizeBytes, photo.lastModified)

    override suspend fun fetch(): DrawableResult {
        val key = model.cacheKey
        val lock = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
        return lock.withLock {
            readCached()?.let { return@withLock result(it, DataSource.DISK) }
            decodeSlots.withPermit {
                if (Build.VERSION.SDK_INT >= 29 && photo.source == "local" && !photo.hasRawName()) {
                    readSystemThumbnail()?.let { return@withPermit result(square(it), DataSource.DISK) }
                }
                val bitmap = decodeFallback() ?: throw IOException("无法读取照片缩略图")
                currentCoroutineContext().ensureActive()
                val square = square(bitmap)
                writeCached(square)
                result(square, DataSource.DISK)
            }
        }
    }

    @androidx.annotation.RequiresApi(29)
    private suspend fun readSystemThumbnail(): Bitmap? = try {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val size = Size(model.edge, model.edge)
                continuation.resumeWith(Result.success(context.contentResolver.loadThumbnail(uri, size, signal)))
            } catch (error: Exception) {
                continuation.resumeWithException(error)
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: UnsupportedOperationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private suspend fun decodeFallback(): Bitmap? {
        if (photo.hasRawName()) {
            val preview = rawSource()
            decode(preview)?.let { return it }
            if (preview is ByteArray) decode(raw)?.let { return it }
            return if (preview != uri) decode(uri) else null
        }
        decode(uri)?.let { return it }
        // Files whose extension hides a CR3 still get a thumbnail.
        val type = PhotoFormatProbe.read(context, photo.uri, photo.sizeBytes, photo.lastModified)
        if (type != PhotoFileType.CR3) return null
        val preview = rawSource()
        return decode(preview) ?: if (preview is ByteArray) decode(raw) else null
    }

    private fun rawSource(): Any {
        val fullPreview = PreviewCache.fileFor(raw.cacheKey)
        if (fullPreview.isFile && fullPreview.length() > 0) return fullPreview
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                val parser = Cr3Parser.open(descriptor)
                if (parser.isStandaloneJpeg()) uri else parser.readGridPreview(model.edge) ?: raw
            } ?: raw
        } catch (_: IOException) {
            raw
        } catch (_: SecurityException) {
            raw
        }
    }

    private suspend fun decode(source: Any): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(source)
            .size(model.edge)
            .scale(Scale.FILL)
            .precision(Precision.EXACT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
        return (loader.execute(request).drawable as? BitmapDrawable)?.bitmap
    }

    private fun result(bitmap: Bitmap, source: DataSource) =
        DrawableResult(BitmapDrawable(context.resources, bitmap), isSampled = true, dataSource = source)

    private fun square(bitmap: Bitmap): Bitmap =
        ThumbnailUtils.extractThumbnail(bitmap, model.edge, model.edge)
            ?: throw IOException("无法生成照片缩略图")

    private fun readCached(): Bitmap? = try {
        cache.openSnapshot(model.cacheKey)?.use { BitmapFactory.decodeFile(it.data.toString()) }
    } catch (_: IOException) {
        null
    }

    private fun writeCached(bitmap: Bitmap) {
        try {
            val editor = cache.openEditor(model.cacheKey) ?: return
            var committed = false
            try {
                val format = if (bitmap.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                val saved = editor.data.toFile().outputStream().use { bitmap.compress(format, 86, it) }
                if (saved) {
                    editor.metadata.toFile().writeText("1")
                    editor.commit()
                    committed = true
                }
            } finally {
                if (!committed) editor.abort()
            }
        } catch (_: IOException) {
            // Cache writes can fail when storage is full; still display the decoded tile.
        }
    }

    class Factory(private val context: Context, private val cache: () -> DiskCache) : Fetcher.Factory<GalleryThumbnail> {
        override fun create(data: GalleryThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            GalleryThumbnailFetcher(context, data, imageLoader, cache())
    }

    private companion object {
        val locks = Array(64) { Mutex() }
        val decodeSlots = Semaphore(4)
    }
}
