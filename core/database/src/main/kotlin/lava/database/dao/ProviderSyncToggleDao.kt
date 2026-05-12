package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.ProviderSyncToggleEntity

@Dao
interface ProviderSyncToggleDao {
    @Query("SELECT * FROM provider_sync_toggle WHERE providerId = :providerId")
    fun observe(providerId: String): Flow<ProviderSyncToggleEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderSyncToggleEntity)
}
