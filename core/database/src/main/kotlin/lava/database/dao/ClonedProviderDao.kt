package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.ClonedProviderEntity

@Dao
interface ClonedProviderDao {
    @Query("SELECT * FROM cloned_provider")
    fun observeAll(): Flow<List<ClonedProviderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ClonedProviderEntity)

    @Query("DELETE FROM cloned_provider WHERE syntheticId = :id")
    suspend fun delete(id: String)
}
