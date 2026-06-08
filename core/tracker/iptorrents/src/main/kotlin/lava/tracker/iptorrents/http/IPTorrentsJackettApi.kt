package lava.tracker.iptorrents.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin HTTP transport for the IPTorrents provider: every call goes to the local
 * lava-api-go sidecar's `GET /jackett/search?indexer=iptorrents&q=<query>` route
 * (and to the `downloadUrl` Jackett /dl/ proxy links that route returns). The
 * app NEVER talks to iptorrents.com directly — Cloudflare + Jackett + FlareSolverr
 * all live server-side.
 *
 * ## §6.R — no hardcoded host/port
 * The sidecar base URL is NOT a literal here. It is supplied by the caller
 * (resolved from BuildConfig / runtime config / the §6.G test's
 * `-DiptorrentsJackettBaseUrl`), so this class carries zero connection literals.
 * The only literals are the route PATH and query-parameter NAMES, which are part
 * of lava-api-go's API contract, not connection config.
 *
 * ## §6.H — no credentials on the wire from the app
 * Jackett's api_key + the IPTorrents username/password are server-side secrets
 * held by lava-api-go / Jackett; this client sends neither. It carries only the
 * search query.
 */
@Singleton
class IPTorrentsJackettApi @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // FlareSolverr's Cloudflare solve can be slow server-side; allow headroom.
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Issues `GET <baseUrl>/jackett/search?indexer=iptorrents&q=<query>` and
     * returns the raw JSON body. Throws [IOException] on a non-2xx status so the
     * caller surfaces a real failure rather than parsing an error page.
     *
     * [baseUrl] is the lava-api-go origin (e.g. `https://localhost:8443`),
     * supplied by config — never hardcoded here.
     */
    suspend fun searchJson(baseUrl: String, query: String): String = withContext(Dispatchers.IO) {
        val url: HttpUrl = baseUrl.trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment(PATH_JACKETT)
            .addPathSegment(PATH_SEARCH)
            .addQueryParameter(PARAM_INDEXER, INDEXER_IPTORRENTS)
            .addQueryParameter(PARAM_QUERY, query)
            .build()
        client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("lava-api-go /jackett/search failed: HTTP ${response.code} for $url")
            }
            body
        }
    }

    /**
     * Fetches the bytes at [downloadUrl] — the Jackett `/dl/` .torrent proxy link
     * that a SearchItem carries. The link is already a fully-qualified URL the
     * route handed back, so no base-URL composition is needed.
     */
    suspend fun downloadBytes(downloadUrl: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(downloadUrl).get().build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("IPTorrents .torrent download failed: HTTP ${response.code} for $downloadUrl")
            }
            response.body?.bytes() ?: throw IOException("empty .torrent body for $downloadUrl")
        }
    }

    private companion object {
        // lava-api-go route contract (NOT connection config — see class KDoc).
        const val PATH_JACKETT = "jackett"
        const val PATH_SEARCH = "search"
        const val PARAM_INDEXER = "indexer"
        const val PARAM_QUERY = "q"
        const val INDEXER_IPTORRENTS = "iptorrents"
    }
}
