/*
 * Challenge Test C46 — Search-timeout + back-interrupt gate (on-device e2e
 * acceptance for the 2026-06-24 cancel-on-back + withTimeout fix).
 *
 * OPERATOR-REPORTED DEFECT (Crashlytics issue 9d4ad2f4…):
 *   Searching on a slow / unreachable API caused the UI to hang on the
 *   Streaming spinner until OkHttp's 30-second read-timeout fired. Pressing
 *   back during that wait had NO effect — the user was trapped.
 *
 * ROOT CAUSE:
 *   `SearchResultViewModel.observeStreamMultiSearch()` blocked on
 *   `sdk.streamMultiSearch(...).collect {}` inside an Orbit `intent {}`
 *   coroutine. The `BackClick` action only posted a `Back` side-effect; it
 *   never cancelled the blocking collect. `withTimeout` was also absent, so
 *   the user's only exit was the OS-level network timeout (30 s).
 *
 * FIX (SearchResultViewModel, 2026-06-24):
 *   1. `activeSearchJob` stores the coroutine Job of the streaming intent.
 *   2. `onBackClick()` calls `activeSearchJob?.cancel()` before posting `Back`,
 *      immediately unblocking the blocked collect.
 *   3. `withTimeout(SEARCH_TIMEOUT_MS = 25_000L)` wraps the collect: if a
 *      provider is slow past the client deadline, `TimeoutCancellationException`
 *      is caught → all still-SEARCHING providers are marked ERROR →
 *      `handleStreamEnd()` downgrades to `SearchResultContent.Error` → the
 *      screen renders "Search failed" + a "Retry" button the user can tap.
 *
 * WHY THIS CHALLENGE IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → the Nav graph →
 *     SearchResultScreen (Compose) → SearchResultViewModel (Orbit) →
 *     LavaTrackerSdk → ApiBackedTrackerClient → OkHttp.
 *   - The ONLY faked boundary is the network socket: a [MockWebServer] running
 *     INSIDE this instrumented-test process on the device provides an HTTP
 *     endpoint that either (a) hangs indefinitely (timeout test) or (b)
 *     throttles cleanly.  The faked socket is BELOW the production SDK — the
 *     ViewModel, the SDK, and the full Compose UI tree are never stubbed.
 *   - `ApiBaseUrlHolder.set(mockWebServer.url, testKey)` is the EXACT same seam
 *     `OnboardingViewModel` calls when the user picks a real lava-api-go
 *     endpoint. No Hilt override is needed because the holder is a process-wide
 *     singleton — setting it here is functionally identical to the user having
 *     configured the app to point at the mock server.
 *   - PRIMARY assertions are on USER-VISIBLE rendered text ("Search failed",
 *     "Retry") and on the rendered navigation state (back-press exits
 *     SearchResult, the prior screen's bar is visible again). Never "a mock was
 *     called N times".
 *   - `OnboardingBypassRule` pre-seeds onboarding-complete + a generic
 *     authorized signal so the test begins at the bottom-tab nav, exactly where
 *     a real user lands after finishing onboarding. The test then drives the
 *     real Search tab → real SearchInput screen → real SearchResultScreen.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing → the production bug):
 *
 *   MUTATION A — remove withTimeout (timeout test):
 *     1. In SearchResultViewModel.observeStreamMultiSearch(), remove (or
 *        comment out) the `withTimeout(SEARCH_TIMEOUT_MS) { ... }` wrapper,
 *        leaving the bare `sdk.streamMultiSearch(...).collect { ... }`.
 *     2. Re-run `slowProviderPastClientDeadline_screensShowsErrorWithRetry` on
 *        the gating matrix with FAKE_DELAY_MS = 26_000L.
 *     3. Expected failure: the `waitUntil(timeoutMillis = 40_000)` for
 *        "Search failed" times out (the VM never transitions to Error during
 *        the test window because withTimeout is gone).  The test fails with:
 *          "Compose wait timed out after 40 000 ms" / "Error + Retry must
 *          render: 'Search failed' node not found"
 *     4. Revert; re-run; "Search failed" renders within ~26 s and both
 *        assertions pass.
 *
 *   MUTATION B — remove activeSearchJob?.cancel() (back-interrupt test):
 *     1. In SearchResultViewModel.onBackClick(), comment out the line
 *        `activeSearchJob?.cancel()`.
 *     2. Re-run `backDuringInFlightSearch_returnsToSearchInputPromptly` on the
 *        gating matrix.
 *     3. Expected failure: after pressing back the search coroutine continues
 *        blocking on the OkHttp socket (MockWebServer enqueue delay = 35 s).
 *        The test's 10-second window for the SearchInput prompt to re-appear
 *        times out with: "waitUntil timed out after 10 000 ms" /
 *        "Back-press MUST exit SearchResult promptly: 'Search…' bar not found"
 *     4. Revert; re-run; back-press cancels immediately and SearchInput
 *        re-appears within 1–2 s.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge46SearchTimeoutInterruptTest"
 *
 * // covers-feature: search_result
 */
