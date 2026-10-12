package com.dcimgallery.raw.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 一张被索引的照片（本机相册或外接盘 CR3）。 */
@Entity(tableName = "photos")
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

    @Query("SELECT * FROM photos ORDER BY takenAt DESC, displayName DESC")
    fun observeAll(): Flow<List<PhotoEntity>>

    @Upsert
    suspend fun upsertAll(items: List<PhotoEntity>)

    @Query("DELETE FROM photos WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    @Query("DELETE FROM photos")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM photos")
    suspend fun count(): Int
}

@Database(entities = [PhotoEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun build(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dcimraw.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
