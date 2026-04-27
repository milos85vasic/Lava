package lava.domain.usecase

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import lava.data.api.repository.FavoritesRepository
import lava.data.api.service.FavoritesService
import lava.dispatchers.api.Dispatchers
import lava.models.topic.Topic
import javax.inject.Inject

class LoadFavoritesUseCase @Inject constructor(
    private val favoritesService: FavoritesService,
    private val favoritesRepository: FavoritesRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke() {
        withContext(dispatchers.default) {
            runCatching {
                coroutineScope {
                    val remoteFavoriteTopics = favoritesService.getFavorites()
                    if (remoteFavoriteTopics.isNotEmpty()) {
                        val idsToDelete = favoritesRepository.getIds()
                            .subtract(remoteFavoriteTopics.map(Topic::id).toSet())
                            .toList()
                        favoritesRepository.add(remoteFavoriteTopics)
                        favoritesRepository.removeById(idsToDelete)
                    }
                }
            }
        }
    }
}
