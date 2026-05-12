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

    /**
     * Non-Flow snapshot of every cloned provider row. Added in SP-4
     * Phase A+B (Task 15) for the SDK's `listAvailableTrackers()` union
     * surface — that method is non-suspending today, so an in-thread
     * suspend point is required to read the DAO without `runBlocking`
     * abuse. Room runs this on its internal query executor.
     */
    @Query("SELECT * FROM cloned_provider")
    suspend fun getAll(): List<ClonedProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ClonedProviderEntity)

    @Query("DELETE FROM cloned_provider WHERE syntheticId = :id")
    suspend fun delete(id: String)
}
