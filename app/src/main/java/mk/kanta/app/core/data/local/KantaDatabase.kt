package mk.kanta.app.core.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ContainerEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class KantaDatabase : RoomDatabase() {
    abstract fun containerDao(): ContainerDao
}
