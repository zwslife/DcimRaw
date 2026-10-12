package com.dcimgallery.raw.core.coil

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * CR3 内嵌预览的磁盘缓存。
 *
 * 提取出的全尺寸 JPEG 原始字节直接落盘（不解码重编码，零画质损失），
 * 后续任意尺寸请求都由 Coil 从该文件采样解码。容量超限时按最旧访问时间清理。
 */
object PreviewCache {

    private const val MAX_BYTES = 512L * 1024 * 1024 // 512MB
    private lateinit var dir: File

    fun init(context: Context) {
        dir = File(context.filesDir, "preview_cache")
        dir.mkdirs()
        Thread({ evictIfNeeded() }, "preview-cache-evict").start()
    }

    fun fileFor(key: String): File = File(dir, md5(key) + ".jpg")

    fun store(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        target.setLastModified(System.currentTimeMillis())
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
