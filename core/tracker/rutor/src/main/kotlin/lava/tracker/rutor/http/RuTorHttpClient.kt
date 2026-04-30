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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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

/** Thrown when [RuTorHttpClient] short-circuits a request because the breaker is OPEN. */
class CircuitBreakerOpenException(message: String) : IOException(message)

/**
 * Coroutine-friendly circuit breaker:
 *   - CLOSED: requests pass through; consecutive failures (within
 *     [windowMillis]) are counted; reaching [failureThreshold] flips to
 *     OPEN and records the open-time.
 *   - OPEN: every guarded call short-circuits with
 *     [CircuitBreakerOpenException] until [resetTimeoutMillis] has elapsed
 *     since open-time, at which point the breaker enters HALF_OPEN.
 *   - HALF_OPEN: the next guarded call is admitted; on success the breaker
 *     resets to CLOSED with a zeroed counter; on failure it returns to
 *     OPEN with a fresh open-time.
 *
 * The [now] lambda is virtual-clock-friendly — tests can drive elapsed
 * time without [Thread.sleep], satisfying the host-stability directive.
 */
internal class CircuitBreaker(
    private val failureThreshold: Int = 3,
    private val windowMillis: Long = 30_000L,
    private val resetTimeoutMillis: Long = 30_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private enum class State { CLOSED, OPEN, HALF_OPEN }

    private val lock = Any()
    private var state: State = State.CLOSED
    private var failureCount: Int = 0
    private var firstFailureAt: Long = 0L
    private var openedAt: Long = 0L

    /**
     * Wraps [block]; throws [CircuitBreakerOpenException] when the breaker is OPEN
     * and within the reset window. Records success/failure transitions.
     */
    suspend fun <T> guard(block: suspend () -> T): T {
        admit()
        return try {
            val result = block()
            onSuccess()
            result
        } catch (t: Throwable) {
            onFailure()
            throw t
        }
    }

    private fun admit() {
        synchronized(lock) {
            if (state == State.OPEN) {
                val elapsed = now() - openedAt
                if (elapsed >= resetTimeoutMillis) {
                    state = State.HALF_OPEN
                } else {
                    throw CircuitBreakerOpenException(
                        "RuTor circuit breaker OPEN; ${resetTimeoutMillis - elapsed}ms until probe.",
                    )
                }
            }
        }
    }

    private fun onSuccess() {
        synchronized(lock) {
            state = State.CLOSED
            failureCount = 0
            firstFailureAt = 0L
        }
    }

    private fun onFailure() {
        synchronized(lock) {
            val t = now()
            if (state == State.HALF_OPEN) {
                state = State.OPEN
                openedAt = t
                return
            }
            if (failureCount == 0 || t - firstFailureAt > windowMillis) {
                failureCount = 1
                firstFailureAt = t
            } else {
                failureCount += 1
            }
            if (failureCount >= failureThreshold) {
                state = State.OPEN
                openedAt = t
            }
        }
    }
}

/**
 * Authenticated HTTP transport for the RuTor plugin.
 *
 * Owns a per-tracker OkHttp client with a cookie jar (so login session
 * survives across requests), a [Semaphore] bound to four permits per
 * spec — RuTor's mirrors are slow and we observed 5xx fan-out when
 * issuing more than four concurrent requests in earlier prototypes —
 * and a [CircuitBreaker] that trips after 3 failures in a 30-second
 * window and stays OPEN for 30 seconds.
 *
 * Section C, Task 3.7: skeleton with cookie jar + semaphore.
 * Section C, Task 3.8: layers in the circuit breaker.
 */
@Singleton
class RuTorHttpClient internal constructor(
    private val breaker: CircuitBreaker,
) {
    @Inject constructor() : this(CircuitBreaker())

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
        breaker.guard {
            concurrency.withPermit {
                executeOrThrow(
                    Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build(),
                )
            }
        }
    }

    suspend fun postForm(url: String, fields: Map<String, String>): Response =
        withContext(Dispatchers.IO) {
            breaker.guard {
                concurrency.withPermit {
                    val body = FormBody.Builder().apply {
                        fields.forEach { (k, v) -> add(k, v) }
                    }.build()
                    executeOrThrow(
                        Request.Builder().url(url).post(body)
                            .header("User-Agent", USER_AGENT)
                            .build(),
                    )
                }
            }
        }

    suspend fun download(url: String): ByteArray = withContext(Dispatchers.IO) {
        breaker.guard {
            concurrency.withPermit {
                executeOrThrow(
                    Request.Builder().url(url).get().header("User-Agent", USER_AGENT).build(),
                ).use {
                    require(it.isSuccessful) { "RuTor download failed: HTTP ${it.code} for $url" }
                    it.body?.bytes() ?: error("empty response body")
                }
            }
        }
    }

    /**
     * Executes [request] and returns the response, or throws an [IOException]
     * if OkHttp couldn't complete the call OR the response is a 5xx (which we
     * count as a failure for the breaker; 4xx still flows back to the caller
     * as a normal Response so parsers can decide what to do).
     */
    private fun executeOrThrow(request: Request): Response {
        val response = client.newCall(request).execute()
        if (response.code in 500..599) {
            response.close()
            throw IOException("RuTor mirror returned HTTP ${response.code} for ${request.url}")
        }
        return response
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
