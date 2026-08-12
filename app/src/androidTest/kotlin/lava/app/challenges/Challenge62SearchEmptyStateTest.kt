/*
 * Challenge Test C62 — search renders a visible EMPTY state ("Nothing found")
 * when a provider returns zero results (LVA-086, video #5).
 *
 * DRAFT authored 2026-06-26 (§6.AK cycle-coverage spec §2.5 LVA-086). This file
 * is authored statically — it has NOT been executed on a device by its author
 * (the device + Android Gradle are owned by another stream). The main stream
 * device-runs it reproduce-first per §6.AK clause 2.
 *
 * OPERATOR-REPORTED DEFECT (video #5 against 1076, LVA-086, P1):
 *   "No empty-state and no loading indicator on search results (perceived
 *    hang)." When a search legitimately returned ZERO results, the screen
 *   rendered nothing — a blank screen with no "No results" text — so the user
 *   perceived the app was stuck. This Challenge asserts the empty result set
 *   produces a VISIBLE empty-state placeholder.
 *
 * WHAT THE USER DOES (the surface this Challenge traverses verbatim):
 *   MainActivity → bottom-tab nav → Search action → SearchInputScreen → type a
 *   nonsense query → IME submit → SearchResultScreen → the search completes with
 *   zero results → the "Nothing found" empty-state placeholder renders.
 *
 * The seam: a [MockWebServer] inside this instrumented-test process returns an
 * EMPTY result set (`"results":[]`) with HTTP 200 for the per-provider search
 * request. One provider is seeded into the real [ProviderConfigRepository] so
 * the search-input submit resolves a NON-EMPTY providerIds set → the production
 * observeStreamMultiSearch path runs. The provider reaches a SUCCESSFUL terminal
 * state (HTTP 200, zero rows = DONE, not ERROR), so `handleStreamEnd()` produces
 * `SearchResultContent.Empty` (NOT `Error`): items empty + no provider in ERROR
 * → Empty (SearchResultViewModel.kt:491-502).
 *
 * THE REAL EMPTY-STATE STRING (grepped, not invented):
 *   `SearchResultContent.Empty` → `emptyItem(titleRes = R.string
 *   .search_screen_result_empty_title, ...)` (SearchResultScreen.kt:280-284) →
 *   `Empty` → `Placeholder` renders `Text(stringResource(titleRes))`
 *   (Placeholder.kt:115-120). The string value is:
 *     <string name="search_screen_result_empty_title">Nothing found</string>
 *   (feature/search_result/src/main/res/values/strings.xml:14).
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → Nav graph →
 *     SearchInputScreen → SearchInputViewModel → SearchResultScreen →
 *     SearchResultViewModel → LavaTrackerSdk → ApiBackedTrackerClient → OkHttp.
 *     The ONLY faked boundary is the network socket (MockWebServer).
 *   - PRIMARY assertion is on USER-VISIBLE rendered text: the "Nothing found"
 *     empty-state placeholder. A blank screen (the operator's defect) renders no
 *     such node and the assertion fails.
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix / with the defect re-introduced)
 * 1. Apply the mutation: in
 *    feature/search_result/.../SearchResultViewModel.kt, inside
 *    `handleStreamEnd`, change the empty-but-not-all-failed branch
 *      `reduce { state.copy(searchContent = SearchResultContent.Empty) }`
 *    to
 *      `reduce { state.copy(searchContent = SearchResultContent.Initial) }`
 *    so a zero-result search renders the loading spinner forever instead of the
 *    empty-state placeholder (the operator's perceived-hang). NON-CRASHING.
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run ONLY this Challenge:
 *    `adb shell am instrument -w -e class lava.app.challenges.Challenge62SearchEmptyStateTest digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner`
 * 4. Expected failure: the 45 s `waitUntil` for the "Nothing found" text times
 *    out; the final assertion fails with
 *      "A zero-result search MUST render the 'Nothing found' empty-state
 *       placeholder; got a blank/loading screen instead."
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout SearchResultViewModel.kt).
 * 6. Rebuild and re-run the identical Challenge.
 * 7. Expected pass: the "Nothing found" empty-state renders within 45 s.
 *
 * ### Mutation type
 * NON-CRASHING BREAK — the mutation reproduces the perceived-hang blank screen
 * the operator saw, not a crash (§6.AB.3).
 *
 * ### LVA-008 dependency
 * This Challenge navigates to `search_result` (a nested route). The
 * [LenientTeardownRule] (outside the compose rule) swallows ONLY the
 * activity-destroy nav-teardown IllegalStateException so the empty-state
 * assertion decides pass/fail. Any genuine assertion failure propagates.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge62SearchEmptyStateTest"
 *
 * // covers-feature: search_result
 * // covers-changelog: Search now shows a clear "No results" empty state instead of a blank screen (LVA-086)
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
import kotlinx.coroutines.runBlocking
import lava.app.LenientTeardownRule
import lava.app.OnboardingBypassRule
import lava.credentials.ProviderConfigRepository
import lava.tracker.api.AuthType
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerCapability
import lava.tracker.client.ApiBaseUrlHolder
import lava.tracker.registry.TrackerRegistry
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge62SearchEmptyStateTest {

    // ── Rules ────────────────────────────────────────────────────────────────
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    @get:Rule(order = 2)
    val lenientTeardown = LenientTeardownRule()

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ── Fields ───────────────────────────────────────────────────────────────
    private lateinit var server: MockWebServer

    private val TEST_AUTH_KEY = "C62-TEST-KEY"

    // One built-in, search-enabled provider so the submit resolves a NON-EMPTY
    // providerIds set → the streaming multi-search path runs.
    private val SEEDED_PROVIDER = "rutracker"

    // The real, grepped empty-state title (search_screen_result_empty_title).
    private val EMPTY_STATE_TITLE = "Nothing found"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderSeedEntryPoint {
        fun providerConfigRepository(): ProviderConfigRepository
        fun trackerRegistry(): TrackerRegistry
    }

    private fun providerRepo(): ProviderConfigRepository {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return EntryPointAccessors.fromApplication(app, ProviderSeedEntryPoint::class.java)
            .providerConfigRepository()
    }

    private fun registry(): TrackerRegistry {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return EntryPointAccessors.fromApplication(app, ProviderSeedEntryPoint::class.java)
            .trackerRegistry()
    }

    @Before
    fun setUp() {
        hiltRule.inject()

        runBlocking { providerRepo().ensureDefault(SEEDED_PROVIDER) }

        // MockWebServer returns HTTP 200 with an EMPTY result set for every
        // per-provider search request. The provider reaches a DONE (not ERROR)
        // terminal state, so handleStreamEnd() produces SearchResultContent.Empty
        // — the "Nothing found" placeholder — NOT the Error placeholder.
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(EMPTY_SEARCH_RESULT_JSON)
        }
        server.start()
        ApiBaseUrlHolder.set(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            key = TEST_AUTH_KEY,
        )

        // §6.AK root-cause fix (2026-08-10): SEEDED_PROVIDER ("rutracker") is a
        // BUNDLED compiled-in factory that ignores ApiBaseUrlHolder entirely — only
        // the dynamic ApiBackedTrackerClient installed by
        // DefaultTrackerRegistry.populateFrom() reads it. OnboardingBypassRule
        // skips the real onboarding flow, so populateFrom() never runs, and this
        // test's MockWebServer was a no-op. See C58 for the full analysis
        // (identical setup pattern).
        registry().populateFrom(
            listOf(
                RemoteTrackerDescriptor(
                    trackerId = SEEDED_PROVIDER,
                    displayName = SEEDED_PROVIDER,
                    baseUrls = emptyList(),
                    capabilities = setOf(TrackerCapability.SEARCH),
                    authType = AuthType.NONE,
                    encoding = "UTF-8",
                ),
            ),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        ApiBaseUrlHolder.reset()
        registry().populateFrom(emptyList())
        runCatching { runBlocking { providerRepo().keepOnlySearchEnabled(emptySet()) } }
    }

    @Test
    fun zeroResultSearch_rendersNothingFoundEmptyState() {
        // Bottom-tab nav is up (OnboardingBypassRule).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Search history", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Tap the AppBar Search action → SearchInputScreen.
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Type a nonsense query (guaranteed zero hits) and submit via IME Search.
        composeRule.onNode(hasSetTextAction()).performTextInput("zxzxzxzxzxzxzxzx")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // PRIMARY ASSERTION — the empty-state placeholder renders its real title
        // text. A blank/loading screen (the operator's defect) renders no such
        // node. This is the user-visible signal that the search COMPLETED with no
        // results rather than hanging.
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithText(EMPTY_STATE_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "A zero-result search MUST render the '$EMPTY_STATE_TITLE' empty-state " +
                "placeholder; got a blank/loading screen instead.",
            composeRule.onAllNodesWithText(EMPTY_STATE_TITLE).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    companion object {
        // Minimal valid lava-api-go SearchResult JSON with ZERO result items
        // (results empty). Field names match ApiBackedTrackerClient.SearchResultDto.
        private const val EMPTY_SEARCH_RESULT_JSON =
            """{"provider":"rutracker","page":0,"totalPages":0,"results":[]}"""
    }
}
