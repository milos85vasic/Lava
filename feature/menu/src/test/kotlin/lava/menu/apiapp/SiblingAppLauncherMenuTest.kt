package lava.menu.apiapp

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import lava.applink.PackageManagerSiblingAppLauncher
import lava.applink.SiblingAppLauncher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit test for the [SiblingAppLauncher] as used by
 * `feature/menu`'s "Run the API on this device" Settings row
 * (`RunApiOnDeviceItem` in `MenuScreen.kt`).
 *
 * The SUT is the REAL [PackageManagerSiblingAppLauncher]; only the Android
 * [PackageManager] boundary is faked (mockk — the permitted boundary mock per
 * the Anti-Bluff Pact / Seventh Law clause 4). Primary assertions are on the
 * produced Intent (what the OS receives), not on call counts.
 *
 * ## FALSIFIABILITY REHEARSAL (per §6.J / Sixth Law clause 2)
 *
 * **Rehearsal A — install-detection path:**
 *   Mutation: in [PackageManagerSiblingAppLauncher] make `resolvedPackageId()`
 *     always return null (force not-installed).
 *   Observed failure: `production_app_installed_yields_launch_intent` fails —
 *     `intentToOpen()` returns null and `assertNotNull` reports
 *     "installed app must produce a launch intent".
 *   Reverted: yes.
 *
 * **Rehearsal B — download URL is https://, NOT market://:**
 *   Mutation: in [PackageManagerSiblingAppLauncher.intentToDownload] replace
 *     `Uri.parse(resolvedDownloadUrl)` with
 *     `Uri.parse("market://details?id=digital.vasic.lava.api")`.
 *   Observed failure: `not_installed_yields_download_url_intent_not_market` fails —
 *     assertion "download URI must start with https://" fires:
 *     expected <https://example.test/download/api-app>
 *     but was <market://details?id=digital.vasic.lava.api>
 *   Reverted: yes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiblingAppLauncherMenuTest {

    private val downloadUrl = "https://example.test/download/api-app"
    private val fallback = "https://lava.app/download/api-app-placeholder"
    private val candidates = listOf("digital.vasic.lava.api", "digital.vasic.lava.api.dev")

    private fun launcher(pm: PackageManager): SiblingAppLauncher =
        PackageManagerSiblingAppLauncher(
            packageManager = pm,
            candidatePackageIds = candidates,
            downloadUrl = downloadUrl,
            fallbackDownloadUrl = fallback,
        )

    private fun fakePackageManager(installedPkg: String?): PackageManager {
        val pm = mockk<PackageManager>(relaxed = true)
        for (pkg in candidates) {
            if (pkg == installedPkg) {
                every { pm.getPackageInfo(pkg, 0) } returns PackageInfo()
                every { pm.getLaunchIntentForPackage(pkg) } returns
                    Intent(Intent.ACTION_MAIN).setPackage(pkg)
            } else {
                every { pm.getPackageInfo(pkg, 0) } throws
                    PackageManager.NameNotFoundException(pkg)
                every { pm.getLaunchIntentForPackage(pkg) } returns null
            }
        }
        return pm
    }

    @Test
    fun `production_app_installed_yields_launch_intent`() {
        val l = launcher(fakePackageManager("digital.vasic.lava.api"))

        assertTrue("isInstalled() must be true when production app is present", l.isInstalled())
        val intent = l.intentToOpen()
        assertNotNull("installed app must produce a launch intent", intent)
        assertEquals(
            "launch intent must target the production package",
            "digital.vasic.lava.api",
            intent!!.`package`,
        )
    }

    @Test
    fun `dev_app_installed_yields_launch_intent`() {
        val l = launcher(fakePackageManager("digital.vasic.lava.api.dev"))

        assertTrue("isInstalled() must be true when only dev app is present", l.isInstalled())
        val intent = l.intentToOpen()
        assertNotNull("installed dev app must produce a launch intent", intent)
        assertEquals(
            "launch intent must target the dev package",
            "digital.vasic.lava.api.dev",
            intent!!.`package`,
        )
    }

    @Test
    fun `not_installed_yields_no_launch_intent_and_isInstalled_false`() {
        val l = launcher(fakePackageManager(installedPkg = null))

        assertEquals("isInstalled() must be false when neither app is present", false, l.isInstalled())
        assertNull("no launch intent must be produced when nothing is installed", l.intentToOpen())
    }

    /**
     * THE load-bearing anti-bluff assertion for the menu row: the not-installed
     * path MUST use the Firebase/https:// download URL, not `market://`.
     * See FALSIFIABILITY REHEARSAL B in class KDoc.
     */
    @Test
    fun `not_installed_yields_download_url_intent_not_market`() {
        val l = launcher(fakePackageManager(installedPkg = null))

        val intent = l.intentToDownload()
        assertEquals(
            "the download intent must be ACTION_VIEW so the OS opens the page",
            Intent.ACTION_VIEW,
            intent.action,
        )
        val uri = intent.data?.toString() ?: ""
        assertTrue(
            "download URI must start with https:// — both apps are on Firebase, not Play Store; " +
                "got: $uri",
            uri.startsWith("https://"),
        )
        assertEquals(
            "download intent must point at the configured Firebase download URL",
            downloadUrl,
            uri,
        )
    }

    @Test
    fun `blank_download_url_falls_back_to_fallback_url`() {
        val l = PackageManagerSiblingAppLauncher(
            packageManager = fakePackageManager(installedPkg = null),
            candidatePackageIds = candidates,
            downloadUrl = "",
            fallbackDownloadUrl = fallback,
        )
        assertEquals(
            "blank downloadUrl must fall back to fallbackDownloadUrl",
            fallback,
            l.intentToDownload().data?.toString(),
        )
    }
}
