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
 * Flow (DEEP restoration 2026-06-08 — the search-and-assert-result step
 * is restored now that navigation-compose was bumped 2.9.0 → 2.9.1 in
 * commit 7e6e7bcb, removing the test-teardown lifecycle race that forced
 * the shallow reduction):
 *   1. Onboarding "Select Provider" screen
 *   2. Tap "Internet Archive"
 *   3. Tap Continue
 *   4. Main app's Search-history empty state renders (NOT "Authorization
 *      required to search" — the legacy auth bridge has unblocked it)
 *   5. Tap the AppBar Search action → SearchInputScreen → type a query →
 *      submit via the IME search action.
 *   6. SearchResultScreen renders real archive.org results — assert a
 *      result row appears (the "Internet Archive" provider chip on a
 *      TopicListItem row, the user-visible per-provider result label).
 *
 * The primary assertion (clause 6.J/§6.AB) is now on the rendered
 * archive.org RESULT ROW reached by traversing the real onboarding +
 * the real Search → SearchInput → SearchResult production chain end to
 * end against the real archive.org backend — not merely the authorized
 * empty state.
 *
 * Anti-bluff posture (clauses 6.J/6.L): traverses the full onboarding
 * flow (layer-1 short-circuit + layer-2 sdk.switchTracker + layer-3
 * signalAuthorized) AND the real search path; each can be falsified by
 * reverting its production change.
 *
 * STATUS: requires ArchiveOrgDescriptor.verified=true to make the
 * provider appear in onboarding. `assumeTrue(verified)` gates it.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In core/tracker/archiveorg/.../ArchiveOrgSearch.kt make the search
 *      mapper return an empty result list (e.g. `return emptyList()` from
 *      the response parser) — the screen still composes, it just shows the
 *      "Nothing found" empty state (a NON-crashing break).
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: no result row renders; the waitUntil for the
 *      "Internet Archive" result-row chip times out and the final
 *      assertion fails with "archive.org search must render at least one
 *      result row".
 *   4. Revert; re-run; test passes.
 *
 *   The onboarding-layer falsifiability (revert the AuthType.NONE
 *   short-circuit in ProviderLoginViewModel → spinner persists, step 4
 *   "Search history" times out) is preserved from the prior version.
 *
 * Honest network dependency: step 6 crosses the real archive.org network.
 * On the gate-host archive.org MUST be reachable (real-stack, Seventh Law
 * clause 2); the test fails loudly (timeout) on an empty list — no silent
 * green.
 *
 * Evidence at .lava-ci-evidence/sp3a-challenges/C11-2026-05-04-redesign.json.
 *
 * // covers-feature: search_result
 */
package lava.app.challenges

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import lava.tracker.archiveorg.ArchiveOrgDescriptor
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge11ArchiveOrgAnonymousSearchTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickArchiveOrg_continue_thenSearch_rendersRealResultRow() {
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

        // Step 1: Welcome screen.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2: provider list — deselect all others, pick Internet Archive.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        arrayOf("RuTracker.org", "RuTor.info", "Project Gutenberg").forEach { name ->
            try { composeRule.onNodeWithText(name).performClick() } catch (_: AssertionError) { }
        }
        composeRule.onNodeWithText("Next").performClick()

        // Step 3: configure — tap Continue for AuthType.NONE provider.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Configure Internet Archive").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        // Step 4: Summary screen.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start Exploring").performClick()

        // Step 5: main app MUST mount and the Search tab MUST render
        // the AUTHORIZED empty state — that's "Search history".
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }

        // Step 6: DEEP — drive a real archive.org search.
        // Tap the AppBar Search action (content-desc "Search").
        composeRule.onAllNodesWithContentDescription("Search").onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Search…").fetchSemanticsNodes().isNotEmpty()
        }
        // Type into the single editable field (hasSetTextAction — the
        // placeholder disappears once text is entered) then fire the IME
        // Search action; "…\n" via performTextInput would NOT submit.
        composeRule.onNode(hasSetTextAction()).performTextInput("ubuntu")
        composeRule.onNode(hasSetTextAction()).performImeAction()

        // Step 7: SearchResultScreen — assert a real archive.org RESULT ROW
        // renders. Each TopicListItem result row carries a FavoriteButton
        // (content-desc "Favorite", from designsystem_action_favorite). This
        // is the robust per-row signal: it appears ONLY when a result row
        // composes, NOT on the "Nothing found" empty state and NOT on the
        // provider filter chip (which renders from providerIds regardless of
        // results). An empty-results mutation therefore makes this time out.
        composeRule.waitUntil(timeoutMillis = 45_000) {
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty()
        }
        require(
            composeRule.onAllNodesWithContentDescription("Favorite").fetchSemanticsNodes().isNotEmpty(),
        ) { "archive.org search must render at least one result row (FavoriteButton)" }
    }
}
