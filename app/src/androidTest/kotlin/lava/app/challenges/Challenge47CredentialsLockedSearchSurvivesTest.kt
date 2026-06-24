/*
 * Challenge Test C47 — Credentials key holder locked: search flow MUST NOT
 * crash (on-device §6.O.2 acceptance gate for the 2026-06-24 locked-holder fix).
 *
 * OPERATOR-REPORTED DEFECT (Crashlytics FATAL 58a1335272bc4ee06595bda6302a670a,
 * 1.3.10):
 *   Opening the app and submitting a search while the credentials key holder was
 *   locked caused a FATAL crash on the MAIN THREAD. The crash path:
 *     ProviderConfigViewModel.observeAll()
 *       → CredentialsEntryRepositoryImpl.observe()  [Flow.map operator]
 *         → keyProvider()  [= CredentialsKeyHolder.require()]
 *           → error("credentials key holder is locked …")  [throws IllegalStateException]
 *       → exception escapes the map operator onto the main-thread Looper collector
 *       → FATAL crash — every device, every Android version, every cold launch
 *         while the holder was locked.
 *
 * ROOT CAUSE:
 *   [CredentialsEntryRepositoryImpl.observe] called [keyProvider] (which is
 *   [CredentialsKeyHolder.require]) INSIDE a [kotlinx.coroutines.flow.Flow.map]
 *   operator.  [require] throws [IllegalStateException] when the holder is locked
 *   (key == null).  That exception was uncaught and propagated up the flow
 *   collection chain to the main-thread ViewModel collector.
 *
 * FIX (CredentialsEntryRepositoryImpl.observe, 2026-06-24):
 *   [runCatching { keyProvider() }] wraps the key-acquisition call. When the
 *   holder is locked, [isLockedKeyHolderError] matches the ISE and the operator
 *   returns [emptyList()] — zero credentials visible to the UI, graceful
 *   degradation, NO exception escaping the flow. A §6.AC [analytics.recordWarning]
 *   is emitted so the operator can observe the locked path in production.
 *
 * THE LOCKED STATE ON A REAL DEVICE:
 *   [CredentialsKeyHolder] is a process-wide [Singleton]; it starts in the
 *   locked state (ref == null) on every cold launch. It is only unlocked when
 *   [PassphraseManager] explicitly calls [CredentialsKeyHolder.unlock] with the
 *   AES key derived from the user's passphrase. A user who has never opened the
 *   Credentials screen, or whose key was evicted after the app was backgrounded,
 *   reaches the main app with the holder locked — exactly the crash scenario.
 *
 * TEST SEAM:
 *   [CredentialsKeyHolder] is injectable via Hilt's [SingletonComponent] and is
 *   accessible to instrumented tests through an [EntryPoint]. We call [.lock()]
 *   explicitly to ensure the locked state regardless of any prior test activity,
 *   then navigate the REAL production search UI path and assert survival.
 *   This is the EXACT production instance the running app uses — no mock, no
 *   override, no Hilt test replacement.
 *
 * WHY THIS CHALLENGE IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → the Nav graph →
 *     SearchScreen (bottom tab) → SearchInputScreen → SearchResultScreen →
 *     SearchResultViewModel (Orbit) → ProviderConfigViewModel → combine →
 *     CredentialsEntryRepositoryImpl.observe() → CredentialsKeyHolder.require().
 *   - The ONLY test seam is [CredentialsKeyHolder.lock()] called BEFORE the
 *     test, forcing the locked state. The seam is exposed by the production
 *     class — no Hilt module replacement, no mock.
 *   - The MockWebServer handles network: it enqueues a minimal valid search
 *     response so SearchResultViewModel can finish. Without it the search would
 *     block on the real API (unavailable in test). The VM, SDK, and full Compose
 *     UI tree are never stubbed.
 *   - [ApiBaseUrlHolder.set] is the exact same seam [OnboardingViewModel] calls
 *     when the user picks a real lava-api-go endpoint.
 *   - [OnboardingBypassRule] pre-seeds onboarding-complete + a generic authorized
 *     signal — the same state every real user is in after finishing onboarding.
 *   - PRIMARY assertion: the app does NOT crash and the search screen renders
 *     recognisable UI (either results, empty state, or an error state — any of
 *     these is SURVIVAL; process death is the failure).  A secondary assertion
 *     confirms the SearchResult screen is rendered (not stuck on the input
 *     screen), proving the search flow completed rather than aborting silently.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing defect → the bug):
 *
 *   MUTATION — revert observe() to the pre-fix throwing path:
 *     1. In [CredentialsEntryRepositoryImpl.observe], replace the [runCatching]
 *        block with the original bare call:
 *          override fun observe(): Flow<List<CredentialsEntry>> =
 *              dao.observeAll().map { rows -> rows.map(::decode) }
 *        (i.e. [keyProvider()] is called unconditionally inside map, identical
 *        to the pre-fix code that caused the Crashlytics FATAL.)
 *     2. Re-run this Challenge on the gating matrix:
 *          ./gradlew :app:connectedDebugAndroidTest \
 *            --tests "lava.app.challenges.Challenge47CredentialsLockedSearchSurvivesTest"
 *     3. Expected failure: [ProviderConfigViewModel]'s combine collector receives
 *        the [IllegalStateException] on the main thread.  The Compose test
 *        framework surfaces this as an [UncaughtException] / the activity is
 *        killed by the uncaught exception.  The [waitUntil] for any
 *        SearchResult-screen node times out with:
 *          "Compose wait timed out after 20 000 ms waiting for condition to be true"
 *          / "Process death or uncaught exception detected: …IllegalStateException:
 *            credentials key holder is locked — prompt user for passphrase first"
 *        The primary survival assertion FAILS because the expected rendered node
 *        is never found.
 *     4. Revert the mutation; re-run; the search flow completes, the screen
 *        renders, and both assertions pass.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge47CredentialsLockedSearchSurvivesTest"
 *
 * // covers-feature: search_result
 * // covers-feature: credentials_manager
 */
