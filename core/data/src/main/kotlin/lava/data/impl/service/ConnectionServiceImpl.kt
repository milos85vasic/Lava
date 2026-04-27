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
import lava.models.settings.isLocalHost
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

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

    override suspend fun isReachable(host: String) = withContext(dispatchers.io) {
        runCatching {
            val scheme = if (host.isLocalHost()) "http" else "https"
            val url = URL("$scheme://$host")
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 5_000
                connection.connect()
                connection.responseCode == 200
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
}
