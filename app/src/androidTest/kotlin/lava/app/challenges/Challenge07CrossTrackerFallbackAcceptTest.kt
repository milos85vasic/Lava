/*
 * Challenge Test C7 — Cross-tracker fallback ACCEPT (rendered-modal flow,
 * 2026-06-08, after the screen-wiring closure).
 *
 * The shallow C7 (Phase 2.9, 2026-05-04) only asserted the Topics tab was
 * reachable. The 2026-06-08 contract-guard rev recorded an honest §6.J
 * DEAD-END finding: the CrossTrackerFallbackModal Composable existed and the
 * ViewModel had proposeFallback / onFallbackAccept / the FallbackAccept action
 * + crossTrackerFallback state slot, BUT SearchResultScreen never read
 * state.crossTrackerFallback and never called CrossTrackerFallbackModal(...).
 * The modal was dead-ended at the screen layer (§6.Q/C37 class) — a real user
 * could never see it.
 *
 * THAT GAP IS NOW CLOSED. SearchResultScreen renders the modal when
 * state.crossTrackerFallback != null, dispatching FallbackAccept on the
 * "Try <tracker>" confirm button via the same `onAction` idiom every control
 * uses. The full rendered accept flow is therefore reachable, so this
 * Challenge is upgraded from a contract guard to the real rendered-modal flow.
 *
 * WHAT THIS CHALLENGE ASSERTS (rendered, user-visible state):
 *   The production `CrossTrackerFallbackModal` Composable (the SAME one
 *   SearchResultScreen now renders) shows the "Try <tracker>" confirm
 *   button + the "<failedTracker> is unavailable" title a real user sees when
 *   every mirror of the failed tracker is unreachable, and TAPPING
 *   "Try <tracker>" fires the production onAccept callback — the exact
 *   callback SearchResultScreen wires to perform(FallbackAccept). This mirrors
 *   C30's `tapped`-capture rendered pattern (createComposeRule + setContent +
 *   onNodeWithText + performClick + capture).
 *
 *   The modal Composable is public in :feature:search_result, so :app renders
 *   it directly — the same wiring SearchResultScreen.SearchResultScreen(state,
 *   onAction) instantiates (state.crossTrackerFallback?.let { ... onAccept =
 *   { onAction(FallbackAccept) } }). The FallbackAccept action itself is
 *   internal to the feature module; the screen's dispatch is what the modal's
 *   onAccept lambda drives, and that lambda IS what this test captures.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   Production-path mutation (the dead-end this Challenge guards against
 *   recurring): in SearchResultScreen.kt delete the
 *   `state.crossTrackerFallback?.let { proposal -> CrossTrackerFallbackModal(
 *   ... onAccept = { onAction(SearchResultAction.FallbackAccept) } ... ) }`
 *   block (revert to the dead-ended screen). The modal no longer renders in
 *   the production user flow, re-introducing the exact §6.J defect. The
 *   feature-level rendered assertion below still passes (it renders the modal
 *   Composable directly), so the screen-wiring regression is additionally
 *   guarded by SearchResultViewModelFallbackTest at the VM layer
 *   (FallbackAccept_clears_crossTrackerFallback_slot) — that test fails if the
 *   action is unhandled.
 *
 *   Composable-level mutation proving THIS test discriminates: in
 *   CrossTrackerFallbackModal.kt change the confirmButton label from
 *   `Text("Try $proposedTracker")` to `Text("Confirm")`. Re-run:
 *   `onNodeWithText("Try RuTor").performClick()` finds no node →
 *   "Failed: assertExists ... could not find any node that satisfies:
 *   (Text + EditableText contains 'Try RuTor' (ignoreCase: false))" and the
 *   accept callback never fires → `accepted.value` stays false → the final
 *   assert fails with "tapping 'Try RuTor' did not invoke onAccept". Revert;
 *   re-run; passes.
 *
 * Honest scope: SOURCE-WRITTEN + COMPILE-VERIFIED. EXECUTION against a booted
 * emulator is GATE-HOST-DEFERRED per §6.AH-debt (this macOS host cannot boot
 * the Containers-driven emulator; host-direct is forbidden by §6.AH). This
 * Challenge MUST NOT be recorded as a passing attestation row until it has
 * EXECUTED on a Linux x86_64 + KVM gate-host.
 *
 * // covers-feature: search_result
 */
package lava.app.challenges

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import lava.designsystem.theme.LavaTheme
import lava.search.result.components.CrossTrackerFallbackModal
import org.junit.Rule
import org.junit.Test

class Challenge07CrossTrackerFallbackAcceptTest {

    @get:Rule
    val composeRule = createComposeRule()

    // CHALLENGE: the production fallback modal renders the failed-tracker
    // title + the "Try <proposedTracker>" confirm affordance a real user taps.
    @Test
    fun fallbackModal_rendersTitleAndTryButton() {
        composeRule.setContent {
            LavaTheme {
                CrossTrackerFallbackModal(
                    failedTracker = "RuTracker",
                    proposedTracker = "RuTor",
                    onAccept = {},
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("RuTracker is unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Try RuTor").assertIsDisplayed()
    }

    // CHALLENGE: tapping "Try <proposedTracker>" fires the production onAccept
    // callback — the exact callback SearchResultScreen wires to
    // perform(FallbackAccept). This is the user-visible accept action.
    @Test
    fun tappingTryButton_invokesOnAccept() {
        val accepted = mutableStateOf(false)
        composeRule.setContent {
            LavaTheme {
                CrossTrackerFallbackModal(
                    failedTracker = "RuTracker",
                    proposedTracker = "RuTor",
                    onAccept = { accepted.value = true },
                    onDismiss = {},
                )
            }
        }

        composeRule.onNodeWithText("Try RuTor").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { accepted.value }
        assert(accepted.value) {
            "tapping 'Try RuTor' did not invoke onAccept — the cross-tracker " +
                "accept path the screen wires to perform(FallbackAccept) is broken"
        }
    }
}
