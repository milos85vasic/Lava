package lava.domain.usecase

import lava.data.api.service.TopicService
import lava.domain.model.PagingAction
import lava.domain.model.PagingData
import lava.domain.model.PagingDataLoader
import lava.domain.model.refresh
import lava.logger.api.LoggerFactory
import lava.models.topic.Post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

class ObserveTopicPagingDataUseCase @Inject constructor(
    private val topicService: TopicService,
    private val loggerFactory: LoggerFactory,
) {
    suspend operator fun invoke(
        id: String,
        actions: Flow<PagingAction>,
        scope: CoroutineScope,
    ): Flow<PagingData<List<Post>>> {
        return PagingDataLoader(
            fetchData = { page -> topicService.getTopicPage(id, page).commentsPage },
            transform = { posts -> flowOf(posts) },
            actions = actions.onStart { refresh() },
            scope = scope,
            logger = loggerFactory.get("TopicPagingDataLoader"),
        ).flow
    }
}
