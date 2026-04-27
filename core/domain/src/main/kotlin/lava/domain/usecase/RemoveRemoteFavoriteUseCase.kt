package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.service.FavoritesService
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

class RemoveRemoteFavoriteUseCase @Inject constructor(
    private val favoritesService: FavoritesService,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(id: String) {
        withContext(dispatchers.default) {
            favoritesService.remove(id)
        }
    }
}
