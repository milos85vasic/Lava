package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.service.TopicService
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

class AddCommentUseCase @Inject constructor(
    private val topicService: TopicService,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(topicId: String, message: String): Boolean {
        return withContext(dispatchers.default) {
            runCatching {
                topicService.addComment(topicId, message)
            }.isSuccess
        }
    }
}
