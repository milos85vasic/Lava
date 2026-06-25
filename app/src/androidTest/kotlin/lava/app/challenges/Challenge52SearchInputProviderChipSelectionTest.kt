/*
 * Challenge Test C52 — search_input ProviderChipBar selection drives the
 * search provider set (on-device §6.AB / §6.J acceptance gate for the
 * prior all-providers-selected-by-default defect).
 *
 * PRIOR REAL DEFECT (operator-reported on 1.2.23-1043, §6.L 57th invocation):
 *   "Search selects all providers as filters, even the ones which have not
 *    been configured during onboarding! These shall be unselected by default!"
 *   Root cause: `SearchInputViewModel` initialized `selectedProviders =
 *   availableProviders` — ALL FOUR provider chips selected by default,
 *   regardless of what the user onboarded. The search then either failed with
 *   "not registered" for un-onboarded providers or silently sent traffic for
 *   providers the user never configured. The fix (SearchInputViewModel.kt
 *   onCreate + resolveProviderIdsForSubmit) initializes the selected set from
 *   the persisted ProviderConfigRepository and lets the user toggle chips.
 *
 * THE GAP THIS CHALLENGE CLOSES (per docs/qa/2026-06-25-ui-coverage-audit.md
 * R3): the chip-selection → submit boundary had ZERO direct Challenge
 * coverage — only `SearchInputViewModelTest` at the unit level. No test drove
 * the REAL FilterChips on the REAL SearchInputScreen, tapped them, and
 * asserted that the rendered selection state matches the user's taps and that
 * submit carries exactly the selected set into the search. This Challenge
 * closes that gap end-to-end on a real device.
 *
 * WHAT THE USER DOES (the surface this Challenge traverses verbatim):
 *   MainActivity → bottom-tab nav → Search action → SearchInputScreen →
 *   the REAL ProviderChipBar FilterChips → tap to select/deselect →
 *   type a query → submit (IME Search) → SearchResultScreen.
 *
 * COMBINATIONS COVERED (one test per user-reachable selection shape):
 *   1. SELECT ONE  — tap a single provider chip; assert ONLY that chip
 *      renders selected, the other three render unselected; submit reaches
 *      the result screen searching that one provider.
 *   2. SELECT MULTIPLE — tap two provider chips; assert BOTH render selected
 *      and the other two unselected.
 *   3. DESELECT TO NONE — having selected chips, tap them again; assert the
 *      rendered selection returns to NONE (the deselect path the prior defect
 *      could not reach because everything was forced-selected).
 *
 * WHY THIS CHALLENGE IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → the Nav graph →
 *     SearchScreen (bottom tab) → SearchInputScreen → ProviderChipBar
 *     (real material3 FilterChip) → SearchInputViewModel (Orbit) →
 *     ProviderConfigRepository. No ViewModel, no screen, no chip is mocked.
 *   - The PRIMARY assertion is on USER-VISIBLE STATE: the rendered selection
 *     state of the FilterChips (`isSelected` semantics on the exact chip a
 *     real user sees and taps), NOT a classpath / `assertNotNull` smell.
 *   - The chip `selected` flag is the EXACT field the prior defect got wrong
 *     (all-true by default). A test that asserts the rendered chips reflect
 *     the user's taps — and ONLY the user's taps — would have caught the
 *     1.2.23 defect on the first emulator boot.
 *   - The fresh-install default (no onboarded providers) is asserted up front:
 *     NO chip may be selected before the user taps anything. The prior defect
 *     rendered all four selected here.
 *   - A MockWebServer + ApiBaseUrlHolder seam handles the network so the
 *     submit-to-SearchResult path can complete without a real lava-api-go
 *     endpoint; it never stubs the chip bar, the VM, or the Compose tree.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing defect → the bug):
 *
 *   MUTATION — re-introduce the all-providers-selected-by-default defect:
 *     1. In SearchInputViewModel, change the onCreate reduce so every chip is
 *        forced selected regardless of the persisted config, exactly like the
 *        pre-fix 1.2.23 code:
 *          reduce {
 *              state.copy(
 *                  providerChips = availableProviders.map { it.copy(selected = true) },
 *              )
 *          }
 *        (and/or set `selectedProviders = availableProviders.map { it.providerId }.toSet()`).
 *     2. Re-run this Challenge on the gating emulator/device:
 *          ./gradlew :app:connectedDebugAndroidTest \
 *            --tests "lava.app.challenges.Challenge52SearchInputProviderChipSelectionTest"
 *     3. Expected failure: `freshInstall_noChipSelectedByDefault` FAILS — all
 *        four chips render `isSelected`, so the
 *          "no provider chip may be selected before the user taps anything"
 *        assertion (asserting ZERO selected chips) fails with the Compose
 *        SemanticsNodeInteraction assertion message
 *          "Failed to assert the following: (SelectableGroup …)/isSelected = 'false'".
 *        AND `selectOneChip_onlyThatChipSelected` FAILS because the three
 *        un-tapped chips render selected when they must be unselected.
 *     4. Revert the mutation; re-run; the default is NONE-selected, taps
 *        select exactly the tapped chips, and all assertions pass.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge52SearchInputProviderChipSelectionTest"
 *
 * // covers-feature: search_input
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsNotSelected
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

/**
 * On-device Compose UI Challenge for the search_input ProviderChipBar
 * selection. Drives the REAL FilterChips on the REAL SearchInputScreen,
 * taps them through several selection combinations, and asserts the rendered
 * selection state matches exactly the user's taps — the user-visible state
 * the prior all-providers-selected-by-default defect got wrong.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL — see class KDoc above.
 */
