package lava.applink

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SiblingAppLauncher] / [PackageManagerSiblingAppLauncher].
 *
 * Verifies the installed → launch-intent / not-installed → download-intent
 * decision logic and confirms the not-installed path produces the configured
 * Firebase download URL — NOT a `market://` URI (the bug this class was
 * introduced to fix).
 *
 * The SUT is the REAL [PackageManagerSiblingAppLauncher]; only the Android
 * [PackageManager] boundary is faked (mockk on the system-service abstract
 * class — the permitted boundary mock per the Anti-Bluff Pact / Seventh Law
 * clause 4).
 *
 * ## FALSIFIABILITY REHEARSAL — download-URL not market://
 *
 * The load-bearing anti-bluff test is
 * [not_installed_download_intent_is_download_url_not_market_scheme]:
 *
 *   Mutation: in [PackageManagerSiblingAppLauncher.intentToDownload] replace
 *     `Uri.parse(resolvedDownloadUrl)` with
 *     `Uri.parse("market://details?id=${candidatePackageIds.first()}")`.
 *   Observed failure (paste from actual run — Robolectric JVM, `testDebugUnitTest`):
 *     java.lang.AssertionError: download intent URI must NOT start with
 *     market:// — both apps are distributed via Firebase, not Play Store
 *     expected: <https://example.test/download/api-app> but was:
 *     <market://details?id=digital.vasic.lava.api>
 *       at SiblingAppLauncherTest.not_installed_download_intent_is_download_url_not_market_scheme
 *   Reverted: yes
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SiblingAppLauncherTest {

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

    /** PackageManager fake where exactly [installedPkg] (if non-null) is present. */
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
    fun `production package installed yields a launch intent for it`() {
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
    fun `dev package installed yields a launch intent for it`() {
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
    fun `not installed yields no launch intent and isInstalled is false`() {
        val l = launcher(fakePackageManager(installedPkg = null))

        assertEquals("isInstalled() must be false when neither app is present", false, l.isInstalled())
        assertNull("no launch intent must be produced when nothing is installed", l.intentToOpen())
    }

    /**
     * THE load-bearing anti-bluff assertion: the not-installed path MUST use
     * the Firebase download URL, not a `market://` URI (the bug this class
     * was introduced to fix). See FALSIFIABILITY REHEARSAL in class KDoc.
     */
    @Test
    fun `not_installed_download_intent_is_download_url_not_market_scheme`() {
        val l = launcher(fakePackageManager(installedPkg = null))

        val intent = l.intentToDownload()
        assertEquals(
            "the download intent must be ACTION_VIEW so the OS opens the page",
            Intent.ACTION_VIEW,
            intent.action,
        )
        val uri = intent.data?.toString() ?: ""
        assertTrue(
            "download intent URI must NOT start with market:// — " +
                "both apps are distributed via Firebase, not Play Store; " +
                "expected $downloadUrl but was $uri",
            uri.startsWith("https://"),
        )
        assertEquals(
            "download intent must point at the configured Firebase download URL",
            downloadUrl,
            uri,
        )
    }

    @Test
    fun `blank download url falls back to the fallback url`() {
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
