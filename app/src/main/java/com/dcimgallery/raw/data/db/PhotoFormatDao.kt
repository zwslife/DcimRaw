package com.dcimgallery.raw.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** Header classification is cached separately, so it never invalidates the photo-list Flow. */
@Entity(tableName = "photo_formats")
data class PhotoFormatEntity(@PrimaryKey val uri: String, val sizeBytes: Long,
    val lastModified: Long, val format: String)

@Dao
interface PhotoFormatDao {
    @Query("SELECT * FROM photo_formats WHERE uri = :uri")
    suspend fun find(uri: String): PhotoFormatEntity?

    @Query("SELECT * FROM photo_formats WHERE uri IN (:uris)")
    suspend fun findAll(uris: List<String>): List<PhotoFormatEntity>

    @Upsert
    suspend fun upsertAll(formats: List<PhotoFormatEntity>)

    @Query("DELETE FROM photo_formats WHERE uri NOT IN (SELECT uri FROM photos)")
    suspend fun deleteOrphans()
}
