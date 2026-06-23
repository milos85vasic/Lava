package lava.search.result

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onEach
import lava.common.analytics.AnalyticsTracker
import lava.common.analytics.rethrowIfCancellation
import lava.domain.model.PagingAction
import lava.domain.model.append
import lava.domain.model.retry
import lava.domain.usecase.AddSearchHistoryUseCase
import lava.domain.usecase.EnrichFilterUseCase
import lava.domain.usecase.ObserveAuthStateUseCase
import lava.domain.usecase.ObserveSearchPagingDataUseCase
import lava.domain.usecase.ObserveSettingsUseCase
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.logger.api.LoggerFactory
import lava.models.auth.isAuthorized
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.search.Order
import lava.models.search.Period
import lava.models.search.Sort
import lava.models.topic.Author
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.Torrent
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SortField
import lava.tracker.api.model.SortOrder
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.client.MultiSearchEvent
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
    private val observeSettingsUseCase: ObserveSettingsUseCase,
    private val analytics: AnalyticsTracker,
    private val sdk: LavaTrackerSdk,
) : ViewModel(), ContainerHost<SearchPageState, SearchResultSideEffect> {
    private val logger = loggerFactory.get("SearchResultViewModel")
    private val mutableFilter = MutableStateFlow(savedStateHandle.filter)
    private val pagingActions = MutableSharedFlow<PagingAction>()

    override val container: Container<SearchPageState, SearchResultSideEffect> = container(
        initialState = SearchPageState(mutableFilter.value),
        onCreate = {
            observeFilter()
            val filter = mutableFilter.value
            when {
                filter.providerIds == null -> observePagingData()
                // 2026-06-14 SEARCH FIX (operator-reported "problem reaching the
                // trackers in any scenario"): multi-provider search fans out
                // client-side via sdk.streamMultiSearch → GET /v1/{provider}/search,
                // which IS served by both the embedded on-device api-app and the
                // standalone lava-api-go. The old GoApi → observeSseSearch branch
                // targeted GET /v1/search — an endpoint NO backend registers (verified:
                // neither internal/router/router.go nor the standalone handlers) — so
                // every GoApi search 404'd. Route GoApi through the proven per-provider
                // SDK path (it carries the per-instance Lava-Auth key + permissive-LAN
                // client from the 1.3.8 wiring via ApiBaseUrlHolder).
                else -> observeStreamMultiSearch(filter)
            }
        },
    )

    /**
     * SP-4 Phase D (2026-05-13). Client-direct multi-provider parallel
     * search via `LavaTrackerSdk.streamMultiSearch`. This is the sole
     * multi-provider search path — it fans out per-provider over
     * `GET /v1/{provider}/search`, served by both the on-device api-app and
     * the standalone lava-api-go. Renders into `SearchResultContent.Streaming`.
     */
    private fun observeStreamMultiSearch(filter: Filter) = intent {
        val providerIds = filter.providerIds
        if (providerIds.isNullOrEmpty()) return@intent

        reduce {
            state.copy(
                searchContent = SearchResultContent.Streaming(
                    items = emptyList(),
                    activeProviders = providerIds.map { pid ->
                        ProviderStreamStatus(
                            providerId = pid,
                            displayName = pid,
                            status = StreamStatus.SEARCHING,
                        )
                    },
                ),
            )
        }

        val request = SearchRequest(
            query = filter.query.orEmpty(),
            sort = SortField.DATE,
            sortOrder = SortOrder.DESCENDING,
        )

        sdk.streamMultiSearch(request, providerIds).collect { event ->
            handleMultiSearchEvent(event)
        }
        // After the flow completes naturally, downgrade the Streaming
        // state to Content (or Empty if no items arrived).
        handleStreamEnd()
    }

    private fun handleMultiSearchEvent(event: MultiSearchEvent) = intent {
        reduce { applyMultiSearchEvent(state, event) }
        // §6.J anti-bluff: the pure transformation is extracted to
        // `applyMultiSearchEvent` at file-scope so the Phase D VM-consumer
        // test can drive it directly without the orbit intent/reduce
        // wrapper. Keep the local `when` as a sanity guard so a future
        // refactor that adds a new event variant fails fast here
        // (exhaustive `when`).
        when (event) {
            is MultiSearchEvent.ProviderStart -> Unit
            is MultiSearchEvent.ProviderResults -> Unit
            is MultiSearchEvent.ProviderFailure -> recordProviderFailure(event)
            // no-telemetry: a provider lacking TrackerCapability.SEARCH is a
            // benign terminal state (skipped, not failed) per the
            // MultiSearchEvent KDoc — not an error worth a non-fatal.
            is MultiSearchEvent.ProviderUnsupported -> Unit
            is MultiSearchEvent.AllProvidersDone -> {
                // Final snapshot — UI already reflects per-provider
                // results via the incremental events; no additional
                // reduce needed here. handleStreamEnd() in the caller
                // downgrades to Content/Empty.
            }
        }
    }

    /**
     * §6.AC: record a per-provider streaming-search failure to telemetry.
     *
     * This arm was previously `-> Unit`, which DROPPED [MultiSearchEvent.ProviderFailure.cause]
     * entirely: a per-provider failure (e.g. HTTP 401 / connection-refused
     * surfaced by `ApiBackedTrackerClient.getString` as
     * `"API request failed: HTTP <code> for <url>"`) rendered the generic
     * "Something went wrong" ERROR row with NO captured reason. On a RELEASE
     * build there is no Chucker and the cause is logged nowhere, so the
     * failure mode was undiagnosable in the field — exactly the
     * release-only search failure an operator reported (2026-06-22). Recording
     * the cause surfaces the precise HTTP status + failing provider in the
     * operator's Crashlytics non-fatal feed so the root layer can be pinned
     * from real-user evidence rather than guessed.
     *
     * §6.H: the recorded context carries the provider id, the operation, and
     * the (already user-facing) [MultiSearchEvent.ProviderFailure.reason] — no
     * credential, token, or cookie. The cause's message is the api-client's
     * own "HTTP <code> for <url>" string (the url is a LAN/cloud search URL,
     * not a secret).
     */
    private fun recordProviderFailure(event: MultiSearchEvent.ProviderFailure) {
        val context = mapOf(
            AnalyticsTracker.Params.FEATURE to "search",
            AnalyticsTracker.Params.OPERATION to "streamMultiSearch",
            AnalyticsTracker.Params.SCREEN to "search_result",
            AnalyticsTracker.Params.PROVIDER to event.providerId,
            AnalyticsTracker.Params.ERROR_MESSAGE to event.reason,
        )
        val cause = event.cause
        if (cause != null) {
            cause.rethrowIfCancellation()
            analytics.recordNonFatal(cause, context)
        } else {
            analytics.recordWarning(event.reason, context)
        }
    }

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
            is SearchResultAction.FallbackAccept -> onFallbackAccept()
            is SearchResultAction.FallbackDismiss -> onFallbackDismiss()
            is SearchResultAction.SetFilterProvider -> onSetFilterProvider(action.providerId)
        }
    }

    /**
     * SP-3a Phase 4 (Task 4.18). Hook for the legacy paging path to
     * surface a CrossTrackerFallbackProposed proposal as state. The
     * production paging path is wired to `ObserveSearchPagingDataUseCase`
     * (not the SDK directly), so the proposal currently arrives via
     * an out-of-band SDK call only when the consumer explicitly invokes
     * the new SDK surface. The state slot + actions are introduced now
     * so a single subsequent commit can route the proposal through the
     * paging UseCase without further VM-shape changes (Phase 5 work).
     *
     * Visibility: internal so the screen wrapper can drive it from
     * tests until the paging path is migrated.
     */
    internal fun proposeFallback(failedTrackerId: String, proposedTrackerId: String) = intent {
        reduce {
            state.copy(
                crossTrackerFallback = CrossTrackerFallbackProposal(
                    failedTrackerId = failedTrackerId,
                    proposedTrackerId = proposedTrackerId,
                ),
            )
        }
    }

    private fun onFallbackAccept() = intent {
        // Clear the modal; the resumeWith lambda is owned by the paging
        // path that originally posted the proposal. In the current shape
        // the screen invokes resumeWith directly via a dedicated callback
        // (Task 4.18 minimal scope). Acceptance here just dismisses the
        // modal so the paging UI re-renders with the new outcome.
        reduce { state.copy(crossTrackerFallback = null) }
    }

    private fun onFallbackDismiss() = intent {
        val failed = state.crossTrackerFallback?.failedTrackerId
        reduce { state.copy(crossTrackerFallback = null) }
        if (failed != null) {
            postSideEffect(SearchResultSideEffect.ShowFallbackDismissedError(failed))
        }
    }

    private fun onSetFilterProvider(providerId: String?) = intent {
        reduce { state.copy(selectedFilterProvider = providerId) }
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

    private fun handleStreamEnd() = intent {
        val current = state.searchContent
        if (current is SearchResultContent.Streaming) {
            // Operator-reported broken-search RECOVERY fix (2026-06-23):
            // a stream that ended with NO items AND at least one provider in
            // ERROR is a FAILURE, not an empty result. Rendering Empty
            // ("Nothing found") for a failed search is the misleading shape
            // §6.AB warns about — and it left onRetryClick's Error branch
            // (lines 327-343) as unreachable dead code, so the user's only
            // recovery affordance (the Error+Retry placeholder) never showed.
            // Now: items empty + any ERROR row -> Error(reason) -> screen
            // renders the Retry button -> RetryClick re-subscribes the stream.
            // Covered by SearchResultViewModelRetryTest.
            val anyProviderFailed = current.activeProviders.any { it.status == StreamStatus.ERROR }
            if (current.items.isEmpty()) {
                if (anyProviderFailed) {
                    reduce {
                        state.copy(
                            searchContent = SearchResultContent.Error(
                                reason = "search failed",
                            ),
                        )
                    }
                } else {
                    reduce { state.copy(searchContent = SearchResultContent.Empty) }
                }
            } else {
                reduce {
                    state.copy(
                        searchContent = SearchResultContent.Content(
                            torrents = current.items,
                            categories = emptyList(),
                        ),
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
            .onFailure {
                it.rethrowIfCancellation()
                analytics.recordNonFatal(
                    it,
                    mapOf(AnalyticsTracker.Params.TOPIC_ID to topicModel.topic.id),
                )
                postSideEffect(SearchResultSideEffect.ShowFavoriteToggleError)
            }
    }

    private fun onListBottomReached() = intent {
        pagingActions.append()
    }

    private fun onRetryClick() = intent {
        // Sweep finding #2 closure (2026-05-17, 1.2.29-1049). Retry now
        // also covers the SearchResultContent.Error state introduced this
        // cycle for SSE/streaming failures. Behavior is dispatch-by-state:
        //   - If we're in Error from a stream failure → re-subscribe the
        //     same stream by calling the appropriate observe* path
        //     (mirrors the onCreate dispatch table at line 78-82).
        //   - Otherwise → fall through to Paging3's retry as before
        //     (idempotent — retry on a non-error LoadState is a no-op).
        if (state.searchContent is SearchResultContent.Error) {
            val filter = mutableFilter.value
            when {
                filter.providerIds == null -> observePagingData()
                // 2026-06-14 SEARCH FIX (operator-reported "problem reaching the
                // trackers in any scenario"): multi-provider search fans out
                // client-side via sdk.streamMultiSearch → GET /v1/{provider}/search,
                // which IS served by both the embedded on-device api-app and the
                // standalone lava-api-go. The old GoApi → observeSseSearch branch
                // targeted GET /v1/search — an endpoint NO backend registers (verified:
                // neither internal/router/router.go nor the standalone handlers) — so
                // every GoApi search 404'd. Route GoApi through the proven per-provider
                // SDK path (it carries the per-instance Lava-Auth key + permissive-LAN
                // client from the 1.3.8 wiring via ApiBaseUrlHolder).
                else -> observeStreamMultiSearch(filter)
            }
            return@intent
        }
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
        // LVA-052 — multi-search result items carry their source providerId;
        // thread it so the topic download action can branch HTTP-file vs
        // `.torrent`. Null on the single-tracker paging path.
        postSideEffect(
            SearchResultSideEffect.OpenTopic(
                id = topicModel.topic.id,
                providerId = topicModel.providerId,
            ),
        )
    }
}

/**
 * SP-4 Phase D consumer — pure-state transformation that reduces a
 * single `MultiSearchEvent` into a new `SearchPageState`. Extracted
 * to file-scope so unit tests can drive it directly without the
 * orbit VM machinery.
 *
 * Contract: if the current `searchContent` is not `Streaming`, the
 * event is ignored and the state is returned unchanged (the events
 * only make sense while a streaming search is in flight). Per-event
 * semantics:
 *
 *  - `ProviderStart`: stamps the provider's displayName into
 *    `providerDisplayNames` + into the matching `ProviderStreamStatus`
 *    row (the row pre-exists with `SEARCHING` from
 *    `observeStreamMultiSearch` init).
 *  - `ProviderResults`: appends mapped items + flips the provider's
 *    row to `DONE` with the new `resultCount`.
 *  - `ProviderFailure`: flips the provider's row to `ERROR`.
 *  - `ProviderUnsupported`: flips the provider's row to `DONE` with
 *    `resultCount = 0` (the user-visible "this provider was skipped"
 *    signal — same shape as DONE with zero results).
 *  - `AllProvidersDone`: no state change at this level; the caller's
 *    `handleStreamEnd()` downgrades Streaming → Content/Empty.
 */
internal fun applyMultiSearchEvent(
    state: SearchPageState,
    event: MultiSearchEvent,
): SearchPageState {
    val current = state.searchContent
    if (current !is SearchResultContent.Streaming) return state

    return when (event) {
        is MultiSearchEvent.ProviderStart -> state.copy(
            providerDisplayNames = state.providerDisplayNames + (event.providerId to event.displayName),
            searchContent = current.copy(
                activeProviders = current.activeProviders.map {
                    if (it.providerId == event.providerId) it.copy(displayName = event.displayName) else it
                },
            ),
        )
        is MultiSearchEvent.ProviderResults -> {
            val newItems = event.items.map { item ->
                TopicModel(
                    topic = Torrent(id = item.torrentId, title = item.title),
                    providerId = event.providerId,
                )
            }
            state.copy(
                searchContent = current.copy(
                    items = current.items + newItems,
                    activeProviders = current.activeProviders.map {
                        if (it.providerId == event.providerId) {
                            it.copy(
                                status = StreamStatus.DONE,
                                resultCount = it.resultCount + newItems.size,
                            )
                        } else {
                            it
                        }
                    },
                ),
            )
        }
        is MultiSearchEvent.ProviderFailure -> state.copy(
            searchContent = current.copy(
                activeProviders = current.activeProviders.map {
                    if (it.providerId == event.providerId) it.copy(status = StreamStatus.ERROR) else it
                },
            ),
        )
        is MultiSearchEvent.ProviderUnsupported -> state.copy(
            searchContent = current.copy(
                activeProviders = current.activeProviders.map {
                    if (it.providerId == event.providerId) {
                        it.copy(status = StreamStatus.DONE, resultCount = 0)
                    } else {
                        it
                    }
                },
            ),
        )
        is MultiSearchEvent.AllProvidersDone -> state
    }
}
