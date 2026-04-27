package lava.data.impl.repository

import lava.data.api.repository.SuggestsRepository
import lava.data.converters.toEntity
import lava.database.dao.SuggestDao
import lava.database.entity.SuggestEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class SuggestsRepositoryImpl @Inject constructor(
    private val suggestDao: SuggestDao,
) : SuggestsRepository {
    override fun observeSuggests(): Flow<List<String>> = suggestDao.observerAll().map { suggests ->
        suggests.map(SuggestEntity::suggest)
    }

    override suspend fun addSuggest(suggest: String) {
        suggestDao.insert(suggest.toEntity())
    }

    override suspend fun clear() {
        suggestDao.deleteAll()
    }
}
