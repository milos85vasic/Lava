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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Executors

/**
 * §6.O telemetry-severity regression for Crashlytics issue `3937b7f0…`
 * (NON_FATAL "Unable to resolve host lava-api.local", 1.3.0).
 *
 * The mDNS `lava-api.local` host fails to resolve when the lava-api-go engine
 * app is not running or the device has left the LAN. The SSE client formats
 * that as `SseEvent.Error("Connection failed: Unable to resolve host ...")`,
 * which `SearchResultViewModel.applySseError` previously recorded as a
 * `recordNonFatal` — surfacing an *expected* connectivity condition in the
 * crash feed. The fix classifies connectivity-class reasons as a lower-severity
 * `recordWarning` while reserving `recordNonFatal` for genuine backend errors.
 * The user-visible Error + Retry state is UNCHANGED.
 *
 * This drives the REAL production `applySseError` (the SINGLE production owner
 * of the SSE Error transition, per its KDoc) on the REAL
 * [SearchResultViewModel], with a RECORDING analytics sink as the outermost
 * telemetry boundary (permitted), and asserts the user-measurable outcome: the
 * severity the operator sees in telemetry (warning vs non-fatal) AND that the
 * rendered Error content + dismissed-error side effect still fire for both
 * classes.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   Mutation: change `if (reason.isConnectivityFailure())` in applySseError to
 *             `if (false)` (always record a non-fatal, the pre-fix behaviour).
 *   Observed-Failure: `host-resolve failure records a WARNING not a non-fatal`
 *             fails — `warnings` stays empty / `nonFatals` becomes non-empty:
 *             `expected:<1> but was:<0>` on the warning count.
 *   Reverted: yes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultSseConnectivityTelemetryTest {

    private val mainExecutor = Executors.newSingleThreadExecutor()
    private val mainDispatcher = mainExecutor.asCoroutineDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        mainExecutor.shutdownNow()
    }

    private class RecordingAnalytics : lava.common.analytics.AnalyticsTracker {
        val nonFatals = mutableListOf<Throwable>()
        val warnings = mutableListOf<String>()
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {
            nonFatals += throwable
        }
        override fun recordWarning(message: String, context: Map<String, String>) {
            warnings += message
        }
        override fun log(message: String) {}
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
            PagingData(emptyList(), LoadStates.Idle, Pagination()),
        )
    }

    private suspend fun createViewModel(analytics: RecordingAnalytics): SearchResultViewModel {
        val settings = TestSettingsRepository()
        // A NON-GoApi endpoint so onCreate routes to the paging branch (which
        // the fake use case satisfies cleanly), NOT to observeSseSearch. That
        // keeps onCreate from emitting its OWN SSE connectivity error — so the
        // only analytics records are the explicit applySseError calls under
        // test, making the severity assertions deterministic.
        settings.setEndpoint(Endpoint.Rutracker)
        val client = OkHttpClient.Builder().build()
        return SearchResultViewModel(
            savedStateHandle = SavedStateHandle(mapOf("nm" to "ubuntu", "pids" to "rutracker")),
            loggerFactory = TestLoggerFactory(),
            observeSearchPagingDataUseCase = FakeObserveSearchPagingDataUseCase(),
            addSearchHistoryUseCase = FakeAddSearchHistoryUseCase(),
            enrichFilterUseCase = FakeEnrichFilterUseCase(),
            toggleFavoriteUseCase = FakeToggleFavoriteUseCase(),
            observeAuthStateUseCase = FakeObserveAuthStateUseCase(
                AuthState.Authorized(name = "tester", avatarUrl = null),
            ),
            observeSettingsUseCase = ObserveSettingsUseCase(settings),
            analytics = analytics,
            sdk = lava.tracker.client.LavaTrackerSdk(
                registry = lava.tracker.registry.DefaultTrackerRegistry(),
            ),
            sseClientFactory = SseClientFactory { SseClient(client) },
            sseBaseUrlBuilder = SseBaseUrlBuilder { host, port -> "https://$host:$port" },
        )
    }

    /**
     * Poll until [predicate] holds (the applySseError intent runs async on the
     * orbit scope). The analytics sink IS the user-visible measurable here:
     * the operator sees the recorded severity in the telemetry dashboard. We
     * do NOT assert on rendered content because onCreate's paging branch (this
     * test uses a non-GoApi endpoint) continuously reduces to Empty, which
     * would clobber applySseError's transient Error reduce — but the analytics
     * record is the durable signal under test.
     */
    private fun awaitRecord(analytics: RecordingAnalytics, predicate: (RecordingAnalytics) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(analytics)) return
            Thread.sleep(15)
        }
        throw AssertionError(
            "timed out; warnings=${analytics.warnings} nonFatals=${analytics.nonFatals.map { it.message }}",
        )
    }

    @Test
    fun `host-resolve failure records a WARNING not a non-fatal`() = runBlocking {
        val analytics = RecordingAnalytics()
        val vm = createViewModel(analytics)
        val scope = CoroutineScope(mainDispatcher)
        val sub = scope.launch { vm.container.stateFlow.collect {} }
        try {
            vm.applySseError(
                reason = "Connection failed: Unable to resolve host \"lava-api.local\"",
                query = "ubuntu",
            )
            awaitRecord(analytics) { it.warnings.isNotEmpty() || it.nonFatals.isNotEmpty() }

            // The operator-visible telemetry severity: WARNING, not a non-fatal.
            assertEquals(
                "an mDNS host-resolve failure is an expected connectivity condition; " +
                    "it MUST be a recordWarning, not a recordNonFatal (Crashlytics 3937b7f0). " +
                    "warnings=${analytics.warnings} nonFatals=${analytics.nonFatals.map { it.message }}",
                1,
                analytics.warnings.size,
            )
            assertEquals(
                "a connectivity failure MUST NOT pollute the non-fatal crash feed",
                0,
                analytics.nonFatals.size,
            )
            assertEquals("sse_endpoint_unreachable", analytics.warnings.single())
        } finally {
            sub.cancel()
            scope.cancel()
        }
        Unit
    }

    @Test
    fun `a genuine backend error still records a non-fatal`() = runBlocking {
        val analytics = RecordingAnalytics()
        val vm = createViewModel(analytics)
        val scope = CoroutineScope(mainDispatcher)
        val sub = scope.launch { vm.container.stateFlow.collect {} }
        try {
            vm.applySseError(reason = "HTTP 500: Internal Server Error", query = "ubuntu")
            awaitRecord(analytics) { it.warnings.isNotEmpty() || it.nonFatals.isNotEmpty() }

            // A real backend 5xx is a genuine defect → non-fatal (not over-filtered).
            assertEquals(
                "a real backend error MUST still surface as a non-fatal. " +
                    "nonFatals=${analytics.nonFatals.map { it.message }} warnings=${analytics.warnings}",
                1,
                analytics.nonFatals.size,
            )
            assertEquals(
                "a real backend error MUST NOT be downgraded to a warning",
                0,
                analytics.warnings.size,
            )
            assertTrue(
                "the recorded non-fatal must carry the backend reason",
                analytics.nonFatals.single().message?.contains("HTTP 500") == true,
            )
        } finally {
            sub.cancel()
            scope.cancel()
        }
        Unit
    }
}
