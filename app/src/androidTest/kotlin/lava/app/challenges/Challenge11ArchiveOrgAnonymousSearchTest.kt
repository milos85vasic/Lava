/*
 * Challenge Test C11 — Internet Archive anonymous Continue flow
 * (Phase 4.1a redesign 2026-05-04, SHALLOW).
 *
 * The Internet Archive provider was the catalyst for clause 6.G: it
 * shipped with AuthType.NONE, the user tapped Continue, and the spinner
 * never cleared. The fix landed in commit 49714c0; the JVM regression
 * test landed in commit 32706b8; the OnboardingScreen single-collector
 * fix landed in commit b60665d; the AuthService.signalAuthorized
 * bridge landed in commit 45fd1ae. THIS test is the gating Compose UI
 * Challenge that proves the FULL CHAIN works on the real device surface.
 *
 * Flow:
 *   1. Onboarding "Select Provider" screen
 *   2. Tap "Internet Archive"
 *   3. Tap Continue
 *   4. Main app's Search-history empty state renders (NOT "Authorization
 *      required to search" — the legacy auth bridge has unblocked it)
 *
 * The deep-coverage version (search archive.org for "ubuntu", assert
 * result row with "downloads" or "seeders" text) hits the same
 * androidx.navigation:navigation-compose 2.9.0 lifecycle bug as
 * C4-C8 + C2's deep version. Owed once the library is upgraded;
 * recorded in the per-test status matrix in
 * docs/superpowers/plans/2026-05-04-pending-completion-plan.md.
 *
 * Anti-bluff posture (clauses 6.J/6.L):
 *
 *   This test is honestly REDUCED in scope, not a bluff. It traverses
 *   the full onboarding flow + the layer-1 (short-circuit) + layer-2
 *   (OnboardingScreen + sdk.switchTracker) + layer-3 (signalAuthorized)
 *   fixes — every one of which can be falsified by reverting that
 *   layer's production change. The user-visible "Search history" empty
 *   state is the constitutional definition of "the IA flow works."
 *
 * STATUS: requires ArchiveOrgDescriptor.verified=true to make the
 * provider appear in onboarding. Phase 4.1a flips the descriptor;
 * `assumeTrue(verified)` here is removed in the same commit.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In feature/login/.../ProviderLoginViewModel.kt revert the IA
 *      fix layer 1 (drop the `provider?.authType == "NONE"` short-
 *      circuit). Reproduces the original 2026-05-04 IA bug.
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: spinner persists after Continue; the
 *      waitUntil for "Search history" times out.
 *   4. Revert; re-run; test passes.
 *
 * Evidence at .lava-ci-evidence/sp3a-challenges/C11-2026-05-04-redesign.json.
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.tracker.archiveorg.ArchiveOrgDescriptor
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge11ArchiveOrgAnonymousSearchTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickArchiveOrg_continue_reachesAuthorizedMainApp() {
        hiltRule.inject()

        // Clause 6.G gate: skip when ArchiveOrg is not yet verified.
        // After Phase 4.1a flips the descriptor, this assumeTrue
        // becomes a no-op. Kept here so that future operators who
        // un-verify the descriptor (e.g. mid-incident) get clean
        // skips instead of mysterious failures.
        assumeTrue(
            "ArchiveOrgDescriptor.verified must be true for this test to apply (clause 6.G)",
            ArchiveOrgDescriptor.verified,
        )

        // Step 1: provider list — Internet Archive must appear because
        // the descriptor is verified=true.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Internet Archive", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Internet Archive", substring = true, ignoreCase = true)
            .performClick()

        // Step 2: tap Continue. AuthType.NONE — no credentials needed.
        // This is the EXACT user action that broke before commit 49714c0.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Continue", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Continue", substring = true, ignoreCase = true)
            .performClick()

        // Step 3: spinner MUST clear, main app MUST mount, and the
        // Search tab MUST render the AUTHORIZED empty state — that's
        // "Search history" (legacy AuthService received the bridge
        // signal). If it renders "Authorization required to search"
        // instead, the layer-3 bridge is broken (regression).
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
