package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.SearchHistoryRepository
import lava.dispatchers.api.Dispatchers
import lava.models.search.Filter
import javax.inject.Inject

/**
 * Add-search-history use-case.
 *
 * Promoted to an interface 2026-04-30 (SP-3a paging-graph closure) so feature
 * tests can substitute a real, named test fake instead of a `mockk<...>(relaxed = true)`.
 * Production code is unaffected: the Hilt graph in `DomainModule` binds
 * [AddSearchHistoryUseCaseImpl] to this interface.
 */
interface AddSearchHistoryUseCase {
    suspend operator fun invoke(filter: Filter)
}

class AddSearchHistoryUseCaseImpl @Inject constructor(
    private val enrichFilterUseCase: EnrichFilterUseCase,
    private val repository: SearchHistoryRepository,
    private val dispatchers: Dispatchers,
) : AddSearchHistoryUseCase {
    override suspend operator fun invoke(filter: Filter) {
        withContext(dispatchers.default) {
            repository.add(enrichFilterUseCase(filter))
        }
    }
}
