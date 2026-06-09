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
    /**
     * LVA-070 — [providerId] is threaded through to the persisted favorite row
     * so an archiveorg/gutenberg topic favorited from the topic screen records
     * its source provider and later routes to HTTP_DOWNLOAD. Null (the default)
     * keeps every existing caller compiling and preserves legacy behaviour.
     */
    suspend operator fun invoke(id: String, providerId: String? = null)
}

class ToggleFavoriteUseCaseImpl @Inject constructor(
    private val addLocalFavoriteUseCase: AddLocalFavoriteUseCase,
    private val removeLocalFavoriteUseCase: RemoveLocalFavoriteUseCase,
    private val favoritesRepository: FavoritesRepository,
    private val backgroundService: BackgroundService,
    private val dispatchers: Dispatchers,
) : ToggleFavoriteUseCase {
    override suspend operator fun invoke(id: String, providerId: String?) {
        withContext(dispatchers.default) {
            val isFavorites = favoritesRepository.contains(id)
            if (isFavorites) {
                removeLocalFavoriteUseCase(id)
                backgroundService.removeFavoriteTopic(id)
            } else {
                addLocalFavoriteUseCase(id, providerId)
                backgroundService.addFavoriteTopic(id)
            }
        }
    }
}
