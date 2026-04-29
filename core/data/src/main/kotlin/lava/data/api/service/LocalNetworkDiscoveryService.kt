package lava.data.api.service

import kotlinx.coroutines.flow.Flow

interface LocalNetworkDiscoveryService {
    fun discover(): Flow<DiscoveredEndpoint>
}

/**
 * One mDNS hit that the LAN advertised.
 *
 * SP-3 (2026-04-29) added [engine] so the use case can disambiguate which
 * backend was found (legacy Ktor proxy under `_lava._tcp` with TXT
 * `engine=ktor` vs. lava-api-go under `_lava-api._tcp` with TXT
 * `engine=go`) and construct the correct [lava.models.settings.Endpoint]
 * variant. [host] is the resolved IP, [port] is the service port the
 * publisher chose, [name] is the mDNS service name (typically "Lava API"
 * or similar). [engine] defaults to [Engine.Ktor] for backward
 * compatibility with fixtures and fakes that pre-date SP-3.
 */
data class DiscoveredEndpoint(
    val host: String,
    val port: Int,
    val name: String,
    val engine: Engine = Engine.Ktor,
) {
    enum class Engine {
        /** Legacy Ktor proxy — `_lava._tcp`, HTTP, port usually 8080. */
        Ktor,

        /** Go API service — `_lava-api._tcp`, HTTPS, port usually 8443. */
        Go,

        /** TXT record present but engine value unrecognised; treat conservatively. */
        Unknown,
    }
}
