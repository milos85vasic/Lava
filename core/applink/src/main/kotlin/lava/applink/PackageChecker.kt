package lava.applink

import android.content.Context

/**
 * Seam: "does <pkg> have a launch intent?" Tests fake this.
 *
 * Returns a non-null token when the package is installed and launchable,
 * null when it is absent. The caller only null-checks the result, keeping
 * [CrossAppLauncher.decideLaunch] free of Android types and pure-JVM
 * unit-testable. The real impl returns the actual [android.content.Intent]
 * from PackageManager (a subtype of Any); test fakes may return any
 * non-null sentinel.
 */
interface PackageChecker {
    fun installedLaunchIntent(pkg: String): Any?
}

/**
 * Real impl. NOTE: getLaunchIntentForPackage returns null on API 30+ for an
 * INSTALLED app unless the caller declares a `<queries>` entry for it — both
 * app manifests MUST declare the counterpart package (see Tasks 2.3 / 3.6).
 */
class PackageManagerChecker(private val context: Context) : PackageChecker {
    override fun installedLaunchIntent(pkg: String): Any? =
        context.packageManager.getLaunchIntentForPackage(pkg)
}
