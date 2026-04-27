package lava.domain.usecase

import lava.data.api.repository.FavoritesRepository
import lava.dispatchers.api.Dispatchers
import kotlinx.coroutines.withContext
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
