package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.dispatchers.api.Dispatchers
import lava.models.search.Filter
import javax.inject.Inject

/**
 * Enrich-filter use-case.
 *
 * Promoted to an interface 2026-04-30 (SP-3a paging-graph closure) so feature
 * tests can substitute a real, named test fake instead of a `mockk<...>(relaxed = true)`.
 * Production code is unaffected: the Hilt graph in `DomainModule` binds
 * [EnrichFilterUseCaseImpl] to this interface.
 */
interface EnrichFilterUseCase {
    suspend operator fun invoke(filter: Filter): Filter
}

class EnrichFilterUseCaseImpl @Inject constructor(
    private val getCategoryUseCase: GetCategoryUseCase,
    private val dispatchers: Dispatchers,
) : EnrichFilterUseCase {
    override suspend operator fun invoke(filter: Filter): Filter {
        return withContext(dispatchers.default) {
            filter.copy(categories = filter.categories?.map { getCategoryUseCase(it.id) })
        }
    }
}
