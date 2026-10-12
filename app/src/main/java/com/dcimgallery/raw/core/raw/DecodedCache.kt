package com.dcimgallery.raw.core.raw

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * RAW 完整解码结果的磁盘缓存（JPEG q95）。
 * 解码一张全尺寸要几秒，落盘后二次打开与导出都是毫秒级。
 */
object DecodedCache {

    private const val MAX_BYTES = 1024L * 1024 * 1024 // 1GB
    private const val JPEG_QUALITY = 95
    private lateinit var dir: File

    fun init(context: Context) {
        // v2：v0.5.2 修复红蓝通道互换，旧缓存（蓝色）整目录弃用
        File(context.filesDir, "decoded_cache").deleteRecursively()
        dir = File(context.filesDir, "decoded_cache_v2")
        dir.mkdirs()
        Thread({ evictIfNeeded() }, "decoded-cache-evict").start()
    }

    fun fileFor(key: String): File = File(dir, md5(key) + ".jpg")

    /** 把解码 Bitmap 以 JPEG q95 写入缓存（导出系统相册也复用该文件）。 */
    fun storeJpeg(target: File, bitmap: Bitmap): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) return false
            }
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            target.setLastModified(System.currentTimeMillis())
            true
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }

    fun clear() {
        if (::dir.isInitialized) dir.listFiles()?.forEach { it.delete() }
    }

    private fun evictIfNeeded() {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= MAX_BYTES) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    private fun md5(s: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
