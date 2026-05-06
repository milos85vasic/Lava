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
 */
data class DiscoveredEndpoint(
    val host: String,
    val port: Int,
    val name: String,
    val engine: Engine = Engine.Ktor,
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

        /** TXT record present but engine value unrecognised; treat conservatively. */
        Unknown,
    }
}
