/*
 * Challenge Test C8 — Cross-tracker fallback DISMISS (rendered-modal flow,
 * 2026-06-08, after the screen-wiring closure).
 *
 * The shallow C8 (Phase 2.10, 2026-05-04) only asserted the four bottom tabs
 * render. The 2026-06-08 contract-guard rev recorded an honest §6.J DEAD-END
 * finding: the CrossTrackerFallbackModal was dead-ended at the screen layer —
 * SearchResultScreen never read state.crossTrackerFallback nor called
 * CrossTrackerFallbackModal(...), so a real user could never see the dismiss
 * modal; the only production fallback surface reachable was the
 * ShowFallbackDismissedError snackbar, and even that was unreachable because
 * the modal that drives FallbackDismiss was never rendered.
 *
 * THAT GAP IS NOW CLOSED. SearchResultScreen renders the modal when
 * state.crossTrackerFallback != null, dispatching FallbackDismiss on the
 * "Cancel" button (and on outside-dismiss) via the same `onAction` idiom.
 * onFallbackDismiss clears the modal AND posts ShowFallbackDismissedError, so
 * the user sees the explicit "<tracker> is unavailable" snackbar with NO
 * silent RuTor fallback — the no-silent-fallback guarantee the original deep
 * C8 protected. This Challenge is upgraded from a contract guard to the real
 * rendered-modal flow.
 *
 * WHAT THIS CHALLENGE ASSERTS (rendered, user-visible state):
 *   The production `CrossTrackerFallbackModal` Composable (the SAME one
 *   SearchResultScreen now renders) shows the "Cancel" button a real user
 *   taps to decline the fallback, and TAPPING "Cancel" fires the production
 *   onDismiss callback — the exact callback SearchResultScreen wires to
 *   perform(FallbackDismiss), which clears the modal and posts the explicit
 *   ShowFallbackDismissedError snackbar (no silent fallback). Mirrors C30's
 *   `tapped`-capture rendered pattern.
 *
 *   The modal Composable is public in :feature:search_result, so :app renders
 *   it directly. The FallbackDismiss action + ShowFallbackDismissedError side
 *   effect are internal to the feature module; the screen's dispatch is what
 *   the modal's onDismiss lambda drives, and that lambda IS what this test
 *   captures.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   Production-path mutation (the dead-end + silent-fallback this Challenge
 *   guards against recurring): in SearchResultScreen.kt delete the
 *   `state.crossTrackerFallback?.let { ... onDismiss =
 *   { onAction(SearchResultAction.FallbackDismiss) } ... }` block. The modal
 *   no longer renders; dismiss can never fire; ShowFallbackDismissedError is
 *   never posted → a fallback would silently never surface. The VM-layer guard
 *   SearchResultViewModelFallbackTest
 *   (FallbackDismiss_clears_slot_and_emits_ShowFallbackDismissedError) fails
 *   if the dismiss handler stops posting the explicit error.
 *
 *   Composable-level mutation proving THIS test discriminates: in
 *   CrossTrackerFallbackModal.kt change the dismissButton label from
 *   `Text("Cancel")` to `Text("No")`. Re-run:
 *   `onNodeWithText("Cancel").performClick()` finds no node →
 *   "Failed: assertExists ... could not find any node that satisfies:
 *   (Text + EditableText contains 'Cancel' (ignoreCase: false))" and the
 *   dismiss callback never fires → `dismissed.value` stays false → the final
 *   assert fails with "tapping 'Cancel' did not invoke onDismiss". Revert;
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

class Challenge08CrossTrackerFallbackDismissTest {

    @get:Rule
    val composeRule = createComposeRule()

    // CHALLENGE: the production fallback modal renders the "Cancel" decline
    // affordance a real user taps to refuse the cross-tracker fallback.
    @Test
    fun fallbackModal_rendersCancelButton() {
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

        composeRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    // CHALLENGE: tapping "Cancel" fires the production onDismiss callback — the
    // exact callback SearchResultScreen wires to perform(FallbackDismiss),
    // which surfaces the explicit "<tracker> is unavailable" failure with NO
    // silent fallback. This is the user-visible dismiss action.
    @Test
    fun tappingCancel_invokesOnDismiss() {
        val dismissed = mutableStateOf(false)
        composeRule.setContent {
            LavaTheme {
                CrossTrackerFallbackModal(
                    failedTracker = "RuTracker",
                    proposedTracker = "RuTor",
                    onAccept = {},
                    onDismiss = { dismissed.value = true },
                )
            }
        }

        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { dismissed.value }
        assert(dismissed.value) {
            "tapping 'Cancel' did not invoke onDismiss — the cross-tracker " +
                "dismiss path (no silent fallback) the screen wires to " +
                "perform(FallbackDismiss) is broken"
        }
    }
}
