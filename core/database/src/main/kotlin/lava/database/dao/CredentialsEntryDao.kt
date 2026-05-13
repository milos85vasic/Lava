package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.CredentialsEntryEntity

@Dao
interface CredentialsEntryDao {
    /**
     * SP-4 Phase G (2026-05-13). Read paths filter `deletedAt IS NULL`
     * so soft-deleted rows are invisible to the UI while remaining in
     * the table for outbox + backup propagation.
     */
    @Query("SELECT * FROM credentials_entry WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CredentialsEntryEntity>>

    @Query("SELECT * FROM credentials_entry WHERE id = :id AND deletedAt IS NULL")
    suspend fun get(id: String): CredentialsEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CredentialsEntryEntity)

    /**
     * SP-4 Phase G (2026-05-13). Soft-delete: stamps `deletedAt` so
     * read paths skip the row but the row's existence is preserved for
     * Phase E's sync upload + backup-restore semantics.
     */
    @Query("UPDATE credentials_entry SET deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    /**
     * Hard-delete. Retained for tests and for a future hard-purge sweep
     * that physically removes rows whose `deletedAt` is older than the
     * retention window. NOT called from the production UI path.
     */
    @Query("DELETE FROM credentials_entry WHERE id = :id")
    suspend fun delete(id: String)
}
