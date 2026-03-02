package de.paperdrop.data.db

import androidx.room.*
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
    val documentId: Int?      = null
)

enum class UploadStatus { RUNNING, SUCCESS, FAILED }

// ── DAO ───────────────────────────────────────────────────────────────────────

@Dao
interface UploadDao {

    @Insert
    suspend fun insert(entity: UploadEntity): Long

    @Query("UPDATE uploads SET status = :status, errorMessage = :error WHERE id = :id")
    suspend fun updateStatus(id: Long, status: UploadStatus, error: String? = null)

    @Query("UPDATE uploads SET status = :status, documentId = :documentId WHERE id = :id")
    suspend fun updateStatus(id: Long, status: UploadStatus, documentId: Int)

    @Query("SELECT fileUri FROM uploads WHERE status = 'SUCCESS'")
    suspend fun getAllUris(): List<String>

    @Query("SELECT * FROM uploads ORDER BY timestamp DESC")
    fun getAllUploads(): Flow<List<UploadEntity>>

    @Query("DELETE FROM uploads WHERE timestamp < :before AND status = 'SUCCESS'")
    suspend fun cleanupOlderThan(before: Long)

    @Query("DELETE FROM uploads")
    suspend fun deleteAll()
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(entities = [UploadEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadDao(): UploadDao
}

class Converters {
    @TypeConverter fun fromStatus(s: UploadStatus) = s.name
    @TypeConverter fun toStatus(s: String)         = UploadStatus.valueOf(s)
}
