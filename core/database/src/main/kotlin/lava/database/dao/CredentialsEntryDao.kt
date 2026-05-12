package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.CredentialsEntryEntity

@Dao
interface CredentialsEntryDao {
    @Query("SELECT * FROM credentials_entry ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CredentialsEntryEntity>>

    @Query("SELECT * FROM credentials_entry WHERE id = :id")
    suspend fun get(id: String): CredentialsEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CredentialsEntryEntity)

    @Query("DELETE FROM credentials_entry WHERE id = :id")
    suspend fun delete(id: String)
}
