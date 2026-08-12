/*
 * Challenge Test C59 — Search uses ONLY the onboarded providers (LVA-084, video #2).
 *
 * DRAFT authored 2026-06-26 (Stream B, §6.AK cycle-coverage spec
 * docs/superpowers/specs/2026-06-26-ak-cycle-coverage-spec.md §2.2). Authored
 * statically — NOT executed on a device by its author (device owned by another
 * stream). The main stream device-runs it reproduce-first per §6.AK clause 2.
 *
 * OPERATOR-REPORTED DEFECT (video #2 against 1076, LVA-084, P0):
 *   "Onboarded provider is NOT the provider set used by Search; unconfigured
 *    providers are active as filters." Root cause: `SearchInputViewModel`
 *   initialized `selectedProviders = availableProviders` — a HARDCODED list of
 *   four ids (rutracker/rutor/archiveorg/gutenberg) — so the chip bar showed
 *   four phantom providers regardless of what the user onboarded, and the search
 *   queried the wrong set. The fix sources the chip bar from
 *   `ProviderConfigRepository` (the same table onboarding writes to), filtered
 *   by `searchEnabled && isEnabled`.
 *
 * WHAT THE USER DOES (the surface this Challenge traverses verbatim):
 *   onboard exactly ONE provider → MainActivity → Search action →
 *   SearchInputScreen → observe the provider filter chip bar.
 *
 * The seam: exactly ONE provider ("rutracker") is seeded into the real
 * [ProviderConfigRepository]; NO other provider is onboarded. The search-input
 * chip bar (the surface LVA-084's bug lived on) MUST therefore show exactly that
 * one provider chip and NONE of the other (un-onboarded) providers.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → Nav graph →
 *     SearchInputScreen → SearchInputViewModel → ProviderConfigRepository. The
 *     chip bar is the real material3 ProviderChipBar; nothing is mocked above the
 *     network socket.
 *   - PRIMARY assertion is on USER-VISIBLE rendered state: the chip labels in the
 *     search-input ProviderChipBar. The single onboarded provider's chip MUST be
 *     present and selected; the THREE un-onboarded providers MUST be ABSENT. The
 *     hardcoded-four defect rendered all four here — this assertion would have
 *     caught it on the first emulator boot.
 *   - SECONDARY assertion (search uses the onboarded set): after submit, the
 *     results filter chip bar MUST NOT show any un-onboarded provider label.
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix / with the defect re-introduced)
 * 1. Apply the mutation: in
 *    feature/search_input/.../SearchInputViewModel.kt, restore the hardcoded
 *    default set — make `loadOnboardedChips()` build chips from a fixed list
 *    `listOf("rutracker","rutor","archiveorg","gutenberg")` instead of
 *    `onboardedSearchableProviders()`. NON-CRASHING.
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run ONLY this Challenge:
 *    `adb shell am instrument -w -e class lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner`
 * 4. Expected failure: the un-onboarded chips now render, so
 *      "Un-onboarded provider 'RuTor.info' MUST NOT appear as a search chip …"
 *    fails (RuTor.info / Internet Archive / Project Gutenberg are present).
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout SearchInputViewModel.kt).
 * 6. Rebuild and re-run.
 * 7. Expected pass: only "RuTracker.org" (the seeded provider) renders as a chip,
 *    selected; the other three are absent.
 *
 * ### Mutation type
 * NON-CRASHING BREAK — the mutation renders extra chips, not a crash (§6.AB.3).
 *
 * ### LVA-008 dependency
 * The search-input route (`search/search_input`) and the submit-to-`search_result`
 * route are nested. The [LenientTeardownRule] (outside the compose rule) swallows
 * ONLY the activity-destroy nav-teardown IllegalStateException so the chip
 * assertions decide pass/fail.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge59SearchUsesOnboardedProvidersTest"
 *
 * // covers-feature: search_input
 * // covers-changelog: Search filters follow the providers you onboarded (LVA-084)
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsSelected
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
class Challenge59SearchUsesOnboardedProvidersTest {

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

    private val TEST_AUTH_KEY = "C59-TEST-KEY"

    // The ONLY onboarded provider. Its descriptor (RuTrackerDescriptor) declares
    // trackerId="rutracker", displayName="RuTracker.org".
    private val SEEDED_PROVIDER = "rutracker"
    private val SEEDED_LABEL = "RuTracker.org"

    // Providers that are NOT onboarded — they MUST NOT appear as chips. Labels
    // come from their descriptors: RuTorDescriptor → "RuTor.info",
    // ArchiveOrgDescriptor → "Internet Archive", GutenbergDescriptor →
    // "Project Gutenberg".
    private val UNONBOARDED_LABELS = listOf("RuTor.info", "Internet Archive", "Project Gutenberg")

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

        // Onboard EXACTLY ONE provider. Clear any leftover rows first by disabling
        // all, then enable only the seeded one — so the chip bar reflects exactly
        // one onboarded, search-enabled provider regardless of sibling-test order.
        runBlocking {
            providerRepo().keepOnlySearchEnabled(emptySet())
            providerRepo().ensureDefault(SEEDED_PROVIDER)
            providerRepo().keepOnlySearchEnabled(setOf(SEEDED_PROVIDER))
        }

        // Empty-results dispatcher: results filter chips render from
        // filter.providerIds regardless of results, so an empty body is enough to
        // let the results screen mount for the secondary assertion.
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"provider":"rutracker","page":0,"totalPages":0,"results":[]}""")
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
        // test's MockWebServer was a no-op (the app was silently hitting the real
        // rutracker.org, unreachable from this sandboxed emulator). See C58 for
        // the full analysis (identical setup pattern).
        registry().populateFrom(
            listOf(
                RemoteTrackerDescriptor(
                    trackerId = SEEDED_PROVIDER,
                    displayName = SEEDED_LABEL,
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
    fun searchInputChipBar_showsOnlyOnboardedProvider_andSearchDoesNotUseOthers() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Search history", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Open SearchInputScreen.
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()

        // Wait for the onboarded provider's chip to render in the ProviderChipBar.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(SEEDED_LABEL).fetchSemanticsNodes().isNotEmpty()
        }

        // PRIMARY ASSERTION (a) — the single onboarded provider's chip renders and
        // is selected by default (the onboarded set is selected on load).
        composeRule.onAllNodesWithText(SEEDED_LABEL).onFirst().assertIsSelected()

        // PRIMARY ASSERTION (b) — NO un-onboarded provider appears as a chip. The
        // hardcoded-four defect rendered all of these.
        UNONBOARDED_LABELS.forEach { label ->
            assertTrue(
                "Un-onboarded provider '$label' MUST NOT appear as a search chip " +
                    "(only the onboarded provider should render). The hardcoded-four " +
                    "default would render it.",
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty(),
            )
        }

        // SECONDARY ASSERTION — submit and confirm the SEARCH uses only the
        // onboarded set: the results screen mounts and its filter chip bar shows
        // no un-onboarded provider label.
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // The SearchResultScreen's filter chip bar renders an "All" chip
        // (SearchResultScreen.kt:386) — the only screen that shows "All", and it
        // composes only once providerIds is non-empty. Wait for it as the
        // results-mounted signal AND so the chip bar exists before the absence checks.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("All").fetchSemanticsNodes().isNotEmpty()
        }
        UNONBOARDED_LABELS.forEach { label ->
            assertTrue(
                "Search MUST query only the onboarded provider — the results " +
                    "filter chip bar MUST NOT show un-onboarded provider '$label'.",
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty(),
            )
        }
    }
}
