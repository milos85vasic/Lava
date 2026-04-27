package lava.domain.usecase

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import lava.data.api.service.SearchService
import lava.domain.model.PagingAction
import lava.domain.model.PagingData
import lava.domain.model.PagingDataLoader
import lava.domain.model.refresh
import lava.logger.api.LoggerFactory
import lava.models.search.Filter
import lava.models.topic.TopicModel
import lava.models.topic.Torrent
import javax.inject.Inject

class ObserveSearchPagingDataUseCase @Inject constructor(
    private val enrichTopicsUseCase: EnrichTopicsUseCase,
    private val searchService: SearchService,
    private val loggerFactory: LoggerFactory,
) {
    operator fun invoke(
        filterFlow: Flow<Filter>,
        actionsFlow: Flow<PagingAction>,
        scope: CoroutineScope,
    ): Flow<PagingData<List<TopicModel<Torrent>>>> {
        return filterFlow.flatMapLatest { filter ->
            PagingDataLoader(
                fetchData = { page -> searchService.search(filter, page) },
                transform = { torrents -> enrichTopicsUseCase(torrents) },
                actions = actionsFlow.onStart { refresh() },
                scope = scope,
                logger = loggerFactory.get("SearchPagingDataLoader"),
            ).flow
        }
    }
}
