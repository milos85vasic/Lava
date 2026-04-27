package lava.domain.usecase

import lava.data.api.repository.FavoriteSearchRepository
import lava.models.search.Search
import javax.inject.Inject

class UnpinSearchHistoryUseCase @Inject constructor(
    private val repository: FavoriteSearchRepository,
) {
    suspend operator fun invoke(search: Search) {
        repository.remove(search.id)
    }
}
