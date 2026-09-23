package mk.kanta.app.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ContainerDao {

    /**
     * Everything cached inside the viewport. A Flow so the map redraws by itself
     * the moment a refresh lands, with no callback plumbing in the ViewModel.
     */
    @Query(
        """
        SELECT * FROM containers
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
        LIMIT :limit
        """,
    )
    fun observeInBbox(
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        limit: Int = 20_000,
    ): Flow<List<ContainerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(containers: List<ContainerEntity>)

    @Query("SELECT COUNT(*) FROM containers")
    suspend fun count(): Int

    /**
     * Drop rows the server no longer returns for an area we just refreshed, so a
     * container deleted upstream (§4.6 soft delete) actually disappears from the
     * cache instead of lingering forever.
     */
    @Query(
        """
        DELETE FROM containers
        WHERE lat BETWEEN :minLat AND :maxLat
          AND lon BETWEEN :minLon AND :maxLon
          AND id NOT IN (:keepIds)
        """,
    )
    suspend fun pruneBbox(
        minLon: Double,
        minLat: Double,
        maxLon: Double,
        maxLat: Double,
        keepIds: List<String>,
    )

    @Query("DELETE FROM containers")
    suspend fun clear()
}
