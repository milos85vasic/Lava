package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.SyncOutboxEntity

@Dao
interface SyncOutboxDao {
    @Insert
    suspend fun enqueue(entity: SyncOutboxEntity): Long

    @Query("SELECT * FROM sync_outbox ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<SyncOutboxEntity>>

    @Query("DELETE FROM sync_outbox WHERE id = :id")
    suspend fun ack(id: Long)
}
