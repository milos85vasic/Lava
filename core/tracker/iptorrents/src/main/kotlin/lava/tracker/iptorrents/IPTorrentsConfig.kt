package lava.tracker.iptorrents

/**
 * Resolver for the lava-api-go sidecar base URL the IPTorrents provider routes
 * through.
 *
 * ## §6.R — no hardcoded host/port
 * There is NO compile-time default URL: the sidecar origin is deployment config.
 * It is resolved (in priority order) from:
 *   1. an explicit override (the clone/config path or a test),
 *   2. the `iptorrentsJackettBaseUrl` JVM system property (set by the §6.G test
 *      task from `-DiptorrentsJackettBaseUrl=...`),
 *   3. the `IPTORRENTS_JACKETT_BASE_URL` environment variable (the gitignored
 *      `.env`-sourced runtime value on the device/host).
 *
 * When none is present, [resolve] returns null and the feature impls fail the
 * call honestly ("sidecar base URL not configured") rather than guessing a host.
 *
 * On Android the production value is injected via BuildConfig/runtime config
 * wiring in `:core:tracker:client`; this pure-Kotlin module stays Android-free
 * and only reads the env/property seam, keeping it unit-testable on the JVM.
 */
object IPTorrentsConfig {

    const val SYSTEM_PROPERTY: String = "iptorrentsJackettBaseUrl"
    const val ENV_VAR: String = "IPTORRENTS_JACKETT_BASE_URL"

    /** Returns the configured sidecar base URL, or null when unconfigured. */
    fun resolve(override: String? = null): String? =
        override?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getProperty(SYSTEM_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv(ENV_VAR)?.trim()?.takeIf { it.isNotEmpty() }
}
