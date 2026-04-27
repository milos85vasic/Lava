package lava.domain.usecase

import lava.data.api.repository.FavoriteSearchRepository
import lava.data.api.repository.SearchHistoryRepository
import lava.dispatchers.api.Dispatchers
import lava.domain.model.search.SearchHistory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ObserveSearchHistoryUseCase @Inject constructor(
    private val searchHistoryRepository: SearchHistoryRepository,
    private val favoriteSearchRepository: FavoriteSearchRepository,
    private val dispatchers: Dispatchers,
) {
    operator fun invoke(): Flow<SearchHistory> {
        return combine(
            searchHistoryRepository.observeAll().distinctUntilChanged(),
            favoriteSearchRepository.observeAll().distinctUntilChanged(),
        ) { searches, favorites ->
            withContext(dispatchers.default) {
                SearchHistory(
                    pinned = searches.filter { it.id in favorites },
                    other = searches.filter { it.id !in favorites },
                )
            }
        }
    }
}
