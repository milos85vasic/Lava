package lava.domain.usecase

import lava.data.api.repository.FavoriteSearchRepository
import lava.data.api.repository.SearchHistoryRepository
import lava.data.api.repository.SuggestsRepository
import lava.data.api.repository.VisitedRepository
import lava.dispatchers.api.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ClearHistoryUseCase @Inject constructor(
    private val suggestsRepository: SuggestsRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val favoriteSearchRepository: FavoriteSearchRepository,
    private val visitedRepository: VisitedRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke() {
        withContext(dispatchers.default) {
            suggestsRepository.clear()
            searchHistoryRepository.clear()
            favoriteSearchRepository.clear()
            visitedRepository.clear()
        }
    }
}
