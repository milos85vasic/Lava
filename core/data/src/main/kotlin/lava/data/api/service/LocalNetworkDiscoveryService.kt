package lava.data.api.service

import kotlinx.coroutines.flow.Flow

interface LocalNetworkDiscoveryService {
    fun discover(): Flow<DiscoveredEndpoint>
}

/**
 * One mDNS hit that the LAN advertised.
 *
 * SP-3 (2026-04-29) added [engine] so the use case can disambiguate which
 * backend was found and construct the correct [lava.models.settings.Endpoint]
 * variant. [host] is the resolved IP, [port] is the service port the
 * publisher chose, [name] is the mDNS service name (typically "Lava API"
 * or similar).
 *
 * 2026-05-06: the legacy Ktor proxy was removed from the project. The
 * [Engine.Ktor] enum value is preserved for backward compatibility with
 * fixtures, persisted Room rows from older app versions, and any LAN
 * device still running an older `_lava._tcp` advertiser. New
 * advertisements should be `_lava-api._tcp` (Go). [engine] defaults to
 * [Engine.Ktor] for that historical-compatibility reason; new fakes and
 * tests should explicitly pass [Engine.Go].
 *
 * Sub-project 2 (on-device API, 2026-06-02) added [platform] and [storage],
 * parsed from the advertisement's TXT records. The on-device Lava-API app
 * advertises TXT `engine=go`, `platform=android`, `storage=sqlite`; the
 * host/server advertiser carries `engine=go` but omits `platform`. Both
 * default to `null` (additive) so existing fakes and tests that do not pass
 * them keep compiling and render exactly as a host/server instance. The
 * label that distinguishes the two for the user is computed by
 * [discoveredApiLabel].
 */
data class DiscoveredEndpoint(
    val host: String,
    val port: Int,
    val name: String,
    val engine: Engine = Engine.Ktor,
    val platform: String? = null,
    val storage: String? = null,
) {
    enum class Engine {
        /**
         * Legacy Ktor proxy — `_lava._tcp`, HTTP, port usually 8080.
         * The Ktor proxy module was removed in 2026-05-06; this enum
         * value is retained for backward compatibility with persisted
         * Room rows and pre-removal LAN advertisers, but no in-tree
         * code path produces it for new advertisements.
         */
        Ktor,

        /** Go API service — `_lava-api._tcp`, HTTPS, port usually 8443. */
        Go,

        /**
         * Developer Go API instance — `_lava-api-dev._tcp`, advertised by
         * a side-by-side lava-api-go process running on a different port
         * (e.g. 8543) so the developer can iterate on the API without
         * disturbing the production instance. Only the debug build of
         * the Android client subscribes to this service type; release
         * builds ignore it entirely so a stray DEV advertiser on a
         * production user's LAN cannot redirect their traffic.
         */
        GoDev,

        /** TXT record present but engine value unrecognised; treat conservatively. */
        Unknown,
    }
}

/**
 * The user-facing label used in "available API instances" lists (onboarding
 * ApiSelection step, Connections screen) to distinguish an API running ON THE
 * USER'S OWN ANDROID DEVICE from one running on a host/server.
 *
 * The mapping is driven purely by the `platform` TXT-record value Sub-project 1
 * publishes:
 *   - `platform=android`  → [LABEL_ANDROID_DEVICE] ("On this network · Android device")
 *   - any other non-blank platform → [LABEL_NETWORK] ("On this network") + the
 *     raw platform value in parens, so an unexpected platform is surfaced
 *     honestly rather than silently mislabeled as a host.
 *   - blank / absent platform → [LABEL_NETWORK] ("On this network") — the
 *     host/server advertiser, rendered exactly as before Sub-project 2.
 *
 * No underscores in any returned label (operator directive). Pure function so
 * the TXT→label mapping is unit-testable without an emulator or NsdManager.
 */
fun discoveredApiLabel(platform: String?): String {
    val normalized = platform?.trim()?.lowercase()
    return when {
        normalized == PLATFORM_ANDROID -> LABEL_ANDROID_DEVICE
        normalized.isNullOrEmpty() -> LABEL_NETWORK
        else -> "$LABEL_NETWORK ($normalized)"
    }
}

/** The `platform` TXT value an on-device Lava-API instance advertises. */
const val PLATFORM_ANDROID: String = "android"

/** Label for an on-device (this-phone-or-another-Android) API instance. */
const val LABEL_ANDROID_DEVICE: String = "On this network · Android device"

/** Label for a host/server API instance discovered on the LAN. */
const val LABEL_NETWORK: String = "On this network"
