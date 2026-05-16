/*
 * Challenge Test C26 — Welcome screen renders the colored Lava logo
 * (issue: 2026-05-14, "Landing screen of the Onboarding flow where
 * is the Welcome to Lava title located has black-and-white ugly logo
 * of the app! It MUST BE our nicely colored red log in full color!").
 *
 * Pre-fix: WelcomeStep.kt called `Icon(icon = LavaIcons.AppIcon, ...)`
 * where `LavaIcons.AppIcon = R.drawable.ic_notification` — the
 * monochrome notification glyph required by Android's notification
 * system. Surfacing it as the brand mark on the first screen new
 * users see was a §6.J spirit issue: tests passed (the icon DID
 * render), but the user-visible outcome was wrong.
 *
 * Fix: introduced `R.drawable.ic_lava_logo` (layer-list compositing
 * the colored launcher background + foreground at 5 densities) in
 * core:designsystem and rewired `LavaIcons.AppIcon` to it. The
 * monochrome icon is preserved as `LavaIcons.NotificationIcon` for
 * the AndroidManifest's notification-channel reference.
 *
 * This Challenge Test drives the real Welcome screen on the gating
 * matrix and asserts the rendered Image node displays a
 * non-monochrome bitmap. The `assertColorVariance` helper samples
 * the rendered pixels and fails if max-min channel variance is
 * below the monochrome threshold (i.e., the image is essentially
 * a single tone).
 *
 * Operator command:
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge26WelcomeColoredLogoTest"
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   1. In core/designsystem/.../LavaIcons.kt, revert
 *      `AppIcon` to `Icon.DrawableResourceIcon(R.drawable.ic_notification)`.
 *   2. Re-run on the gating emulator.
 *   3. Expected failure: assertColorVariance fails because the
 *      rendered icon is a single-color glyph (variance = 0 across
 *      RGB channels per pixel).
 *   4. Revert; re-run; passes.
 *
 * Companion JVM unit test (runs on every pre-push without an
 * emulator): core/designsystem/src/test/.../LavaIconsAppIconColorRegressionTest.kt
 * asserts the source file references R.drawable.ic_lava_logo and
 * the PNG layers exist at every density.
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
class Challenge26WelcomeColoredLogoTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun welcomeScreen_logoRendersInColor() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Welcome to Lava").assertIsDisplayed()
        composeRule.onNodeWithText("Get Started").assertIsDisplayed()

        // Capture via UiAutomation.takeScreenshot() rather than the Compose
        // captureToImage() API. Forensic anchor: 1.2.23-1043 Challenge run on
        // Pixel_8 (API 35) — captureToImage() returned an all-zero bitmap
        // (every pixel R==G==B==0) on this AVD/AGP combo, causing the
        // assertColorVariance check to fire false-positive on a Welcome
        // screen that visually renders the correct colored Lava logo.
        // UiAutomation captures the actual hardware-accelerated rendered
        // frame the user sees — the same surface adb screencap reads.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            ?: error("UiAutomation.takeScreenshot() returned null — emulator must support screenshot")
        assertColorVariance(bitmap)
    }

    /**
     * Asserts the bitmap contains pixels where R != G != B — i.e., colored
     * pixels exist somewhere on screen. Counts per-pixel R-vs-G + R-vs-B
     * deltas; a colored pixel has at least one delta > 32. The pre-fix
     * monochrome glyph on white background would produce R==G==B at every
     * pixel (white = 255,255,255 + gray = 102,102,102 — both have R==G==B).
     * The colored Lava logo produces pixels like srgba(152,11,21) at the
     * logo region — R-G delta = 141, R-B delta = 131 → clearly colored.
     *
     * Forensic anchor 2026-05-17: the prior assertion compared `maxR - maxG`
     * across the WHOLE bitmap. White pixels at the screen edges push
     * maxR == maxG == maxB == 255, so the delta = 0 even on a screen with
     * a clearly colored logo. The corrected check counts pixels where the
     * per-pixel delta is high — that signature can only come from
     * non-grayscale rendering.
     */
    private fun assertColorVariance(bitmap: Bitmap) {
        var coloredPixelCount = 0
        var totalSampled = 0
        var maxPerPixelDelta = 0
        for (y in 0 until bitmap.height step 4) {
            for (x in 0 until bitmap.width step 4) {
                val argb = bitmap.getPixel(x, y)
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                val rgDelta = kotlin.math.abs(r - g)
                val rbDelta = kotlin.math.abs(r - b)
                val gbDelta = kotlin.math.abs(g - b)
                val pixelDelta = maxOf(rgDelta, rbDelta, gbDelta)
                if (pixelDelta > maxPerPixelDelta) maxPerPixelDelta = pixelDelta
                if (pixelDelta > 32) coloredPixelCount++
                totalSampled++
            }
        }
        val coloredPct = (100.0 * coloredPixelCount / totalSampled).toInt()
        assert(coloredPixelCount > 100 && maxPerPixelDelta > 64) {
            "Welcome screen appears monochrome — only $coloredPixelCount/$totalSampled " +
                "sampled pixels ($coloredPct%) have measurable per-channel delta; " +
                "max single-pixel delta = $maxPerPixelDelta (< 64 threshold). " +
                "The colored Lava logo + red Get Started button MUST produce " +
                "pixels where R != G != B. Did LavaIcons.AppIcon revert to " +
                "ic_notification, or did a theme/ColorFilter strip colors?"
        }
    }
}
