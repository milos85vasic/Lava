package lava.models.settings

/**
 * One of the backends the Lava Android client can talk to.
 *
 * SP-3.2 (2026-04-29). The historical `Endpoint.Proxy` (public
 * lava-app.tech instance of the legacy Ktor proxy) was REMOVED.
 * It surfaced as a confusing seeded entry in the Connections list
 * with no operational role for the user, who runs `lava-api-go`
 * on the LAN. The only public-Internet endpoint now is
 * [Rutracker] (direct rutracker.org).
 *
 * 2026-05-06: the entire Ktor `:proxy` module was removed from the
 * project. Production code paths that constructed LAN [Mirror] endpoints
 * with HTTP/8080 semantics (the legacy Ktor proxy advertiser) are now
 * dead. The [Mirror] type is retained for the rutracker public-mirror
 * use case (a different concept from the LAN proxy).
 *
 * SP-3 (2026-04-29) introduced [GoApi] as a peer of [Mirror]. [GoApi]
 * points at a `lava-api-go` instance (advertised on mDNS as
 * `_lava-api._tcp` with TXT `engine=go`, default port 8443, HTTPS with
 * the LAN-permissive OkHttp client per SP-3.1).
 *
 * The discriminator matters for transport:
 *
 *   - [Mirror] on a LAN IP            → http://host                   (legacy advertiser, no longer in-tree)
 *   - [Mirror] on a public hostname   → https://host/forum/           (rutracker mirror)
 *   - [GoApi]                         → https://host:port             (LAN api-go, permissive TLS)
 *   - [Rutracker]                     → https://rutracker.org/forum/  (direct, system trust)
 *
 * Built by [lava.network.data.NetworkApiRepositoryImpl] which constructs the
 * right Ktor [io.ktor.client.HttpClient] for each variant.
 */
sealed interface Endpoint {
    val host: String

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
