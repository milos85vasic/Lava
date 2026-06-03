package lava.applink

import android.content.Context
import android.content.Intent

/** Seam: "is <pkg> installed, and what intent launches it?" Tests fake this. */
interface PackageChecker {
    fun isInstalled(pkg: String): Boolean
    fun launchIntentFor(pkg: String): Intent?
}

/**
 * Real impl. NOTE: getLaunchIntentForPackage returns null on API 30+ for an
 * INSTALLED app unless the caller declares a <queries> entry for it — both
 * app manifests MUST declare the counterpart package (see Tasks 2.3 / 3.6).
 */
class PackageManagerChecker(private val context: Context) : PackageChecker {
    override fun isInstalled(pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    override fun launchIntentFor(pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)
}
