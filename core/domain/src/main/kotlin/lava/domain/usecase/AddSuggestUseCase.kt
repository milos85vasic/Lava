package lava.domain.usecase

import lava.data.api.repository.SuggestsRepository
import lava.dispatchers.api.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AddSuggestUseCase @Inject constructor(
    private val suggestsRepository: SuggestsRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(query: String?) {
        withContext(dispatchers.default) {
            if (!query.isNullOrBlank()) {
                suggestsRepository.addSuggest(query)
            }
        }
    }
}
