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
import lava.tracker.rutracker.api.RuTrackerApiFactory
import lava.network.impl.ProxyNetworkApi
import lava.network.serialization.JsonFactory
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import kotlin.io.encoding.Base64

internal class NetworkApiRepositoryImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: OkHttpClient,
    @Named("lan") private val lanOkHttpClient: OkHttpClient,
    private val networkLogger: NetworkLogger,
) : NetworkApiRepository {
    private val apiMap = mutableMapOf<Endpoint, NetworkApi>()

    override suspend fun getApi(): NetworkApi {
        val endpoint = endpoint()
        return apiMap.getOrPut(endpoint) {
            when (endpoint) {
                // LAN lava-api-go — permissive TLS via lanOkHttpClient.
                // Trust boundary documented in NetworkModule.lanOkHttpClient KDoc.
                is Endpoint.GoApi -> proxyApi(
                    host = endpoint.host,
                    port = endpoint.port,
                    scheme = "https",
                    client = lanOkHttpClient,
                )
                is Endpoint.RutrackerEndpoint -> {
                    if (endpoint.host.isLocalHost()) {
                        // SP-3.3 (2026-04-29). Mirror entries on the LAN
                        // historically may carry an embedded `:port` in the
                        // host string (mDNS publishers from before SP-3
                        // emitted "ip:port" as the host). Pass that port
                        // through to Ktor's URLBuilder explicitly — calling
                        // `url(host="ip:port")` does NOT round-trip and will
                        // route requests at the wrong target. When no port
                        // is encoded, default to 8080 (the legacy Ktor proxy).
                        val (h, p) = parseHostPort(endpoint.host)
                        proxyApi(
                            host = h,
                            port = p ?: LEGACY_LAN_PROXY_PORT,
                            scheme = "http",
                            client = okHttpClient,
                        )
                    } else {
                        // Public rutracker (direct or mirror) — strict TLS.
                        rutrackerApi(endpoint.host)
                    }
                }
            }
        }
    }

    private fun parseHostPort(s: String): Pair<String, Int?> {
        val sep = s.lastIndexOf(':')
        return if (sep > 0) {
            s.substring(0, sep) to s.substring(sep + 1).toIntOrNull()
        } else {
            s to null
        }
    }

    private companion object {
        const val LEGACY_LAN_PROXY_PORT = 8080
    }

    override suspend fun getCaptchaUrl(url: String): String {
        return when (val endpoint = endpoint()) {
            is Endpoint.GoApi -> goApiUrl(endpoint, "/captcha/${url.encode()}")
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
            is Endpoint.GoApi -> goApiUrl(endpoint, "/download/$id")
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
            is Endpoint.GoApi -> "Auth-Token" to token
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

    /**
     * Build a [ProxyNetworkApi] (Lava-API wire shape, identical for the
     * legacy Ktor proxy and the lava-api-go service per SP-2 §10 parity).
     *
     * SP-3 (2026-04-29) added the [port] and [scheme] parameters so a
     * caller can target the Go API on its non-default port (8443) via
     * HTTPS. SP-3.1 (same date) added the [client] parameter so the
     * caller can choose between the strict-TLS [okHttpClient] (public
     * Internet) and the permissive-TLS LAN client. Routing decisions
     * are made by the calling `when (endpoint)` block in [getApi];
     * see [lava.network.di.NetworkModule.lanOkHttpClient] KDoc for the
     * trust-boundary rationale.
     */
    private fun proxyApi(
        host: String,
        port: Int?,
        scheme: String,
        client: OkHttpClient,
    ): NetworkApi {
        return ProxyNetworkApi(
            HttpClient(OkHttp) {
                engine { preconfigured = client }
                defaultRequest {
                    if (port != null) {
                        url(scheme = scheme, host = host, port = port)
                    } else {
                        url(scheme = scheme, host = host)
                    }
                }
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

    /**
     * SP-3.3 (2026-04-29). For LAN [Endpoint.Mirror], if the host string
     * doesn't already encode a port, append the default Ktor proxy port
     * (8080) — otherwise this URL ends up resolving to port 80, where
     * nothing is listening on the operator's machine.
     */
    private fun proxyUrl(host: String, path: String): String {
        return if (host.isLocalHost()) {
            val (h, p) = parseHostPort(host)
            "http://$h:${p ?: LEGACY_LAN_PROXY_PORT}$path"
        } else {
            "https://$host$path"
        }
    }

    private fun goApiUrl(endpoint: Endpoint.GoApi, path: String): String {
        return "https://${endpoint.host}:${endpoint.port}$path"
    }

    private fun String.encode(): String {
        return Base64.UrlSafe.encode(encodeToByteArray())
    }
}
