package lava.testing.repository

import kotlinx.coroutines.flow.Flow
import lava.data.api.repository.SuggestsRepository

class TestSuggestsRepository : SuggestsRepository {
    override fun observeSuggests(): Flow<List<String>> {
        TODO("Not yet implemented")
    }

    override suspend fun addSuggest(suggest: String) {
        TODO("Not yet implemented")
    }

    override suspend fun clear() {
    }
}
