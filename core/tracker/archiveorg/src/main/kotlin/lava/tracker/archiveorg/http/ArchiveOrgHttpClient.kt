package lava.tracker.archiveorg.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP transport for the Internet Archive provider.
 *
 * Owns a per-tracker OkHttp client with sensible timeouts. Unlike RuTor,
 * there is no cookie jar (archive.org requires no auth) and no circuit
 * breaker (archive.org's CDN is reliable enough for SP-3a scope).
 *
 * All JSON deserialization uses kotlinx.serialization with [ignoreUnknownKeys]
 * so archive.org API additions don't break parsing.
 */
@Singleton
class ArchiveOrgHttpClient @Inject constructor() {

    internal val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun get(url: String): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .get()
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
                "Archive.org download failed: HTTP ${response.code} for $url"
            }
            response.body?.bytes() ?: error("empty response body")
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Lava/1.2.0"
    }
}
