/*
 * Challenge Test C56 — Rating dialog (feature/rating) renders its real
 * Show state and acting on it DISMISSES the dialog. UI-audit gap W6 /
 * matrix row "rating" (docs/qa/2026-06-25-ui-coverage-audit.md R6: C30
 * is WEAK — classpath-only `RatingViewModel::class.java` assertion,
 * never renders or interacts). This Challenge is the behavior-asserting
 * upgrade.
 *
 * WHAT C30 DID NOT DO (the bluff this closes):
 *   C30 only asserts `RatingViewModel::class.java.name == "…"` — it
 *   proves the class is on the classpath, NOT that the rating dialog
 *   renders, NOT that a user can act on it, NOT that the action does
 *   anything user-visible. By §6.AB it is a WEAK presence test. C56
 *   drives the REAL Show state through the REAL
 *   ObserveRatingRequestUseCase engagement gate, in the REAL MainActivity
 *   composition (which composes RatingDialog() as an overlay), and
 *   asserts the user-visible dismissal outcome.
 *
 * HOW THE REAL Show STATE IS REACHED (no test-only state injection):
 *   The production gate is ObserveRatingRequestUseCaseImpl: it emits
 *   RatingRequest.Show only when ALL of —
 *     (a) rating not disabled,
 *     (b) launch-count countdown has reached 0, AND
 *     (c) an engagement threshold is crossed (search history > 3 OR
 *         visited > 5 OR bookmarks > 2).
 *   C56 satisfies these through the REAL repositories via a Hilt entry
 *   point — exactly the rows production writes:
 *     - RatingRepository.setLaunchCount(0)        → condition (b)
 *     - RatingRepository.postponeRatingRequest()  → makes Show carry
 *       allowDisableForever=true so the dialog renders the
 *       "Больше не спрашивать" (never-ask-again) button this test taps
 *       (postpone does NOT dismiss — the gate keeps emitting Show)
 *     - SearchHistoryRepository.add(Filter(query=…)) ×4 → condition (c)
 *       (HistoryCounter == 3, so 4 non-pinned searches cross it)
 *   (a) holds by default. The dialog that then appears is the SAME
 *   RatingRequest.Show every real, engaged user sees — produced by the
 *   real use case observing the seeded rows, not a forced fake state.
 *   Because RatingDialog observes reactively (collectAsState over
 *   ObserveRatingRequestUseCase), seeding after the activity launches
 *   still flips the rendered state — exactly as it does in production
 *   when the user crosses the threshold mid-session.
 *
 * WHAT THIS TEST ASSERTS (primary = user-visible rendered state):
 *   1. The real RatingDialog renders its Show state inside MainActivity:
 *      the dialog title "Как вам приложение?" (the production string in
 *      RatingDialog.kt) and the "Больше не спрашивать" (never-ask-again)
 *      TextButton are rendered. A user who reaches the engagement
 *      threshold (request postponed) sees exactly this prompt.
 *   2. ACT on the prompt via the real production path: tapping
 *      "Больше не спрашивать" fires RatingAction.NeverAskAgainClick →
 *      RatingViewModel.onNeverAskAgainClick → DisableRatingRequestUseCase
 *      — the SAME terminal disable-the-request action the star-tap
 *      RatingClick path uses (onRatingClick also calls
 *      disableRatingRequestUseCase). The never-ask-again button is chosen
 *      because it carries real, addressable button text (the star Row has
 *      contentDescription=null and cannot be semantically targeted),
 *      while exercising the identical user-visible terminal outcome.
 *   3. After the disable action the real ObserveRatingRequestUseCase
 *      flips its emission back to RatingRequest.Hide, so the dialog title
 *      "Как вам приложение?" is no longer rendered — the chief assertion.
 *      A real user who acts on the prompt sees it disappear; that
 *      disappearance is what C56 gates.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → RatingDialog()
 *     (the production overlay call at MainActivity.kt) → RatingViewModel
 *     (real Orbit container) → the real Observe/Disable rating use cases
 *     → the real RatingRepository + SearchHistoryRepository. No mocked
 *     ViewModel, no faked Show state, no Robolectric.
 *   - OnboardingBypassRule pre-seeds onboarding-complete so MainActivity
 *     reaches the `showOnboarding == false` branch that composes
 *     RatingDialog() — the exact state every real user is in after
 *     finishing onboarding.
 *   - The open-link side effect (the star-tap RatingClick path) is not
 *     exercised here; the never-ask-again path is pure disable, so no
 *     browser intent launches. The PRIMARY assertion is the dialog's
 *     disappearance (rendered Compose state).
 *   - The Show state is produced by the real engagement gate, so a
 *     regression that breaks the gate (dialog never shows) fails step 1,
 *     and a regression that breaks act-dismissal (dialog stays after the
 *     tap) fails step 3.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing defect):
 *   MUTATION — make the terminal action a no-op so the dialog never
 *   dismisses:
 *     1. In feature/rating/.../RatingViewModel.kt, in
 *        onNeverAskAgainClick(), remove the `disableRatingRequestUseCase()`
 *        call so the click does nothing terminal (the app does NOT crash,
 *        the button still highlights):
 *          private fun onNeverAskAgainClick() = intent {
 *              // disableRatingRequestUseCase()   <-- removed
 *          }
 *        The engagement gate keeps emitting RatingRequest.Show, so the
 *        dialog STAYS rendered after the tap.
 *     2. Re-run on the gating emulator:
 *          ./gradlew :app:connectedDebugAndroidTest --tests \
 *            "lava.app.challenges.Challenge56RatingSubmitDismissesDialogTest"
 *     3. Expected failure: the waitUntil for the title to disappear times
 *        out; the assertion
 *          "Acting on the rating prompt (never-ask-again) MUST dismiss the
 *           rating dialog — RatingViewModel is not disabling the rating
 *           request if the title is still rendered after the tap."
 *        fires because "Как вам приложение?" is still present.
 *     4. Restore disableRatingRequestUseCase(); re-run; the action
 *        dismisses the dialog and the test passes.
 *
 * HONEST SCOPE (§6.J / §6.AH gate-host deferral):
 *   SOURCE-WRITTEN + DISCRIMINATION-SCANNER-VERIFIED on the current
 *   darwin/arm64 host. NOT yet EXECUTED against an emulator — per §6.AH
 *   the emulator MUST run inside a Container/VM via the Containers
 *   submodule, a path that does not yet boot on this macOS host
 *   (§6.AH-debt). The §6.AE.5 per-AVD attestation row is produced when
 *   the operator runs scripts/run-challenge-matrix.sh on an eligible
 *   gate-host. Device-exec status: PENDING (gate-host deferred).
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge56RatingSubmitDismissesDialogTest"
 *
 * // covers-feature: rating
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import lava.app.OnboardingBypassRule
import lava.data.api.repository.RatingRepository
import lava.data.api.repository.SearchHistoryRepository
import lava.models.search.Filter
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge56RatingSubmitDismissesDialogTest {

    // ── Hilt entry point to reach the production rating + history repos ──
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RatingSeedEntryPoint {
        fun ratingRepository(): RatingRepository
        fun searchHistoryRepository(): SearchHistoryRepository
    }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Pre-seeds onboarding-complete so MainActivity reaches the
    // showOnboarding==false branch that composes RatingDialog() — the
    // exact state a real user is in after finishing onboarding.
    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var ratingRepository: RatingRepository
    private lateinit var searchHistoryRepository: SearchHistoryRepository

    private val title = "Как вам приложение?"
    private val neverAskAgain = "Больше не спрашивать"

    @Before
    fun setUp() {
        hiltRule.inject()
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val entry = EntryPointAccessors.fromApplication(app, RatingSeedEntryPoint::class.java)
        ratingRepository = entry.ratingRepository()
        searchHistoryRepository = entry.searchHistoryRepository()

        // Seed the REAL engagement gate so the REAL ObserveRatingRequestUseCase
        // emits RatingRequest.Show — exactly the rows production writes.
        // RatingDialog observes reactively, so seeding after the activity
        // launches still flips the rendered state (the same way it does in
        // production when the user crosses the threshold mid-session).
        runBlocking {
            searchHistoryRepository.clear()
            // launch-count countdown reached 0 → condition (b)
            ratingRepository.setLaunchCount(0)
            // Postpone so RatingRequest.Show carries allowDisableForever=true
            // → the dialog renders the "Больше не спрашивать" button this
            // test taps. Postpone does NOT dismiss; only disable does.
            ratingRepository.postponeRatingRequest()
            // search history > HistoryCounter(3) → engagement condition (c)
            repeat(4) { i ->
                searchHistoryRepository.add(Filter(query = "c56-engagement-$i"))
            }
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            // Restore a clean slate so the seeded engagement does not leak
            // into other tests.
            searchHistoryRepository.clear()
        }
    }

    @Test
    fun ratingShowState_renders_andActingOnItDismissesDialog() {
        hiltRule.inject()

        // Step 1 — the real Show state renders inside MainActivity: the
        // production dialog title + the never-ask-again button are
        // visible. A real engaged user (request postponed) sees exactly
        // this prompt.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "The rating dialog MUST render its Show state — the production title " +
                "\"$title\" MUST be visible once the real engagement gate " +
                "(launch-count 0 + search history > 3) is crossed. If this fails, " +
                "ObserveRatingRequestUseCase is not emitting RatingRequest.Show.",
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty(),
        )
        composeRule.onNodeWithText(neverAskAgain).assertExists()

        // Step 2 — act on the prompt via the real production path: tap the
        // "Больше не спрашивать" button. This fires
        // RatingAction.NeverAskAgainClick → the real ViewModel calls
        // DisableRatingRequestUseCase — the same terminal disable the
        // star-tap RatingClick path performs, on an addressable control.
        composeRule.onNodeWithText(neverAskAgain).performClick()

        // Step 3 — PRIMARY ASSERTION: the dialog DISMISSES. Disabling the
        // rating request makes the real ObserveRatingRequestUseCase emit
        // RatingRequest.Hide, so the title disappears. This is the
        // user-visible outcome of acting on the prompt: it goes away.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "Acting on the rating prompt (never-ask-again) MUST dismiss the rating dialog — " +
                "RatingViewModel is not disabling the rating request if the title " +
                "\"$title\" is still rendered after the tap.",
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty(),
        )
    }
}
