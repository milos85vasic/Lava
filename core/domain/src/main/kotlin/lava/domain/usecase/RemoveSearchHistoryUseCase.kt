package lava.domain.usecase

import lava.data.api.repository.FavoriteSearchRepository
import lava.data.api.repository.SearchHistoryRepository
import lava.models.search.Search
import javax.inject.Inject

class RemoveSearchHistoryUseCase @Inject constructor(
    private val searchHistoryRepository: SearchHistoryRepository,
    private val favoriteSearchRepository: FavoriteSearchRepository,
) {
    suspend operator fun invoke(search: Search) {
        searchHistoryRepository.remove(search.id)
        favoriteSearchRepository.remove(search.id)
    }
}
