package de.paperdrop.data.db

import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ── Entity ────────────────────────────────────────────────────────────────────

@Entity(tableName = "uploads")
data class UploadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fileName: String,
    val fileUri: String,
    val status: UploadStatus,
    val timestamp: Long,
    val errorMessage: String? = null,
    val documentId: Int?      = null,
    val isDuplicate: Boolean  = false,
    val taskId: String?       = null
)

enum class UploadStatus { RUNNING, SUCCESS, FAILED }

// ── DAO ───────────────────────────────────────────────────────────────────────

@Dao
interface UploadDao {

    @Insert
    suspend fun insert(entity: UploadEntity): Long

    @Query("UPDATE uploads SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: UploadStatus, error: String? = null)

    @Query("UPDATE uploads SET status = :status, documentId = :documentId, isDuplicate = :isDuplicate WHERE id = :id")
    suspend fun updateStatus(id: Long, status: UploadStatus, documentId: Int, isDuplicate: Boolean = false)

    @Query("UPDATE uploads SET taskId = :taskId WHERE id = :id")
    suspend fun updateTaskId(id: Long, taskId: String)

    @Query("SELECT * FROM uploads WHERE fileUri = :uri ORDER BY id DESC LIMIT 1")
    suspend fun getByUri(uri: String): UploadEntity?

    @Query("SELECT fileUri FROM uploads")
    suspend fun getAllUris(): List<String>

    @Query("SELECT * FROM uploads ORDER BY timestamp DESC")
    fun getAllUploads(): Flow<List<UploadEntity>>

    @Query("DELETE FROM uploads WHERE timestamp < :before AND status = 'SUCCESS'")
    suspend fun cleanupOlderThan(before: Long)

    @Query("DELETE FROM uploads")
    suspend fun deleteAll()
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(entities = [UploadEntity::class], version = 3, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE uploads ADD COLUMN taskId TEXT")
    }
}

class Converters {
    @TypeConverter fun fromStatus(s: UploadStatus) = s.name
    @TypeConverter fun toStatus(s: String)         = UploadStatus.valueOf(s)
}
