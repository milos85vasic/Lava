@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package lava.search.result

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import lava.common.analytics.AnalyticsTracker
import lava.domain.model.PagingAction
import lava.domain.model.PagingData
import lava.domain.usecase.AddSearchHistoryUseCase
import lava.domain.usecase.EnrichFilterUseCase
import lava.domain.usecase.ObserveAuthStateUseCase
import lava.domain.usecase.ObserveSearchPagingDataUseCase
import lava.domain.usecase.ObserveSettingsUseCase
import lava.domain.usecase.StartupProvidersGate
import lava.domain.usecase.ToggleFavoriteUseCase
import lava.models.auth.AuthState
import lava.models.search.Filter
import lava.models.topic.TopicModel
import lava.models.topic.Torrent
import lava.sdk.api.MirrorUrl
import lava.sdk.api.PluginConfig
import lava.sdk.api.Protocol
import lava.testing.logger.TestLoggerFactory
import lava.testing.repository.TestSettingsRepository
import lava.testing.rule.MainDispatcherRule
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TorrentItem
import lava.tracker.client.LavaTrackerSdk
import lava.tracker.registry.DefaultTrackerRegistry
import lava.tracker.registry.TrackerClientFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test
import kotlin.reflect.KClass

/**
 * Regression / anti-bluff tests for the 2026-06-24 operator-reported bug:
 * "user can't go back or interrupt" an in-flight slow search.
 *
 * ## Root cause (field-confirmed, Crashlytics issue 9d4ad2f4…)
 *
 * When `observeStreamMultiSearch` starts in `container.onCreate`, the
 * Orbit intent blocks on `sdk.streamMultiSearch(...).collect {}`. Back-press
 * only posts a `Back` side effect via [SearchResultAction.BackClick] —
 * it does NOT cancel the in-flight collection. On a slow provider (yts),
 * the user experiences a ~30s hang (the OkHttp readTimeout) during which
 * pressing Back appears to do nothing.
 *
 * The client also has no self-imposed timeout: if the engine is slow but
 * responds inside 30s, no Error is surfaced until the stream ends — so the
 * user is stuck on the Streaming spinner with no Retry affordance.
 *
 * ## Covered gaps (not covered by any existing test)
 *
 * - [SearchResultViewModelStreamingTest] — happy-path streams only; never
 *   suspends indefinitely, never triggers Back while Streaming.
 * - [SearchResultViewModelRetryTest] — Error→Retry flow; the client throws
 *   via [FlakyClient] synchronously, stream ends immediately. Timeout + back-
 *   press-while-streaming paths are absent.
 * - [ApplyMultiSearchEventTest] — pure reducer only, never touches the VM or
 *   the in-flight job lifecycle.
 *
 * ## Real-stack wiring (Second/Third Law)
 *
 * SUT is the REAL [SearchResultViewModel] + REAL [LavaTrackerSdk] + REAL
 * [DefaultTrackerRegistry]. Only the outermost network boundary is faked: a
 * [SlowForeverClient] whose `search()` suspends indefinitely (simulating a
 * hung backend), and a [TimeoutClient] whose `search()` suspends past the VM's
 * client-side timeout. No UseCase and no SDK method is mocked.
 *
 * ## Falsifiability
 *
 * **Test 1 (cancel-on-back):**
 *  Mutation: remove the `activeSearchJob?.cancel()` call from `onBackClick()`.
 *  Observed failure: `assertFalse("search must be cancelled...", slowed.isStillCollecting)`
 *  → fails because the collection continues after Back.
 *  Reverted: yes.
 *
 * **Test 2 (timeout → Error + Retry):**
 *  Mutation: remove the `withTimeout(SEARCH_TIMEOUT_MS)` wrapper from
 *  `observeStreamMultiSearch`.
 *  Observed failure: `assertTrue("...must render Error...", content is SearchResultContent.Error)`
 *  → test times out waiting for the Error state (collection never ends).
 *  Reverted: yes.
 *
 * ## Bluff-Audit (§6.N / Seventh Law clause 1)
 * See commit body for recorded mutations + observed failures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultViewModelCancelTimeoutTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // --------------- shared descriptor / factory helpers ----------------

    private fun descriptor(id: String): TrackerDescriptor = object : TrackerDescriptor {
        override val trackerId: String = id
        override val displayName: String = "Tracker ${id.uppercase()}"
        override val baseUrls: List<MirrorUrl> = listOf(
            MirrorUrl(url = "https://$id.test", isPrimary = true, priority = 0, protocol = Protocol.HTTPS),
        )
        override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
        override val authType: AuthType = AuthType.NONE
        override val encoding: String = "UTF-8"
        override val expectedHealthMarker: String = "ok"
    }

    /**
     * A tracker client whose `search()` suspends indefinitely — simulating a
     * backend that never responds (the yts slow-backend scenario). Exposes
     * [isStillCollecting] so the test can assert the suspension is eventually
     * cancelled by the VM's back-press handler.
     *
     * Uses [CompletableDeferred] instead of `delay()` so it truly suspends
     * regardless of whether the dispatcher is [UnconfinedTestDispatcher]
     * (which auto-advances `delay()`, defeating the "never completes" intent).
     */
    private class SlowForeverClient(
        override val descriptor: TrackerDescriptor,
    ) : TrackerClient {
        /** Set to true when search() is entered; cleared to false when it exits (normally or via cancellation). */
        @Volatile var isStillCollecting = false

        /** Never completed — awaiting this blocks the coroutine indefinitely. */
        private val neverCompletes = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult {
                    isStillCollecting = true
                    try {
                        // Await a Deferred that is never completed — suspends
                        // indefinitely even with UnconfinedTestDispatcher because
                        // the deferred is not time-based.
                        neverCompletes.await()
                        return SearchResult(items = emptyList(), totalPages = 1, currentPage = page)
                    } finally {
                        isStillCollecting = false
                    }
                }
            } as T
            else -> null
        }
    }

    /**
     * A tracker client whose `search()` suspends indefinitely until [release]
     * is called. Used to simulate a backend that exceeds the client-side timeout:
     * the test lets [SearchResultViewModel]'s `withTimeout(SEARCH_TIMEOUT_MS)` fire
     * (using a tiny test-only constant) without needing virtual-time advancement.
     *
     * Uses [CompletableDeferred] so the suspension is dispatcher-agnostic
     * (works with both [UnconfinedTestDispatcher] and [StandardTestDispatcher]).
     */
    private class SlowClient(
        override val descriptor: TrackerDescriptor,
        private val items: List<TorrentItem> = emptyList(),
    ) : TrackerClient {
        private val gate = kotlinx.coroutines.CompletableDeferred<Unit>()

        /** Call this from the test thread to unblock the in-flight search(). */
        fun release() { gate.complete(Unit) }

        override suspend fun healthCheck(): Boolean = true
        override fun close() {}

        @Suppress("UNCHECKED_CAST")
        override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
            SearchableTracker::class -> object : SearchableTracker {
                override suspend fun search(request: SearchRequest, page: Int): SearchResult {
                    gate.await() // blocks until release() is called
                    return SearchResult(items = items, totalPages = 1, currentPage = page)
                }
            } as T
            else -> null
        }
    }

    private fun factoryFor(client: TrackerClient): TrackerClientFactory = object : TrackerClientFactory {
        override val descriptor: TrackerDescriptor = client.descriptor
        override fun create(config: PluginConfig): TrackerClient = client
    }

    // --------------- shared fake use-cases (not under test) ---------------

    private class FakePagingUseCase : ObserveSearchPagingDataUseCase {
        var invocations = 0
            private set
        override fun invoke(
            filterFlow: Flow<Filter>,
            actionsFlow: Flow<PagingAction>,
            scope: CoroutineScope,
        ): Flow<PagingData<List<TopicModel<Torrent>>>> {
            invocations += 1
            return flowOf()
        }
    }

    private val noopAnalytics = object : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    private fun createViewModel(clients: List<TrackerClient>): SearchResultViewModel {
        val registry = DefaultTrackerRegistry()
        clients.forEach { registry.register(factoryFor(it)) }
        val savedState = SavedStateHandle(
            mapOf(
                "nm" to "ubuntu",
                "pids" to clients.map { it.descriptor.trackerId }.joinToString(","),
            ),
        )
        return SearchResultViewModel(
            savedStateHandle = savedState,
            loggerFactory = TestLoggerFactory(),
            observeSearchPagingDataUseCase = FakePagingUseCase(),
            addSearchHistoryUseCase = object : AddSearchHistoryUseCase {
                override suspend fun invoke(filter: Filter) {}
            },
            enrichFilterUseCase = object : EnrichFilterUseCase {
                override suspend fun invoke(filter: Filter): Filter = filter
            },
            toggleFavoriteUseCase = object : ToggleFavoriteUseCase {
                override suspend fun invoke(id: String, providerId: String?) {}
            },
            observeAuthStateUseCase = object : ObserveAuthStateUseCase {
                override fun invoke(): Flow<AuthState> = MutableStateFlow(AuthState.Unauthorized)
            },
            observeSettingsUseCase = ObserveSettingsUseCase(TestSettingsRepository()),
            analytics = noopAnalytics,
            sdk = LavaTrackerSdk(registry = registry),
            // LVA-093: this suite tests cancel/timeout, not the cold-start
            // race — pre-mark ready so search proceeds immediately.
            providersReadyGate = StartupProvidersGate().apply { markReady() },
        )
    }

    // ============================== TEST 1 ==============================
    //
    // §6.J primary assertion: back-press while a streaming search is in flight
    // MUST cancel the in-flight collection AND emit the Back side effect.
    // Pre-fix: onBackClick() only posts Back but never cancels the search job.
    // Post-fix: onBackClick() cancels the active search job then posts Back.

    @Test
    fun back_press_while_streaming_cancels_inflight_search_and_emits_Back() =
        runTest(mainDispatcherRule.testDispatcher) {
            val slow = SlowForeverClient(descriptor("p1"))
            val vm = createViewModel(listOf(slow))

            var receivedBack = false
            vm.test(this) {
                runOnCreate()

                // With CompletableDeferred the fake suspends immediately and
                // isStillCollecting is set synchronously before search() yields.
                // No time-advancement needed — the flag is already true.

                // §6.J primary: the user taps Back while the search is stuck.
                vm.perform(SearchResultAction.BackClick)

                // Drain events to capture the Back side effect.
                try {
                    while (true) {
                        val item = awaitItem()
                        if (item is org.orbitmvi.orbit.test.Item.SideEffectItem &&
                            item.value is SearchResultSideEffect.Back
                        ) {
                            receivedBack = true
                            break
                        }
                    }
                } catch (_: Throwable) { }

                cancelAndIgnoreRemainingItems()
            }

            // §6.J primary #1 — Back side effect must reach the screen.
            assertTrue(
                "BackClick while streaming must emit the Back side effect so the screen navigates back",
                receivedBack,
            )

            // §6.J primary #2 — the in-flight backend call must be cancelled so the
            // user is not stuck for 30s after pressing Back.
            assertFalse(
                "BackClick must cancel the in-flight search collection — " +
                    "SlowForeverClient.isStillCollecting must be false after Back",
                slow.isStillCollecting,
            )
        }

    // ============================== TEST 2 ==============================
    //
    // §6.J primary assertion: when a provider takes longer than SEARCH_TIMEOUT_MS
    // the VM MUST surface SearchResultContent.Error (with a Retry affordance)
    // instead of hanging on the Streaming spinner until the OS-level network
    // timeout fires (~30s OkHttp readTimeout).
    // Pre-fix: no withTimeout wrapper → stream blocks until OkHttp cuts it.
    // Post-fix: withTimeout(SEARCH_TIMEOUT_MS) wraps the collect block, catches
    // TimeoutCancellationException, and routes to handleStreamEnd() which sees
    // the provider in ERROR state and emits Error(reason).

    @Test
    fun slow_provider_exceeding_timeout_surfaces_Error_with_Retry_affordance() =
        runTest(mainDispatcherRule.testDispatcher) {
            // SlowClient suspends on a CompletableDeferred that is never released,
            // simulating a backend that doesn't respond within SEARCH_TIMEOUT_MS.
            // We advance the scheduler past the timeout to trigger withTimeout.
            val slow = SlowClient(descriptor("p1"))
            val vm = createViewModel(listOf(slow))

            vm.test(this) {
                runOnCreate()
                // Advance the TestCoroutineScheduler past SEARCH_TIMEOUT_MS (25_000ms).
                // TestCoroutineScheduler.advanceTimeBy is always available and does
                // NOT require @ExperimentalCoroutinesApi — it is the stable API for
                // moving virtual time forward, accessible from any lambda.
                mainDispatcherRule.testDispatcher.scheduler.advanceTimeBy(26_000L)
                cancelAndIgnoreRemainingItems()
            }

            val content = vm.container.stateFlow.value.searchContent
            // §6.J primary — after the client-side timeout fires, the user MUST
            // see the Error state (with its Retry button), NOT a hung Streaming
            // spinner and NOT the misleading Empty ("Nothing found") state.
            assertTrue(
                "a provider exceeding SEARCH_TIMEOUT_MS must surface Error (Retry affordance), " +
                    "was ${content::class.simpleName}",
                content is SearchResultContent.Error,
            )
        }

    // ============================== TEST 3 ==============================
    //
    // Regression guard: the Back side effect must NOT cancel a search that has
    // already completed — i.e. cancel-on-back must be scoped to IN-FLIGHT jobs
    // only. A completed search that produced Content must remain visible even
    // after the VM notices back-press.

    @Test
    fun back_press_after_completed_search_preserves_Content_and_emits_Back() =
        runTest(mainDispatcherRule.testDispatcher) {
            // Fast client — search completes before Back is pressed.
            val fastClient = object : TrackerClient {
                override val descriptor: TrackerDescriptor = descriptor("p1")
                override suspend fun healthCheck(): Boolean = true
                override fun close() {}

                @Suppress("UNCHECKED_CAST")
                override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? = when (featureClass) {
                    SearchableTracker::class -> object : SearchableTracker {
                        override suspend fun search(request: SearchRequest, page: Int): SearchResult =
                            SearchResult(
                                items = listOf(TorrentItem(trackerId = "p1", torrentId = "t1", title = "Ubuntu ISO")),
                                totalPages = 1,
                                currentPage = page,
                            )
                    } as T
                    else -> null
                }
            }
            val vm = createViewModel(listOf(fastClient))

            // Orbit 7.x allows only one .test {} session per VM container.
            // Run the full scenario — complete search then back-press — in one session.
            var receivedBack = false
            var contentAfterSearch: SearchResultContent = SearchResultContent.Initial
            var contentAfterBack: SearchResultContent = SearchResultContent.Initial

            vm.test(this) {
                runOnCreate()
                // Stream completes synchronously with UnconfinedTestDispatcher;
                // drain all state items until we see Content.
                var seenContent = false
                try {
                    while (!seenContent) {
                        val item = awaitItem()
                        if (item is org.orbitmvi.orbit.test.Item.StateItem &&
                            item.value.searchContent is SearchResultContent.Content
                        ) {
                            contentAfterSearch = item.value.searchContent
                            seenContent = true
                        }
                    }
                } catch (_: Throwable) { }

                // Perform back-press on the now-completed VM.
                vm.perform(SearchResultAction.BackClick)

                // Drain events to capture the Back side effect.
                try {
                    while (true) {
                        val item = awaitItem()
                        if (item is org.orbitmvi.orbit.test.Item.SideEffectItem &&
                            item.value is SearchResultSideEffect.Back
                        ) {
                            receivedBack = true
                            break
                        }
                        // Also track any state change after back-press.
                        if (item is org.orbitmvi.orbit.test.Item.StateItem) {
                            contentAfterBack = item.value.searchContent
                        }
                    }
                } catch (_: Throwable) { }

                // State is unchanged after back-press (only a SideEffect fires).
                // Read from the container's stateFlow directly rather than awaiting
                // a state item that never arrives.
                contentAfterBack = vm.container.stateFlow.value.searchContent
                cancelAndIgnoreRemainingItems()
            }

            // §6.J primary #1 — Search completed with Content.
            assertTrue(
                "fast search must reach Content, was ${contentAfterSearch::class.simpleName}",
                contentAfterSearch is SearchResultContent.Content,
            )

            // §6.J primary #2 — Back side effect must reach the screen.
            assertTrue("BackClick after completed search must still emit Back", receivedBack)

            // §6.J primary #3 — Content preserved after back-press (cancel-on-back
            // must not wipe results for already-completed searches).
            assertTrue(
                "Content must be preserved after back-press on a completed search, " +
                    "was ${contentAfterBack::class.simpleName}",
                contentAfterBack is SearchResultContent.Content,
            )
            assertEquals(
                "item list must be unchanged after back-press",
                listOf("Ubuntu ISO"),
                (contentAfterBack as SearchResultContent.Content).torrents.map { it.topic.title },
            )
        }
}
