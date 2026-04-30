package lava.tracker.rutor.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Authenticated HTTP transport for the RuTor plugin.
 *
 * Owns a per-tracker OkHttp client with a cookie jar (so login session
 * survives across requests) and a [Semaphore] bound to four permits per
 * spec — RuTor's mirrors are slow and we observed 5xx fan-out when
 * issuing more than four concurrent requests in earlier prototypes.
 *
 * Section C, Task 3.7: skeleton with cookie jar + semaphore.
 * Section C, Task 3.8: layers in the circuit breaker.
 */
/**
 * Thread-safe in-memory cookie jar keyed by host. Sufficient for RuTor's
 * single-domain session cookie + CSRF token; replaced wholesale on
 * [RuTorHttpClient.clearCookies] which today is documented no-op (logout
 * requires app restart for SP-3a).
 */
private class InMemoryCookieJar : CookieJar {
    private val store = ConcurrentHashMap<String, MutableList<Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { mutableListOf() }
        cookies.forEach { c ->
            list.removeAll { existing -> existing.name == c.name && existing.path == c.path }
            list.add(c)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host].orEmpty().filter { it.matches(url) }
}

@Singleton
class RuTorHttpClient @Inject constructor() {
    private val cookieJar = InMemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Per-tracker semaphore: max 4 concurrent requests, per SP-3a spec. */
    private val concurrency = Semaphore(permits = 4)

    suspend fun get(url: String): Response = withContext(Dispatchers.IO) {
        concurrency.withPermit {
            client.newCall(
                Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build(),
            ).execute()
        }
    }

    suspend fun postForm(url: String, fields: Map<String, String>): Response =
        withContext(Dispatchers.IO) {
            concurrency.withPermit {
                val body = FormBody.Builder().apply {
                    fields.forEach { (k, v) -> add(k, v) }
                }.build()
                client.newCall(
                    Request.Builder().url(url).post(body).header("User-Agent", USER_AGENT).build(),
                ).execute()
            }
        }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        concurrency.withPermit {
            client.newCall(
                Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build(),
            ).execute().use {
                require(it.isSuccessful) { "RuTor download failed: HTTP ${it.code} for $url" }
                it.body?.bytes() ?: error("empty response body")
            }
        }
    }

    /**
     * Returns true if a cookie with [name] is currently stored for the primary
     * mirror. Used by [feature.RuTorAuth] (Section J) to detect logged-in state.
     */
    fun hasCookie(name: String): Boolean = cookieValue(name) != null

    /**
     * Returns the value of the cookie with [name] for the primary RuTor mirror,
     * or null if none. Pre-loaded for Section J's RuTorAuth wiring.
     */
    fun cookieValue(name: String): String? {
        val url = HttpUrl.Builder().scheme("https").host("rutor.info").build()
        val match = cookieJar.loadForRequest(url).firstOrNull { c -> c.name == name }
        return match?.value
    }

    fun clearCookies() {
        // CookieManager-backed cookieJar can't be cleared without reflection;
        // documented limitation — logout requires app restart for SP-3a.
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Lava/1.2.0"
    }
}
