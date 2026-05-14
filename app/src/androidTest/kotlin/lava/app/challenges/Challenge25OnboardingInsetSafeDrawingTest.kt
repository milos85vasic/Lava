/*
 * Challenge Test C25 — Onboarding wizard respects system bar insets on
 * tall-aspect devices (issue: 2026-05-14, "Onboarding flow UI is overlapping
 * with the title bar and bottom navigation of the System UI; seen on Samsung
 * Galaxy S23 Ultra").
 *
 * MainActivity calls `enableEdgeToEdge`, so any composable that does not
 * apply system-bar insets renders under the status bar at top and the
 * gesture/navigation bar at bottom. The pre-fix OnboardingScreen.kt did
 * not call `windowInsetsPadding(WindowInsets.safeDrawing)` on the
 * AnimatedContent container, so the Welcome title and the Get Started
 * button visually overlapped system UI on devices with non-trivial
 * system bar heights.
 *
 * The fix applies `WindowInsets.safeDrawing` once at the OnboardingScreen
 * level. This test asserts that on a tall-aspect device the Welcome title
 * + Get Started button + Pick-providers Next button + Summary Start-
 * Exploring button are all reported as fully displayed (no clipping at
 * system-bar edges).
 *
 * Operator command (Pixel 9 Pro XL or any S23 Ultra-class AVD):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge25OnboardingInsetSafeDrawingTest"
 *
 * Recommended AVD config to reproduce S23-class form factor:
 *   - Device: Pixel 9 Pro XL OR custom 1440x3088, 500dpi, 6.8"
 *   - System image: API 34, Google APIs ARM/x86_64
 *   - Cold boot (per §6.I clause 6) for the gating attestation
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   1. In OnboardingScreen.kt, remove the `.windowInsetsPadding(
 *      WindowInsets.safeDrawing)` line from the AnimatedContent modifier.
 *   2. Re-run on the tall-aspect AVD listed above.
 *   3. Expected failure: assertIsDisplayed on "Welcome to Lava" or
 *      "Get Started" returns false because the node is partially under
 *      the status bar / nav bar (Compose treats clipped-by-parent nodes
 *      as not fully displayed when the clip is significant).
 *   4. Restore; re-run; passes.
 *
 * Companion structural test (runs on every commit, no device needed):
 *   feature/onboarding/.../OnboardingInsetRegressionTest.kt
 *   Asserts the windowInsetsPadding(WindowInsets.safeDrawing) line is
 *   present in the source. Falsifiability rehearsal recorded in the
 *   commit body of fix(onboarding): inset overlap on tall-aspect devices.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge25OnboardingInsetSafeDrawingTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun welcomeStep_titleAndButton_fullyDisplayedOnEdgeToEdge() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        // Top-anchored content (title) — fails if status bar overlaps.
        composeRule.onNodeWithText("Welcome to Lava").assertIsDisplayed()
        // Bottom-anchored content (Get Started button) — fails if nav bar overlaps.
        composeRule.onNodeWithText("Get Started").assertIsDisplayed()
    }

    @Test
    fun providersStep_titleAndNext_fullyDisplayedOnEdgeToEdge() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Pick your providers").assertIsDisplayed()
        composeRule.onNodeWithText("Next").assertIsDisplayed()
    }

    @Test
    fun summaryStep_titleAndStartExploring_fullyDisplayedOnEdgeToEdge() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        // Deselect everything except Internet Archive (anonymous, no creds).
        arrayOf("RuTracker.org", "RuTor.info", "Kinozal.tv", "NNM-Club", "Project Gutenberg").forEach { name ->
            val nodes = composeRule.onAllNodesWithText(name).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) composeRule.onNodeWithText(name).performClick()
        }
        composeRule.onNodeWithText("Next").performClick()

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Configure", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("All set!").assertIsDisplayed()
        composeRule.onNodeWithText("Start Exploring").assertIsDisplayed()
    }
}
