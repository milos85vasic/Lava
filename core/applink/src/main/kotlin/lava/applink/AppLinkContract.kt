package lava.applink

/**
 * Single source of truth for the client ↔ api-app intent contract.
 * Both :app and :api-app depend on this so the two ends cannot drift
 * (a drifted contract is a silent bluff: one app sends an extra the
 * other never reads). Package ids come from BuildConfig (variant-aware).
 */
object AppLinkContract {
    /** Explicit-component launch carries these extras (client → api-app). */
    const val EXTRA_START_API = "lava.applink.START_API"
    const val EXTRA_RETURN_TO = "lava.applink.RETURN_TO"

    /** Return launch carries these (api-app → client). */
    const val EXTRA_API_HOST = "lava.applink.API_HOST"
    const val EXTRA_API_PORT = "lava.applink.API_PORT"

    /** Loopback host the on-device API binds for same-device callers. */
    const val LOOPBACK_HOST = "127.0.0.1"

    /** Signature-level permission guarding the key provider read. */
    const val PERMISSION_READ_API_KEY = "digital.vasic.lava.permission.READ_API_KEY"

    /** Release package ids (Play-Store fallback target). */
    val CLIENT_RELEASE_PACKAGE: String get() = BuildConfig.CLIENT_RELEASE_PACKAGE
    val API_RELEASE_PACKAGE: String get() = BuildConfig.API_RELEASE_PACKAGE

    /** market:// + web Play-Store URIs for a release package id. */
    fun marketUri(releasePackage: String) = "market://details?id=$releasePackage"
    fun playWebUri(releasePackage: String) =
        "https://play.google.com/store/apps/details?id=$releasePackage"
}
