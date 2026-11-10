package com.dcimgallery.raw.core.raw

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import com.dcimgallery.raw.GalleryApp
import com.dcimgallery.raw.core.tone.ToneParams
import com.dcimgallery.raw.core.tone.MaskLayer
import com.dcimgallery.raw.core.tone.MaskRenderCache
import com.dcimgallery.raw.core.cr3.Cr3Parser
import com.dcimgallery.raw.core.cr3.extractFullJpegPreview
import com.dcimgallery.raw.core.geometry.PhotoGeometry
import com.dcimgallery.raw.core.geometry.PhotoGeometryRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * RAW 完整解码调度。
 *
 * 三层缓存：内存 LruCache(2 张) → 磁盘 JPEG q95（DecodedCache）→ LibRaw 全解码。
 * 解码串行执行（单张内存峰值 ~170MB，避免并发 OOM）。
 */
class RawDecodeRepository(private val app: GalleryApp) {

    private val decodeMutex = Mutex()

    /** 最近解码结果（全尺寸约 96MB/张，manifest 已开 largeHeap） */
    private val memCache = object : LruCache<String, Bitmap>(2) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    /** 大图页后台解码（静默失败：失败时保持显示内嵌预览）。 */
    suspend fun decode(uriString: String): Bitmap? {
        memCache.get(uriString)?.let { return it }
        return withContext(Dispatchers.IO) {
            decodeMutex.withLock {
                memCache.get(uriString)?.let { return@withLock it }
                val file = DecodedCache.fileFor(uriString)
                val type = runCatching { readFileType(uriString) }.getOrNull()
                // Ordinary images are decoded from their original bytes, preserving
                // transparency and avoiding a second lossy JPEG cache conversion.
                val bitmap = if ((type == PhotoFileType.CR3 || type == null) && file.exists() && file.length() > 0) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    runCatching { decodeOnce(uriString, type == PhotoFileType.CR3) }.getOrNull()
                        ?.also { if (type == PhotoFileType.CR3) DecodedCache.storeJpeg(file, it) }
                }
                bitmap?.let { memCache.put(uriString, it) }
                bitmap
            }
        }
    }

    private fun readFileType(uriString: String): PhotoFileType {
        val uri = Uri.parse(uriString)
        return app.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(12)
            var count = 0
            while (count < header.size) {
                val n = input.read(header, count, header.size - count)
                if (n <= 0) break
                count += n
            }
            PhotoFileType.fromHeader(header.copyOf(count))
        } ?: throw RuntimeException("无法读取图片")
    }

    private fun decodeOnce(uriString: String, isCr3: Boolean): Bitmap {
        val uri = Uri.parse(uriString)
        if (!isCr3) {
            val decoded = app.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inPreferredColorSpace = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB)
                })
            } ?: throw RuntimeException("图片格式无法解码")
            val matrix = runCatching {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    val exif = ExifInterface(input)
                    Matrix().apply {
                        if (exif.isFlipped) postScale(-1f, 1f)
                        postRotate(exif.rotationDegrees.toFloat())
                    }
                }
            }.getOrNull()
            if (matrix == null || matrix.isIdentity) return decoded
            return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { corrected -> if (corrected !== decoded) decoded.recycle() }
        }
        val pfd = app.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
            ?: throw RuntimeException("无法打开文件描述符")
        pfd.use {
            val fd = it.detachFd() // 所有权移交 native（桥接层负责关闭）
            return RawDecoder.decodeFd(fd, false)
                ?: throw RuntimeException("RAW 解码返回空")
        }
    }

    /** Copy the camera's embedded JPEG bytes directly, preserving its metadata and compression. */
    suspend fun exportEmbeddedJpeg(uriString: String, displayName: String): String? = withContext(Dispatchers.IO) {
        val resolver = app.contentResolver
        var pending: Uri? = null
        try {
            val bytes = resolver.openFileDescriptor(Uri.parse(uriString), "r")?.use { pfd ->
                Cr3Parser.open(pfd).extractFullJpegPreview()
            } ?: return@withContext "RAW 中没有可导出的内嵌 JPEG，或原片无法读取"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, displayName.substringBeforeLast('.') + "_embedded.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CR3相册")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val item = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext "系统相册写入被拒绝"
            pending = item
            val out = resolver.openOutputStream(item) ?: throw IllegalStateException("无法写入相册文件")
            out.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val published = resolver.update(item, ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }, null, null)
                check(published > 0) { "无法发布相册文件" }
            }
            pending = null
            null
        } catch (error: Exception) {
            error.message ?: "内嵌 JPEG 导出失败"
        } finally {
            pending?.let { runCatching { resolver.delete(it, null, null) } }
        }
    }

    /**
     * 导出当前照片为 JPEG 到系统相册（Pictures/CR3相册），应用当前调色参数。
     * 调色矩阵与预览 GPU 渲染共用同一份参数，所见即所得。
     * @return null 表示成功；否则为失败原因（用于界面提示）。
     */
    suspend fun exportJpeg(uriString: String, displayName: String, tone: ToneParams,
        masks: List<MaskLayer> = emptyList(), geometry: PhotoGeometry = PhotoGeometry()): String? =
        withContext(Dispatchers.IO) {
            var pending: Uri? = null
            var colored: Bitmap? = null
            var transformed: Bitmap? = null
            try {
                val bitmap = decode(uriString) ?: return@withContext "图片解码失败"
                val colorBitmap = if (tone.isNeutral && masks.none { it.enabled && !it.tone.isNeutral }) {
                    bitmap
                } else {
                    val data = tone.toRenderData()
                    val dst = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { colored = it }
                    if (!RawDecoder.renderToneFull(bitmap, data.matrix, data.lut, data.hsl, data.effects, dst) ||
                        !MaskRenderCache().apply(dst, masks)) {
                        return@withContext "调色渲染失败"
                    }
                    dst
                }
                val outBitmap = PhotoGeometryRenderer.render(colorBitmap, geometry).also {
                    if (it !== colorBitmap) transformed = it
                }

                val resolver = app.contentResolver
                val fileName = displayName.substringBeforeLast('.') + ".jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CR3相册")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }
                val itemUri = resolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values,
                ) ?: return@withContext "系统相册写入被拒绝"
                pending = itemUri

                val written = resolver.openOutputStream(itemUri)?.use { out ->
                    outBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                } ?: false
                if (!written) {
                    return@withContext "文件写入失败"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    check(resolver.update(itemUri, values, null, null) > 0) { "无法发布相册文件" }
                }
                pending = null
                null
            } catch (e: Exception) {
                e.message ?: "未知错误"
            } finally {
                transformed?.recycle()
                colored?.recycle()
                pending?.let { runCatching { app.contentResolver.delete(it, null, null) } }
            }
        }
}
