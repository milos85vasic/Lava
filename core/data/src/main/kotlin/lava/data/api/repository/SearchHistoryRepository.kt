package lava.data.api.repository

import kotlinx.coroutines.flow.Flow
import lava.models.search.Filter
import lava.models.search.Search

interface SearchHistoryRepository {
    fun observeAll(): Flow<List<Search>>
    suspend fun add(filter: Filter)
    suspend fun remove(id: Int)
    suspend fun clear()
}
