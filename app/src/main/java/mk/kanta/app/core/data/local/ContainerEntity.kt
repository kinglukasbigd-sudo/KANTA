package mk.kanta.app.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A container cached for the map (§2 "Local storage: Room (cache …)").
 *
 * Spec §8 wants the map interactive in under 2 s from cold. Waiting for the
 * network before drawing anything cannot meet that, so the last-seen containers
 * are drawn immediately and refreshed underneath.
 *
 * Indexed on lat/lon because every read is a bounding-box query.
 */
@Entity(
    tableName = "containers",
    indices = [Index(value = ["lat", "lon"])],
)
data class ContainerEntity(
    @PrimaryKey val id: String,
    val code: String,
    val kind: String,
    val category: String,
    val status: String,
    val verified: Boolean,
    val lon: Double,
    val lat: Double,
    /** When this row was last refreshed from the server. */
    val cachedAt: Long = System.currentTimeMillis(),
)
