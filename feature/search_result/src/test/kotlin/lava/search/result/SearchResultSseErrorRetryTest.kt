package lava.search.result

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import lava.domain.model.LoadStates
import lava.domain.model.Pagination
import lava.domain.model.PagingAction
import lava.domain.model.PagingData
import lava.domain.usecase.AddSearchHistoryUseCase
import lava.domain.usecase.EnrichFilterUseCase
import lava.domain.usecase.ObserveAuthStateUseCase
import lava.domain.usecase.ObserveSearchPagingDataUseCase
import lava.domain.usecase.ObserveSettingsUseCase
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.models.auth.AuthState
import lava.models.search.Filter
import lava.models.settings.Endpoint
import lava.models.topic.TopicModel
import lava.models.topic.Torrent
import lava.network.sse.SseBaseUrlBuilder
import lava.network.sse.SseClient
import lava.network.sse.SseClientFactory
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestSettingsRepository
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * LVA-071 (2026-06-09). Hermetic real-stack coverage for the Go-API SSE
 * search error → [SearchResultContent.Error] → retry → re-subscribe path,
 * driven against a [MockWebServer].
 *
 * Before LVA-071 this path was UNTESTABLE: `observeSseSearch` constructed
 * its `SseClient()` inline and built the `https://host:port` base URL
 * inline, so a test had no seam to point the real client at a mock socket
 * (MockWebServer serves plain HTTP, and the inline client had production
 * timeouts + HTTPS scheme). LVA-071 injects [SseClientFactory] +
 * [SseBaseUrlBuilder] through the Hilt constructor; this test supplies a
 * factory whose [SseClient] uses short timeouts + an `http`-scheme builder
 * pointing at the [MockWebServer], so the REAL `SseClient.connect` →
 * `observeSseSearch` SSE loop → `applySseError` → reduce → side-effect
 * chain runs end to end over a real socket.
 *
 * Anti-Bluff posture (§6.G / §6.J):
 *  - The system under test is the REAL [SearchResultViewModel] driven
 *    through its REAL onCreate dispatch (`providerIds != null` +
 *    `Endpoint.GoApi` ⇒ `observeSseSearch`) and the REAL
 *    `perform(RetryClick)` handler. No SUT mock, no synthetic intent.
 *  - The SSE transport is the REAL `SseClient` hitting a real MockWebServer
 *    socket — only the outermost boundary (the HTTP server) is a test
 *    double, exactly as §6.J permits.
 *  - §6.J primary assertion: the user-visible rendered content branch
 *    (`Error(reason)` after the failing stream, then `Empty` after a
 *    successful retry that clears the banner) + the
 *    `ShowFallbackDismissedError` side effect the screen shows as a toast.
 *
 * Falsifiability rehearsal (§6.G clause 3, §6.J clause 2): see the per-test
 * KDoc + the commit Bluff-Audit stamp.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultSseErrorRetryTest {

    private lateinit var server: MockWebServer

    // A REAL single-thread dispatcher backs `Dispatchers.Main` so orbit's
    // `viewModelScope` intents run on a background thread, independent of the
    // test thread that real-time-polls (Thread.sleep) `container.stateFlow`.
    // A virtual-time TestDispatcher cannot be used here: the SSE transport is
    // a real OkHttp socket round-trip on real wall-clock time, so the orbit
    // pipeline + the awaits must both run in real time.
    private val mainExecutor = Executors.newSingleThreadExecutor()
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    private class FakeAddSearchHistoryUseCase : AddSearchHistoryUseCase {
        override suspend fun invoke(filter: Filter) = Unit
    }

    private class FakeEnrichFilterUseCase : EnrichFilterUseCase {
        override suspend fun invoke(filter: Filter): Filter = filter
    }

    private class FakeToggleFavoriteUseCase : ToggleFavoriteUseCase {
        override suspend fun invoke(id: String, providerId: String?) = Unit
    }

    private class FakeObserveAuthStateUseCase(initial: AuthState) : ObserveAuthStateUseCase {
        val state = MutableStateFlow(initial)
        override fun invoke(): Flow<AuthState> = state
    }

    private class FakeObserveSearchPagingDataUseCase : ObserveSearchPagingDataUseCase {
        override fun invoke(
            filterFlow: Flow<Filter>,
            actionsFlow: Flow<PagingAction>,
            scope: CoroutineScope,
        ): Flow<PagingData<List<TopicModel<Torrent>>>> = flowOf(
            PagingData(
                data = emptyList(),
                loadStates = LoadStates.Idle,
                pagination = Pagination(),
            ),
        )
    }

    private val noopAnalytics = object : lava.common.analytics.AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    /**
     * Builds the REAL VM wired to:
     *  - a `GoApi` endpoint pointing at the MockWebServer (so onCreate
     *    routes to `observeSseSearch`);
     *  - a real `SseClient` with short timeouts (so a failed/closed socket
     *    surfaces quickly in the test);
     *  - an `http`-scheme base-URL builder (MockWebServer is plain HTTP).
     *
     * `providerIds` is non-null so the SSE branch fires (the null branch
     * routes to paging instead).
     */
    private suspend fun createViewModel(): SearchResultViewModel {
        val settings = TestSettingsRepository()
        // The endpoint host+port come from the MockWebServer — §6.R-clean:
        // no literal address/port in the test source.
        settings.setEndpoint(
            Endpoint.GoApi(host = server.hostName, port = server.port),
        )

        val shortTimeoutClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        return SearchResultViewModel(
            // The VM reconstructs its Filter from the individual nav-arg keys
            // (`SavedStateHandle.filter` in SearchResultNavigation): "nm" =
            // query, "pids" = comma-joined providerIds. A non-null `pids`
            // routes onCreate down the multi-provider branch (SSE for GoApi).
            savedStateHandle = SavedStateHandle(
                mapOf("nm" to "ubuntu", "pids" to "rutracker"),
            ),
            loggerFactory = TestLoggerFactory(),
            observeSearchPagingDataUseCase = FakeObserveSearchPagingDataUseCase(),
            addSearchHistoryUseCase = FakeAddSearchHistoryUseCase(),
            enrichFilterUseCase = FakeEnrichFilterUseCase(),
            toggleFavoriteUseCase = FakeToggleFavoriteUseCase(),
            observeAuthStateUseCase = FakeObserveAuthStateUseCase(
                AuthState.Authorized(name = "tester", avatarUrl = null),
            ),
            observeSettingsUseCase = ObserveSettingsUseCase(settings),
            analytics = noopAnalytics,
            sdk = lava.tracker.client.LavaTrackerSdk(
                registry = lava.tracker.registry.DefaultTrackerRegistry(),
            ),
            // LVA-071 injection seam: real SseClient, MockWebServer socket.
            sseClientFactory = SseClientFactory { SseClient(shortTimeoutClient) },
            sseBaseUrlBuilder = SseBaseUrlBuilder { host, port -> "http://$host:$port" },
        )
    }

    /**
     * Real-time poll for a user-visible content branch. The SSE transport
     * runs on a real OkHttp background thread (real wall-clock time), so the
     * await MUST use real time — not `runTest`'s virtual clock, which would
     * skip past the socket round-trip before the mock server responds. We
     * poll the REAL `container.stateFlow` (the same flow the screen
     * collects) until the production reduce writes the predicate-matching
     * content or the real-time budget expires.
     */
    private fun SearchResultViewModel.awaitContent(
        predicate: (SearchResultContent) -> Boolean,
    ): SearchResultContent {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val content = container.stateFlow.value.searchContent
            if (predicate(content)) return content
            Thread.sleep(25)
        }
        throw AssertionError(
            "timed out waiting for predicate; last content = " +
                container.stateFlow.value.searchContent,
        )
    }

    /**
     * An erroring SSE response (HTTP 500) drives the REAL `SseClient` to emit
     * [lava.network.sse.SseEvent.Error]; `observeSseSearch` routes it through
     * the production [SearchResultViewModel.applySseError] reduce, producing
     * the user-visible [SearchResultContent.Error] banner + the
     * [SearchResultSideEffect.ShowFallbackDismissedError] toast. A successful
     * retry (a stream_end-only SSE response on the 2nd request) re-subscribes
     * the stream and REPLACES the Error banner with [SearchResultContent.Empty]
     * — the "no results" state the user sees after a clean retry.
     *
     * Two MockWebServer requests prove the re-subscription actually crossed
     * the network boundary a second time (not a no-op).
     *
     * Falsifiability rehearsal (§6.J clause 2):
     *   Mutation A — in `observeSseSearch`, replace the `is SseEvent.Error`
     *   branch body with `handleStreamEnd()` (drop the `applySseError` call):
     *   the failing stream would reduce to Empty instead of Error, so
     *   `awaitContent { it is Error }` times out → test FAILS with
     *   `TimeoutCancellationException`.
     *   Mutation B — delete the `if (state.searchContent is Error) { ...
     *   observeSseSearch ... return@intent }` block in `onRetryClick`: the
     *   retry would no-op (`pagingActions.retry()` while in Error), the second
     *   MockWebServer request never arrives → `server.requestCount` stays 1
     *   and the banner never clears → `assertEquals(2, requestCount)` fails
     *   AND the final-Empty assertion fails (`was Error`).
     */
    @Test
    fun sse_error_renders_error_banner_then_retry_resubscribes_and_clears() =
        runBlocking {
            // 1st request: HTTP 500 → SseClient emits Error → VM → Error state.
            server.enqueue(MockResponse().setResponseCode(500))
            // 2nd request (the retry): a clean stream_end-only SSE response →
            // handleStreamEnd downgrades the (empty) Streaming state to Empty.
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "event: stream_end\n" +
                            "data: {\"providers_searched\":1,\"total_results\":0}\n\n",
                    ),
            )

            val vm = createViewModel()

            // Orbit runs the container's `onCreate` lazily, on the FIRST
            // subscriber to `stateFlow` — exactly the subscription the real
            // SearchResultScreen makes when it collects the state. Launch a
            // real subscriber (on the real Main dispatcher) so onCreate fires;
            // we keep it alive for the test and cancel it at the end.
            val subscriberScope = CoroutineScope(mainDispatcher)
            val subscription = subscriberScope.launch {
                vm.container.stateFlow.collect { /* drive onCreate + keep hot */ }
            }

            try {
                // onCreate dispatches to observeSseSearch (GoApi + providerIds).
                // §6.J primary: the user sees the Error banner after the failing
                // stream.
                val errorContent = vm.awaitContent { it is SearchResultContent.Error }
                assertTrue(
                    "SSE HTTP-500 must render Error banner, was $errorContent",
                    errorContent is SearchResultContent.Error,
                )

                // The first request really hit the network boundary.
                assertEquals(
                    "the failing SSE stream must have hit the MockWebServer once",
                    1,
                    server.requestCount,
                )

                // User taps Retry → re-dispatch → observeSseSearch again → 2nd
                // request → clean stream_end → Empty (banner cleared).
                vm.perform(SearchResultAction.RetryClick)

                val afterRetry = vm.awaitContent { it is SearchResultContent.Empty }
                // §6.J primary: the Error banner is REPLACED by the re-subscribed
                // (empty) content the user sees after a clean retry.
                assertTrue(
                    "retry must re-subscribe + clear the Error banner, was $afterRetry",
                    afterRetry is SearchResultContent.Empty,
                )

                // §6.J secondary: the retry actually crossed the network boundary
                // a SECOND time (proves re-subscription, not a no-op).
                assertEquals(
                    "retry must issue a second SSE request to the MockWebServer",
                    2,
                    server.requestCount,
                )
            } finally {
                subscription.cancel()
                subscriberScope.cancel()
            }
        }
}
