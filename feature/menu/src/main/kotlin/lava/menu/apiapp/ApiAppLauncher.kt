package lava.menu.apiapp

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Sub-project 2 (on-device API integration) — controls launching, or routing
 * the user to download, the separate **Lava API** Android app
 * (`digital.vasic.lava.api` / `.api.dev`).
 *
 * The Lava API app, when installed and launched, boots its own on-device API
 * and advertises it over mDNS (`platform=android`) so it appears in this
 * client's discovered "available API instances" list (onboarding ApiSelection
 * step). This launcher is the Settings affordance for "Run the API on this
 * device":
 *   - if the API app is installed → [intentToOpen] launches it (which boots
 *     its API);
 *   - if not → [intentToDownload] opens a download page so the user can install
 *     it first.
 *
 * The decision logic (installed → open vs. not-installed → download) is pure
 * given an [isInstalled] result, which is why the production impl reads install
 * state through a [PackageManager] seam: the unit test
 * (`ApiAppLauncherTest`) injects a fake [PackageManager] and asserts the
 * returned intent, with no emulator required.
 */
interface ApiAppLauncher {

    /**
     * Whether EITHER the production (`digital.vasic.lava.api`) OR the dev
     * (`digital.vasic.lava.api.dev`) Lava API app is installed on this device.
     */
    fun isInstalled(): Boolean

    /**
     * The Intent that launches the installed Lava API app's launcher activity
     * (which boots its on-device API), or `null` if neither variant is
     * installed (the caller should then use [intentToDownload]).
     */
    fun intentToOpen(): Intent?

    /**
     * The Intent that opens the configured download page for the Lava API app.
     * Always non-null; used when [isInstalled] is false.
     */
    fun intentToDownload(): Intent
}

/**
 * Default [ApiAppLauncher] backed by the Android [PackageManager].
 *
 * @param downloadUrl where to send the user to install the Lava API app. §6.R:
 *   this MUST be supplied by the caller from build-time config
 *   (`BuildConfig.LAVA_API_APP_DOWNLOAD_URL`, sourced from `.env`), never a
 *   source literal here. The app-layer factory passes it in.
 */
class PackageManagerApiAppLauncher(
    private val packageManager: PackageManager,
    private val downloadUrl: String,
) : ApiAppLauncher {

    override fun isInstalled(): Boolean = installedPackageId() != null

    override fun intentToOpen(): Intent? {
        val pkg = installedPackageId() ?: return null
        return packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun intentToDownload(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * The first installed Lava API package id (production preferred over dev),
     * or `null` if neither is installed. A `PackageManager.NameNotFoundException`
     * means "not installed" — caught and treated as absent rather than
     * propagated.
     */
    private fun installedPackageId(): String? =
        API_APP_PACKAGE_IDS.firstOrNull { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }

    companion object {
        /**
         * The production Lava API app's application id.
         *
         * This is a sibling-app application identifier within the same product
         * family (cf. this client's own `digital.vasic.lava.client`), not a
         * connection address / secret — it is the constant the OS uses to
         * resolve whether the API app is installed. Listed first so it is
         * preferred over the dev variant.
         */
        const val API_APP_ID: String = "digital.vasic.lava.api"

        /** The dev/debug Lava API app's application id (`.dev` suffix). */
        const val API_APP_ID_DEV: String = "digital.vasic.lava.api.dev"

        /** Production preferred, then dev. */
        val API_APP_PACKAGE_IDS: List<String> = listOf(API_APP_ID, API_APP_ID_DEV)

        /**
         * Documented placeholder download page, used ONLY when the build-config
         * `LAVA_API_APP_DOWNLOAD_URL` is blank (CI / fresh checkout with no
         * `.env`). The real link lives in `.env` (§6.R) and is wired by the
         * app layer; this constant exists so the row still produces a valid
         * Intent rather than crashing on a blank URI.
         *
         * TODO sub-project 4: the Firebase App Distribution link for
         * `digital.vasic.lava.api` is the real value; it will be configured via
         * `.env` → `BuildConfig.LAVA_API_APP_DOWNLOAD_URL`, making this fallback
         * unused in configured builds.
         */
        const val PLACEHOLDER_DOWNLOAD_URL: String = "https://lava.app/download/api-app"

        /**
         * Build a launcher from a [Context]. [downloadUrl] SHOULD come from
         * build config (§6.R) — the app-layer wiring passes
         * `BuildConfig.LAVA_API_APP_DOWNLOAD_URL`; a blank value falls back to
         * [PLACEHOLDER_DOWNLOAD_URL].
         */
        fun from(context: Context, downloadUrl: String): ApiAppLauncher =
            PackageManagerApiAppLauncher(
                packageManager = context.packageManager,
                downloadUrl = downloadUrl.ifBlank { PLACEHOLDER_DOWNLOAD_URL },
            )
    }
}
