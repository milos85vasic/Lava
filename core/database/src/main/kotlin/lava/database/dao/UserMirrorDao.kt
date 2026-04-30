package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.UserMirrorEntity

@Dao
interface UserMirrorDao {

    @Query("SELECT * FROM tracker_mirror_user WHERE tracker_id = :trackerId ORDER BY priority ASC")
    suspend fun loadAll(trackerId: String): List<UserMirrorEntity>

    @Query("SELECT * FROM tracker_mirror_user WHERE tracker_id = :trackerId ORDER BY priority ASC")
    fun observe(trackerId: String): Flow<List<UserMirrorEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserMirrorEntity)

    @Query("DELETE FROM tracker_mirror_user WHERE tracker_id = :trackerId AND url = :url")
    suspend fun delete(trackerId: String, url: String)

    @Query("DELETE FROM tracker_mirror_user WHERE tracker_id = :trackerId")
    suspend fun clear(trackerId: String)
}
