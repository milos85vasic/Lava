package lava.domain.usecase

import lava.data.api.repository.FavoritesRepository
import lava.data.api.service.FavoritesService
import lava.data.api.service.TopicService
import lava.dispatchers.api.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AddRemoteFavoriteUseCase @Inject constructor(
    private val favoritesService: FavoritesService,
    private val favoritesRepository: FavoritesRepository,
    private val topicService: TopicService,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(id: String) {
        withContext(dispatchers.default) {
            require(favoritesService.add(id))
            runCatching {
                coroutineScope {
                    val topic = topicService.getTopic(id)
                    favoritesRepository.add(topic)
                }
            }
        }
    }
}
