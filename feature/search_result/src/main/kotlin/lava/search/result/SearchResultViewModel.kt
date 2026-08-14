package lava.search.result

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeout
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
import lava.domain.usecase.StartupProvidersGate
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
import lava.tracker.api.model.TimePeriod
import lava.tracker.client.ApiHttpException
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
    // LVA-093 (2026-08): cold-start provider-repopulation readiness gate — see
    // [StartupProvidersGate] KDoc for the race this closes.
    private val providersReadyGate: StartupProvidersGate,
) : ViewModel(), ContainerHost<SearchPageState, SearchResultSideEffect> {
    private val logger = loggerFactory.get("SearchResultViewModel")
    private val mutableFilter = MutableStateFlow(savedStateHandle.filter)
    private val pagingActions = MutableSharedFlow<PagingAction>()

    /**
     * 2026-06-24 CANCEL-ON-BACK FIX (operator-reported "can't go back or
     * interrupt" during a slow search — Crashlytics issue 9d4ad2f4…).
     *
     * The `observeStreamMultiSearch` intent blocks on
     * `sdk.streamMultiSearch(...).collect {}`. Because Orbit `intent {}`
     * blocks are individual coroutines on the container's scope, back-press
     * (which only posts a `Back` side effect) never cancels that blocking
     * collect — the user waits the full OkHttp readTimeout (30s) before
     * the hung Streaming spinner transitions to Error/Empty.
     *
     * Fix: store the `Job` returned by the streaming intent so
     * `onBackClick()` can cancel it explicitly before posting `Back`.
     * The [withTimeout] in [observeStreamMultiSearch] provides a client-side
     * deadline (25s < OkHttp 30s) so a slow-but-not-hung provider surfaces
     * `Error + Retry` before the OS-level network stack times out.
     *
     * Thread-safety: written only from the container's single-threaded
     * intent dispatcher; no external mutation.
     */
    @Volatile
    private var activeSearchJob: Job? = null

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
     *
     * 2026-06-24 cancel-on-back + timeout fixes:
     *  - The returned [Job] is stored in [activeSearchJob] so [onBackClick]
     *    can cancel the in-flight collection without waiting for the OS-level
     *    network timeout (30s OkHttp readTimeout).
     *  - [withTimeout] with [SEARCH_TIMEOUT_MS] (25s) gives the user an
     *    actionable Error+Retry state before OkHttp's own 30s fires. A
     *    [TimeoutCancellationException] is caught here; it does NOT propagate
     *    as a coroutine-cancellation signal because `withTimeout` inside an
     *    Orbit `intent {}` is a LOCAL timeout, not a scope cancel.
     *  - Any other [Exception] (IOException, ApiHttpException, etc.) is also
     *    caught and recorded so an unhandled throw cannot leave the UI stuck
     *    on the Streaming spinner indefinitely. Back-press [Job.cancel] itself
     *    throws [CancellationException] which is re-thrown so Orbit's scope
     *    cleans up correctly (rethrowIfCancellation() in callers).
     */
    private fun observeStreamMultiSearch(filter: Filter) = intent {
        val providerIds = filter.providerIds
        if (providerIds.isNullOrEmpty()) return@intent

        // Defensive (code-review 2026-06-24): cancel any prior in-flight search
        // before starting a new one so the single-slot activeSearchJob can never
        // leak an earlier collection. Not reachable today (each new query opens a
        // fresh ViewModel), but future-proofs against any double-invocation.
        activeSearchJob?.cancel()
        // Register this job so back-press can cancel it immediately.
        activeSearchJob = currentCoroutineContext()[Job]

        // LVA-085 x LVA-093 composition fix (2026-08-13): the readiness wait
        // MUST happen BEFORE resolving display names, not after. Originally
        // (LVA-093, commit 9d85c5cf) this wait was placed AFTER the initial
        // Streaming-state reduce, on the reasoning that the wait only guards
        // `sdk.streamMultiSearch`'s tracker-client resolution below. That
        // missed that LVA-085 (commit 80c9380d, landed the SAME cycle) added
        // an EARLIER `sdk.listAvailableTrackers()` read — the display-name
        // resolution — inside that same pre-wait reduce. On a genuine cold
        // start, if `RepopulateProvidersOnStartupUseCase` has not finished
        // yet, that earlier read silently returned the stale/bundled
        // registry, reintroducing the exact raw-provider-id bug LVA-085
        // claimed to fix (chips render "archiveorg" instead of
        // "Archive.org") — invisible to both fixes' own unit tests because
        // each test suite's default fixture pre-marks the gate ready before
        // exercising its own concern in isolation. Awaiting the gate FIRST
        // — bounded by [StartupProvidersGate.DEFAULT_AWAIT_TIMEOUT_MS] (5s,
        // independent of and far shorter than the 25s search timeout) —
        // means the very first frame the user sees (both the loading
        // indicator, LVA-086, and the resolved chip labels, LVA-085) reads
        // the SAME already-settled registry the actual search below uses.
        providersReadyGate.awaitReady()

        // LVA-085 (2026-06-25 QA video #4): eagerly resolve every requested
        // provider's display name from the live tracker registry — the SAME
        // source `lava.search.input.ProviderDisplayNameResolver` uses for the
        // search-input chip bar (`LavaTrackerSdk.listAvailableTrackers()` →
        // `trackerId to displayName`) — and seed it into `providerDisplayNames`
        // in the SAME reduce that opens the `Streaming` state.
        //
        // Root cause: `providerDisplayNames` used to start as `emptyMap()` and
        // was populated ONLY as each provider's async `MultiSearchEvent
        // .ProviderStart` arrived (one event per provider, dispatched from
        // inside `sdk.streamMultiSearch`'s per-provider coroutines — see
        // `applyMultiSearchEvent` below). `SearchResultScreen
        // .ProviderFilterChipBar` renders from this map via
        // `providerDisplayNames[pid] ?: pid` the INSTANT `filterProviderChipIds`
        // is non-empty — i.e. on the very first composed frame of the
        // `Streaming` state, strictly before any `ProviderStart` event can
        // possibly have arrived. Every results-screen chip therefore rendered
        // the raw provider id ("archiveorg", "torrentdownloads", "kinozal",
        // "yts") instead of its display name ("Archive.org",
        // "TorrentDownloads", "Kinozal.tv", "YTS") for at least that first
        // frame — exactly what the 2026-06-25 QA video captured at frames
        // 0060/0125/0130. `listAvailableTrackers()` is a synchronous,
        // in-memory registry lookup (no network I/O), so resolving it here
        // costs nothing and removes the race entirely.
        val resolvedDisplayNames = sdk.listAvailableTrackers()
            .filter { it.trackerId in providerIds }
            .associate { it.trackerId to it.displayName }

        reduce {
            state.copy(
                providerDisplayNames = state.providerDisplayNames + resolvedDisplayNames,
                searchContent = SearchResultContent.Streaming(
                    items = emptyList(),
                    activeProviders = providerIds.map { pid ->
                        ProviderStreamStatus(
                            providerId = pid,
                            displayName = resolvedDisplayNames[pid] ?: pid,
                            status = StreamStatus.SEARCHING,
                        )
                    },
                ),
            )
        }

        val request = SearchRequest(
            query = filter.query.orEmpty(),
            categories = filter.categories?.map { it.id }.orEmpty(),
            sort = filter.sort.toSortField(),
            sortOrder = filter.order.toSortOrder(),
            author = filter.author?.name,
            period = filter.period.toTimePeriod(),
        )

        try {
            withTimeout(SEARCH_TIMEOUT_MS) {
                sdk.streamMultiSearch(request, providerIds).collect { event ->
                    handleMultiSearchEvent(event)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Client-side deadline exceeded — the engine is slow but we don't want the
            // user stuck on the Streaming spinner until OkHttp's own 30s readTimeout fires.
            // Mark all still-SEARCHING providers as ERROR so handleStreamEnd() produces
            // SearchResultContent.Error (with its Retry button) instead of Empty.
            // §6.AC telemetry: record as a warning so the Crashlytics non-fatal feed shows
            // how often providers exceed the client deadline (no credentials per §6.H).
            analytics.recordWarning(
                "streamMultiSearch timeout after ${SEARCH_TIMEOUT_MS}ms",
                mapOf(
                    AnalyticsTracker.Params.FEATURE to "search",
                    AnalyticsTracker.Params.OPERATION to "streamMultiSearch",
                    AnalyticsTracker.Params.SCREEN to "search_result",
                    AnalyticsTracker.Params.ERROR_CLASS to "TimeoutCancellationException",
                ),
            )
            reduce {
                val current = state.searchContent
                if (current is SearchResultContent.Streaming) {
                    state.copy(
                        searchContent = current.copy(
                            activeProviders = current.activeProviders.map { p ->
                                if (p.status == StreamStatus.SEARCHING) p.copy(status = StreamStatus.ERROR) else p
                            },
                        ),
                    )
                } else {
                    state
                }
            }
        } catch (e: Exception) {
            e.rethrowIfCancellation()
            // Unexpected whole-stream exception (e.g. connectivity lost before
            // the first event). Record non-fatal and fall through to handleStreamEnd()
            // which will see 0 items + ERROR providers → Error(reason).
            analytics.recordNonFatal(
                e,
                mapOf(
                    AnalyticsTracker.Params.FEATURE to "search",
                    AnalyticsTracker.Params.OPERATION to "streamMultiSearch",
                    AnalyticsTracker.Params.SCREEN to "search_result",
                    AnalyticsTracker.Params.ERROR_CLASS to e::class.simpleName.orEmpty(),
                    AnalyticsTracker.Params.ERROR_MESSAGE to (e.message?.take(512) ?: ""),
                ),
            )
            reduce {
                val current = state.searchContent
                if (current is SearchResultContent.Streaming) {
                    state.copy(
                        searchContent = current.copy(
                            activeProviders = current.activeProviders.map { p ->
                                if (p.status == StreamStatus.SEARCHING) p.copy(status = StreamStatus.ERROR) else p
                            },
                        ),
                    )
                } else {
                    state
                }
            }
        } finally {
            activeSearchJob = null
        }
        // After the flow completes (naturally or via timeout/exception),
        // downgrade the Streaming state to Content / Error / Empty.
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
        // §6.AC local-trace gap (2026-08-14): the cause was previously ONLY
        // sent to remote Crashlytics via analytics.recordNonFatal/recordWarning
        // below — invisible to on-device logcat and to CI/device-gate
        // evidence capture (which has no Crashlytics read API). Logging it
        // locally too makes a search failure's exact cause (HTTP status,
        // exception class, message) diagnosable from a captured logcat alone.
        logger.e(t = event.cause) {
            "provider search failed: providerId=${event.providerId} reason=${event.reason} " +
                "causeClass=${event.cause?.let { it::class.simpleName }}"
        }
        val baseContext = mapOf(
            AnalyticsTracker.Params.FEATURE to "search",
            AnalyticsTracker.Params.OPERATION to "streamMultiSearch",
            AnalyticsTracker.Params.SCREEN to "search_result",
            AnalyticsTracker.Params.PROVIDER to event.providerId,
            AnalyticsTracker.Params.ERROR_MESSAGE to event.reason,
        )
        val cause = event.cause
        if (cause != null) {
            cause.rethrowIfCancellation()
            // §6.AC: When the cause is an ApiHttpException, enrich the context with
            // structured HTTP diagnostic keys so the Crashlytics non-fatal record
            // carries enough information to triage the failure (status + url + method
            // + backend host) without any credentials (§6.H — query-stripped url,
            // no tokens, no cookies).
            val context = if (cause is ApiHttpException) {
                val hostOnly = try {
                    val idx = cause.requestUrl.indexOf("//").let { if (it >= 0) it + 2 else 0 }
                    val noScheme = cause.requestUrl.substring(idx)
                    noScheme.substringBefore("/")
                } catch (_: Exception) {
                    // no-telemetry: URL host-string extraction fallback within the non-fatal
                    // recording helper; the outer analytics.recordNonFatal() call below is the
                    // real telemetry — this catch only selects the fallback context value.
                    cause.requestUrl
                }
                baseContext + mapOf(
                    AnalyticsTracker.Params.HTTP_STATUS to cause.statusCode.toString(),
                    AnalyticsTracker.Params.REQUEST_URL to cause.requestUrl,
                    AnalyticsTracker.Params.HTTP_METHOD to cause.httpMethod,
                    AnalyticsTracker.Params.BASE_URL_HOST to hostOnly,
                )
            } else {
                baseContext
            }
            analytics.recordNonFatal(cause, context)
        } else {
            analytics.recordWarning(event.reason, baseContext)
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
            // 2026-06-26 operator-video FIX (issue #1 "Something went wrong"):
            // distinguish a GENUINE failure (EVERY provider errored) from a
            // PARTIAL failure (some provider succeeded with 0 results while
            // another errored). The prior `anyProviderFailed` rule rendered the
            // full-screen Error whenever ANY single provider errored + items
            // were empty — so a search where yts legitimately returned 0 hits
            // for "1080p" (a quality term, not a movie title) while a DOWN
            // provider (torrentdownloads, Cloudflare 522) errored, wrongly
            // showed "search failed". A provider that searched and found
            // nothing is a valid "No results", NOT a failure. Only when EVERY
            // provider errored (nothing actually searched) is it a real Error.
            // Reproduced by SearchResultViewModelPartialFailureTest +
            // engine-side curl evidence (.lava-ci-evidence/1076-repro/).
            val providers = current.activeProviders
            val allProvidersFailed =
                providers.isNotEmpty() && providers.all { it.status == StreamStatus.ERROR }
            if (current.items.isEmpty()) {
                if (allProvidersFailed) {
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
        // 2026-06-24 CANCEL-ON-BACK FIX (operator-reported "can't go back or
        // interrupt" — Crashlytics 9d4ad2f4…). Cancel the in-flight streaming
        // job BEFORE posting the Back side effect so the UI does not remain
        // frozen on the Streaming spinner for the remaining OkHttp readTimeout
        // (30s) after the user has already pressed Back. Cancellation of a
        // completed (null) job is a safe no-op.
        activeSearchJob?.cancel()
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

    companion object {
        /**
         * Client-side deadline for the full multi-provider streaming search.
         *
         * Set 5 s below OkHttp's `readTimeout` (30 s — `NetworkModule`) so
         * a slow provider surfaces [SearchResultContent.Error] with a Retry
         * affordance BEFORE the OS-level network stack closes the socket.
         * The remaining 5 s gives OkHttp time to drain its own buffers and
         * fire its own cancellation cleanly — avoids a race where both the
         * client timeout and the socket timeout fire simultaneously.
         *
         * Tested by [SearchResultViewModelCancelTimeoutTest
         * .slow_provider_exceeding_timeout_surfaces_Error_with_Retry_affordance].
         */
        internal const val SEARCH_TIMEOUT_MS = 25_000L
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

// ---- domain-to-API model mapping helpers ---------------------------------

private fun Sort.toSortField(): SortField = when (this) {
    Sort.DATE -> SortField.DATE
    Sort.TITLE -> SortField.TITLE
    Sort.DOWNLOADED -> SortField.DATE // approximated — counts not in API model
    Sort.SEEDS -> SortField.SEEDERS
    Sort.LEECHES -> SortField.LEECHERS
    Sort.SIZE -> SortField.SIZE
}

private fun Order.toSortOrder(): SortOrder = when (this) {
    Order.ASCENDING -> SortOrder.ASCENDING
    Order.DESCENDING -> SortOrder.DESCENDING
}

private fun Period.toTimePeriod(): TimePeriod? = when (this) {
    Period.ALL_TIME -> TimePeriod.ALL_TIME
    Period.TODAY -> null // not in API model; let provider decide
    Period.LAST_THREE_DAYS -> null
    Period.LAST_WEEK -> TimePeriod.LAST_WEEK
    Period.LAST_TWO_WEEKS -> null
    Period.LAST_MONTH -> TimePeriod.LAST_MONTH
}
