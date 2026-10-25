package com.dcimgallery.raw.core.raw

import android.content.Context
import android.content.TestResolver
import com.dcimgallery.raw.data.db.AppDatabase
import com.dcimgallery.raw.data.db.PhotoEntity
import kotlinx.coroutines.*

private fun clearMemory() {
    val field = PhotoFormatProbe::class.java.getDeclaredField("cache").apply { isAccessible = true }
    val cache = field.get(PhotoFormatProbe) as MutableMap<*, *>
    synchronized(cache) { cache.clear() }
}

fun main() = runBlocking {
    val resolver = TestResolver()
    val context = Context(resolver)
    val photos = List(128) { PhotoEntity("photo-$it.CR3", 100, 1) }
    val headers = listOf(byteArrayOf(-1, -40, -1), byteArrayOf(0, 0, 0, 16) + "ftypcrx ".toByteArray(), ByteArray(12))
    photos.forEachIndexed { i, photo -> resolver.files[photo.uri] = headers[i % 3] }
    val first = PhotoFormatProbe.readBatch(context, photos)
    check(first == photos.indices.map { PhotoFileType.entries[it % 3] })
    check(resolver.opens.get() == 128 && resolver.peak.get() <= 4)
    check(AppDatabase.dao.rows.size == 128) { "JPEG and UNKNOWN headers must also be cached" }
    clearMemory() // Simulate a process restart, retaining the on-disk DAO boundary.
    check(PhotoFormatProbe.readBatch(context, photos) == first && resolver.opens.get() == 128)
    check(PhotoFormatProbe.read(context, photos[0].uri, 101, 2) == PhotoFileType.JPEG)
    check(resolver.opens.get() == 129) { "Changed file metadata must invalidate classification" }
    val missing = PhotoEntity("temporarily-offline.CR3", 100, 1)
    check(PhotoFormatProbe.read(context, missing.uri, 100, 1) == PhotoFileType.UNKNOWN)
    check(!AppDatabase.dao.rows.containsKey(missing.uri))
    resolver.files[missing.uri] = headers[1]
    check(PhotoFormatProbe.read(context, missing.uri, 100, 1) == PhotoFileType.CR3)
    resolver.files["not-ready"] = ByteArray(0)
    check(PhotoFormatProbe.read(context, "not-ready", 1, 1) == PhotoFileType.UNKNOWN)
    check(!AppDatabase.dao.rows.containsKey("not-ready"))
    resolver.files["not-ready"] = headers[1]
    check(PhotoFormatProbe.read(context, "not-ready", 1, 1) == PhotoFileType.CR3)

    // Cancel after all headers were read but before a batch could be persisted.
    val pending = List(32) { PhotoEntity("pending-$it", 1, 1) }
    pending.forEach { resolver.files[it.uri] = headers[1] }
    resolver.blockedUri = pending.last().uri
    val job = launch { PhotoFormatProbe.readBatch(context, pending) }
    withContext(Dispatchers.IO) { resolver.entered.await() }
    job.cancel(); resolver.release.countDown(); job.join()
    check(pending.none { AppDatabase.dao.rows.containsKey(it.uri) })
    resolver.blockedUri = null
    PhotoFormatProbe.readBatch(context, pending)
    check(pending.all { AppDatabase.dao.rows[it.uri]?.format == "CR3" }) {
        "RAM hits from a cancelled batch must still be flushed to persistent cache"
    }
    println("PASS: actual header probe; max 4 reads; restart performs no file reads; changed metadata invalidates; unavailable files retry; cancelled-batch RAM hits persist on retry")
}
