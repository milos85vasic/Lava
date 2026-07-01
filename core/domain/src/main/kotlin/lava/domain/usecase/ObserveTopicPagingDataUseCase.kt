package lava.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import lava.data.api.service.TopicService
import lava.domain.model.PagingAction
import lava.domain.model.PagingData
import lava.domain.model.PagingDataLoader
import lava.domain.model.refresh
import lava.logger.api.LoggerFactory
import lava.models.topic.Post
import javax.inject.Inject

class ObserveTopicPagingDataUseCase @Inject constructor(
    private val topicService: TopicService,
    private val loggerFactory: LoggerFactory,
) {
    suspend operator fun invoke(
        id: String,
        actions: Flow<PagingAction>,
        scope: CoroutineScope,
        providerId: String? = null,
    ): Flow<PagingData<List<Post>>> {
        return PagingDataLoader(
            // LVA-070 — provider-aware: the comments paging shares the topic
            // fetch, so it MUST route to the same source provider; otherwise the
            // refresh hits the legacy proxy `…/topic2/{id}` and throws
            // (UnknownHost on the QA emulator) → LoadState.Error → the topic
            // screen's "Something went wrong" error item.
            fetchData = { page -> topicService.getTopicPage(id, page, providerId).commentsPage },
            transform = { posts -> flowOf(posts) },
            actions = actions.onStart { refresh() },
            scope = scope,
            logger = loggerFactory.get("TopicPagingDataLoader"),
        ).flow
    }
}
