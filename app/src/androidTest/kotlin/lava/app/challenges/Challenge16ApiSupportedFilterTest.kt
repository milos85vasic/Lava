/*
 * Challenge Test C16 — apiSupported filter shows verified+supported providers
 * on the onboarding "Pick your providers" screen.
 *
 * Rewritten 2026-05-12 (§6.L 16th invocation anti-bluff audit). The prior
 * version of this test asserted "Internet Archive must NOT appear" because
 * the original Phase 12 α-hotfix kept ArchiveOrgDescriptor.apiSupported=false.
 * Phase 2b later flipped apiSupported=true on archiveorg + gutenberg without
 * updating C16 — and the test still passed because its waitUntil condition
 * accepted the Welcome screen, which trivially doesn't render any provider.
 * That was a textbook §6.L bluff: green-light while the underlying behavior
 * had changed.
 *
 * Constitutional binding: Sixth Law clauses 1, 3, 4. The test traverses the
 * same screen the user sees when picking providers, asserts on actually-
 * rendered provider names, and is the load-bearing acceptance gate for the
 * apiSupported filter's current configuration (archiveorg + gutenberg are
 * supported via lava-api-go's per-provider routing as of Phase 2b).
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In `core/tracker/archiveorg/.../ArchiveOrgDescriptor.kt`
 *      set `override val apiSupported: Boolean = false`.
 *   2. Run on the gating matrix:
 *        ./gradlew :app:connectedDebugAndroidTest \
 *          -Pandroid.testInstrumentationRunnerArguments.class=lava.app.challenges.Challenge16ApiSupportedFilterTest
 *   3. Expected failure: "Internet Archive" disappears from the provider
 *      list and the assertion `assertTrue(internetArchive non-empty)` fails.
 *   4. Revert; re-run; assertion passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge16ApiSupportedFilterTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun providerListShowsAllApiSupportedVerifiedProviders() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Welcome to Lava", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Pick your providers")
                .fetchSemanticsNodes().isNotEmpty()
        }

        val expected = listOf("RuTracker.org", "RuTor.info", "Internet Archive", "Project Gutenberg")
        for (name in expected) {
            val nodes = composeRule
                .onAllNodesWithText(name, ignoreCase = true, substring = true)
                .fetchSemanticsNodes()
            assertTrue(
                "Provider '$name' must appear in onboarding list (verified=true, apiSupported=true)",
                nodes.isNotEmpty(),
            )
        }
    }
}
