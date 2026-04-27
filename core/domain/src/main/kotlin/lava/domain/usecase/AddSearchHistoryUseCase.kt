package lava.domain.usecase

import lava.data.api.repository.SearchHistoryRepository
import lava.dispatchers.api.Dispatchers
import lava.models.search.Filter
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AddSearchHistoryUseCase @Inject constructor(
    private val enrichFilterUseCase: EnrichFilterUseCase,
    private val repository: SearchHistoryRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(filter: Filter) {
        withContext(dispatchers.default) {
            repository.add(enrichFilterUseCase(filter))
        }
    }
}