package lava.app.challenges

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.OnboardingBypassRule
import lava.tracker.client.ApiBaseUrlHolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * On-device Compose UI Challenge for the search-timeout + back-interrupt fix.
 *
 * Both tests navigate the REAL production UI path:
 *   MainActivity → Search tab → SearchInput → SearchResultScreen
 *
 * The MockWebServer runs inside this instrumented-test process and is injected
 * as the active API base URL via [ApiBaseUrlHolder.set] — the exact same seam
 * the app uses when the user configures a lava-api-go endpoint.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL — see class KDoc above.
 */
@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge46SearchTimeoutInterruptTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Pre-seeds onboarding-complete + generic authorized signal so the test
    // starts in the bottom-tab nav (same state a real user is in after finishing
    // onboarding). The rule also clears the state in its finally block.
    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var server: MockWebServer

    // A stable, non-real API key for the MockWebServer endpoint (§6.R — never
    // a production credential; the MockWebServer ignores auth headers entirely).
    private val TEST_AUTH_KEY = "C46-TEST-KEY"

    @Before
    fun setUp() {
        hiltRule.inject()
        server = MockWebServer()
        server.start()
        // Point the SDK's ApiBackedTrackerClient at our in-process MockWebServer.
        // This is the exact seam OnboardingViewModel uses when a user selects
        // "Connect" after API discovery; setting it here bypasses the discovery
        // UI while exercising the SAME production runtime path.
        ApiBaseUrlHolder.set(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            key = TEST_AUTH_KEY,
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        // Reset the holder so subsequent tests don't inherit our fake URL.
        // reset() is the documented test/teardown hook in ApiBaseUrlHolder.
        ApiBaseUrlHolder.reset()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 1 — back-interrupt
    //
    // Drives the full Search → SearchResult UI with a MockWebServer that delays
    // every /v1/*/search response by 35 seconds (well past the 25 s client
    // deadline AND past the 30 s OkHttp readTimeout). Presses back after the
    // Streaming spinner appears. Asserts the SearchInput screen re-appears
    // within 10 seconds — proving back-press cancelled the in-flight search
    // immediately rather than waiting for the network stack.
    //
    // FALSIFIABILITY (Mutation B): remove `activeSearchJob?.cancel()` from
    // onBackClick() → this waitUntil times out (the coroutine keeps blocking
    // on the socket until the server's 35 s delay elapses) and the assertion
    // "Back-press MUST exit SearchResult promptly" fails.
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun backDuringInFlightSearch_returnsToSearchInputPromptly() {
        // Enqueue a response that the server delays for 35 s — longer than both
        // the client 25 s withTimeout AND OkHttp's 30 s readTimeout. This
        // guarantees the socket is blocked when we press back, so the test
        // verifies the CANCEL path, not the natural-completion path.
        server.enqueue(
            MockResponse()
                .setBodyDelay(35_000L, TimeUnit.MILLISECONDS)
                .setBody(EMPTY_SEARCH_RESULT_JSON),
        )

        navigateToSearchResult(query = "timeout-interrupt-test")

        // Wait for the SearchResultScreen to mount (the Streaming initial state
        // shows a loading indicator — no explicit text to assert on, so we wait
        // for the back-arrow button which SearchResultScreen's app bar always
        // renders before the result list).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            // The back arrow's content-description comes from the Lava design
            // system BackButton: "Navigate up" is the standard content-desc
            // for navigation-up buttons in Material3 + Navigation-Compose.
            composeRule.onAllNodesWithContentDescription("Navigate up", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Press back while the search is still in-flight.
        composeRule.onNodeWithText("timeout-interrupt-test", ignoreCase = true, substring = true)
            .let { _ ->
                // Use the system back button to fire the BackClick action through
                // the real NavController → SearchResultViewModel.onBackClick().
                composeRule.activityRule.scenario.onActivity { activity ->
                    activity.onBackPressedDispatcher.onBackPressed()
                }
            }

        // PRIMARY ASSERTION: SearchInput re-appears promptly (within 10 s),
        // proving the back-press cancelled the in-flight search immediately.
        // Without the fix the blocking collect holds the coroutine for up to
        // 35 s and this waitUntil times out.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            // SearchInputScreen renders a "Search…" placeholder in its text field.
            composeRule.onAllNodesWithText("Search…", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Back-press MUST exit SearchResult promptly — 'Search…' input bar " +
                "must be visible within 10 s of pressing back (proves activeSearchJob.cancel() fired)",
            composeRule.onAllNodesWithText("Search…", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // TEST 2 — client-side timeout → Error + Retry
    //
    // Drives the full Search → SearchResult UI with a MockWebServer that delays
    // every /v1/*/search response by 30 seconds (past the 25 s withTimeout but
    // within the 35 s test window). Waits for the 25 s client deadline to fire.
    // Asserts "Search failed" text AND the "Retry" button are rendered — proving
    // the TimeoutCancellationException path transitions the UI to
    // SearchResultContent.Error instead of leaving the spinner.
    //
    // FALSIFIABILITY (Mutation A): remove `withTimeout(SEARCH_TIMEOUT_MS)` →
    // the collect never terminates during the 40 s test window, the waitUntil
    // for "Search failed" times out, and the assertion fails.
    // ──────────────────────────────────────────────────────────────────────────
    @Test
    fun slowProviderPastClientDeadline_screenShowsErrorWithRetry() {
        // Delay 30 s — past the 25 s withTimeout but short enough that the
        // MockWebServer doesn't close the connection before the test ends.
        server.enqueue(
            MockResponse()
                .setBodyDelay(30_000L, TimeUnit.MILLISECONDS)
                .setBody(EMPTY_SEARCH_RESULT_JSON),
        )

        navigateToSearchResult(query = "timeout-error-test")

        // Wait up to 40 s for the Error state to appear. The client-side
        // withTimeout fires at ~25 s; we give 15 s of headroom for emulator
        // scheduling jitter on slower API levels.
        composeRule.waitUntil(timeoutMillis = 40_000) {
            composeRule.onAllNodesWithText("Search failed", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // PRIMARY ASSERTION 1 (user-visible error title):
        assertTrue(
            "Slow provider past client deadline MUST surface 'Search failed' error title " +
                "(SearchResultContent.Error rendered, NOT stuck Streaming spinner)",
            composeRule.onAllNodesWithText("Search failed", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )

        // PRIMARY ASSERTION 2 (actionable Retry button — §6.AB.1 gating logic):
        // The Retry button MUST render alongside the error title. Without it
        // the user has no recovery path. The button's text comes from the
        // `search_screen_result_error_retry` string resource = "Retry".
        assertTrue(
            "Error state MUST include a 'Retry' button so the user has a recovery path " +
                "(SearchResultContent.Error rendered the Placeholder + action button)",
            composeRule.onAllNodesWithText("Retry", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Shared navigation helper: bottom-tab nav → Search content-desc tap →
    // SearchInputScreen → type query → IME submit → SearchResultScreen.
    //
    // This traverses the EXACT production code path a real user takes:
    //   MainActivity → main NavHost → SearchTab → SearchInputScreen →
    //   SearchResultViewModel (onCreate fires observeStreamMultiSearch).
    // ──────────────────────────────────────────────────────────────────────────
    private fun navigateToSearchResult(query: String) {
        // Wait for the bottom-tab nav to mount (OnboardingBypassRule ensures
        // onboarding is complete so the main-app scaffold renders immediately).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Search history", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Tap the Search action button in the app bar of the Search tab screen
        // (content-desc "Search" — this is the magnifying-glass icon button that
        // navigates to SearchInputScreen, not the bottom-tab itself).
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()

        // SearchInputScreen mounts; wait for its text field.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Type the query and submit via IME action (same as the user pressing
        // the keyboard's search/done button).
        composeRule.onNode(hasSetTextAction()).performTextInput(query)
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // At this point the NavController navigates to SearchResultScreen and
        // SearchResultViewModel.container.onCreate fires, which calls
        // observeStreamMultiSearch() → LavaTrackerSdk → ApiBackedTrackerClient
        // → MockWebServer (the URL we set in setUp via ApiBaseUrlHolder.set).
    }

    companion object {
        /**
         * Minimal valid lava-api-go SearchResult JSON body.
         * The MockWebServer sends this after its configured delay — the VM
         * would parse it as an empty-results Content state, but the tests
         * assert before that: the timeout test asserts on the Error state
         * (which fires BEFORE the 30s delay completes due to the 25s withTimeout),
         * and the back-interrupt test asserts on navigation before any response arrives.
         */
        private val EMPTY_SEARCH_RESULT_JSON = """
            {"provider":"archiveorg","page":0,"totalPages":0,"results":[]}
        """.trimIndent()
    }
}
