/*
 * Challenge Test C16 — Onboarding filters out apiSupported=false providers.
 *
 * Phase 12 α-hotfix (commit 384ac02) added `apiSupported: Boolean` to
 * `TrackerDescriptor` and gated the user-facing provider lists in
 * ProviderLoginViewModel + TrackerSettingsViewModel on
 * `it.verified && it.apiSupported`. The forensic anchor is the
 * 2026-05-04 alice-bug class: a user onboards Internet Archive, selects
 * the lava-api-go endpoint, runs a search, and gets a generic
 * "Something went wrong" toast because the API service has no v1
 * routes for archiveorg today.
 *
 * This Challenge Test asserts the user-visible outcome of "Internet
 * Archive does NOT appear on the onboarding provider chooser" — the
 * primary signal that the filter is wired correctly at the rendered
 * UI layer, not just at the ViewModel layer.
 *
 * Constitutional binding: Sixth Law clauses 1, 3, 4. The test
 * traverses the same screen the user sees when launching a fresh
 * install, asserts on rendered text (provider absence), and is the
 * load-bearing gate for the α-hotfix from Phase 12.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In `core/tracker/archiveorg/.../ArchiveOrgDescriptor.kt`
 *      add: `override val apiSupported: Boolean = true`
 *   2. Run on the gating matrix:
 *        ./gradlew :app:connectedDebugAndroidTest \
 *          --tests "lava.app.challenges.Challenge16ApiSupportedFilterTest"
 *   3. Expected failure: the onboarding screen now renders an
 *      "Internet Archive" row; assertNotExists fails with
 *      "Failed: assertNotExists" because the provider re-appeared.
 *   4. Revert; re-run; assertion passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge16ApiSupportedFilterTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun internetArchiveAbsentFromProviderList() {
        hiltRule.inject()

        // Wait for onboarding to render OR for already-onboarded home
        // tab. Either state is acceptable for this assertion since both
        // surfaces filter on apiSupported.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Search history")
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Menu")
                    .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("RuTracker", ignoreCase = true, substring = true)
                    .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("RuTor", ignoreCase = true, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }

        // ===== PRIMARY ASSERTION =====
        // "Internet Archive" MUST NOT appear anywhere in the rendered
        // tree once the app has reached either onboarding OR home —
        // because both screens filter the descriptor list on
        // `verified && apiSupported`, and ArchiveOrgDescriptor's
        // apiSupported defaults to false in Phase 1.
        val internetArchiveNodes = composeRule
            .onAllNodesWithText("Internet Archive", ignoreCase = true, substring = true)
            .fetchSemanticsNodes()
        if (internetArchiveNodes.isNotEmpty()) {
            throw AssertionError(
                "Internet Archive provider must NOT appear in the onboarding list " +
                    "while ArchiveOrgDescriptor.apiSupported = false " +
                    "(Phase 12 α-hotfix). Found ${internetArchiveNodes.size} matching node(s).",
            )
        }
    }
}
