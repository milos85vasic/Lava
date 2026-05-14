package lava.onboarding

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §6.Q-spirit structural regression test for the Samsung Galaxy S23 Ultra
 * inset overlap reported 2026-05-14. MainActivity calls `enableEdgeToEdge`,
 * so onboarding draws under the system bars unless the AnimatedContent
 * container applies `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`
 * (or an equivalent inset-aware modifier). Without it, the title row
 * overlaps the status bar and the action button overlaps the gesture/nav
 * bar on tall-aspect devices.
 *
 * Falsifiability rehearsal:
 *   1. Remove the `.windowInsetsPadding(WindowInsets.safeDrawing)` line
 *      from `OnboardingScreen.kt`.
 *   2. Re-run this test.
 *   3. Expected failure: `OnboardingScreen.kt is missing system-bar inset
 *      padding` AssertionError.
 *   4. Restore; re-run; passes.
 */
class OnboardingInsetRegressionTest {

    @Test
    fun onboardingScreen_appliesSystemBarInsetPadding() {
        val source = File("src/main/kotlin/lava/onboarding/OnboardingScreen.kt").readText()

        assertTrue(
            "OnboardingScreen.kt must import androidx.compose.foundation.layout.safeDrawing",
            source.contains("import androidx.compose.foundation.layout.safeDrawing"),
        )
        assertTrue(
            "OnboardingScreen.kt must import androidx.compose.foundation.layout.windowInsetsPadding",
            source.contains("import androidx.compose.foundation.layout.windowInsetsPadding"),
        )
        assertTrue(
            "OnboardingScreen.kt is missing system-bar inset padding — " +
                "the AnimatedContent modifier must call " +
                "windowInsetsPadding(WindowInsets.safeDrawing) so the wizard " +
                "does not draw under the status bar / nav bar on edge-to-edge " +
                "devices (Samsung S23 Ultra and similar).",
            source.contains("windowInsetsPadding(WindowInsets.safeDrawing)"),
        )
    }
}
