package lava.tracker.kinozal.http

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
 * Thread-safe in-memory cookie jar keyed by host.
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

    @Synchronized
    fun allCookies(): List<Cookie> = store.values.flatten()
}

/** Thrown when the circuit breaker is OPEN. */
class CircuitBreakerOpenException(message: String) : IOException(message)

/** Coroutine-friendly circuit breaker. */
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
                        "Kinozal circuit breaker OPEN; ${resetTimeoutMillis - elapsed}ms until probe.",
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
 * Authenticated HTTP transport for the Kinozal plugin.
 *
 * Owns a per-tracker OkHttp client with a cookie jar, a semaphore (max 4
 * concurrent requests), and a circuit breaker.  Response bodies are decoded
 * from windows-1251 to UTF-8 via [bodyString].
 */
@Singleton
class KinozalHttpClient internal constructor(
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

    private val concurrency = Semaphore(permits = 4)
    private val kinozalCharset = java.nio.charset.Charset.forName("windows-1251")

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
                    require(it.isSuccessful) { "Kinozal download failed: HTTP ${it.code} for $url" }
                    it.body?.bytes() ?: error("empty response body")
                }
            }
        }
    }

    private fun executeOrThrow(request: Request): Response {
        val response = client.newCall(request).execute()
        if (response.code in 500..599) {
            response.close()
            throw IOException("Kinozal mirror returned HTTP ${response.code} for ${request.url}")
        }
        return response
    }

    fun hasCookie(name: String): Boolean = cookieValue(name) != null

    fun cookieValue(name: String): String? {
        val kinozalUrl = HttpUrl.Builder().scheme("https").host("kinozal.tv").build()
        cookieJar.loadForRequest(kinozalUrl).firstOrNull { it.name == name }?.let { return it.value }
        return cookieJar.allCookies().firstOrNull { it.name == name }?.value
    }

    fun clearCookies() {
        // Documented limitation — logout requires app restart.
    }

    /** Decodes the response body using windows-1251. */
    fun bodyString(response: Response): String {
        val bytes = response.body?.bytes() ?: return ""
        return String(bytes, kinozalCharset)
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) Lava/1.2.0"
    }
}
