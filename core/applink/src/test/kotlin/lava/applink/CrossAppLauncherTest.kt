package lava.applink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossAppLauncherTest {
    private fun checker(installed: Set<String>) = object : PackageChecker {
        override fun isInstalled(pkg: String) = pkg in installed
        override fun launchIntentFor(pkg: String) = null // not used by decide()
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
}
