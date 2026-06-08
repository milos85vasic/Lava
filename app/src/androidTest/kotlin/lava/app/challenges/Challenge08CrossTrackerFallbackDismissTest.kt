/*
 * Challenge Test C8 — Cross-tracker fallback DISMISS (DEEP restoration
 * 2026-06-08, with an honest dead-end finding).
 *
 * The shallow C8 (Phase 2.10, 2026-05-04) only asserted the four bottom
 * tabs render. The original deep intent (commit f21b4d94): simulate all
 * RuTracker mirrors unhealthy → search → modal appears → tap "Cancel" →
 * explicit failure UI renders → NO silent RuTor fallback.
 *
 * ANTI-BLUFF FINDING (§6.J — same as C7): the CrossTrackerFallbackModal is
 * DEAD-ENDED at the screen layer. SearchResultScreen never reads
 * state.crossTrackerFallback and never calls CrossTrackerFallbackModal(...),
 * so a real user can never see the dismiss modal. The only production
 * fallback surface a user actually reaches is the
 * ShowFallbackDismissedError snackbar ("<tracker> is unavailable") posted by
 * SearchResultViewModel.onFallbackDismiss — and even that is only reachable
 * once the modal that drives FallbackDismiss is wired. Driving "search →
 * modal → tap Cancel → assert Search failed + no RuTor results" would be a
 * BLUFF BY CONSTRUCTION. Per §6.J we refuse the fabrication and guard the
 * production CONTRACT the dismiss path is built on, flagging the wiring gap.
 *
 * WHAT THIS CHALLENGE ASSERTS (the reachable, non-bluff contract):
 *   The cross-tracker-fallback DISMISS surface is present on the runtime
 *   classpath end to end — the FallbackDismiss action the screen must
 *   dispatch on Cancel, AND the ShowFallbackDismissedError side effect the
 *   screen reacts to by showing the "<tracker> is unavailable" snackbar
 *   (the no-silent-fallback guarantee the original deep C8 protected). If
 *   the dismiss-then-surface-explicit-failure contract is removed, this
 *   guard fails.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In SearchResultSideEffect.kt remove
 *      `data class ShowFallbackDismissedError(val failedTracker: String)`.
 *   2. Re-build + re-run on the gating emulator.
 *   3. Expected failure:
 *      Class.forName("...SearchResultSideEffect$ShowFallbackDismissedError")
 *      throws ClassNotFoundException and the check() fails with
 *      "ShowFallbackDismissedError side effect missing — dismiss would
 *      silently fall back with no explicit failure UI". This is exactly the
 *      silent-fallback regression the original deep C8 existed to catch.
 *   4. Revert; re-run; passes.
 *
 *   The ViewModel-level falsifiability (onFallbackDismiss posting the
 *   explicit error and NOT re-running on the alt tracker) is owed in
 *   SearchResultViewModelFallbackTest at the feature/search_result layer.
 *
 * UPGRADE PATH (owed to the main stream): once SearchResultScreen renders
 * the modal with onDismiss = { perform(FallbackDismiss) }, replace this
 * contract guard with the full rendered flow: search with a forced-unhealthy
 * seam → wait for "Cancel" → performClick → assert the "<tracker> is
 * unavailable" snackbar appears AND the result list did NOT silently switch
 * to the proposed tracker.
 *
 * // covers-feature: search_result
 */
package lava.app.challenges

import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge08CrossTrackerFallbackDismissTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @Test
    fun crossTrackerFallback_dismissSurface_isReachableOnRuntimeClasspath() {
        hiltRule.inject()

        // The action the screen must dispatch when the user taps "Cancel".
        val dismissAction = Class.forName("lava.search.result.SearchResultAction\$FallbackDismiss")
        check(dismissAction.simpleName == "FallbackDismiss") {
            "FallbackDismiss action missing — the cross-tracker dismiss contract " +
                "the screen must dispatch was removed"
        }

        // The side effect the screen reacts to by showing the explicit
        // "<tracker> is unavailable" snackbar — the no-silent-fallback
        // guarantee the original deep C8 protected.
        val dismissedError =
            Class.forName("lava.search.result.SearchResultSideEffect\$ShowFallbackDismissedError")
        check(dismissedError.simpleName == "ShowFallbackDismissedError") {
            "ShowFallbackDismissedError side effect missing — dismiss would " +
                "silently fall back with no explicit failure UI"
        }
    }
}
