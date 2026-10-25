package com.dcimgallery.raw.data.db

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

data class PhotoEntity(val uri: String, val sizeBytes: Long, val lastModified: Long)
data class PhotoFormatEntity(val uri: String, val sizeBytes: Long, val lastModified: Long, val format: String)
class TestFormatDao {
    val rows = ConcurrentHashMap<String, PhotoFormatEntity>()
    suspend fun find(uri: String) = rows[uri]
    suspend fun findAll(uris: List<String>) = uris.mapNotNull(rows::get)
    suspend fun upsertAll(formats: List<PhotoFormatEntity>) { formats.forEach { rows[it.uri] = it } }
}
object AppDatabase {
    val dao = TestFormatDao()
    fun build(context: Context) = this
    fun photoFormatDao() = dao
}
