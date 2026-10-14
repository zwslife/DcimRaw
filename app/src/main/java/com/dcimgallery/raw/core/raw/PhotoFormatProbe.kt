package com.dcimgallery.raw.core.raw

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

object PhotoFormatProbe {
    private data class Key(val uri: String, val size: Long, val modified: Long)
    private val cache = ConcurrentHashMap<Key, PhotoFileType>()

    suspend fun read(context: Context, uri: String, size: Long, modified: Long): PhotoFileType {
        val key = Key(uri, size, modified)
        cache[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val type = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                    val header = ByteArray(12)
                    var count = 0
                    while (count < header.size) {
                        val n = input.read(header, count, header.size - count)
                        if (n <= 0) break
                        count += n
                    }
                    PhotoFileType.fromHeader(header.copyOf(count))
                }
            }.getOrNull() ?: PhotoFileType.UNKNOWN
            if (type != PhotoFileType.UNKNOWN) {
                if (cache.size > 512) cache.clear()
                cache[key] = type
            }
            type
        }
    }
}
