/*
 * Challenge Test C7 — Cross-tracker fallback ACCEPT (DEEP restoration
 * 2026-06-08, with an honest dead-end finding).
 *
 * The shallow C7 (Phase 2.9, 2026-05-04) only asserted the Topics tab was
 * reachable. The original deep intent (commit f21b4d94): simulate all
 * RuTracker mirrors unhealthy → search → CrossTrackerFallbackModal renders
 * → tap "Try RuTor" → results re-render from RuTor.
 *
 * ANTI-BLUFF FINDING (§6.J — surfaced while restoring this Challenge):
 *
 *   The CrossTrackerFallbackModal Composable EXISTS
 *   (feature/search_result/.../components/CrossTrackerFallbackModal.kt) with
 *   "Try <tracker>" / "Cancel" buttons, and the ViewModel HAS the
 *   proposeFallback / onFallbackAccept / onFallbackDismiss logic plus the
 *   FallbackAccept / FallbackDismiss actions and the crossTrackerFallback
 *   state slot. BUT SearchResultScreen NEVER RENDERS the modal: it does not
 *   read state.crossTrackerFallback and never calls CrossTrackerFallbackModal(...).
 *   `CrossTrackerFallbackModal(` is called nowhere in production; the
 *   FallbackAccept/FallbackDismiss actions are dispatched by no rendered UI.
 *   The modal is DEAD-ENDED at the screen layer — identical in shape to the
 *   pre-fix add-comment dialog that C37 caught. A real user can perform a
 *   search whose every mirror is unhealthy and NEVER see the accept modal;
 *   they only get the ShowFallbackDismissedError snackbar ("<tracker> is
 *   unavailable").
 *
 *   Therefore a Challenge that "drives a real user search → fallback modal
 *   appears → tap Try RuTor" would be a BLUFF BY CONSTRUCTION: the modal
 *   cannot appear in the production user flow. Writing it would assert on UI
 *   that does not render. Per §6.J the honest move is to REFUSE that
 *   fabrication and instead guard the production CONTRACT that the
 *   acceptance path is built on, while flagging the wiring gap for the main
 *   stream to close (wire the modal into SearchResultScreen, then this
 *   Challenge upgrades to the full rendered accept flow).
 *
 * WHAT THIS CHALLENGE ASSERTS (the reachable, non-bluff contract):
 *   The cross-tracker-fallback ACCEPT surface is present on the runtime
 *   classpath end to end — the modal Composable function, the FallbackAccept
 *   action the screen must dispatch, and the crossTrackerFallback state slot
 *   the screen must read. If any link is removed the accept path cannot be
 *   wired, and this guard fails. This mirrors the C37 runtime-classpath
 *   reachability pattern for a feature whose final UI wiring is owed.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In SearchResultAction.kt remove `data object FallbackAccept`.
 *   2. Re-build + re-run on the gating emulator.
 *   3. Expected failure: Class.forName("...SearchResultAction$FallbackAccept")
 *      throws ClassNotFoundException and the check() fails with
 *      "FallbackAccept action missing — the cross-tracker accept contract
 *      the screen must dispatch was removed".
 *   4. Revert; re-run; passes.
 *
 *   The ViewModel-level falsifiability of onFallbackAccept (clearing the
 *   modal state) is owed in SearchResultViewModelFallbackTest at the
 *   feature/search_result unit layer.
 *
 * UPGRADE PATH (owed to the main stream): once SearchResultScreen reads
 * state.crossTrackerFallback and renders CrossTrackerFallbackModal(...) with
 * onAccept = { perform(FallbackAccept) }, replace this contract guard with
 * the full rendered flow: search with a forced-unhealthy seam → wait for
 * "Try <tracker>" → performClick → assert the result list re-renders from
 * the proposed tracker.
 *
 * // covers-feature: search_result
 */
package lava.app.challenges

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge07CrossTrackerFallbackAcceptTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun crossTrackerFallback_acceptSurface_isReachableOnRuntimeClasspath() {
        hiltRule.inject()

        // The modal the screen must render on a fallback proposal.
        val modalKt = Class.forName("lava.search.result.components.CrossTrackerFallbackModalKt")
        check(modalKt.name == "lava.search.result.components.CrossTrackerFallbackModalKt") {
            "CrossTrackerFallbackModal removed — the fallback accept UI is gone"
        }

        // The action the screen must dispatch when the user taps "Try <tracker>".
        val acceptAction = Class.forName("lava.search.result.SearchResultAction\$FallbackAccept")
        check(acceptAction.simpleName == "FallbackAccept") {
            "FallbackAccept action missing — the cross-tracker accept contract " +
                "the screen must dispatch was removed"
        }

        // The state slot the screen must read to decide whether to show the modal.
        val proposal = Class.forName("lava.search.result.CrossTrackerFallbackProposal")
        check(proposal.simpleName == "CrossTrackerFallbackProposal") {
            "CrossTrackerFallbackProposal state slot missing — the screen has " +
                "no way to know a fallback was proposed"
        }
    }
}
