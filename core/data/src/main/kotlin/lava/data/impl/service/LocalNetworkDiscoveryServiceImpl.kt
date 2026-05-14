package lava.data.impl.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import lava.data.api.service.DiscoveredEndpoint
import lava.data.api.service.LocalNetworkDiscoveryService
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

/**
 * mDNS discovery for both backends advertised on the LAN.
 *
 * SP-3 (2026-04-29) extended the original `_lava._tcp`-only listener to
 * also scan `_lava-api._tcp`. Both service types are queried in parallel
 * via two independent NsdManager.discoverServices calls, and resolved
 * services map to one [DiscoveredEndpoint] each with the correct
 * [DiscoveredEndpoint.Engine] tag derived from either:
 *
 *   - the TXT-record `engine` attribute (authoritative; spec §8.3), or
 *   - the service-type as a fallback (`_lava-api._tcp` → Go,
 *     `_lava._tcp` → Ktor, anything else → Unknown).
 *
 * Sixth-Law clause 3 alignment: the host and port emitted here are the
 * exact bytes a client TCP/UDP probe would reach — no rewriting, no
 * normalization. The TXT-record parse is also a real wire read — falling
 * back to service-type only when the TXT records are absent.
 */
internal class LocalNetworkDiscoveryServiceImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val dispatchers: Dispatchers,
    @lava.data.api.service.DiscoveryServiceTypes private val serviceTypes: List<String>,
) : LocalNetworkDiscoveryService {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager

    override fun discover(): Flow<DiscoveredEndpoint> = callbackFlow {
        val nsd = nsdManager
        if (nsd == null) {
            close()
            return@callbackFlow
        }

        // Pair (listener, the type it was registered against) so awaitClose
        // can stopServiceDiscovery for each.
        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        for (serviceType in serviceTypes) {
            val listener = buildDiscoveryListener(nsd, serviceType, ::trySend)
            listeners += listener
            withContext(dispatchers.main) {
                nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }

        awaitClose {
            for (listener in listeners) {
                runCatching { nsd.stopServiceDiscovery(listener) }
            }
        }
    }

    private fun buildDiscoveryListener(
        nsd: NsdManager,
        watchedServiceType: String,
        emit: (DiscoveredEndpoint) -> Any,
    ): NsdManager.DiscoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(regType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

        @Suppress("DEPRECATION")
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            // SP-3.4 (2026-04-29) forensic anchor: the previous filter was
            // `serviceType.contains("lava")` for the `_lava._tcp` watcher,
            // which ALSO matched `_lava-api._tcp` because "lava" is a
            // proper substring of "lava-api". Real-device verification on
            // SM-S918B / Android 16 produced an `Endpoint.Mirror`
            // (engine=Ktor fallback) for the running lava-api-go service
            // because the cross-matched listener won the race against
            // the `_lava-api._tcp` listener and the device's NsdManager
            // returned no parseable TXT records, so the service-type
            // fallback fired with the wrong watchedServiceType.
            //
            // Fix: match the FULL service+protocol prefix
            // (`lava._tcp` vs `lava-api._tcp`) so the two listeners are
            // mutually exclusive AND so we don't accidentally accept
            // `_lava._udp` for a `_lava._tcp` watcher (different protocol
            // even if the service name is identical).
            if (!matchesServiceType(watchedServiceType, serviceInfo.serviceType)) {
                return
            }
            nsd.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val host = serviceInfo.host?.hostAddress ?: return
                        val port = serviceInfo.port
                        val name = serviceInfo.serviceName
                        emit(
                            DiscoveredEndpoint(
                                host = "$host:$port",
                                port = port,
                                name = name,
                                engine = engineFor(watchedServiceType, serviceInfo),
                            ),
                        )
                    }
                },
            )
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
    }

    /**
     * Map a resolved NsdServiceInfo to its engine tag.
     *
     * Priority: TXT record `engine` attribute (authoritative per SP-2 §8.3),
     * then fallback to the service-type the listener was subscribed to.
     */
    private fun engineFor(watchedServiceType: String, info: NsdServiceInfo): DiscoveredEndpoint.Engine {
        // attributes is a Map<String, ByteArray?>; available on API 21+.
        // Some publishers emit "engine=ktor" / "engine=go" in lower-case
        // per spec; some legacy ones may use mixed case — normalise.
        val txtEngine = runCatching {
            info.attributes["engine"]?.decodeToString()?.lowercase()?.trim()
        }.getOrNull()

        return when (txtEngine) {
            "go-dev" -> DiscoveredEndpoint.Engine.GoDev
            "go" -> DiscoveredEndpoint.Engine.Go
            "ktor" -> DiscoveredEndpoint.Engine.Ktor
            null, "" -> when {
                // Order matters: GoDev's service-type literal CONTAINS the Go
                // literal as a substring after stripping the leading underscore
                // ("lava-api-dev._tcp" contains "lava-api._tcp"? No — but the
                // raw `contains` check would match "_lava-api" as a prefix of
                // "_lava-api-dev". Test the more specific name first.
                watchedServiceType.contains(SERVICE_TYPE_GO_DEV) -> DiscoveredEndpoint.Engine.GoDev
                watchedServiceType.contains(SERVICE_TYPE_GO) -> DiscoveredEndpoint.Engine.Go
                watchedServiceType.contains(SERVICE_TYPE_KTOR) -> DiscoveredEndpoint.Engine.Ktor
                else -> DiscoveredEndpoint.Engine.Unknown
            }
            else -> DiscoveredEndpoint.Engine.Unknown
        }
    }

    companion object {
        // Re-export from the api-layer catalog so the existing in-file
        // engineFor() switch reads the same values the app-layer Hilt
        // module subscribes against (single source of truth).
        private const val SERVICE_TYPE_KTOR = lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPE_KTOR
        private const val SERVICE_TYPE_GO = lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPE_GO
        private const val SERVICE_TYPE_GO_DEV = lava.data.api.service.DiscoveryServiceTypeCatalog.SERVICE_TYPE_GO_DEV
    }
}

/**
 * Whether [foundServiceType] (as reported by Android NsdManager — typically
 * something like `_lava._tcp.local.` with a trailing dot) belongs to the
 * listener watching [watchedServiceType] (one of `_lava._tcp` or
 * `_lava-api._tcp`).
 *
 * Match rule: equality OR `startsWith(watched + '.')` after normalising
 * both inputs (lowercase, leading underscore stripped, trailing dot
 * stripped). The trailing-dot test is what makes `_lava._tcp` reject
 * `_lava-api._tcp` (because "lava-api._tcp" doesn't start with "lava._tcp.")
 * AND reject `_lava._udp` (because "lava._udp..." doesn't start with
 * "lava._tcp."). Top-level + `internal` so the contract test
 * `LocalNetworkDiscoveryContractTest` exercises the same function the
 * production listener uses.
 */
internal fun matchesServiceType(watchedServiceType: String, foundServiceType: String): Boolean {
    val watched = watchedServiceType.trim().trim('.').removePrefix("_").lowercase()
    val found = foundServiceType.trim().trim('.').removePrefix("_").lowercase()
    return found == watched || found.startsWith("$watched.")
}