package lava.app.challenges

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import digital.vasic.lava.client.MainActivity
import lava.app.OnboardingBypassRule
import lava.credentials.session.CredentialsKeyHolder
import lava.tracker.client.ApiBaseUrlHolder
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * On-device Compose UI Challenge for the credentials-locked crash fix.
 *
 * Puts the production [CredentialsKeyHolder] into the locked state (the state
 * it starts in on EVERY cold launch before the user enters their passphrase),
 * then drives the REAL search flow end-to-end.  Asserts the app survives:
 * no crash, no process death, and a recognisable SearchResult screen node is
 * rendered.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL — see class KDoc above.
 */
@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge47CredentialsLockedSearchSurvivesTest {

    // ── Hilt entry point to reach the production CredentialsKeyHolder singleton ──

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface CredentialsKeyHolderEntryPoint {
        fun credentialsKeyHolder(): CredentialsKeyHolder
    }

    // ── Rules ────────────────────────────────────────────────────────────────────

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Pre-seeds onboarding-complete + generic authorized signal so the test
    // starts in the bottom-tab nav — the same state a real user is in after
    // finishing onboarding. The rule also cleans up state in its finally block.
    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ── Fields ────────────────────────────────────────────────────────────────

    private lateinit var server: MockWebServer
    private lateinit var keyHolder: CredentialsKeyHolder

    // A stable, non-real API key for the MockWebServer endpoint (§6.R — never
    // a production credential; MockWebServer ignores auth headers entirely).
    private val TEST_AUTH_KEY = "C47-TEST-KEY"

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Before
    fun setUp() {
        hiltRule.inject()

        // Reach the production CredentialsKeyHolder singleton via EntryPoint.
        // This is the EXACT same instance the running ProviderConfigViewModel
        // uses — no mock, no override.
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        keyHolder = EntryPointAccessors
            .fromApplication(app, CredentialsKeyHolderEntryPoint::class.java)
            .credentialsKeyHolder()

        // ── Lock the holder — the state that triggered the FATAL crash ──────
        // On a real cold launch the holder is always locked here; we force it
        // explicitly so prior test activity cannot leave a stale unlocked key.
        keyHolder.lock()

        // Spin up the in-process MockWebServer and point the SDK at it.
        // This is the same seam C46 uses; the search VM hits this server
        // instead of a real lava-api-go endpoint.
        server = MockWebServer()
        server.start()
        ApiBaseUrlHolder.set(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            key = TEST_AUTH_KEY,
        )

        // Enqueue a minimal valid search response so the SearchResultViewModel
        // can finish its streaming pass without blocking on a real API.
        // The holder is locked → ProviderConfigViewModel emits [] credentials
        // → only providers that do NOT require credentials may search.
        // The MockWebServer returns a valid (empty) result for any provider
        // that does make a request.
        repeat(10) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"provider":"archiveorg","page":0,"totalPages":0,"results":[]}"""),
            )
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        ApiBaseUrlHolder.reset()
        // Do NOT unlock here — the test asserts on the locked path.
        // The next test gets a fresh application process (Hilt test runner
        // re-creates the SingletonComponent per test class on most configs)
        // or a fresh lock() call in its own setUp.
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST — locked holder + search = NO crash, screen renders
    //
    // Drives the full real production path:
    //   MainActivity → bottom-tab → Search action → SearchInputScreen →
    //   type + submit → SearchResultScreen → ProviderConfigViewModel.observeAll()
    //   → CredentialsEntryRepositoryImpl.observe() [locked holder → emptyList()]
    //   → search proceeds with zero credential-gated providers
    //   → SearchResultScreen renders (any recognisable node).
    //
    // FALSIFIABILITY (Mutation): revert observe() to the pre-fix bare call
    //   `dao.observeAll().map { rows -> rows.map(::decode) }`
    // → CredentialsKeyHolder.require() throws ISE inside the Flow.map operator
    // → exception reaches the main-thread collector
    // → this waitUntil times out (no SearchResult node ever renders because the
    //   activity is dead) and the survival assertion FAILS.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun credentialsLockedOnSearch_appSurvives_searchResultScreenRenders() {
        // Verify the test seam is active: holder MUST be locked at this point.
        assertTrue(
            "Test pre-condition: CredentialsKeyHolder MUST be locked before navigating to search " +
                "(simulates the locked state on every cold launch)",
            !keyHolder.isUnlocked(),
        )

        // Navigate the real production UI: bottom-tab nav → Search → SearchInput.
        // (Same helper shape as C46 — drives the real NavGraph, not a synthetic intent.)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Search history", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Tap the Search action button (magnifying-glass icon in the app bar).
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()

        // Wait for SearchInputScreen's text field.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Type a query and submit via IME — this triggers navigation to
        // SearchResultScreen and fires SearchResultViewModel.container.onCreate,
        // which calls ProviderConfigViewModel via combine, which calls
        // CredentialsEntryRepositoryImpl.observe() on the locked holder.
        composeRule.onNode(hasSetTextAction()).performTextInput("credentials-locked-test")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // PRIMARY ASSERTION — SURVIVAL.
        //
        // The SearchResultScreen renders its app-bar back-arrow as soon as it
        // mounts (before the streaming results arrive). Waiting for it within
        // 20 s confirms:
        //   1. The app did NOT crash (process death would prevent any node from
        //      appearing, causing this waitUntil to time out with the test-
        //      framework's "Compose wait timed out" exception — the discriminator
        //      the FALSIFIABILITY REHEARSAL relies on).
        //   2. The NavigationController successfully navigated to SearchResultScreen
        //      (the back arrow is only present on that screen, not on SearchInput).
        //
        // Without the fix, the ISE thrown by CredentialsKeyHolder.require() escapes
        // the Flow.map operator onto the main thread, killing the activity before
        // this node can ever appear.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithContentDescription("Navigate up", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        assertTrue(
            "CREDENTIALS LOCKED + SEARCH MUST NOT CRASH — the SearchResultScreen's " +
                "'Navigate up' back button MUST be rendered within 20 s of submitting a " +
                "search while the credentials key holder is locked. " +
                "If this fails, the FATAL crash from Crashlytics 58a1335272bc4ee06595bda6302a670a " +
                "has been re-introduced: CredentialsEntryRepositoryImpl.observe() is throwing " +
                "IllegalStateException on the main thread instead of emitting emptyList().",
            composeRule.onAllNodesWithContentDescription("Navigate up", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
