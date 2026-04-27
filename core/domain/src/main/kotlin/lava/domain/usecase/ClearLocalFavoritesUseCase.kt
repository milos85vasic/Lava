package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.FavoritesRepository
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

class ClearLocalFavoritesUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke() {
        withContext(dispatchers.default) {
            favoritesRepository.clear()
        }
    }
}
