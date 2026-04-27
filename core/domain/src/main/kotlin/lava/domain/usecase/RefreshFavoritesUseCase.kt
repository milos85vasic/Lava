package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

class RefreshFavoritesUseCase @Inject constructor(
    private val loadFavoritesUseCase: LoadFavoritesUseCase,
    private val syncFavoritesUseCase: SyncFavoritesUseCase,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke() {
        withContext(dispatchers.default) {
            loadFavoritesUseCase()
            syncFavoritesUseCase()
        }
    }
}
