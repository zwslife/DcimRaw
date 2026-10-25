package com.dcimgallery.raw.core.raw

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.dcimgallery.raw.data.db.AppDatabase
import com.dcimgallery.raw.data.db.PhotoEntity
import com.dcimgallery.raw.data.db.PhotoFormatEntity

object PhotoFormatProbe {
    private data class Key(val uri: String, val size: Long, val modified: Long)
    private val cache = object : LinkedHashMap<Key, PhotoFileType>(8192, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PhotoFileType>?): Boolean = size > 8192
    }
    private val reads = Semaphore(4)
    private fun cached(key: Key): PhotoFileType? = synchronized(cache) { cache[key] }
    private fun remember(key: Key, type: PhotoFileType) = synchronized(cache) { cache[key] = type }
    private fun PhotoFormatEntity.match(key: Key): PhotoFileType? =
        if (sizeBytes == key.size && lastModified == key.modified)
            runCatching { PhotoFileType.valueOf(format) }.getOrNull() else null

    suspend fun read(context: Context, uri: String, size: Long, modified: Long): PhotoFileType {
        val key = Key(uri, size, modified)
        cached(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val dao = AppDatabase.build(context).photoFormatDao()
            dao.find(uri)?.match(key)?.let { remember(key, it); return@withContext it }
            val type = reads.withPermit { currentCoroutineContext().ensureActive(); readHeader(context, uri) }
            if (type != null) {
                remember(key, type)
                dao.upsertAll(listOf(PhotoFormatEntity(uri, size, modified, type.name)))
            }
            type ?: PhotoFileType.UNKNOWN
        }
    }

    /** Bulk lookup + four bounded file reads; one database write per batch, not per file. */
    suspend fun readBatch(context: Context, photos: List<PhotoEntity>): List<PhotoFileType> = withContext(Dispatchers.IO) {
        require(photos.size <= 400)
        val dao = AppDatabase.build(context).photoFormatDao()
        val saved = dao.findAll(photos.map { it.uri }).associateBy { it.uri }
        val discovered = coroutineScope {
            photos.map { photo -> async {
                val key = Key(photo.uri, photo.sizeBytes, photo.lastModified)
                val persisted = saved[photo.uri]?.match(key)
                val known = cached(key) ?: persisted
                val type = known ?: reads.withPermit {
                    currentCoroutineContext().ensureActive()
                    readHeader(context, photo.uri)
                }
                if (type != null) remember(key, type)
                Pair(type ?: PhotoFileType.UNKNOWN, if (persisted != type && type != null)
                    PhotoFormatEntity(photo.uri, photo.sizeBytes, photo.lastModified, type.name) else null)
            } }.awaitAll()
        }
        val changed = discovered.mapNotNull { it.second }
        if (changed.isNotEmpty()) dao.upsertAll(changed)
        discovered.map { it.first }
    }

    private fun readHeader(context: Context, uri: String): PhotoFileType? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val header = ByteArray(12)
            var count = 0
            while (count < header.size) {
                val n = input.read(header, count, header.size - count)
                if (n <= 0) break
                count += n
            }
            val type = PhotoFileType.fromHeader(header.copyOf(count))
            type.takeIf { count == header.size || it != PhotoFileType.UNKNOWN }
        }
    }.getOrNull()
}
