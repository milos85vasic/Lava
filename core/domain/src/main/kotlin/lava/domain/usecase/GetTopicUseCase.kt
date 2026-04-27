package lava.domain.usecase

import lava.data.api.service.TopicService
import lava.dispatchers.api.Dispatchers
import lava.models.topic.TopicPage
import kotlinx.coroutines.withContext
import javax.inject.Inject

class GetTopicUseCase @Inject constructor(
    private val topicService: TopicService,
    private val visitTopicUseCase: VisitTopicUseCase,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(id: String): TopicPage {
        return withContext(dispatchers.default) {
            topicService.getTopicPage(id).also {
                visitTopicUseCase(it)
            }
        }
    }
}
