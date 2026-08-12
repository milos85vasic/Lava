/*
 * Challenge Test C61 — results filter chips show provider DISPLAY NAMES, not
 * raw lowercase ids (LVA-085, video #4).
 *
 * DRAFT authored 2026-06-26 (§6.AK cycle-coverage spec §2.4 LVA-085). This file
 * is authored statically — it has NOT been executed on a device by its author
 * (the device + Android Gradle are owned by another stream). The main stream
 * device-runs it reproduce-first per §6.AK clause 2.
 *
 * OPERATOR-REPORTED DEFECT (video #4 against 1076, LVA-085, P1):
 *   The results filter chips rendered the RAW lowercase provider id
 *   ('torrentdownloads', 'archiveorg', 'kinozal', 'yts') instead of the
 *   human-readable displayName ('Torrent Downloads', 'Internet Archive',
 *   'Kinozal', 'YTS'). The prior LVA-079 "fix" sorted the raw id LIST; its JVM
 *   test asserted the id list, never the rendered LABEL — a §6.AB bluff. This
 *   Challenge drives the REAL rendered chip text on a device.
 *
 * WHAT THE USER DOES (the surface this Challenge traverses verbatim):
 *   MainActivity → bottom-tab nav → Search action → SearchInputScreen → type a
 *   query → IME submit → SearchResultScreen → the ProviderFilterChipBar renders
 *   one chip per requested provider. Each chip's label MUST be the provider's
 *   displayName ("RuTracker.org", "Internet Archive"), never its raw id
 *   ("rutracker", "archiveorg").
 *
 * The seam: a [MockWebServer] inside this instrumented-test process is injected
 * as the active API base URL via [ApiBaseUrlHolder.set] (the same seam C58/C52
 * use). TWO providers whose id != displayName are seeded into the real
 * [ProviderConfigRepository] so the search-input submit resolves a NON-EMPTY,
 * multi-provider providerIds set → the production observeStreamMultiSearch path
 * runs → `ProviderFilterChipBar` renders one chip per provider.
 *
 * WHY THE DISPLAY NAME IS THE PRODUCTION SDK'S, NOT THE MOCK'S:
 *   `LavaTrackerSdk.streamMultiSearch` emits
 *   `MultiSearchEvent.ProviderStart(id, descriptor.displayName)`
 *   (LavaTrackerSdk.kt:826) where `descriptor.displayName` comes from the LOCAL
 *   tracker registry:
 *     - RuTrackerDescriptor.displayName = "RuTracker.org"  (RuTrackerDescriptor.kt:11)
 *     - ArchiveOrgDescriptor.displayName = "Internet Archive" (ArchiveOrgDescriptor.kt:21)
 *   `applyMultiSearchEvent` writes that displayName into
 *   `SearchPageState.providerDisplayNames` (SearchResultViewModel.kt:672), and
 *   `ProviderFilterChipBar` renders `providerDisplayNames[pid] ?: pid`
 *   (SearchResultScreen.kt:394). So the chip text is independent of MockWebServer.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → Nav graph →
 *     SearchInputScreen → SearchInputViewModel → SearchResultScreen →
 *     SearchResultViewModel → LavaTrackerSdk → ApiBackedTrackerClient → OkHttp.
 *     The ONLY faked boundary is the network socket (MockWebServer).
 *   - PRIMARY assertion is on USER-VISIBLE rendered text: each chip's label
 *     equals the provider's displayName, AND no chip renders the raw lowercase
 *     id. The raw-id-absence assertion is the discriminating one — it is exactly
 *     what the operator saw broken.
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix / with the defect re-introduced)
 * 1. Apply the mutation: in
 *    feature/search_result/.../SearchResultScreen.kt, inside
 *    `ProviderFilterChipBar`, replace
 *      `val displayName = providerDisplayNames[pid] ?: pid`
 *    with
 *      `val displayName = pid`
 *    so each chip renders the raw lowercase id. NON-CRASHING.
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run ONLY this Challenge:
 *    `adb shell am instrument -w -e class lava.app.challenges.Challenge61ResultsChipsShowDisplayNamesTest digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner`
 * 4. Expected failure: the 45 s `waitUntil` for the "RuTracker.org" chip times
 *    out (the chip now reads "rutracker"); the test fails with
 *      "Results filter chip MUST render the provider displayName 'RuTracker.org',
 *       not the raw id 'rutracker'."
 *    AND the raw-id-absence assertion fails because a chip with exact text
 *    "rutracker" is present.
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout SearchResultScreen.kt).
 * 6. Rebuild and re-run the identical Challenge.
 * 7. Expected pass: chips read "RuTracker.org" + "Internet Archive"; no chip
 *    reads the raw "rutracker" / "archiveorg".
 *
 * ### Mutation type
 * NON-CRASHING BREAK — the mutation renders the wrong (raw-id) chip text the
 * operator actually saw, not a crash (§6.AB.3).
 *
 * ### LVA-008 dependency
 * This Challenge navigates to `search_result` (a nested route). The
 * [LenientTeardownRule] (outside the compose rule) swallows ONLY the
 * activity-destroy nav-teardown IllegalStateException so the chip-label
 * assertion decides pass/fail. Any genuine assertion failure propagates.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge61ResultsChipsShowDisplayNamesTest"
 *
 * // covers-feature: search_result
 * // covers-changelog: Provider chips show friendly names (RuTracker.org, Internet Archive) instead of raw ids (LVA-085)
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
class Challenge61ResultsChipsShowDisplayNamesTest {

    // ── Rules ────────────────────────────────────────────────────────────────
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Onboarding-complete + generic authorized signal so the test starts in the
    // bottom-tab nav. It does NOT seed any provider — the @Before block does.
    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    // LVA-008: swallow the activity-destroy nav-teardown ISE (not a user-path
    // crash) so the chip-label assertion decides pass/fail. Outer of composeRule.
    @get:Rule(order = 2)
    val lenientTeardown = LenientTeardownRule()

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ── Fields ───────────────────────────────────────────────────────────────
    private lateinit var server: MockWebServer

    // Non-real API key (§6.R — never a production credential; MockWebServer
    // ignores auth headers entirely).
    private val TEST_AUTH_KEY = "C61-TEST-KEY"

    // Two providers whose raw id != displayName, both built-in descriptors in
    // the live registry:
    //   rutracker  → "RuTracker.org"     (RuTrackerDescriptor.kt:11)
    //   archiveorg → "Internet Archive"  (ArchiveOrgDescriptor.kt:21)
    private val PROVIDER_RUTRACKER = "rutracker"
    private val PROVIDER_ARCHIVEORG = "archiveorg"
    private val DISPLAY_RUTRACKER = "RuTracker.org"
    private val DISPLAY_ARCHIVEORG = "Internet Archive"

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

        // Seed BOTH providers as onboarded, search-enabled so the search-input
        // submit resolves providerIds = [archiveorg, rutracker] (sorted) → the
        // production observeStreamMultiSearch path runs → ProviderFilterChipBar
        // renders one chip per provider. ensureDefault writes isEnabled=true,
        // searchEnabled=true (its declared defaults).
        runBlocking {
            providerRepo().ensureDefault(PROVIDER_RUTRACKER)
            providerRepo().ensureDefault(PROVIDER_ARCHIVEORG)
        }

        // MockWebServer returns ONE result row for EVERY per-provider search
        // request. The provider DISPLAY NAME on the chips comes from the SDK's
        // local descriptor (via MultiSearchEvent.ProviderStart), NOT this body —
        // the non-empty body only ensures the providers reach a successful
        // terminal state so the result screen settles into Content.
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(resultJsonFor(request.path.orEmpty()))
        }
        server.start()
        ApiBaseUrlHolder.set(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            key = TEST_AUTH_KEY,
        )

        // §6.AK root-cause fix (2026-08-10): both PROVIDER_RUTRACKER and
        // PROVIDER_ARCHIVEORG are BUNDLED compiled-in factories that ignore
        // ApiBaseUrlHolder entirely — only the dynamic ApiBackedTrackerClient
        // installed by DefaultTrackerRegistry.populateFrom() reads it.
        // OnboardingBypassRule skips the real onboarding flow, so populateFrom()
        // never runs, and this test's MockWebServer was a no-op. See C58 for the
        // full analysis (identical setup pattern).
        registry().populateFrom(
            listOf(
                RemoteTrackerDescriptor(
                    trackerId = PROVIDER_RUTRACKER,
                    displayName = DISPLAY_RUTRACKER,
                    baseUrls = emptyList(),
                    capabilities = setOf(TrackerCapability.SEARCH),
                    authType = AuthType.NONE,
                    encoding = "UTF-8",
                ),
                RemoteTrackerDescriptor(
                    trackerId = PROVIDER_ARCHIVEORG,
                    displayName = DISPLAY_ARCHIVEORG,
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
        // Disable the seeded providers so they do not leak as search-enabled
        // chips into sibling tests expecting a fresh "no onboarded providers" state.
        runCatching { runBlocking { providerRepo().keepOnlySearchEnabled(emptySet()) } }
    }

    @Test
    fun resultsFilterChips_renderDisplayNames_notRawIds() {
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

        // Type a query and submit via the IME Search action.
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // Confirm the SearchResultScreen mounted: its provider filter chip bar
        // always renders the "All" chip (SearchResultScreen.kt:386).
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("All").fetchSemanticsNodes().isNotEmpty()
        }

        // PRIMARY ASSERTION (part 1) — each provider chip renders its DISPLAY
        // NAME. The displayName is stamped into providerDisplayNames by the SDK's
        // MultiSearchEvent.ProviderStart (descriptor.displayName), so we wait for
        // the human-readable chip text to appear (it starts as the raw id and
        // flips to the displayName once ProviderStart arrives).
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithText(DISPLAY_RUTRACKER).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(DISPLAY_ARCHIVEORG).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Results filter chip MUST render the provider displayName " +
                "'$DISPLAY_RUTRACKER', not the raw id '$PROVIDER_RUTRACKER'.",
            composeRule.onAllNodesWithText(DISPLAY_RUTRACKER).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "Results filter chip MUST render the provider displayName " +
                "'$DISPLAY_ARCHIVEORG', not the raw id '$PROVIDER_ARCHIVEORG'.",
            composeRule.onAllNodesWithText(DISPLAY_ARCHIVEORG).fetchSemanticsNodes().isNotEmpty(),
        )

        // PRIMARY ASSERTION (part 2) — the discriminating check: NO chip (or any
        // node) renders the raw lowercase provider id. onAllNodesWithText does an
        // EXACT, case-sensitive match by default, so "RuTracker.org" does NOT
        // match "rutracker" — only a raw-id chip would. This is exactly the
        // operator-reported defect.
        assertTrue(
            "NO results filter chip may render the raw lowercase id " +
                "'$PROVIDER_RUTRACKER' — it must show the displayName '$DISPLAY_RUTRACKER'.",
            composeRule.onAllNodesWithText(PROVIDER_RUTRACKER).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "NO results filter chip may render the raw lowercase id " +
                "'$PROVIDER_ARCHIVEORG' — it must show the displayName '$DISPLAY_ARCHIVEORG'.",
            composeRule.onAllNodesWithText(PROVIDER_ARCHIVEORG).fetchSemanticsNodes().isEmpty(),
        )
    }

    // Minimal valid lava-api-go SearchResult JSON with ONE result item, tagged
    // with the provider parsed from the request path so each per-provider
    // request gets a body that names its own provider. Field names match
    // ApiBackedTrackerClient.SearchResultDto + SearchItemDto.
    private fun resultJsonFor(path: String): String {
        val provider = when {
            path.contains(PROVIDER_RUTRACKER) -> PROVIDER_RUTRACKER
            path.contains(PROVIDER_ARCHIVEORG) -> PROVIDER_ARCHIVEORG
            else -> PROVIDER_RUTRACKER
        }
        return """{"provider":"$provider","page":0,"totalPages":1,""" +
            """"results":[{"id":"1","title":"Ubuntu 24.04 LTS Desktop",""" +
            """"sizeBytes":1,"seeders":7,"leechers":0,""" +
            """"magnetLink":"magnet:?xt=urn:btih:AB",""" +
            """"downloadUrl":"https://x/1.torrent","infoHash":"AB","category":"OS"}]}"""
    }
}
