package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.FavoritesRepository
import lava.data.api.service.TopicService
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

class AddLocalFavoriteUseCase @Inject constructor(
    private val topicService: TopicService,
    private val favoritesRepository: FavoritesRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(id: String) {
        withContext(dispatchers.default) {
            val topic = topicService.getTopic(id)
            favoritesRepository.add(topic)
        }
    }
}
