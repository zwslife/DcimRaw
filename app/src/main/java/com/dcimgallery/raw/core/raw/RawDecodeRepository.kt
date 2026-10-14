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
                val bitmap = if (file.exists() && file.length() > 0) {
                    BitmapFactory.decodeFile(file.absolutePath)
                } else {
                    runCatching { decodeOnce(uriString) }.getOrNull()
                        ?.also { DecodedCache.storeJpeg(file, it) }
                }
                bitmap?.let { memCache.put(uriString, it) }
                bitmap
            }
        }
    }

    private fun decodeOnce(uriString: String): Bitmap {
        val pfd = app.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
            ?: throw RuntimeException("无法打开文件描述符")
        pfd.use {
            val jpeg = Cr3Parser.open(it).readStandaloneJpeg()
            if (jpeg != null) {
                val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                    ?: throw RuntimeException("JPEG 解码失败")
                val exif = ExifInterface(jpeg.inputStream())
                val matrix = Matrix().apply {
                    if (exif.isFlipped) postScale(-1f, 1f)
                    postRotate(exif.rotationDegrees.toFloat())
                }
                if (matrix.isIdentity) return decoded
                return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                    .also { corrected -> if (corrected !== decoded) decoded.recycle() }
            }
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
    suspend fun exportJpeg(uriString: String, displayName: String, tone: ToneParams, masks: List<MaskLayer> = emptyList()): String? =
        withContext(Dispatchers.IO) {
            try {
                val bitmap = decode(uriString) ?: return@withContext "RAW 解码失败"
                val outBitmap = if (tone.isNeutral && masks.none { it.enabled && !it.tone.isNeutral }) {
                    bitmap
                } else {
                    val data = tone.toRenderData()
                    val dst = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
                    if (!RawDecoder.renderToneFull(bitmap, data.matrix, data.lut, data.hsl, data.effects, dst) ||
                        !MaskRenderCache().apply(dst, masks)) {
                        dst.recycle()
                        return@withContext "调色渲染失败"
                    }
                    dst
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

                val written = resolver.openOutputStream(itemUri)?.use { out ->
                    outBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                } ?: false
                if (outBitmap !== bitmap) outBitmap.recycle()
                if (!written) {
                    resolver.delete(itemUri, null, null)
                    return@withContext "文件写入失败"
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(itemUri, values, null, null)
                }
                null
            } catch (e: Exception) {
                e.message ?: "未知错误"
            }
        }
}