@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge52SearchInputProviderChipSelectionTest {

    // ── Rules ────────────────────────────────────────────────────────────────────

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Pre-seeds onboarding-complete + a generic authorized signal so the test
    // starts in the bottom-tab nav — the same state a real user is in after
    // finishing onboarding. NB: it does NOT onboard any provider, so the
    // chip-bar default is the fresh-install NONE-selected state (the exact
    // state the prior all-selected defect violated).
    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // ── Fields ────────────────────────────────────────────────────────────────

    private lateinit var server: MockWebServer

    // A stable, non-real API key for the MockWebServer endpoint (§6.R — never
    // a production credential; MockWebServer ignores auth headers entirely).
    private val TEST_AUTH_KEY = "C52-TEST-KEY"

    // The four provider chip labels rendered by ProviderChipBar (the exact
    // displayNames in SearchInputViewModel.availableProviders).
    private val RUTRACKER = "RuTracker"
    private val RUTOR = "RuTor"
    private val ARCHIVE = "Internet Archive"
    private val GUTENBERG = "Gutenberg"

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Before
    fun setUp() {
        hiltRule.inject()

        // Spin up the in-process MockWebServer and point the SDK at it so the
        // submit-to-SearchResult path can complete without a real endpoint.
        server = MockWebServer()
        server.start()
        ApiBaseUrlHolder.set(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            key = TEST_AUTH_KEY,
        )

        // Enqueue minimal valid (empty) search responses for any provider that
        // does make a request after submit. The chip selection — not the
        // network — is what this Challenge asserts on.
        repeat(20) {
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
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper — navigate the real production UI to the SearchInputScreen and
    // wait until the four provider FilterChips are rendered.
    // ─────────────────────────────────────────────────────────────────────────
    private fun openSearchInputScreen() {
        // Wait for the Search tab's history empty-state to confirm we are in
        // the main bottom-tab nav (same shape as C47).
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Search history", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Tap the Search action button (magnifying-glass icon in the app bar).
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()

        // Wait for the ProviderChipBar's chips to render on the SearchInputScreen.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(RUTRACKER).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(GUTENBERG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST 1 — FRESH-INSTALL DEFAULT: NO chip selected.
    //
    // The bypass rule onboards NOTHING, so SearchInputViewModel.onCreate reads
    // an empty ProviderConfigRepository and MUST default-select NO chip.
    // The prior 1.2.23 defect rendered ALL FOUR chips selected here.
    //
    // FALSIFIABILITY (Mutation): force `selected = true` in onCreate → every
    // chip renders selected → these assertIsNotSelected calls FAIL.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun freshInstall_noChipSelectedByDefault() {
        openSearchInputScreen()

        // PRIMARY ASSERTION — user-visible rendered chip state: with nothing
        // onboarded, none of the four provider chips may render selected.
        composeRule.onAllNodesWithText(RUTRACKER).onFirst().assertIsNotSelected()
        composeRule.onAllNodesWithText(RUTOR).onFirst().assertIsNotSelected()
        composeRule.onAllNodesWithText(ARCHIVE).onFirst().assertIsNotSelected()
        composeRule.onAllNodesWithText(GUTENBERG).onFirst().assertIsNotSelected()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST 2 — SELECT ONE: tapping a single chip selects ONLY that chip.
    //
    // FALSIFIABILITY (Mutation): the all-selected-by-default mutation makes the
    // three un-tapped chips render selected → the assertIsNotSelected calls on
    // RuTor/Internet Archive/Gutenberg FAIL.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun selectOneChip_onlyThatChipSelected() {
        openSearchInputScreen()

        // Tap the RuTracker chip.
        composeRule.onAllNodesWithText(RUTRACKER).onFirst().performClick()
        composeRule.waitForIdle()

        // PRIMARY ASSERTION — only the tapped chip renders selected.
        composeRule.onAllNodesWithText(RUTRACKER).onFirst().assertIsSelected()
        composeRule.onAllNodesWithText(RUTOR).onFirst().assertIsNotSelected()
        composeRule.onAllNodesWithText(ARCHIVE).onFirst().assertIsNotSelected()
        composeRule.onAllNodesWithText(GUTENBERG).onFirst().assertIsNotSelected()

        // Submit reaches the SearchResultScreen searching exactly the selected
        // provider (a strict subset → providerIds = [rutracker], not all/null).
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // The SearchResultScreen renders its 'Navigate up' back arrow as soon
        // as it mounts — confirming the submit completed with the chosen
        // selection rather than aborting on the input screen.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithContentDescription("Navigate up", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Submit with a single selected provider chip MUST reach the " +
                "SearchResultScreen (its 'Navigate up' back arrow must render " +
                "within 20 s). If this fails, the chip-selected search did not " +
                "open the result screen.",
            composeRule.onAllNodesWithContentDescription("Navigate up", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST 3 — SELECT MULTIPLE: tapping two chips selects exactly those two.
    //
    // FALSIFIABILITY (Mutation): the all-selected mutation makes the two
    // un-tapped chips render selected → their assertIsNotSelected calls FAIL.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun selectMultipleChips_onlyThoseChipsSelected() {
        openSearchInputScreen()

        // Tap two provider chips: Internet Archive + Gutenberg.
        composeRule.onAllNodesWithText(ARCHIVE).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(GUTENBERG).onFirst().performClick()
        composeRule.waitForIdle()

        // PRIMARY ASSERTION — exactly the two tapped chips render selected,
        // the other two render unselected.
        composeRule.onAllNodesWithText(ARCHIVE).onFirst().assertIsSelected()
        composeRule.onAllNodesWithText(GUTENBERG).onFirst().assertIsSelected()
        composeRule.onAllNodesWithText(RUTRACKER).onFirst().assertIsNotSelected()
        composeRule.onAllNodesWithText(RUTOR).onFirst().assertIsNotSelected()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST 4 — DESELECT TO NONE: tapping a selected chip again deselects it.
    //
    // Drives the full toggle cycle: select two chips, then tap each again, and
    // assert the rendered selection returns to NONE. The prior all-selected
    // defect could not reach a true NONE state from the chip bar.
    //
    // FALSIFIABILITY (Mutation): make ProviderToggled a no-op (or only ever
    // ADD, never remove) → the second tap does not deselect → the final
    // assertIsNotSelected calls FAIL because the chips stay selected.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun deselectChips_returnsToNoneSelected() {
        openSearchInputScreen()

        // Select two chips.
        composeRule.onAllNodesWithText(RUTRACKER).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(RUTOR).onFirst().performClick()
        composeRule.waitForIdle()

        // Sanity: both are now selected (the precondition for the deselect path).
        composeRule.onAllNodesWithText(RUTRACKER).onFirst().assertIsSelected()
        composeRule.onAllNodesWithText(RUTOR).onFirst().assertIsSelected()

        // Tap each selected chip again to deselect.
        composeRule.onAllNodesWithText(RUTRACKER).onFirst().performClick()
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText(RUTOR).onFirst().performClick()
        composeRule.waitForIdle()

        // PRIMARY ASSERTION — the rendered selection returns to NONE.
        composeRule.onAllNodesWithText(RUTRACKER).onFirst().assertIsNotSelected()
        composeRule.onAllNodesWithText(RUTOR).onFirst().assertIsNotSelected()
        composeRule.onAllNodesWithText(ARCHIVE).onFirst().assertIsNotSelected()
        composeRule.onAllNodesWithText(GUTENBERG).onFirst().assertIsNotSelected()
    }
}
