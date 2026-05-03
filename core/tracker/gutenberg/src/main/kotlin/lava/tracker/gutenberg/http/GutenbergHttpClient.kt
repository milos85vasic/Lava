package lava.tracker.gutenberg.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simple HTTP transport for the Project Gutenberg Gutendex API.
 *
 * Unlike RuTor, Gutenberg does not require cookies, circuit breakers,
 * or concurrency throttling — the upstream is a fast CDN-backed JSON API.
 */
@Singleton
class GutenbergHttpClient @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun get(url: String): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute()
    }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) {
                "Gutenberg download failed: HTTP ${response.code} for $url"
            }
            response.body?.bytes() ?: error("empty response body")
        }
    }

    fun <T> decodeFromString(
        deserializer: DeserializationStrategy<T>,
        string: String,
    ): T = json.decodeFromString(deserializer, string)

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Lava/1.2.0"
    }
}
