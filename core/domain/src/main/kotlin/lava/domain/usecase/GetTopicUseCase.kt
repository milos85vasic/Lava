package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.service.TopicService
import lava.dispatchers.api.Dispatchers
import lava.models.topic.TopicPage
import javax.inject.Inject

class GetTopicUseCase @Inject constructor(
    private val topicService: TopicService,
    private val visitTopicUseCase: VisitTopicUseCase,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(id: String, providerId: String? = null): TopicPage {
        return withContext(dispatchers.default) {
            topicService.getTopicPage(id).also {
                // LVA-070 — thread the source provider into the visited record so
                // an archiveorg/gutenberg topic opened from search persists its
                // provider and later routes to HTTP_DOWNLOAD on the topic screen.
                visitTopicUseCase(it, providerId)
            }
        }
    }
}
