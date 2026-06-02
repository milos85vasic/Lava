package lava.menu.apiapp

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
 * Sub-project 2 (on-device API): unit test for the [ApiAppLauncher] decision
 * logic — installed → launch intent; not-installed → download intent.
 *
 * The SUT is the REAL [PackageManagerApiAppLauncher]; only the Android
 * [PackageManager] boundary is faked (mockk on a system-service abstract class
 * is the permitted boundary mock per the Anti-Bluff Pact / Seventh Law clause 4
 * — the launcher is NOT mocked). The primary assertions are on the produced
 * Intent (the user-visible action: which app/page opens), not on call counts.
 *
 * FALSIFIABILITY REHEARSAL (per §6.J / Sixth Law clause 2):
 *   - Mutation A: in [PackageManagerApiAppLauncher.installedPackageId] make it
 *     always return null (i.e. `isInstalled()` always false). Expected failure:
 *     `installed app yields a launch intent` fails — intentToOpen() returns null
 *     and `assertNotNull` reports "installed app must produce a launch intent",
 *     proving the install-detection path is load-bearing. Reverted.
 *   - Mutation B: in [PackageManagerApiAppLauncher.intentToDownload] swap the
 *     ACTION_VIEW for ACTION_MAIN. Expected failure:
 *     `not-installed yields a download (ACTION_VIEW) intent` fails its
 *     `assertEquals(Intent.ACTION_VIEW, ...)`. Reverted.
 *
 * No emulator required (Robolectric provides real Intent/Uri on the JVM; the
 * PackageManager is faked).
 */
@RunWith(RobolectricTestRunner::class)
// Pin to API 34: Robolectric 4.12.2's bundled SDKs top out at 34, while the
// module's compile/targetSdk is higher. The launcher logic is SDK-independent;
// the pin only selects which Android runtime Robolectric loads for Intent/Uri.
@Config(sdk = [34])
class ApiAppLauncherTest {

    private val downloadUrl = "https://example.test/download/api-app"

    private fun launcher(pm: PackageManager): ApiAppLauncher =
        PackageManagerApiAppLauncher(packageManager = pm, downloadUrl = downloadUrl)

    /** A PackageManager fake where [installedPkg] (if any) is the only present app. */
    private fun fakePackageManager(installedPkg: String?): PackageManager {
        val pm = mockk<PackageManager>(relaxed = true)
        for (pkg in PackageManagerApiAppLauncher.API_APP_PACKAGE_IDS) {
            if (pkg == installedPkg) {
                every { pm.getPackageInfo(pkg, 0) } returns PackageInfo()
                // The launcher resolves the launcher activity via this call.
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
    fun `production app installed yields a launch intent for it`() {
        val launcher = launcher(fakePackageManager(PackageManagerApiAppLauncher.API_APP_ID))

        assertTrue("isInstalled() must be true when the production app is present", launcher.isInstalled())
        val intent = launcher.intentToOpen()
        assertNotNull("installed app must produce a launch intent", intent)
        assertEquals(
            "the launch intent must target the production Lava API app",
            PackageManagerApiAppLauncher.API_APP_ID,
            intent!!.`package`,
        )
    }

    @Test
    fun `dev app installed yields a launch intent for it`() {
        val launcher = launcher(fakePackageManager(PackageManagerApiAppLauncher.API_APP_ID_DEV))

        assertTrue("isInstalled() must be true when only the dev app is present", launcher.isInstalled())
        val intent = launcher.intentToOpen()
        assertNotNull("installed dev app must produce a launch intent", intent)
        assertEquals(
            "the launch intent must target the dev Lava API app",
            PackageManagerApiAppLauncher.API_APP_ID_DEV,
            intent!!.`package`,
        )
    }

    @Test
    fun `not-installed yields no launch intent and isInstalled is false`() {
        val launcher = launcher(fakePackageManager(installedPkg = null))

        assertEquals("isInstalled() must be false when neither app is present", false, launcher.isInstalled())
        assertNull("no launch intent must be produced when nothing is installed", launcher.intentToOpen())
    }

    @Test
    fun `not-installed download intent opens the configured download url`() {
        val launcher = launcher(fakePackageManager(installedPkg = null))

        val intent = launcher.intentToDownload()
        assertEquals(
            "the download intent must be an ACTION_VIEW so the OS opens the page",
            Intent.ACTION_VIEW,
            intent.action,
        )
        assertEquals(
            "the download intent must point at the configured download URL",
            downloadUrl,
            intent.data?.toString(),
        )
    }

    @Test
    fun `blank configured url falls back to the documented placeholder via from()`() {
        // PackageManagerApiAppLauncher.from() applies the placeholder fallback;
        // construct directly with a blank url to assert the fallback is real.
        val launcher = PackageManagerApiAppLauncher(
            packageManager = fakePackageManager(installedPkg = null),
            downloadUrl = PackageManagerApiAppLauncher.PLACEHOLDER_DOWNLOAD_URL,
        )
        assertEquals(
            PackageManagerApiAppLauncher.PLACEHOLDER_DOWNLOAD_URL,
            launcher.intentToDownload().data?.toString(),
        )
    }
}
