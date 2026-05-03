package lava.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import lava.database.entity.SearchProviderSelectionEntity

@Dao
interface SearchProviderSelectionDao {

    @Query("SELECT * FROM search_provider_selections WHERE query_hash = :queryHash")
    suspend fun loadForQuery(queryHash: String): List<SearchProviderSelectionEntity>

    @Query("SELECT * FROM search_provider_selections WHERE query_hash = :queryHash")
    fun observeForQuery(queryHash: String): Flow<List<SearchProviderSelectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SearchProviderSelectionEntity)

    @Query("DELETE FROM search_provider_selections WHERE query_hash = :queryHash")
    suspend fun deleteForQuery(queryHash: String)
}
