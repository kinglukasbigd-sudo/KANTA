package mk.kanta.app.core.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A report waiting for a connection (§4.3: "Offline → queue in Room, upload with
 * WorkManager").
 *
 * [id] doubles as the storage file name, which makes a retried upload overwrite
 * the same object instead of leaving orphans behind.
 */
@Entity(tableName = "pending_reports")
data class PendingReportEntity(
    @PrimaryKey val id: String,
    val containerId: String,
    val kind: String,
    val localPhotoPath: String,
    val note: String?,
    val lon: Double,
    val lat: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    /** `queued` until sent; `failed` when the server refused it for good (e.g. too far). */
    val status: String = STATUS_QUEUED,
    val lastError: String? = null,
) {
    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_FAILED = "failed"
    }
}

@Dao
interface PendingReportDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(report: PendingReportEntity)

    @Query("SELECT * FROM pending_reports WHERE status = 'queued' ORDER BY createdAt")
    suspend fun queued(): List<PendingReportEntity>

    @Query("SELECT COUNT(*) FROM pending_reports WHERE status = 'queued'")
    fun observeQueuedCount(): Flow<Int>

    @Query("DELETE FROM pending_reports WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE pending_reports SET attempts = attempts + 1, lastError = :error WHERE id = :id")
    suspend fun recordRetry(id: String, error: String?)

    @Query("UPDATE pending_reports SET status = 'failed', lastError = :error WHERE id = :id")
    suspend fun markFailed(id: String, error: String?)
}
