package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.ProviderCredentialsEntity

@Dao
interface ProviderCredentialsDao {

    @Query("SELECT * FROM provider_credentials WHERE provider_id = :providerId")
    suspend fun load(providerId: String): ProviderCredentialsEntity?

    @Query("SELECT * FROM provider_credentials")
    fun observeAll(): Flow<List<ProviderCredentialsEntity>>

    @Query("SELECT * FROM provider_credentials WHERE provider_id = :providerId")
    fun observe(providerId: String): Flow<ProviderCredentialsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderCredentialsEntity)

    @Query("DELETE FROM provider_credentials WHERE provider_id = :providerId")
    suspend fun delete(providerId: String)
}
