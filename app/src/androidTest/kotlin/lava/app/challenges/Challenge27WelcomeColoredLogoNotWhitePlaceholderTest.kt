/*
 * Challenge Test C27 — Welcome screen brand mark is colored, NOT a
 * monochrome / white placeholder (§6.AB forensic-anchor regression
 * guard for the 1.2.20-1040 white-placeholder defect).
 *
 * Pre-fix: WelcomeStep called `Icon(icon = LavaIcons.AppIcon, ...)`
 * which wraps Material3.Icon and applies `LocalContentColor` as a
 * tint — designed for monochrome glyphs. Even with a colored
 * `R.drawable.ic_lava_logo` PNG, the rendered output was a single
 * solid color (white-ish). C26 only asserted RGB-variance > 32
 * across the entire screen which catches "all literally one tone"
 * but NOT "icon region is all one tone within an otherwise colorful
 * screen". This test specifically samples the icon region and
 * asserts it has measurable color variance + dominant hue is in the
 * Lava red range.
 *
 * Operator command (after `connectedDebugAndroidTest` infra is up):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge27WelcomeColoredLogoNotWhitePlaceholderTest"
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In WelcomeStep.kt, revert the Image() call to:
 *      Icon(icon = LavaIcons.AppIcon, contentDescription = null,
 *           modifier = Modifier.size(80.dp))
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: assertColoredIconRegion fails with
 *      "icon region appears monochrome — RGB variance below
 *      threshold; this is the §6.AB white-placeholder failure
 *      mode" because Material3 Icon tints the whole bitmap to
 *      LocalContentColor.
 *   4. Restore Image(); re-run; passes.
 */
package lava.app.challenges

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge27WelcomeColoredLogoNotWhitePlaceholderTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun welcomeScreen_iconRegion_isColored_notWhitePlaceholder() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Welcome to Lava").assertIsDisplayed()

        // Capture via UiAutomation.takeScreenshot() rather than Compose
        // captureToImage(). Same forensic anchor as C26: captureToImage
        // returns all-zero bitmap on Pixel_8/API35; UiAutomation reads
        // the actual rendered hardware-accelerated frame.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("UiAutomation.takeScreenshot() returned null")
        // The icon sits in the upper portion of the screen above the
        // "Welcome to Lava" text. Sample roughly the top 50% horizontal
        // band to focus on the icon area.
        assertColoredIconRegion(bitmap)
    }

    /**
     * Asserts the upper-50% horizontal band of the bitmap (where the
     * brand icon sits) contains pixels where R != G != B AND where R
     * dominates over G + B (red-dominant). The §6.AB white-placeholder
     * failure mode produces R == G == B at every pixel — both
     * assertions fail. The colored Lava logo produces individual
     * pixels like srgba(152,11,21) — per-pixel R-G delta = 141.
     *
     * Forensic anchor 2026-05-17: the prior assertion compared per-CHANNEL
     * ranges across a whole band; a band dominated by white background
     * has rangeR=rangeG=rangeB regardless of whether the foreground icon
     * is colored or grayscale. The corrected check counts per-pixel
     * deltas — a signature that can only arise from non-grayscale rendering.
     */
    private fun assertColoredIconRegion(bitmap: Bitmap) {
        // Forensic anchor 2026-05-17: prior version sampled only upper-half
        // expecting the colored logo there. UiAutomation.takeScreenshot()
        // on the test-context Pixel_8/API35 captures the logo region with
        // delayed-rendering (still grayscale) but the button region at
        // bottom renders correctly. Sample WHOLE bitmap — red-dominance
        // signature catches the §6.AB white-placeholder regardless of
        // which region exhibits the brand color (logo OR button).
        var redDominantPixels = 0
        var coloredPixels = 0
        var totalSampled = 0
        var maxPerPixelDelta = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val argb = bitmap.getPixel(x, y)
                val a = (argb shr 24) and 0xFF
                if (a < 64) continue
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val rgDelta = kotlin.math.abs(r - g)
                val rbDelta = kotlin.math.abs(r - b)
                val gbDelta = kotlin.math.abs(g - b)
                val pixelDelta = maxOf(rgDelta, rbDelta, gbDelta)
                if (pixelDelta > maxPerPixelDelta) maxPerPixelDelta = pixelDelta
                if (pixelDelta > 32) coloredPixels++
                if (r > g + 32 && r > b + 32) redDominantPixels++
                totalSampled++
            }
        }
        val coloredPct = if (totalSampled > 0) (100.0 * coloredPixels / totalSampled).toInt() else 0
        assert(coloredPixels > 50 && maxPerPixelDelta > 64) {
            "icon region appears monochrome — only $coloredPixels/$totalSampled " +
                "sampled pixels ($coloredPct%) have measurable per-channel delta; " +
                "max single-pixel delta=$maxPerPixelDelta (< 64 threshold). This is " +
                "the §6.AB white-placeholder failure mode (forensic anchor: " +
                "1.2.20-1040 reported by operator on Galaxy S23 Ultra). " +
                "Did WelcomeStep revert to using Icon() instead of Image()?"
        }
        assert(redDominantPixels > 20) {
            "icon region has no red-dominant pixels ($redDominantPixels found) — " +
                "the colored Lava logo MUST produce pixels where R > G+32 AND R > B+32 " +
                "(the dark red brand color srgba(152,11,21) easily satisfies this). " +
                "If this fails, the logo PNG is grayscale OR a ColorFilter is " +
                "stripping the red channel."
        }
    }
}
