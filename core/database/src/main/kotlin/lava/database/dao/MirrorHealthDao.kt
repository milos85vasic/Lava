package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.MirrorHealthEntity

@Dao
interface MirrorHealthDao {

    @Query("SELECT * FROM tracker_mirror_health WHERE tracker_id = :trackerId")
    suspend fun loadAll(trackerId: String): List<MirrorHealthEntity>

    @Query("SELECT * FROM tracker_mirror_health WHERE tracker_id = :trackerId")
    fun observe(trackerId: String): Flow<List<MirrorHealthEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MirrorHealthEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<MirrorHealthEntity>)

    @Query("DELETE FROM tracker_mirror_health WHERE tracker_id = :trackerId")
    suspend fun clear(trackerId: String)
}
