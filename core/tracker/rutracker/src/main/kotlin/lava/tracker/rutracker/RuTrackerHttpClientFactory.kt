package lava.tracker.rutracker

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * SP-4 Phase F.2.6 (2026-05-13) — pure-Kotlin helper that constructs
 * a RuTracker-shaped Ktor [HttpClient] pinned to a caller-supplied
 * base URL.
 *
 * Use case: [RuTrackerClientFactory] calls this for a CLONED provider
 * to thread the clone's `primaryUrl` through the rest of the rutracker
 * subgraph. The Hilt-module path
 * (`:core:tracker:client`/`TrackerClientModule.provideRuTrackerHttpClient`)
 * remains the original-tracker path; both paths produce HttpClients
 * with identical plugins / headers / timeouts so they share the same
 * wire behaviour modulo the base URL.
 *
 * The default [logger] writes to stderr — chosen so this helper is
 * JVM-friendly (no `android.util.Log` dependency from a Kotlin-only
 * module) AND silent enough for production. The Hilt singleton path
 * passes its Android logger explicitly.
 */
object RuTrackerHttpClientFactory {

    /**
     * Build an HttpClient whose `defaultRequest` resolves relative
     * URLs against [baseUrl] (e.g. `tracker.php` → `$baseUrl/tracker.php`).
     *
     * @param baseUrl  Fully-qualified URL ending with a `/`. Typical
     *   production value is `"https://rutracker.org/forum/"`;
     *   typical clone value is `"https://<clone-host>/forum/"`.
     * @param logger   Pluggable Ktor logger. Defaults to the
     *   stderr-writing implementation defined below; the Android
     *   Hilt-module supplies its own Android-Log-backed implementation.
     */
    fun create(
        baseUrl: String,
        logger: Logger = StderrLogger,
    ): HttpClient = HttpClient(OkHttp) {
        defaultRequest {
            url(baseUrl)
            header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            header("Accept-Language", "en-US,en;q=0.5")
            header("Accept-Encoding", "gzip, deflate, br")
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
            connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
            socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
        }
        install(UserAgent) {
            agent = USER_AGENT
        }
        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }
        install(Logging) {
            level = LogLevel.INFO
            this.logger = logger
        }
    }

    private val StderrLogger = object : Logger {
        override fun log(message: String) {
            System.err.println("[RuTrackerHttp] $message")
        }
    }

    private const val REQUEST_TIMEOUT_MILLIS: Long = 60_000
    private const val CONNECT_TIMEOUT_MILLIS: Long = 30_000
    private const val SOCKET_TIMEOUT_MILLIS: Long = 60_000

    // Mirrors TrackerClientModule.provideRuTrackerHttpClient for parity.
    private const val USER_AGENT: String =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
}
