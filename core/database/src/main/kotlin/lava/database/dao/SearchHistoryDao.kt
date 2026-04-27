package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.SearchHistoryEntity

/**
 * DAO for [SearchHistoryEntity] access.
 */
@Dao
interface SearchHistoryDao {
    /**
     * Observe all [SearchHistoryEntity]s in order from newest to latest.
     */
    @Query("SELECT * FROM Search ORDER by timestamp DESC")
    fun observerAll(): Flow<List<SearchHistoryEntity>>

    /**
     * Insert new [SearchHistoryEntity] or replace existed.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SearchHistoryEntity)

    /**
     * Delete [SearchHistoryEntity].
     */
    @Query("DELETE FROM Search WHERE :id == id")
    suspend fun delete(id: Int)

    /**
     * Clear all [SearchHistoryEntity]s.
     */
    @Query("DELETE FROM Search")
    suspend fun deleteAll()
}
