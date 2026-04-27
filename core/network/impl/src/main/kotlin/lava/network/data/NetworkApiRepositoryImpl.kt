package lava.network.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.serialization.kotlinx.json.json
import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import lava.models.settings.isLocalHost
import lava.network.api.NetworkApi
import lava.network.api.RuTrackerApiFactory
import lava.network.impl.ProxyNetworkApi
import lava.network.serialization.JsonFactory
import okhttp3.OkHttpClient
import javax.inject.Inject
import kotlin.io.encoding.Base64

internal class NetworkApiRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    private val networkLogger: NetworkLogger,
) : NetworkApiRepository {
    private val apiMap = mutableMapOf<Endpoint, NetworkApi>()

    override suspend fun getApi(): NetworkApi {
        val endpoint = endpoint()
        return apiMap.getOrPut(endpoint) {
            val host = endpoint.host
            when {
                endpoint is Endpoint.Proxy -> proxyApi(host)
                host.isLocalHost() -> proxyApi(host)
                endpoint is Endpoint.RutrackerEndpoint -> rutrackerApi(host)
                else -> rutrackerApi(host)
            }
        }
    }

    override suspend fun getCaptchaUrl(url: String): String {
        return when (val endpoint = endpoint()) {
            is Endpoint.Proxy -> proxyUrl(endpoint.host, "/captcha/${url.encode()}")
            is Endpoint.RutrackerEndpoint -> {
                if (endpoint.host.isLocalHost()) {
                    proxyUrl(endpoint.host, "/captcha/${url.encode()}")
                } else {
                    url
                }
            }
        }
    }

    override suspend fun getDownloadUri(id: String): String {
        return when (val endpoint = endpoint()) {
            is Endpoint.Proxy -> proxyUrl(endpoint.host, "/download/$id")
            is Endpoint.RutrackerEndpoint -> {
                if (endpoint.host.isLocalHost()) {
                    proxyUrl(endpoint.host, "/download/$id")
                } else {
                    "https://${endpoint.host}/forum/dl.php?t=$id"
                }
            }
        }
    }

    override suspend fun getAuthHeader(token: String): Pair<String, String> {
        return when (val endpoint = endpoint()) {
            is Endpoint.Proxy -> "Auth-Token" to token
            is Endpoint.RutrackerEndpoint -> {
                if (endpoint.host.isLocalHost()) {
                    "Auth-Token" to token
                } else {
                    "Cookie" to token
                }
            }
        }
    }

    private suspend fun endpoint() = settingsRepository.getSettings().endpoint

    private fun proxyApi(host: String): NetworkApi {
        val scheme = if (host.isLocalHost()) "http" else "https"
        return ProxyNetworkApi(
            HttpClient(OkHttp) {
                engine { preconfigured = okHttpClient }
                defaultRequest { url(scheme = scheme, host = host) }
                install(Logging) {
                    logger = networkLogger
                    level = LogLevel.INFO
                }
                install(ContentNegotiation) {
                    json(JsonFactory.create())
                }
            },
        )
    }

    private fun rutrackerApi(host: String): NetworkApi {
        return RuTrackerApiFactory.create(
            HttpClient(OkHttp) {
                engine { preconfigured = okHttpClient }
                defaultRequest { url("https://$host/forum/") }
                install(Logging) {
                    logger = networkLogger
                    level = LogLevel.ALL
                }
            },
        )
    }

    private fun proxyUrl(host: String, path: String): String {
        val scheme = if (host.isLocalHost()) "http" else "https"
        return "$scheme://$host$path"
    }

    private fun String.encode(): String {
        return Base64.UrlSafe.encode(encodeToByteArray())
    }
}
