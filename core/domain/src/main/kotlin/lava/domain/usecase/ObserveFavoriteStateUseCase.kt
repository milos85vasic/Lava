package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import lava.data.api.repository.FavoritesRepository
import javax.inject.Inject

class ObserveFavoriteStateUseCase @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
) {
    operator fun invoke(id: String): Flow<Boolean> {
        return favoritesRepository.observeIds().map { it.contains(id) }
    }
}
