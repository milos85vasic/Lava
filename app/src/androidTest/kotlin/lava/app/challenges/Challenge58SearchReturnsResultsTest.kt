/*
 * Challenge Test C58 — Search returns REAL results (LVA-083, video #1).
 *
 * DRAFT authored 2026-06-26 (Stream B, §6.AK cycle-coverage spec
 * docs/superpowers/specs/2026-06-26-ak-cycle-coverage-spec.md §2.1). This file
 * is authored statically — it has NOT been executed on a device by its author
 * (the device + Android Gradle are owned by another stream). The main stream
 * device-runs it reproduce-first per §6.AK clause 2.
 *
 * OPERATOR-REPORTED DEFECT (video #1 against 1076, LVA-083, P0):
 *   "Search returns ZERO results then 'Something went wrong'" — the primary
 *   function of the app is unusable. Multi-factorial root cause: (a) 401
 *   auth-header overwrite (fixed 1072), (b) engine handler has no deadline so
 *   slow providers exceed the client readTimeout, (c) client-side partial-
 *   failure Error→Empty conversion (1cbf364c). A C00-only gate shipped 1076
 *   claiming a fix while search was still broken — the bluff §6.AK exists to
 *   evict.
 *
 * WHAT THE USER DOES (the surface this Challenge traverses verbatim):
 *   MainActivity → bottom-tab nav → Search action → SearchInputScreen → type a
 *   query → IME submit → SearchResultScreen → at least one RESULT ROW renders
 *   (NOT the "Nothing found" empty state, NOT the "Search failed" Error state).
 *
 * The seam: a [MockWebServer] running INSIDE this instrumented-test process is
 * injected as the active API base URL via [ApiBaseUrlHolder.set] — the exact
 * same seam OnboardingViewModel uses when the user picks a discovered lava-api
 * endpoint (see C46 KDoc). A provider is seeded into the real
 * [ProviderConfigRepository] (the same table onboarding writes to) so the
 * search-input submit resolves a NON-EMPTY providerIds set → the production
 * `SearchResultViewModel.observeStreamMultiSearch` path runs → per-provider
 * `GET /v1/{provider}/search` hits the mock → a non-empty SearchResultDto →
 * a TopicListItem result row composes.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → Nav graph →
 *     SearchInputScreen → SearchInputViewModel → SearchResultScreen →
 *     SearchResultViewModel → LavaTrackerSdk → ApiBackedTrackerClient → OkHttp.
 *     The ONLY faked boundary is the network socket (MockWebServer), which sits
 *     BELOW the production SDK.
 *   - PRIMARY assertion is on USER-VISIBLE rendered state: a result row's
 *     FavoriteButton (content-description "Favorite"). That node composes ONLY
 *     when a real result row renders — never on the "Nothing found" empty state,
 *     never on the "Search failed" Error state, and never on the provider filter
 *     chip bar (which renders from providerIds regardless of results). This is
 *     the exact per-row signal C11 (archive.org) and C38 (IPTorrents) use.
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix / with the defect re-introduced)
 * 1. Apply the mutation: in
 *    feature/search_result/.../SearchResultViewModel.kt, make the streaming
 *    search always end empty — e.g. in `observeStreamMultiSearch`, replace the
 *    `sdk.streamMultiSearch(...).collect { handleMultiSearchEvent(event) }`
 *    body with a no-op (drop every event) so `handleStreamEnd()` sees 0 items
 *    and renders `SearchResultContent.Empty` ("Nothing found"). NON-CRASHING.
 *    (Equivalent stronger mutation per the spec: force
 *    `SearchResultContent.Error("Something went wrong")` immediately after
 *    submission, bypassing the real API call.)
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run ONLY this Challenge:
 *    `adb shell am instrument -w -e class lava.app.challenges.Challenge58SearchReturnsResultsTest digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner`
 * 4. Expected failure: the 45 s `waitUntil` for the "Favorite" per-row node
 *    times out and the final assertion fails with
 *      "Search MUST render at least one result row (a TopicListItem FavoriteButton);
 *       got the empty/error state instead."
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout SearchResultViewModel.kt).
 * 6. Rebuild and re-run the identical Challenge against the mock (or, on a
 *    real engine, a query with known hits).
 * 7. Expected pass: a result row's FavoriteButton renders within 45 s.
 *
 * ### Mutation type
 * NON-CRASHING BREAK — the mutation yields the empty/error screen the operator
 * actually saw, not a crash (§6.AB.3).
 *
 * ### LVA-008 dependency
 * This Challenge navigates to `search_result` (a nested route). The
 * [LenientTeardownRule] (outside the compose rule) swallows ONLY the
 * activity-destroy nav-teardown IllegalStateException so the result-row
 * assertion decides pass/fail. Any genuine assertion failure propagates.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge58SearchReturnsResultsTest"
 *
 * // covers-feature: search_result
 * // covers-changelog: Search actually returns results (LVA-083)
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
class Challenge58SearchReturnsResultsTest {

    // ── Rules ────────────────────────────────────────────────────────────────
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Onboarding-complete + generic authorized signal so the test starts in the
    // bottom-tab nav (where a real user lands after onboarding). It does NOT seed
    // any provider — the @Before block does that explicitly below.
    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    // LVA-008: swallow the activity-destroy nav-teardown ISE (not a user-path
    // crash) so the result-row assertion decides pass/fail. Outer of composeRule.
    @get:Rule(order = 2)
    val lenientTeardown = LenientTeardownRule()

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ── Fields ───────────────────────────────────────────────────────────────
    private lateinit var server: MockWebServer

    // Non-real API key (§6.R — never a production credential; MockWebServer
    // ignores auth headers entirely).
    private val TEST_AUTH_KEY = "C58-TEST-KEY"

    // The single provider onboarded for this test. "rutracker" is a built-in
    // descriptor in the live registry (RuTrackerDescriptor: trackerId="rutracker",
    // displayName="RuTracker.org"), so it resolves a stable chip + a per-provider
    // search request to the mock.
    private val SEEDED_PROVIDER = "rutracker"

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

        // Seed exactly one onboarded, search-enabled provider so the search-input
        // submit resolves a NON-EMPTY providerIds set → the production
        // observeStreamMultiSearch path runs (filter.providerIds != null).
        // ensureDefault writes a ProviderConfig with isEnabled=true,
        // searchEnabled=true (its declared defaults).
        runBlocking { providerRepo().ensureDefault(SEEDED_PROVIDER) }

        // MockWebServer returns ONE result row for EVERY per-provider search
        // request (a Dispatcher avoids FIFO-ordering fragility if the path makes
        // more than one request). The DTO matches ApiBackedTrackerClient's
        // SearchResultDto/SearchItemDto: results[].id + results[].title are the
        // only required fields and id+title render a TopicListItem row.
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(NON_EMPTY_SEARCH_RESULT_JSON)
        }
        server.start()
        ApiBaseUrlHolder.set(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            key = TEST_AUTH_KEY,
        )

        // §6.AK root-cause fix (2026-08-10): SEEDED_PROVIDER ("rutracker") is one
        // of the 7 BUNDLED compiled-in factories (TrackerClientModule.register at
        // DI time) — the real, direct-to-rutracker.org RuTrackerClientFactory, NOT
        // the mockable ApiBackedTrackerClient. ApiBaseUrlHolder only feeds the
        // DYNAMIC client that DefaultTrackerRegistry.populateFrom() installs; a
        // bundled provider ignores it entirely. OnboardingBypassRule (used above)
        // deliberately skips onboarding, so the real populateFrom() call the
        // ApiSelection step normally makes NEVER runs — this test's MockWebServer
        // was previously a no-op and the app was silently hitting the real
        // rutracker.org site, which this sandboxed emulator cannot reach,
        // explaining the observed hang. Install the dynamic mock-backed client for
        // SEEDED_PROVIDER explicitly so search actually routes to MockWebServer.
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
        // Restore the bundled fallback set (§6.AK fix above) so a sibling test
        // doesn't inherit this test's dynamic mock-backed registration.
        registry().populateFrom(emptyList())
        // Disable the seeded provider so it does not leak as a search-enabled chip
        // into sibling tests that expect a fresh "no onboarded providers" state.
        runCatching { runBlocking { providerRepo().keepOnlySearchEnabled(emptySet()) } }
    }

    @Test
    fun searchWithOnboardedProvider_rendersAtLeastOneResultRow() {
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

        // Type a query and submit via the IME Search action (same as a user
        // pressing the keyboard search key).
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // PRIMARY ASSERTION — a real RESULT ROW renders. The FavoriteButton's
        // content-description "Favorite" composes ONLY on a TopicListItem result
        // row (the per-row signal C11/C38 also use). The empty ("Nothing found")
        // and error ("Search failed") states do NOT render it, and the provider
        // filter chip bar does NOT render it.
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithContentDescription("Favorite")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Search MUST render at least one result row (a TopicListItem " +
                "FavoriteButton); got the empty/error state instead.",
            composeRule.onAllNodesWithContentDescription("Favorite")
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    companion object {
        // Minimal valid lava-api-go SearchResult JSON with ONE result item.
        // Field names match ApiBackedTrackerClient.SearchResultDto (provider,
        // page, totalPages, results) + SearchItemDto (id, title required). Same
        // non-empty shape as Challenge44ApiSearchAuthTest's enqueued body.
        private val NON_EMPTY_SEARCH_RESULT_JSON =
            """{"provider":"rutracker","page":0,"totalPages":1,""" +
                """"results":[{"id":"1","title":"Ubuntu 24.04 LTS Desktop",""" +
                """"sizeBytes":1,"seeders":7,"leechers":0,""" +
                """"magnetLink":"magnet:?xt=urn:btih:AB",""" +
                """"downloadUrl":"https://x/1.torrent","infoHash":"AB","category":"OS"}]}"""
    }
}
