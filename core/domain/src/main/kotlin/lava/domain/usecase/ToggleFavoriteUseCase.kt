package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.FavoritesRepository
import lava.dispatchers.api.Dispatchers
import lava.work.api.BackgroundService
import javax.inject.Inject

/**
 * Toggle-favorite use-case.
 *
 * Promoted to an interface 2026-04-30 (SP-3a paging-graph closure) so feature
 * tests can substitute a real, named test fake instead of a `mockk<...>(relaxed = true)`.
 * Production code is unaffected: the Hilt graph in `DomainModule` binds
 * [ToggleFavoriteUseCaseImpl] to this interface.
 */
interface ToggleFavoriteUseCase {
    suspend operator fun invoke(id: String)
}

class ToggleFavoriteUseCaseImpl @Inject constructor(
    private val addLocalFavoriteUseCase: AddLocalFavoriteUseCase,
    private val removeLocalFavoriteUseCase: RemoveLocalFavoriteUseCase,
    private val favoritesRepository: FavoritesRepository,
    private val backgroundService: BackgroundService,
    private val dispatchers: Dispatchers,
) : ToggleFavoriteUseCase {
    override suspend operator fun invoke(id: String) {
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
