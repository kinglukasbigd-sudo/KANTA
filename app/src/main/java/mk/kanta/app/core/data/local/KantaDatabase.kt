package mk.kanta.app.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Version history:
 *  1 — container cache only.
 *  2 — adds pending_reports, the offline queue.
 *
 * From version 2 on, this database holds reports the user has not managed to
 * send yet. Those are the user's work, not a cache, so every future schema change
 * needs a real Migration — see DatabaseModule.
 */
@Database(
    entities = [ContainerEntity::class, PendingReportEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class KantaDatabase : RoomDatabase() {
    abstract fun containerDao(): ContainerDao

    abstract fun pendingReportDao(): PendingReportDao
}
