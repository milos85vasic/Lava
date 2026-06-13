package lava.search.result

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import lava.models.settings.Endpoint
import lava.models.topic.Author
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.Torrent
import lava.network.sse.SseBaseUrlBuilder
import lava.network.sse.SseClientFactory
import lava.network.sse.SseEvent
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
    // LVA-071 (2026-06-09): SSE client + base-URL builder injected so the
    // error → Error → retry path is hermetically testable against a
    // MockWebServer. Defaults preserve pre-LVA-071 production behaviour for
    // any caller that still constructs the VM directly without supplying
    // them (the previous code created `SseClient()` inline + built the
    // `https://host:port` URL inline).
    private val sseClientFactory: SseClientFactory = SseClientFactory.Default,
    private val sseBaseUrlBuilder: SseBaseUrlBuilder = SseBaseUrlBuilder.Https,
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
                currentEndpointIsGoApi() -> observeSseSearch(filter)
                else -> observeStreamMultiSearch(filter)
            }
        },
    )

    /**
     * SP-4 Phase D (2026-05-13). Read the persisted endpoint once at
     * onCreate to choose between SSE (Go API path) and client-direct
     * `streamMultiSearch`.
     */
    private suspend fun currentEndpointIsGoApi(): Boolean {
        return try {
            observeSettingsUseCase().first().endpoint is Endpoint.GoApi
        } catch (t: Throwable) {
            t.rethrowIfCancellation()
            // no-telemetry: feature-flag-style endpoint probe — the
            // boolean false return causes the ViewModel to fall through
            // to the client-direct SDK path (SP-4 Phase D), which IS the
            // safe fallback behavior. Throwable here generally means
            // settings repository not yet hot; firing telemetry on every
            // hot-restart would be noise.
            false
        }
    }

    /**
     * SP-4 Phase D (2026-05-13). Client-direct multi-provider parallel
     * search via `LavaTrackerSdk.streamMultiSearch`. Used when no Go
     * API endpoint is configured (the SSE path's prerequisite). The
     * UI state shape is the same `SearchResultContent.Streaming` the
     * SSE handler drives; only the event source differs.
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
        // state to Content (or Empty if no items arrived) mirroring the
        // SSE handler's handleStreamEnd().
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
            is MultiSearchEvent.ProviderFailure -> Unit
            is MultiSearchEvent.ProviderUnsupported -> Unit
            is MultiSearchEvent.AllProvidersDone -> {
                // Final snapshot — UI already reflects per-provider
                // results via the incremental events; no additional
                // reduce needed here. handleStreamEnd() in the caller
                // downgrades to Content/Empty.
            }
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

    /**
     * Sweep finding #2 closure (2026-05-17). The SSE-failure transition:
     * record the non-fatal, reduce to [SearchResultContent.Error], and
     * post the dismissed-error side effect. Extracted from the inline
     * `observeSseSearch` error branch so it is the SINGLE production owner
     * of the Error transition — `observeSseSearch` calls it on a real SSE
     * `Error` event, and the LVA-069 retry test calls the same method to
     * reach the production Error state deterministically (no test-only
     * backdoor: this IS the production code path the SSE error runs).
     *
     * Visibility: internal so the retry test can drive the real transition.
     */
    internal fun applySseError(reason: String, query: String) = intent {
        // §6.O telemetry refinement for Crashlytics issue `3937b7f0…`
        // (NON_FATAL "Unable to resolve host lava-api.local", 1.3.0). An
        // mDNS `.local` host that won't resolve, a refused connection, or a
        // timed-out connect are EXPECTED connectivity conditions when the
        // lava-api-go engine app is not running or the device has left the
        // LAN — NOT backend defects. Recording each one as a non-fatal
        // (which surfaces in the crash feed) pollutes the feed the same way
        // the §6.AC cancellation noise did. Classify connectivity-class
        // reasons as a lower-severity warning (still operator-visible for
        // triage) and reserve recordNonFatal for genuine stream/backend
        // errors. The user-visible Error + Retry state is UNCHANGED — the
        // user still sees an actionable error and can retry.
        if (reason.isConnectivityFailure()) {
            analytics.recordWarning(
                "sse_endpoint_unreachable",
                mapOf(
                    AnalyticsTracker.Params.QUERY to query,
                    // The sink (FirebaseAnalyticsTracker) caps values at its
                    // own MAX_VALUE_CHARS; no extra truncation needed here.
                    AnalyticsTracker.Params.ERROR to reason,
                ),
            )
        } else {
            analytics.recordNonFatal(
                IllegalStateException("SSE error: $reason"),
                mapOf(AnalyticsTracker.Params.QUERY to query),
            )
        }
        reduce {
            state.copy(searchContent = SearchResultContent.Error(reason))
        }
        postSideEffect(SearchResultSideEffect.ShowFallbackDismissedError("SSE"))
    }

    /**
     * True if an SSE [SseEvent.Error] message describes an EXPECTED
     * connectivity condition rather than a backend defect. The SSE client
     * formats connect-time failures as `"Connection failed: <cause>"`
     * (see `SseClient.connect`), where `<cause>` is the OkHttp/JDK message —
     * e.g. `Unable to resolve host "lava-api.local"` (UnknownHostException,
     * the mDNS engine not on the LAN), `Failed to connect to ...` /
     * `Connection refused` (engine not listening), or `timeout`.
     */
    private fun String.isConnectivityFailure(): Boolean {
        val r = lowercase()
        return r.contains("unable to resolve host") ||
            r.contains("connection failed") ||
            r.contains("failed to connect") ||
            r.contains("connection refused") ||
            r.contains("unknownhost") ||
            r.contains("timeout") ||
            r.contains("timed out")
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

    private fun observeSseSearch(filter: Filter) = intent {
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

        val client = sseClientFactory.create()
        val currentSettings = observeSettingsUseCase().first()
        val apiBaseUrl = when (val ep = currentSettings.endpoint) {
            is Endpoint.GoApi -> sseBaseUrlBuilder.build(ep.host, ep.port)
            else -> return@intent
        }
        val params = buildString {
            append("?q=${filter.query.orEmpty()}")
            append("&providers=${providerIds.joinToString(",")}")
            append("&sort=${filter.sort}")
            append("&order=${filter.order}")
        }

        val headers = mapOf<String, String>()

        client.connect("$apiBaseUrl/v1/search$params", headers).collect { event ->
            when (event) {
                is SseEvent.Event -> handleSseEvent(event)
                is SseEvent.StreamEnd -> handleStreamEnd()
                is SseEvent.Error -> {
                    // Sweep finding #2 closure (2026-05-17, 1.2.29-1049).
                    // Pre-fix this set searchContent = Empty which rendered
                    // "Nothing found" — the same misleading-shape failure
                    // mode SP-3.2 fixed for Unauthorized. Now route to the
                    // new SearchResultContent.Error(reason) which the
                    // Screen renders with an actionable Retry button.
                    val reason = event.message.ifEmpty { "Search stream failed" }
                    applySseError(reason, filter.query.orEmpty())
                }
            }
        }
    }

    private fun handleSseEvent(event: SseEvent.Event) = intent {
        // §6.J anti-bluff: the raw-JSON parse + reduce is extracted to the
        // file-scope pure function `applySseEvent` so the Go-API SSE
        // consumer branch (previously untested — only the client-direct
        // `applyMultiSearchEvent` path had coverage) can be driven directly
        // by a unit test feeding each event's JSON shape. The intent here is
        // the thin orbit wrapper; the parsing logic the LVA-057-class bugs
        // live in (malformed event dropped, wrong field mapping,
        // provider_error not surfaced) is now falsifiable.
        reduce { applySseEvent(state, event.type, event.data) }
    }

    private fun handleStreamEnd() = intent {
        val current = state.searchContent
        if (current is SearchResultContent.Streaming) {
            if (current.items.isEmpty()) {
                reduce { state.copy(searchContent = SearchResultContent.Empty) }
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
                currentEndpointIsGoApi() -> observeSseSearch(filter)
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

/**
 * SP-4 Phase D Go-API SSE consumer — pure-state transformation that
 * parses ONE raw SSE event (its `type` + the JSON `data` payload the
 * Go API streams over `/v1/search`) and reduces it into a new
 * [SearchPageState]. Extracted to file-scope (mirroring
 * [applyMultiSearchEvent], the client-direct SDK path) so unit tests can
 * drive the raw-JSON parsing directly without a running HTTPS Go-API.
 *
 * The Go-API multi-search stream emits four event types per
 * `lava-api-go`'s SSE contract; this function maps each to the
 * user-visible [SearchResultContent.Streaming] state the Compose UI
 * renders (the per-provider status badges + the result list):
 *
 *  - `provider_start` → stamps `display_name` into `providerDisplayNames`
 *    + the matching `ProviderStreamStatus` row (pre-existing with
 *    `SEARCHING`). Falls back to the bare `provider_id` if `display_name`
 *    is absent.
 *  - `results` → maps each `items[]` element (`id` + `title`) into a
 *    `TopicModel` carrying the per-provider id, appends them to the
 *    result list, and flips the row to `RECEIVING` with an incremented
 *    `resultCount`.
 *  - `provider_done` → flips the row to `DONE` with the server-reported
 *    `result_count`.
 *  - `provider_error` → flips the row to `ERROR` (the per-provider error
 *    chip the user sees) WITHOUT failing the whole stream.
 *
 * Contract guarantees (the LVA-057-class concerns this function is the
 * source-of-truth for):
 *  - If `searchContent` is not [SearchResultContent.Streaming], the event
 *    is ignored and state returned unchanged (late events after the user
 *    navigated away MUST NOT overwrite the now-visible list).
 *  - A malformed event missing its `provider_id` is a no-op (state
 *    returned unchanged) — it is NEVER allowed to crash the parse or
 *    silently corrupt another provider's row.
 *  - An unknown `type` is a no-op.
 */
internal fun applySseEvent(
    state: SearchPageState,
    type: String,
    data: String,
): SearchPageState {
    val current = state.searchContent
    if (current !is SearchResultContent.Streaming) return state

    val json = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull()
        ?: return state

    return when (type) {
        "provider_start" -> {
            val pid = json["provider_id"]?.jsonPrimitive?.content ?: return state
            val dname = json["display_name"]?.jsonPrimitive?.content ?: pid
            state.copy(
                providerDisplayNames = state.providerDisplayNames + (pid to dname),
                searchContent = current.copy(
                    activeProviders = current.activeProviders.map {
                        if (it.providerId == pid) it.copy(displayName = dname) else it
                    },
                ),
            )
        }
        "results" -> {
            val pid = json["provider_id"]?.jsonPrimitive?.content ?: return state
            val itemsJson = json["items"]?.jsonArray ?: return state
            val newItems = itemsJson.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val title = obj["title"]?.jsonPrimitive?.content ?: ""
                TopicModel(
                    topic = Torrent(id = id, title = title),
                    providerId = pid,
                )
            }
            state.copy(
                searchContent = current.copy(
                    items = current.items + newItems,
                    activeProviders = current.activeProviders.map {
                        if (it.providerId == pid) {
                            it.copy(
                                status = StreamStatus.RECEIVING,
                                resultCount = it.resultCount + newItems.size,
                            )
                        } else {
                            it
                        }
                    },
                ),
            )
        }
        "provider_done" -> {
            val pid = json["provider_id"]?.jsonPrimitive?.content ?: return state
            val count = json["result_count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            state.copy(
                searchContent = current.copy(
                    activeProviders = current.activeProviders.map {
                        if (it.providerId == pid) {
                            it.copy(status = StreamStatus.DONE, resultCount = count)
                        } else {
                            it
                        }
                    },
                ),
            )
        }
        "provider_error" -> {
            val pid = json["provider_id"]?.jsonPrimitive?.content ?: return state
            state.copy(
                searchContent = current.copy(
                    activeProviders = current.activeProviders.map {
                        if (it.providerId == pid) it.copy(status = StreamStatus.ERROR) else it
                    },
                ),
            )
        }
        else -> state
    }
}
