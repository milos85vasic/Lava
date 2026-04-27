package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.dispatchers.api.Dispatchers
import lava.models.search.Filter
import javax.inject.Inject

class EnrichFilterUseCase @Inject constructor(
    private val getCategoryUseCase: GetCategoryUseCase,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(filter: Filter): Filter {
        return withContext(dispatchers.default) {
            filter.copy(categories = filter.categories?.map { getCategoryUseCase(it.id) })
        }
    }
}
