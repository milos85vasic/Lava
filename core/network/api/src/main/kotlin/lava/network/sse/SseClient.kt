package lava.network.sse

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

sealed interface SseEvent {
    data class Event(val type: String, val data: String) : SseEvent
    data class Error(val message: String) : SseEvent
    data object StreamEnd : SseEvent
}

/**
 * LVA-071 (2026-06-09). Factory for [SseClient] instances.
 *
 * Hoisted so the SSE consumer (e.g. `SearchResultViewModel.observeSseSearch`)
 * receives its [SseClient] via constructor injection instead of constructing
 * one inline with `SseClient()`. The inline construction made the SSE
 * error → Error → retry path impossible to drive hermetically against a
 * `MockWebServer`, because the test had no seam to substitute a client whose
 * `OkHttpClient` points at the mock socket. With this factory injected, a
 * test supplies a factory returning an [SseClient] wired to a short-timeout
 * `OkHttpClient`; production supplies the default factory.
 *
 * `fun interface` so the production Hilt binding is a single-expression
 * lambda and a test fake is a one-liner.
 */
fun interface SseClientFactory {
    fun create(): SseClient

    companion object {
        /**
         * The default factory used in production: every [create] call yields
         * a fresh [SseClient] with the library-default `OkHttpClient`
         * (30s connect, 5min read — long-lived stream).
         */
        val Default: SseClientFactory = SseClientFactory { SseClient() }
    }
}

/**
 * LVA-071 (2026-06-09). Builds the base URL (`scheme://host:port`) for the
 * `lava-api-go` SSE search endpoint from a [host]/[port] pair derived from
 * the active `Endpoint.GoApi` config (§6.R: the host + port come from the
 * persisted endpoint, never a source literal).
 *
 * Hoisted out of `SearchResultViewModel.observeSseSearch` (which previously
 * hard-coded `"https://${host}:${port}"` inline) so:
 *   1. The scheme is owned by ONE injectable component, not duplicated inline.
 *   2. A `MockWebServer`-backed test can substitute an `http`-scheme builder
 *      (MockWebServer serves plain HTTP) without standing up TLS, while
 *      production keeps the `https` scheme `lava-api-go` requires.
 *
 * `fun interface` for the same single-lambda-binding reason as
 * [SseClientFactory].
 */
fun interface SseBaseUrlBuilder {
    fun build(host: String, port: Int): String

    companion object {
        /**
         * Production builder — `lava-api-go` is HTTPS-only on the LAN
         * (permissive-TLS OkHttp client per SP-3.1). The scheme constant
         * is the only literal and it is a protocol identifier, not a
         * connection address/port (§6.R exemption: the host + port are
         * config-derived; `https` is the wire protocol the endpoint type
         * mandates, documented on `Endpoint.GoApi`).
         */
        val Https: SseBaseUrlBuilder = SseBaseUrlBuilder { host, port -> "https://$host:$port" }
    }
}

class SseClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build(),
) {
    fun connect(url: String, headers: Map<String, String> = emptyMap()): Flow<SseEvent> = callbackFlow {
        val requestBuilder = Request.Builder().url(url).header("Accept", "text/event-stream")
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }

        val call = client.newCall(requestBuilder.build())
        val response: Response = try {
            call.execute()
        } catch (e: Exception) {
            trySend(SseEvent.Error("Connection failed: ${e.message}"))
            close()
            return@callbackFlow
        }

        if (!response.isSuccessful) {
            trySend(SseEvent.Error("HTTP ${response.code}: ${response.message}"))
            response.close()
            close()
            return@callbackFlow
        }

        val body = response.body ?: run {
            trySend(SseEvent.Error("Empty response body"))
            response.close()
            close()
            return@callbackFlow
        }

        val reader = BufferedReader(InputStreamReader(body.byteStream()))
        var eventType = ""
        val dataBuilder = StringBuilder()

        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val currentLine = line ?: break
                when {
                    currentLine.startsWith("event: ") -> {
                        eventType = currentLine.removePrefix("event: ").trim()
                    }
                    currentLine.startsWith("data: ") -> {
                        dataBuilder.append(currentLine.removePrefix("data: "))
                    }
                    currentLine.isEmpty() -> {
                        if (dataBuilder.isNotEmpty()) {
                            val event = if (eventType == "stream_end") {
                                SseEvent.StreamEnd
                            } else {
                                SseEvent.Event(eventType, dataBuilder.toString())
                            }
                            trySend(event)
                            dataBuilder.clear()
                            eventType = ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            trySend(SseEvent.Error("Stream read error: ${e.message}"))
        } finally {
            try {
                reader.close()
                response.close()
            } catch (_: Exception) {}
        }

        close()
    }
}
