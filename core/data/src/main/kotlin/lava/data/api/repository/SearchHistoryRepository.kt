package lava.data.api.repository

import lava.models.search.Filter
import lava.models.search.Search
import kotlinx.coroutines.flow.Flow

interface SearchHistoryRepository {
    fun observeAll(): Flow<List<Search>>
    suspend fun add(filter: Filter)
    suspend fun remove(id: Int)
    suspend fun clear()
}
