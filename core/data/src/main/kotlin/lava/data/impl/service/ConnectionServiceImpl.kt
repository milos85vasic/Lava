package lava.data.impl.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import lava.data.api.service.ConnectionService
import lava.dispatchers.api.Dispatchers
import lava.logger.api.LoggerFactory
import lava.models.settings.Endpoint
import lava.models.settings.isLocalHost
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import javax.inject.Inject

/**
 * SP-3.3 (2026-04-29). Reachability is now Endpoint-aware. The previous
 * `isReachable(host: String)` form silently failed for any LAN endpoint
 * that ran on a non-default port: it built `https://host` (port 443)
 * unconditionally for non-local hosts and `http://host` (port 80) for
 * local hosts, neither of which addresses `lava-api-go` on its 8443 port
 * or the legacy Ktor proxy on 8080.
 *
 * The new implementation does a plain TCP `connect()` to the same
 * `host:port` the network stack will actually open when the user picks
 * that endpoint:
 *
 *   - [Endpoint.GoApi]               → `host:port` (default 8443)
 *   - [Endpoint.Mirror] on a LAN IP  → `host:8080` (legacy Ktor proxy default)
 *                                      or the explicit port if the entry
 *                                      stored a `host:port` string.
 *   - [Endpoint.Mirror] on a public  → `host:443`
 *   - [Endpoint.Rutracker]           → `rutracker.org:443`
 *
 * TCP connect is sufficient for the green-icon semantics — the user only
 * needs to know "this endpoint is reachable at all", not "the application
 * protocol is healthy". Application-level errors (HTTP 5xx, TLS handshake
 * failure, JSON parse error) surface in the actual call sites, not the
 * indicator dot.
 */
internal class ConnectionServiceImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val dispatchers: Dispatchers,
    loggerFactory: LoggerFactory,
) : ConnectionService {
    private val logger = loggerFactory.get("ConnectionServiceImpl")
    private val connectivityManager = context.getSystemService<ConnectivityManager>()

    override val networkUpdates: Flow<Boolean> = callbackFlow {
        val connectivityManager = connectivityManager
        if (connectivityManager == null) {
            channel.trySend(false)
            channel.close()
        } else {
            val callback = object : ConnectivityManager.NetworkCallback() {
                private val networks = mutableSetOf<Network>()

                override fun onAvailable(network: Network) {
                    networks += network
                    notifyNetworksChanged()
                }

                override fun onLost(network: Network) {
                    networks -= network
                    notifyNetworksChanged()
                }

                private fun notifyNetworksChanged() {
                    channel.trySend(networks.isNotEmpty())
                }
            }
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            connectivityManager.registerNetworkCallback(request, callback)

            channel.trySend(connectivityManager.isNetworkConnected())

            awaitClose {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }
    }
        .conflate()
        .onEach { logger.d { "isNetworkConnected: $it" } }

    override suspend fun isReachable(endpoint: Endpoint): Boolean = withContext(dispatchers.io) {
        val (host, port) = endpoint.connectTarget()
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                true
            }
        }.getOrElse { false }
    }

    override suspend fun isInternetReachable(): Boolean = withContext(dispatchers.io) {
        runCatching {
            val url = URL(INTERNET_PROBE_URL)
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.connect()
                connection.responseCode in 200..399
            } catch (e: IOException) {
                false
            } finally {
                connection.disconnect()
            }
        }.getOrElse { false }
    }

    @Suppress("DEPRECATION")
    private fun ConnectivityManager.isNetworkConnected() = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
            activeNetwork
                ?.let(::getNetworkCapabilities)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

        else -> activeNetworkInfo?.isConnected
    } ?: false

    private companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val INTERNET_PROBE_URL = "https://google.com"
    }
}

/**
 * Maps an [Endpoint] to the host and port a TCP probe should target.
 * Top-level + `internal` so it can be unit-tested without an Android
 * `Context` (the rest of [ConnectionServiceImpl] needs `ConnectivityManager`).
 *
 * SP-3.3 (2026-04-29) extraction. Forensic anchor: the previous
 * `isReachable(host: String)` shape silently dropped any non-default
 * port, so `Endpoint.GoApi(host, 8443)` got probed at port 443/80 and
 * the user saw "Mirror has no green icon" / "GoApi has no green icon".
 * The mapping below makes the per-variant target explicit and testable.
 */
internal fun Endpoint.connectTarget(): Pair<String, Int> = when (this) {
    is Endpoint.GoApi -> host to port
    is Endpoint.Rutracker -> host to HTTPS_PORT
    is Endpoint.Mirror -> {
        val (h, p) = parseHostPort(host)
        val resolvedPort = p ?: if (h.isLocalHost()) LAN_PROXY_PORT else HTTPS_PORT
        h to resolvedPort
    }
}

internal fun parseHostPort(s: String): Pair<String, Int?> {
    val sep = s.lastIndexOf(':')
    return if (sep > 0) {
        s.substring(0, sep) to s.substring(sep + 1).toIntOrNull()
    } else {
        s to null
    }
}

private const val HTTPS_PORT = 443
private const val LAN_PROXY_PORT = 8080
