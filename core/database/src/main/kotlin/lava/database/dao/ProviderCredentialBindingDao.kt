package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.ProviderCredentialBindingEntity

@Dao
interface ProviderCredentialBindingDao {
    @Query("SELECT * FROM provider_credential_binding WHERE providerId = :providerId")
    fun observe(providerId: String): Flow<ProviderCredentialBindingEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProviderCredentialBindingEntity)

    @Query("DELETE FROM provider_credential_binding WHERE providerId = :providerId")
    suspend fun unbind(providerId: String)
}
