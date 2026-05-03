package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.ProviderConfigEntity

@Dao
interface ProviderConfigDao {

    @Query("SELECT * FROM provider_configs WHERE provider_id = :providerId")
    suspend fun load(providerId: String): ProviderConfigEntity?

    @Query("SELECT * FROM provider_configs")
    fun observeAll(): Flow<List<ProviderConfigEntity>>

    @Query("SELECT * FROM provider_configs WHERE provider_id = :providerId")
    fun observe(providerId: String): Flow<ProviderConfigEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderConfigEntity)

    @Query("DELETE FROM provider_configs WHERE provider_id = :providerId")
    suspend fun delete(providerId: String)
}
