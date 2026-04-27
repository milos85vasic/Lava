package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.VisitedRepository
import lava.dispatchers.api.Dispatchers
import lava.models.topic.TopicPage
import javax.inject.Inject

class VisitTopicUseCase @Inject constructor(
    private val visitedRepository: VisitedRepository,
    private val favoritesRepository: FavoritesRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(topic: TopicPage) {
        withContext(dispatchers.default) {
            favoritesRepository.markVisited(topic.id)
            visitedRepository.add(topic)
        }
    }
}
