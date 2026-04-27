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

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            @Suppress("DEPRECATION")
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                nsd.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}

                        @Suppress("DEPRECATION")
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            val port = serviceInfo.port
                            val name = serviceInfo.serviceName
                            trySend(
                                DiscoveredEndpoint(
                                    host = "$host:$port",
                                    port = port,
                                    name = name,
                                ),
                            )
                        }
                    },
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }

        withContext(dispatchers.main) {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }

        awaitClose {
            nsd.stopServiceDiscovery(discoveryListener)
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_lava._tcp"
    }
}
