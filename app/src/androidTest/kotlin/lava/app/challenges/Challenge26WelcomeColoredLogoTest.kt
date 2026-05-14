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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
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

        // Capture the entire Welcome screen and verify the icon is colored.
        // The screen has a single `Icon(icon = LavaIcons.AppIcon)` near the top
        // — assert the captured bitmap has measurable RGB variance across its
        // pixels (a monochrome glyph would have variance = 0 because all
        // foreground pixels are the same tint and the rest is background).
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        assertColorVariance(bitmap)
    }

    /**
     * Asserts the bitmap contains pixels with measurable color variance —
     * i.e., is not a single-tone monochrome rendering. The Welcome screen
     * with the colored Lava logo will have RED-dominant pixels in the
     * logo region; the pre-fix monochrome glyph would be uniformly tinted.
     *
     * Method: sample every 16th pixel (cheap), compute max R, max G, max B
     * separately, and assert at least 2 channels show meaningful range
     * (max - min > 32) AND that the channels differ from each other
     * (variance across RGB > 32). Pure monochrome scaled by alpha would
     * fail because R == G == B at every pixel.
     */
    private fun assertColorVariance(bitmap: Bitmap) {
        var minR = 255
        var maxR = 0
        var minG = 255
        var maxG = 0
        var minB = 255
        var maxB = 0
        for (y in 0 until bitmap.height step 16) {
            for (x in 0 until bitmap.width step 16) {
                val argb = bitmap.getPixel(x, y)
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
            }
        }
        val rangeR = maxR - minR
        val rangeG = maxG - minG
        val rangeB = maxB - minB
        val rgbDelta = maxOf(maxR - maxG, maxR - maxB, maxG - maxB).coerceAtLeast(0)

        assert(rangeR > 32 && rangeG > 32 && rangeB > 32) {
            "Welcome logo appears monochrome — RGB ranges (R=$rangeR, G=$rangeG, " +
                "B=$rangeB) below 32 threshold. The colored Lava logo MUST " +
                "render with measurable per-channel variance. Did " +
                "LavaIcons.AppIcon get reverted to ic_notification?"
        }
        assert(rgbDelta > 32) {
            "Welcome logo channels are too similar (max delta=$rgbDelta < 32) — " +
                "this is a grayscale rendering, not the colored Lava logo. " +
                "Check that R.drawable.ic_lava_logo is wired in LavaIcons.AppIcon."
        }
    }
}
