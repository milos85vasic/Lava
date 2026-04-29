package lava.models.settings

/**
 * One of the backends the Lava Android client can talk to.
 *
 * SP-3 (2026-04-29) introduced [GoApi] as a peer of [Mirror]. Both are LAN
 * endpoints, but [GoApi] points at a `lava-api-go` instance (advertised on
 * mDNS as `_lava-api._tcp` with TXT `engine=go`, default port 8443, HTTPS
 * with operator-installed CA), whereas a [Mirror] on a private IP is treated
 * as a legacy LAN Ktor proxy (advertised as `_lava._tcp` with TXT
 * `engine=ktor`, default port 8080, HTTP).
 *
 * The wire format is identical between the two LAN backends — see SP-2
 * §10 cross-backend parity (8/8 fixtures PASS). The discriminator matters
 * for transport:
 *
 *   - [Proxy]                         → https://lava-app.tech         (port 443)
 *   - [Mirror] on a LAN IP            → http://host                   (port 80, legacy proxy)
 *   - [Mirror] on a public hostname   → https://host/forum/           (rutracker mirror)
 *   - [GoApi]                         → https://host:port             (TLS via user-installed CA, port required)
 *
 * Built by [lava.network.data.NetworkApiRepositoryImpl] which constructs the
 * right Ktor [io.ktor.client.HttpClient] for each variant.
 */
sealed interface Endpoint {
    val host: String

    data object Proxy : Endpoint {
        override val host: String = "lava-app.tech"
    }

    /**
     * A `lava-api-go` instance on the local network.
     *
     * The default port is the SP-2 spec §8.1 listener (`:8443`); it is
     * carried explicitly on the type because the legacy proxy uses 8080
     * and adding a mistyped port to a [Mirror] would silently route
     * through the wrong scheme. Carrying it on the type makes the URL
     * builder's job unambiguous.
     */
    data class GoApi(override val host: String, val port: Int = DEFAULT_PORT) : Endpoint {
        companion object {
            const val DEFAULT_PORT: Int = 8443
        }
    }

    sealed interface RutrackerEndpoint : Endpoint

    data object Rutracker : RutrackerEndpoint {
        override val host: String = "rutracker.org"
    }

    data class Mirror(override val host: String) : RutrackerEndpoint

    companion object
}
