package lava.search.result

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onEach
import lava.domain.model.PagingAction
import lava.domain.model.append
import lava.domain.model.retry
import lava.domain.usecase.AddSearchHistoryUseCase
import lava.domain.usecase.EnrichFilterUseCase
import lava.domain.usecase.ObserveAuthStateUseCase
import lava.domain.usecase.ObserveSearchPagingDataUseCase
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.logger.api.LoggerFactory
import lava.models.auth.isAuthorized
import lava.models.forum.Category
import lava.models.search.Order
import lava.models.search.Period
import lava.models.search.Sort
import lava.models.topic.Author
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
internal class SearchResultViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    loggerFactory: LoggerFactory,
    private val observeSearchPagingDataUseCase: ObserveSearchPagingDataUseCase,
    private val addSearchHistoryUseCase: AddSearchHistoryUseCase,
    private val enrichFilterUseCase: EnrichFilterUseCase,
    private val toggleFavoriteUseCase: ToggleFavoriteUseCase,
    // SP-3.2 (2026-04-29): observe auth state to render Unauthorized
    // empty-state instead of the misleading "Nothing found" when the
    // user has not signed in to the upstream tracker.
    private val observeAuthStateUseCase: ObserveAuthStateUseCase,
) : ViewModel(), ContainerHost<SearchPageState, SearchResultSideEffect> {
    private val logger = loggerFactory.get("SearchResultViewModel")
    private val mutableFilter = MutableStateFlow(savedStateHandle.filter)
    private val pagingActions = MutableSharedFlow<PagingAction>()

    override val container: Container<SearchPageState, SearchResultSideEffect> = container(
        initialState = SearchPageState(mutableFilter.value),
        onCreate = {
            observeFilter()
            observePagingData()
        },
    )

    fun perform(action: SearchResultAction) {
        logger.d { "Perform $action" }
        when (action) {
            is SearchResultAction.BackClick -> onBackClick()
            is SearchResultAction.ExpandAppBarClick -> onExpandAppBarClick()
            is SearchResultAction.FavoriteClick -> onFavoriteClick(action.topicModel)
            is SearchResultAction.ListBottomReached -> onListBottomReached()
            is SearchResultAction.LoginClick -> onLoginClick()
            is SearchResultAction.RetryClick -> onRetryClick()
            is SearchResultAction.SearchClick -> onSearchClick()
            is SearchResultAction.SetAuthor -> onSetAuthor(action.author)
            is SearchResultAction.SetCategories -> onSetCategories(action.categories)
            is SearchResultAction.SetOrder -> onSetOrder(action.order)
            is SearchResultAction.SetPeriod -> onSetPeriod(action.period)
            is SearchResultAction.SetSort -> onSetSort(action.sort)
            is SearchResultAction.TopicClick -> onTopicClick(action.topicModel)
        }
    }

    private fun observeFilter() = intent {
        mutableFilter.emit(enrichFilterUseCase(state.filter))
        mutableFilter
            .onEach(addSearchHistoryUseCase::invoke)
            .collectLatest { filter ->
                reduce { state.copy(filter = filter) }
            }
    }

    /**
     * SP-3.2 (2026-04-29). When auth state is `Unauthorized`, paging
     * data is suppressed and `SearchResultContent.Unauthorized` is
     * rendered with a Login button — matching the user-mandate fix
     * for "search returns Nothing found instead of prompting login."
     * When the user becomes authorized (returning from login), this
     * intent re-emits and the paging data flow takes over.
     *
     * Sixth-Law clause 1: same surface (auth state) the user touches
     * via the Login button. Clause 3: primary user-visible state is
     * the rendered content branch (Unauthorized vs Empty vs Content).
     */
    private fun observePagingData() = intent {
        logger.d { "Start observing paging data" }
        observeAuthStateUseCase().collectLatest { authState ->
            if (!authState.isAuthorized) {
                reduce {
                    state.copy(
                        searchContent = SearchResultContent.Unauthorized,
                        loadStates = lava.domain.model.LoadStates.Idle,
                    )
                }
                return@collectLatest
            }
            observeSearchPagingDataUseCase(
                filterFlow = mutableFilter,
                actionsFlow = pagingActions,
                scope = viewModelScope,
            ).collectLatest { (data, loadingState) ->
                reduce {
                    state.copy(
                        searchContent = when {
                            data == null -> SearchResultContent.Initial
                            data.isEmpty() -> SearchResultContent.Empty
                            else -> SearchResultContent.Content(
                                torrents = data,
                                categories = data.mapNotNull { it.topic.category }.distinct(),
                            )
                        },
                        loadStates = loadingState,
                    )
                }
            }
        }
    }

    private fun onLoginClick() = intent {
        postSideEffect(SearchResultSideEffect.OpenLogin)
    }

    private fun onBackClick() = intent {
        postSideEffect(SearchResultSideEffect.Back)
    }

    private fun onExpandAppBarClick() = intent {
        reduce { state.copy(appBarExpanded = !state.appBarExpanded) }
    }

    private fun onFavoriteClick(topicModel: TopicModel<out Topic>) = intent {
        runCatching { toggleFavoriteUseCase(topicModel.topic.id) }
            .onFailure { postSideEffect(SearchResultSideEffect.ShowFavoriteToggleError) }
    }

    private fun onListBottomReached() = intent {
        pagingActions.append()
    }

    private fun onRetryClick() = intent {
        pagingActions.retry()
    }

    private fun onSearchClick() = intent {
        val filter = state.filter.copy(period = Period.ALL_TIME)
        postSideEffect(SearchResultSideEffect.OpenSearchInput(filter))
    }

    private fun onSetAuthor(author: Author?) = intent {
        mutableFilter.emit(mutableFilter.value.copy(author = author))
        reduce { state.copy(appBarExpanded = false) }
    }

    private fun onSetCategories(categories: List<Category>?) = intent {
        mutableFilter.emit(mutableFilter.value.copy(categories = categories))
        reduce { state.copy(appBarExpanded = false) }
    }

    private fun onSetSort(sort: Sort) = intent {
        mutableFilter.emit(mutableFilter.value.copy(sort = sort))
    }

    private fun onSetOrder(order: Order) = intent {
        mutableFilter.emit(mutableFilter.value.copy(order = order))
    }

    private fun onSetPeriod(period: Period) = intent {
        val filter = state.filter.copy(query = null, period = period)
        postSideEffect(SearchResultSideEffect.OpenSearchResult(filter))
    }

    private fun onTopicClick(topicModel: TopicModel<out Topic>) = intent {
        postSideEffect(SearchResultSideEffect.OpenTopic(topicModel.topic.id))
    }
}
