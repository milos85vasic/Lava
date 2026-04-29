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

        for (serviceType in SERVICE_TYPES) {
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
            // NsdManager normalises service-type strings (trailing dot,
            // case) so accept any prefix-match against the type we
            // actually subscribed to. Cross-matches across types still
            // can't happen because each listener is registered against
            // a single SERVICE_TYPE entry.
            if (!serviceInfo.serviceType.contains(watchedServiceType.trimStart('_').substringBefore('.'))) {
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
            "go" -> DiscoveredEndpoint.Engine.Go
            "ktor" -> DiscoveredEndpoint.Engine.Ktor
            null, "" -> when {
                watchedServiceType.contains(SERVICE_TYPE_GO) -> DiscoveredEndpoint.Engine.Go
                watchedServiceType.contains(SERVICE_TYPE_KTOR) -> DiscoveredEndpoint.Engine.Ktor
                else -> DiscoveredEndpoint.Engine.Unknown
            }
            else -> DiscoveredEndpoint.Engine.Unknown
        }
    }

    companion object {
        private const val SERVICE_TYPE_KTOR = "_lava._tcp"
        private const val SERVICE_TYPE_GO = "_lava-api._tcp"

        private val SERVICE_TYPES = listOf(SERVICE_TYPE_KTOR, SERVICE_TYPE_GO)
    }
}
