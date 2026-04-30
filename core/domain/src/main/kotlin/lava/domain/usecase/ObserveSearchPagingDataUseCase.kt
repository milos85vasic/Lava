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

/**
 * Search-paging observation use-case.
 *
 * Promoted to an interface 2026-04-30 (SP-3a paging-graph closure) so feature
 * tests can substitute a real, named test fake instead of a `mockk<...>(relaxed = true)`
 * — the latter is a bluff under Seventh Law clause 4(d) (anonymous boundary
 * with no behavior). Production code is unaffected: the Hilt graph in
 * `DomainModule` binds [ObserveSearchPagingDataUseCaseImpl] to this interface.
 */
interface ObserveSearchPagingDataUseCase {
    operator fun invoke(
        filterFlow: Flow<Filter>,
        actionsFlow: Flow<PagingAction>,
        scope: CoroutineScope,
    ): Flow<PagingData<List<TopicModel<Torrent>>>>
}

class ObserveSearchPagingDataUseCaseImpl @Inject constructor(
    private val enrichTopicsUseCase: EnrichTopicsUseCase,
    private val searchService: SearchService,
    private val loggerFactory: LoggerFactory,
) : ObserveSearchPagingDataUseCase {
    override operator fun invoke(
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
