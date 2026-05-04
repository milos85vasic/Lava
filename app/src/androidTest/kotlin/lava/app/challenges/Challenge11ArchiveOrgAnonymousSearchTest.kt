/*
 * Challenge Test C11 — Internet Archive anonymous search (clause 6.G,
 * forensic anchor).
 *
 * The Internet Archive provider was the catalyst for clause 6.G: it
 * shipped with AuthType.NONE, the user tapped Continue, and the spinner
 * never cleared. The fix landed in commit 49714c0; the JVM regression
 * test landed in commit 32706b8. THIS test is the gating Compose UI
 * Challenge that proves the fix works on the real device surface against
 * the real archive.org service.
 *
 * Flow:
 *   onboarding → pick "Internet Archive" → tap Continue → main app
 *   appears (no spinner stuck) → search "ubuntu" → result row visible.
 *
 * Network reality: archive.org is globally reachable. A test environment
 * without route would produce a deterministic timeout, not a false-green.
 *
 * STATUS: requires ArchiveOrgDescriptor.verified=true to make the provider
 * appear in onboarding.
 *
 * FALSIFIABILITY REHEARSAL:
 *
 *   1. In feature/login/.../ProviderLoginViewModel.kt revert the IA fix
 *      half 1 (drop the `provider?.authType == "NONE"` short-circuit).
 *      Reproduces the original 2026-05-04 IA bug.
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: spinner persists after Continue; the waitUntil
 *      for the search bar times out.
 *   4. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge11ArchiveOrgAnonymousSearchTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pickArchiveOrg_continue_searchUbuntu_resultRowVisible() {
        hiltRule.inject()

        // Step 1: provider list. Archive.org must appear iff verified=true.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Internet Archive", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.onNodeWithText("Internet Archive", substring = true, ignoreCase = true)
            .performClick()

        // Step 2: tap Continue. AuthType.NONE — no credentials needed.
        // This is the EXACT user action that broke before commit 49714c0.
        composeRule.onNodeWithText("Continue", substring = true, ignoreCase = true)
            .performClick()

        // Step 3: spinner MUST clear and main app's search surface MUST
        // appear within the timeout. If the IA bug regresses, this
        // waitUntil times out — the test detects the regression on the
        // real device surface, not just at the JVM ViewModel level.
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Search", substring = false)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Step 4: search archive.org for "ubuntu".
        composeRule.onNodeWithText("Search").performClick()
        composeRule.onNodeWithContentDescription("Search field")
            .performTextInput("ubuntu")
        composeRule.onNodeWithText("Go").performClick()

        // Step 5: assert primary user-visible state (Sixth Law clause 3).
        // Archive.org returns "downloads" rather than "seeders" because
        // its items aren't torrents. We accept either.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            val seedersHits = composeRule
                .onAllNodesWithText("seeders", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
            val downloadsHits = composeRule
                .onAllNodesWithText("downloads", substring = true, ignoreCase = true)
                .fetchSemanticsNodes()
            seedersHits.isNotEmpty() || downloadsHits.isNotEmpty()
        }
    }
}
