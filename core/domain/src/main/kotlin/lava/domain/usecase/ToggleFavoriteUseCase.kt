package lava.domain.usecase

import lava.data.api.repository.FavoritesRepository
import lava.dispatchers.api.Dispatchers
import lava.work.api.BackgroundService
import kotlinx.coroutines.withContext
import javax.inject.Inject

class ToggleFavoriteUseCase @Inject constructor(
    private val addLocalFavoriteUseCase: AddLocalFavoriteUseCase,
    private val removeLocalFavoriteUseCase: RemoveLocalFavoriteUseCase,
    private val favoritesRepository: FavoritesRepository,
    private val backgroundService: BackgroundService,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(id: String) {
        withContext(dispatchers.default) {
            val isFavorites = favoritesRepository.contains(id)
            if (isFavorites) {
                removeLocalFavoriteUseCase(id)
                backgroundService.removeFavoriteTopic(id)
            } else {
                addLocalFavoriteUseCase(id)
                backgroundService.addFavoriteTopic(id)
            }
        }
    }
}
