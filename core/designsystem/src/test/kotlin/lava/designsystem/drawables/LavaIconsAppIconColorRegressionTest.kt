package lava.designsystem.drawables

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §6.J/§6.Q-spirit structural regression test for the WelcomeStep
 * monochrome-icon bug reported 2026-05-14.
 *
 * Pre-fix: `LavaIcons.AppIcon = Icon.DrawableResourceIcon(R.drawable.ic_notification)`.
 * `ic_notification` is the Android-required monochrome notification glyph;
 * surfacing it as the Welcome screen brand mark gave testers a black-and-
 * white logo where the colored Lava logo was expected. Operator reported:
 * "the Welcome to Lava title is located has black-and-white ugly logo of
 * the app! It MUST BE our nicely colored red log in full color!"
 *
 * Fix: introduced `ic_lava_logo` (layer-list compositing the colored
 * launcher background + foreground at 5 densities) and rewired
 * `LavaIcons.AppIcon` to it. The monochrome icon is preserved as
 * `LavaIcons.NotificationIcon` for the Android notification system.
 *
 * This test reads the LavaIcons source AND verifies the colored asset
 * exists at every required density. If a future contributor reverts
 * `AppIcon` back to a monochrome resource OR removes the colored asset
 * files, this test fails on the next pre-push.
 *
 * Falsifiability rehearsal:
 *   1. Revert `LavaIcons.AppIcon` to `Icon.DrawableResourceIcon(R.drawable.ic_notification)`.
 *   2. Re-run this test.
 *   3. Expected failure: `AssertionError: LavaIcons.AppIcon must reference
 *      R.drawable.ic_lava_logo` fires with the directive.
 *   4. Restore; re-run; passes.
 */
class LavaIconsAppIconColorRegressionTest {

    @Test
    fun appIcon_pointsToColoredLavaLogo() {
        val source = File("src/main/kotlin/lava/designsystem/drawables/LavaIcons.kt").readText()

        assertTrue(
            "LavaIcons.AppIcon must reference R.drawable.ic_lava_logo (the colored " +
                "composite drawable), NOT R.drawable.ic_notification (the monochrome " +
                "notification glyph). The Welcome screen's title bar surfaces this " +
                "icon to first-launch users; it MUST be the colored Lava logo.",
            source.contains("val AppIcon: Icon = Icon.DrawableResourceIcon(R.drawable.ic_lava_logo)"),
        )
        assertTrue(
            "LavaIcons.NotificationIcon must remain wired to R.drawable.ic_notification — " +
                "Android's notification system requires a monochrome glyph and the " +
                "AndroidManifest references this name.",
            source.contains("val NotificationIcon: Icon = Icon.DrawableResourceIcon(R.drawable.ic_notification)"),
        )
    }

    @Test
    fun coloredLogoAsset_existsAtEveryDensity() {
        val resDir = File("src/main/res")
        val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        val missing = mutableListOf<String>()
        for (density in densities) {
            for (layer in listOf("background", "foreground")) {
                val path = "drawable-$density/ic_lava_logo_$layer.png"
                if (!File(resDir, path).exists()) missing += path
            }
        }
        assertTrue(
            "Colored Lava logo PNG layers missing at: ${missing.joinToString(", ")}. " +
                "Each density must ship both background + foreground so the layer-list " +
                "drawable composites correctly across DPI buckets.",
            missing.isEmpty(),
        )
        assertTrue(
            "Composite drawable XML must exist at drawable/ic_lava_logo.xml.",
            File(resDir, "drawable/ic_lava_logo.xml").exists(),
        )
    }
}
