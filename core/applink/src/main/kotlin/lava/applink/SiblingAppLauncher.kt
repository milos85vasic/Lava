package lava.applink

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * Shared launcher for sibling apps within the Lava product family
 * (`digital.vasic.lava.*`). Checks whether any of [candidatePackageIds] is
 * installed; if so, returns a launch [Intent] for it; if not, returns a
 * download [Intent] pointing at [downloadUrl].
 *
 * This replaces [PackageManagerApiAppLauncher] (previously in `:feature:menu`)
 * and [CrossAppLauncher] (which used `market://` — a dead link since both apps
 * are distributed via Firebase App Distribution, not Google Play).
 *
 * Design invariants:
 *   - No `market://` or Play Store URL anywhere in this class.  The
 *     not-installed fallback is ALWAYS the [downloadUrl] supplied by the
 *     caller from build-time config (§6.R: never a source literal).
 *   - The caller layer adds EXTRA_START_API / EXTRA_RETURN_TO / EXTRA_API_HOST /
 *     EXTRA_API_PORT extras to the Intent returned by [intentToOpen] — this
 *     class produces a bare launcher Intent; the bidirectional-handoff extras
 *     live in [AppLinkContract] and are the caller's responsibility.
 *   - [candidatePackageIds] are package identifiers within the same product
 *     family.  They are NOT credentials or secrets — they are the stable
 *     app-store IDs the OS uses to resolve install state (§6.R exemption: same
 *     rationale as the prior PackageManagerApiAppLauncher.API_APP_ID constants).
 */
interface SiblingAppLauncher {

    /**
     * Whether any of the [candidatePackageIds] is currently installed.
     */
    fun isInstalled(): Boolean

    /**
     * Returns a launcher [Intent] for the first installed candidate package,
     * with [Intent.FLAG_ACTIVITY_NEW_TASK] set, or `null` when none of the
     * candidates is installed.
     *
     * The caller is responsible for adding any [AppLinkContract] extras before
     * invoking `startActivity`.
     */
    fun intentToOpen(): Intent?

    /**
     * Returns an [Intent.ACTION_VIEW] [Intent] that opens [downloadUrl].
     * Always non-null; used when [isInstalled] is false.
     */
    fun intentToDownload(): Intent
}

/**
 * Default [SiblingAppLauncher] backed by the Android [PackageManager].
 *
 * @param candidatePackageIds Ordered list of package ids to check; the first
 *   installed one is used.  Production preference: release id first, dev id
 *   second.
 * @param downloadUrl The Firebase App Distribution (or other) download page for
 *   the target app.  §6.R: MUST be supplied from build-time config
 *   (`BuildConfig.*_DOWNLOAD_URL`), never a source literal here.  When blank
 *   the caller-supplied [fallbackDownloadUrl] is used so the Intent is always
 *   valid.
 * @param fallbackDownloadUrl Used ONLY when [downloadUrl] is blank (CI / fresh
 *   checkout without a configured `.env`).  Each call site supplies a module-
 *   specific placeholder so no two modules share the same undifferentiated URL.
 */
class PackageManagerSiblingAppLauncher(
    private val packageManager: PackageManager,
    private val candidatePackageIds: List<String>,
    downloadUrl: String,
    fallbackDownloadUrl: String,
) : SiblingAppLauncher {

    private val resolvedDownloadUrl: String =
        downloadUrl.ifBlank { fallbackDownloadUrl }

    override fun isInstalled(): Boolean = resolvedPackageId() != null

    override fun intentToOpen(): Intent? {
        val pkg = resolvedPackageId() ?: return null
        return packageManager.getLaunchIntentForPackage(pkg)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    override fun intentToDownload(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(resolvedDownloadUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * The first installed package id from [candidatePackageIds], or `null` when
     * none is installed.  [PackageManager.NameNotFoundException] is treated as
     * "not installed" rather than propagated.
     */
    private fun resolvedPackageId(): String? =
        candidatePackageIds.firstOrNull { pkg ->
            runCatching {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }

    companion object {
        /**
         * Build a [SiblingAppLauncher] from a [Context].
         *
         * @param context Android context (application or activity).
         * @param candidatePackageIds Ordered list; release id first.
         * @param downloadUrl From build config (§6.R).
         * @param fallbackDownloadUrl Placeholder used when [downloadUrl] is blank.
         */
        fun from(
            context: Context,
            candidatePackageIds: List<String>,
            downloadUrl: String,
            fallbackDownloadUrl: String,
        ): SiblingAppLauncher = PackageManagerSiblingAppLauncher(
            packageManager = context.packageManager,
            candidatePackageIds = candidatePackageIds,
            downloadUrl = downloadUrl,
            fallbackDownloadUrl = fallbackDownloadUrl,
        )
    }
}
