/*
 * Challenge Test C60 — Search-input chips and results-filter chips AGREE
 * (LVA-079, video #3).
 *
 * DRAFT authored 2026-06-26 (Stream B, §6.AK cycle-coverage spec
 * docs/superpowers/specs/2026-06-26-ak-cycle-coverage-spec.md §2.3). Authored
 * statically — NOT executed on a device by its author (device owned by another
 * stream). The main stream device-runs it reproduce-first per §6.AK clause 2.
 *
 * OPERATOR-REPORTED DEFECT (video #3 against 1076, LVA-079, P1):
 *   "Search-input chips vs results-filter chips disagree + the results chip set
 *    is non-deterministic run-to-run." Root cause: the input chips and the
 *   results filter chips were sourced from DIFFERENT places — input from
 *   `SearchInputViewModel.selectedProviders` (the onboarded set) and results from
 *   the SSE response order. The fix routes the results chip set through
 *   `SearchPageState.filterProviderChipIds` = `filter.providerIds.distinct().sorted()`
 *   — the SAME request-derived, deterministically-sorted set the input layer uses.
 *
 * WHAT THE USER DOES (the surface this Challenge traverses verbatim):
 *   onboard ≥2 providers → MainActivity → Search action → SearchInputScreen
 *   (observe input provider chips) → type a query → IME submit →
 *   SearchResultScreen (observe results filter chips) → the two chip sets AGREE.
 *
 * The seam: TWO providers ("rutracker", "rutor") are seeded into the real
 * [ProviderConfigRepository]; both render as input chips. After submit, the
 * results filter chip bar (the real `ProviderFilterChipBar` in SearchResultScreen
 * — renders `providerDisplayNames[pid] ?: pid`) MUST show the SAME provider
 * labels — and NO extra/un-onboarded provider.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → Nav graph →
 *     SearchInputScreen (real ProviderChipBar) → SearchInputViewModel →
 *     SearchResultScreen (real ProviderFilterChipBar) → SearchResultViewModel.
 *     Nothing is mocked above the network socket.
 *   - PRIMARY assertion is on USER-VISIBLE rendered state: the provider-chip
 *     LABELS rendered on BOTH screens. Each onboarded provider's label MUST
 *     appear on the input chip bar AND on the results filter chip bar (set
 *     agreement), and no un-onboarded provider label may appear on the results
 *     chip bar (no phantom/extra chip).
 *
 * KNOWN DEPENDENCY (flagged honestly for the device-runner): the results filter
 * chips render `state.providerDisplayNames[pid] ?: pid`. `providerDisplayNames`
 * is populated by the streaming `MultiSearchEvent.ProviderStart` events (which
 * carry each provider's displayName from the live registry). If those events do
 * not fire / do not stamp display names (the LVA-085 surface), the results chips
 * fall back to the RAW id ("rutracker") and will NOT match the input label
 * ("RuTracker.org") — i.e. this Challenge is RED until display-name propagation
 * is correct. That is the intended reproduce-first signal, not a flake: a build
 * where input and results disagree is exactly the defect under test.
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix / with the defect re-introduced)
 * 1. Apply the mutation (set disagreement): in
 *    feature/search_input/.../SearchInputViewModel.kt, make
 *    `resolveProviderIdsForSubmit()` inject a phantom provider not in the
 *    onboarded set — e.g. `return (selectedProviders + "gutenberg").toList().sorted()`.
 *    The results screen then renders a "Project Gutenberg" filter chip the input
 *    screen never showed. NON-CRASHING.
 *    (Equivalent order-disagreement mutation per the spec: remove the `.sorted()`
 *    from `onboardedSearchableProviders()` while leaving `filterProviderChipIds`
 *    sorted, so the two surfaces order differently.)
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run ONLY this Challenge:
 *    `adb shell am instrument -w -e class lava.app.challenges.Challenge60InputResultsChipsAgreeTest digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner`
 * 4. Expected failure: the phantom mutation makes
 *      "Results filter chip bar MUST NOT show un-onboarded provider 'Project Gutenberg' …"
 *    fail (an extra chip appears on results that was never on input).
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout SearchInputViewModel.kt).
 * 6. Rebuild and re-run.
 * 7. Expected pass: the input chip set {"RuTracker.org","RuTor.info"} equals the
 *    results filter chip set (each label present on both, no extra on results).
 *
 * ### Mutation type
 * NON-CRASHING BREAK — the mutation yields a disagreeing chip set, not a crash
 * (§6.AB.3).
 *
 * ### LVA-008 dependency
 * Navigates `search/search_input` → `search_result` (nested routes). The
 * [LenientTeardownRule] (outside the compose rule) swallows ONLY the
 * activity-destroy nav-teardown IllegalStateException so the chip assertions
 * decide pass/fail.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge60InputResultsChipsAgreeTest"
 *
 * // covers-feature: search_result
 * // covers-changelog: Search-input and results filter chips agree (LVA-079)
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
import lava.tracker.client.ApiBaseUrlHolder
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
class Challenge60InputResultsChipsAgreeTest {

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

    private val TEST_AUTH_KEY = "C60-TEST-KEY"

    // Two onboarded providers. Labels from descriptors:
    // rutracker → "RuTracker.org", rutor → "RuTor.info".
    private val SEEDED_PROVIDERS = listOf("rutracker", "rutor")
    private val SEEDED_LABELS = listOf("RuTracker.org", "RuTor.info")

    // A provider deliberately NOT onboarded — it must appear on NEITHER chip bar.
    private val UNONBOARDED_LABEL = "Project Gutenberg"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ProviderSeedEntryPoint {
        fun providerConfigRepository(): ProviderConfigRepository
    }

    private fun providerRepo(): ProviderConfigRepository {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return EntryPointAccessors.fromApplication(app, ProviderSeedEntryPoint::class.java)
            .providerConfigRepository()
    }

    @Before
    fun setUp() {
        hiltRule.inject()

        // Onboard EXACTLY the two seeded providers (and nothing else).
        runBlocking {
            providerRepo().keepOnlySearchEnabled(emptySet())
            SEEDED_PROVIDERS.forEach { providerRepo().ensureDefault(it) }
            providerRepo().keepOnlySearchEnabled(SEEDED_PROVIDERS.toSet())
        }

        // Empty-results dispatcher — the filter chips render from the request
        // provider set regardless of results, so empty bodies are enough to let
        // the results screen + its chip bar compose.
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
    }

    @After
    fun tearDown() {
        server.shutdown()
        ApiBaseUrlHolder.reset()
        runCatching { runBlocking { providerRepo().keepOnlySearchEnabled(emptySet()) } }
    }

    @Test
    fun inputProviderChips_agreeWith_resultsFilterChips() {
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Search history", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Open SearchInputScreen and wait for BOTH input chips to render.
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            SEEDED_LABELS.all { label ->
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
        }

        // PRECONDITION — capture the input chip set: both seeded labels are on the
        // input chip bar, the un-onboarded provider is not.
        SEEDED_LABELS.forEach { label ->
            assertTrue(
                "Input chip bar MUST show onboarded provider '$label'.",
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty(),
            )
        }
        assertTrue(
            "Input chip bar MUST NOT show un-onboarded provider '$UNONBOARDED_LABEL'.",
            composeRule.onAllNodesWithText(UNONBOARDED_LABEL).fetchSemanticsNodes().isEmpty(),
        )

        // Submit → SearchResultScreen.
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // Results screen mounted when its filter chip bar renders the "All" chip
        // (SearchResultScreen.kt:386) — the only screen that shows "All", composed
        // once providerIds is non-empty. Wait for it before the agreement checks.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("All").fetchSemanticsNodes().isNotEmpty()
        }

        // PRIMARY ASSERTION (a) — AGREEMENT: every input provider label is also
        // present on the results filter chip bar (same set).
        SEEDED_LABELS.forEach { label ->
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(
                "Results filter chip bar MUST show the same provider '$label' the " +
                    "input chip bar showed (input/results chip sets MUST agree). If " +
                    "this fails with the raw id instead of the label, the results " +
                    "display-name propagation (LVA-085) is broken.",
                composeRule.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty(),
            )
        }

        // PRIMARY ASSERTION (b) — NO EXTRA: the results chip bar shows no provider
        // the input chip bar did not (catches phantom/extra chips and the
        // SSE-response-order source defect).
        assertTrue(
            "Results filter chip bar MUST NOT show un-onboarded provider " +
                "'$UNONBOARDED_LABEL' — the results chip set must equal the input " +
                "chip set, never a superset.",
            composeRule.onAllNodesWithText(UNONBOARDED_LABEL).fetchSemanticsNodes().isEmpty(),
        )
    }
}
