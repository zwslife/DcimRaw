package com.dcimgallery.raw.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 一张被索引的照片（本机相册或外接盘 CR3）。 */
@Entity(tableName = "photos", indices = [Index(value = ["takenAt", "displayName", "uri"])])
data class PhotoEntity(
    /** 本机为 MediaStore content uri，外接为 SAF document uri */
    @PrimaryKey val uri: String,
    val displayName: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** 拍摄时间（本机取 DATE_TAKEN，外接暂用文件修改时间） */
    val takenAt: Long,
    /** 所在目录名 */
    val folder: String,
    /** 来源："local"（本机存储）| "external"（外接存储） */
    val source: String,
)

@Dao
interface PhotoDao {

    @Query("SELECT * FROM photos ORDER BY takenAt DESC, displayName DESC, uri DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos")
    suspend fun getAll(): List<PhotoEntity>

    @Upsert
    suspend fun upsertAll(items: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM photos WHERE uri IN (:uris)")
    suspend fun deleteByUris(uris: List<String>)

    @Query("DELETE FROM photos")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int
}

@Database(entities = [PhotoEntity::class, PhotoFormatEntity::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao
    abstract fun photoFormatDao(): PhotoFormatDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_takenAt_displayName_uri ON photos(takenAt, displayName, uri)")
                db.execSQL("CREATE TABLE IF NOT EXISTS photo_formats (uri TEXT NOT NULL PRIMARY KEY, sizeBytes INTEGER NOT NULL, lastModified INTEGER NOT NULL, format TEXT NOT NULL)")
            }
        }

        fun build(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dcimraw.db",
                ).addMigrations(MIGRATION_2_3).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
