package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.ClonedProviderEntity

@Dao
interface ClonedProviderDao {
    /**
     * SP-4 Phase G (2026-05-13). Read paths filter `deletedAt IS NULL`
     * so soft-deleted clones are invisible to the SDK + UI while
     * remaining in the table for outbox + backup propagation.
     */
    @Query("SELECT * FROM cloned_provider WHERE deletedAt IS NULL")
    fun observeAll(): Flow<List<ClonedProviderEntity>>

    /**
     * Non-Flow snapshot of every cloned provider row. Added in SP-4
     * Phase A+B (Task 15) for the SDK's `listAvailableTrackers()` union
     * surface — that method is non-suspending today, so an in-thread
     * suspend point is required to read the DAO without `runBlocking`
     * abuse. Room runs this on its internal query executor.
     */
    @Query("SELECT * FROM cloned_provider WHERE deletedAt IS NULL")
    suspend fun getAll(): List<ClonedProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ClonedProviderEntity)

    /**
     * SP-4 Phase G (2026-05-13). Soft-delete: stamps `deletedAt` so
     * read paths skip the row.
     */
    @Query("UPDATE cloned_provider SET deletedAt = :deletedAt WHERE syntheticId = :id")
    suspend fun softDelete(id: String, deletedAt: Long)

    /**
     * Hard-delete. Retained for tests + future hard-purge sweep.
     */
    @Query("DELETE FROM cloned_provider WHERE syntheticId = :id")
    suspend fun delete(id: String)
}
