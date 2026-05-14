package lava.designsystem.drawables

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §6.J/§6.Q-spirit structural regression test for the WelcomeStep
 * monochrome-icon bug reported 2026-05-14 + the §6.Z forensic-anchor
 * cold-launch crash on Lava-Android-1.2.19-1039 (Crashlytics issue
 * `40a62f97a5c65abb56142b4ca2c37eeb`, FATAL on Samsung Galaxy S23
 * Ultra / Android 16).
 *
 * Two layered failures the test now blocks:
 *
 * 1. **Monochrome glyph as the brand mark** (1.2.19 introduced this
 *    by setting `LavaIcons.AppIcon = R.drawable.ic_notification`).
 *    Operator: "MUST BE our nicely colored red log in full color".
 *
 * 2. **`<layer-list>` XML drawable in `LavaIcons.AppIcon`** (1.2.19
 *    introduced this by trying to fix #1 with a layer-list compositing
 *    background + foreground PNGs). `androidx.compose.ui.res.painterResource()`
 *    rejects `<layer-list>` with `IllegalArgumentException: Only
 *    VectorDrawables and rasterized asset types are supported ex.
 *    PNG, JPG, WEBP` — every cold launch crashed.
 *
 * Fix: single composited PNG per density (`drawable-{mdpi,hdpi,xhdpi,
 * xxhdpi,xxxhdpi}/ic_lava_logo.png`) — `painterResource()` accepts
 * raster bitmaps directly.
 *
 * Falsifiability rehearsal (§6.J/§6.N.1.1):
 *   1. Revert `LavaIcons.AppIcon` to `Icon.DrawableResourceIcon(R.drawable.ic_notification)`.
 *   2. Re-run this test → AssertionError on `appIcon_pointsToColoredLavaLogo`.
 *   3. Restore + add back a layer-list XML at drawable/ic_lava_logo.xml
 *      → AssertionError on `coloredLogoAsset_isNotLayerListXml`.
 *   4. Restore + remove the xxxhdpi PNG → AssertionError on
 *      `coloredLogoAsset_existsAtEveryDensity`.
 *
 * Rehearsal #1 + #3 verified locally on commit  TBD-this-cycle  (2026-05-14).
 */
class LavaIconsAppIconColorRegressionTest {

    @Test
    fun appIcon_pointsToColoredLavaLogo() {
        val source = File("src/main/kotlin/lava/designsystem/drawables/LavaIcons.kt").readText()

        assertTrue(
            "LavaIcons.AppIcon must reference R.drawable.ic_lava_logo (the colored " +
                "raster bitmap), NOT R.drawable.ic_notification (the monochrome " +
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
            val path = "drawable-$density/ic_lava_logo.png"
            if (!File(resDir, path).exists()) missing += path
        }
        assertTrue(
            "Colored Lava logo PNG missing at: ${missing.joinToString(", ")}. " +
                "Each density must ship a single composited bitmap so painterResource() " +
                "(which only accepts vector drawables OR raster bitmaps — see §6.Z " +
                "forensic anchor 40a62f97a5c65abb56142b4ca2c37eeb) can load it across " +
                "all DPI buckets.",
            missing.isEmpty(),
        )
    }

    /**
     * The §6.Z forensic-anchor failure: 1.2.19-1039 shipped
     * `R.drawable.ic_lava_logo` as a `<layer-list>` XML. `painterResource()`
     * rejected it with `IllegalArgumentException: Only VectorDrawables and
     * rasterized asset types are supported`. This test asserts neither the
     * XML drawable file NOR the per-density `ic_lava_logo_foreground.png` /
     * `_background.png` layer files exist — only the single composited PNGs.
     */
    @Test
    fun coloredLogoAsset_isNotLayerListXml() {
        val resDir = File("src/main/res")
        assertFalse(
            "drawable/ic_lava_logo.xml MUST NOT exist — painterResource() does not " +
                "support <layer-list> drawables (Crashlytics 40a62f97a5c65abb56142b4ca2c37eeb, " +
                "Samsung Galaxy S23 Ultra cold-launch FATAL on 1.2.19-1039). Use a " +
                "single composited PNG per density instead.",
            File(resDir, "drawable/ic_lava_logo.xml").exists(),
        )
        val densities = listOf("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        for (density in densities) {
            for (layer in listOf("foreground", "background")) {
                val path = "drawable-$density/ic_lava_logo_$layer.png"
                assertFalse(
                    "$path MUST NOT exist — that's a leftover layer file from the " +
                        "broken 1.2.19 layer-list approach. Use the single composited " +
                        "ic_lava_logo.png per density.",
                    File(resDir, path).exists(),
                )
            }
        }
    }
}
