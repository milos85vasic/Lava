package lava.applink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CrossAppLauncher.decideLaunch].
 *
 * Pure-JVM — no Android runtime required.  The fake [PackageChecker] returns
 * a plain [Any] sentinel (non-null = installed) rather than a real
 * [android.content.Intent], keeping every test runnable via `testDebugUnitTest`
 * without Robolectric.
 *
 * ## FALSIFIABILITY REHEARSAL (I-2 — variant vs release package swap)
 * Mutation applied: in [CrossAppLauncher.decideLaunch], the `Launch(...)` call
 * was temporarily changed to `Launch(releasePackage, extras)` instead of
 * `Launch(targetPackage, extras)`.
 * Observed failure (test `variant_package_installed_yields_Launch_with_variant_id`):
 *   expected:<digital.vasic.lava.api.[dev]> but was:<digital.vasic.lava.api.[]>
 *   junit.framework.ComparisonFailure at CrossAppLauncherTest.kt
 * Reverted: yes — production code restored to `Launch(targetPackage, extras)`.
 */
class CrossAppLauncherTest {

    /**
     * Boolean-backed fake: returns a non-null [Any] sentinel when [pkg] is in
     * [installed], null otherwise.  No Android framework types needed.
     */
    private fun checker(installed: Set<String>) = object : PackageChecker {
        override fun installedLaunchIntent(pkg: String): Any? =
            if (pkg in installed) Any() else null
    }

    // Bluff-Audit target: decideLaunch must pick StoreRedirect when absent.
    @Test fun installed_target_yields_Launch_with_extras() {
        val launcher = CrossAppLauncher(checker(setOf("digital.vasic.lava.api")))
        val d = launcher.decideLaunch(
            targetPackage = "digital.vasic.lava.api",
            releasePackage = "digital.vasic.lava.api",
            extras = mapOf(AppLinkContract.EXTRA_START_API to "true"),
        )
        assertTrue(d is LaunchDecision.Launch)
        d as LaunchDecision.Launch
        assertEquals("digital.vasic.lava.api", d.targetPackage)
        assertEquals("true", d.extras[AppLinkContract.EXTRA_START_API])
    }

    @Test fun absent_target_yields_StoreRedirect_to_release_listing() {
        val launcher = CrossAppLauncher(checker(emptySet()))
        val d = launcher.decideLaunch(
            targetPackage = "digital.vasic.lava.api.dev",
            releasePackage = "digital.vasic.lava.api",
            extras = emptyMap(),
        )
        assertTrue(d is LaunchDecision.StoreRedirect)
        d as LaunchDecision.StoreRedirect
        assertEquals("market://details?id=digital.vasic.lava.api", d.marketUri)
        assertEquals(
            "https://play.google.com/store/apps/details?id=digital.vasic.lava.api",
            d.webUri,
        )
    }

    /**
     * I-2: when targetPackage differs from releasePackage (debug variant case),
     * the Launch result MUST carry the variant id, not the release id.
     * A swap of targetPackage/releasePackage inside decideLaunch would make
     * this test fail with a package-id mismatch — see FALSIFIABILITY REHEARSAL
     * in the class KDoc above.
     */
    @Test fun variant_package_installed_yields_Launch_with_variant_id() {
        val variantPkg = "digital.vasic.lava.api.dev"
        val releasePkg = "digital.vasic.lava.api"
        // Only the variant is "installed"; the release id is absent.
        val launcher = CrossAppLauncher(checker(setOf(variantPkg)))
        val d = launcher.decideLaunch(
            targetPackage = variantPkg,
            releasePackage = releasePkg,
            extras = emptyMap(),
        )
        assertTrue(d is LaunchDecision.Launch)
        d as LaunchDecision.Launch
        assertEquals(
            "Launch must carry the variant package, not the release package",
            variantPkg,
            d.targetPackage,
        )
    }
}
