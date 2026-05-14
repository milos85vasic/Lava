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

        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        // The icon sits in the upper portion of the screen above the
        // "Welcome to Lava" text. Sample roughly the top 30% horizontal
        // band to focus on the icon area.
        assertColoredIconRegion(bitmap)
    }

    /**
     * Asserts the upper-30% horizontal band of the bitmap (where the
     * brand icon sits) has measurable color variance AND a dominant
     * hue distinct from neutral grays/whites. The §6.AB white-
     * placeholder failure mode produces a near-uniform tone in this
     * region; the colored Lava logo produces a red-dominant region.
     */
    private fun assertColoredIconRegion(bitmap: Bitmap) {
        val iconBandHeight = bitmap.height / 3
        var minR = 255
        var maxR = 0
        var minG = 255
        var maxG = 0
        var minB = 255
        var maxB = 0
        var totalR = 0L
        var totalG = 0L
        var totalB = 0L
        var samples = 0
        for (y in 0 until iconBandHeight step 8) {
            for (x in 0 until bitmap.width step 8) {
                val argb = bitmap.getPixel(x, y)
                val a = (argb shr 24) and 0xFF
                if (a < 64) continue // skip transparent/near-transparent pixels
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
                if (g < minG) minG = g
                if (g > maxG) maxG = g
                if (b < minB) minB = b
                if (b > maxB) maxB = b
                totalR += r
                totalG += g
                totalB += b
                samples++
            }
        }
        val rangeR = maxR - minR
        val rangeG = maxG - minG
        val rangeB = maxB - minB
        val avgR = if (samples > 0) (totalR / samples).toInt() else 0
        val avgG = if (samples > 0) (totalG / samples).toInt() else 0
        val avgB = if (samples > 0) (totalB / samples).toInt() else 0
        val redDominance = avgR - maxOf(avgG, avgB)

        // Discrimination check 1: per-channel variance must be measurable
        // (not single-tone). A monochrome white-tinted icon has R==G==B
        // at every pixel and thus rangeR == rangeG == rangeB — but ALL
        // are clamped to a narrow window because the alpha channel
        // gradients the same single color. Set thresholds tight enough
        // to catch the white-placeholder failure mode.
        assert(rangeR > 24 && rangeG > 24 && rangeB > 24) {
            "icon region appears monochrome — RGB variance below threshold " +
                "(rangeR=$rangeR, rangeG=$rangeG, rangeB=$rangeB). This is " +
                "the §6.AB white-placeholder failure mode (forensic anchor: " +
                "1.2.20-1040 reported by operator on Galaxy S23 Ultra). " +
                "Did WelcomeStep revert to using Icon() instead of Image()?"
        }

        // Discrimination check 2: dominant hue must lean red, not be
        // a neutral gray. The Lava brand mark is red-dominant.
        assert(redDominance > 16) {
            "icon region's dominant hue is not red — avg(R)=$avgR " +
                "vs max(avg G, avg B)=${maxOf(avgG, avgB)} (delta=$redDominance). " +
                "The colored Lava logo is red-dominant; a white-placeholder " +
                "(§6.AB) or grayscale-tinted (Material3 Icon) rendering " +
                "would have R == G == B and fail this assertion."
        }
    }
}
