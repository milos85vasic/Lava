package lava.tracker.client

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.api.TrackerFeature
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.feature.CommentsTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.feature.HttpDownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.LoginResult
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.api.model.TorrentItem
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlin.reflect.KClass

/**
 * Dynamic Provider Discovery (2026-06-11, spec §4.2 / plan Task 4.1).
 *
 * A generic [TrackerClient] whose [descriptor] is a [RemoteTrackerDescriptor]
 * vended by the chosen lava-api-go instance (`GET /v1/providers`). It implements
 * the feature interfaces by issuing `GET|POST /v1/{descriptor.trackerId}/{op}`
 * against `apiBaseUrl` — the SAME server that vouched for the provider routes the
 * call to the right scraper/Jackett-sidecar server-side.
 *
 * **Capability Honesty (clause 6.E).** [getFeature] returns a non-null impl IFF
 * [descriptor] declares the matching [TrackerCapability]; otherwise null. This is
 * the identical gating shape the compiled-in clients use (see
 * `lava.tracker.archiveorg.ArchiveOrgClient.getFeature`): a `when(featureClass)`
 * whose each arm is guarded by `if (CAP in descriptor.capabilities)`.
 *
 * **Network seam.** The client owns a raw [OkHttpClient] and builds request URLs
 * from `apiBaseUrl`, mirroring [lava.data.provider.ProviderCatalogRepository]'s
 * established seam (which fetches `/v1/providers` the same way). No new not-yet-
 * existing network abstraction is introduced; OkHttp IS the seam, and
 * `MockWebServer` is the only fakeable boundary below the SUT (anti-bluff §6.J).
 *
 * The `/v1/...` strings are API CONTRACT ROUTES, not connection addresses/ports,
 * and are §6.R-exempt exactly like [ProviderCatalogRepository.PROVIDERS_PATH].
 *
 * // PENDING-INTEGRATION: the request PATHS are pinned by spec §4.3
 * // (`/v1/P/{op}`) and are the load-bearing assertions. The RESPONSE BODY shapes
 * // ([SearchResultDto] / [LoginResultDto]) mirror the lava-api-go wire structs in
 * // `internal/provider/provider.go` (SearchResult/SearchItem) as of 2026-06-11.
 * // When Phase 4 (API side) finalises the per-op response envelope, reconcile
 * // these DTOs against the regenerated `internal/gen/server` OpenAPI types.
 */
class ApiBackedTrackerClient(
    override val descriptor: RemoteTrackerDescriptor,
    private val apiBaseUrl: String,
    private val httpClient: OkHttpClient,
    private val json: Json = DEFAULT_JSON,
) : TrackerClient {

    // ---- feature impls (each gated on a capability in getFeature) -----------

    private val searchable = object : SearchableTracker {
        override suspend fun search(request: SearchRequest, page: Int): SearchResult {
            val url = baseUrl("search").newBuilder()
                .addQueryParameter("query", request.query)
                .addQueryParameter("page", page.toString())
                .apply {
                    request.author?.let { addQueryParameter("author", it) }
                    if (request.categories.isNotEmpty()) {
                        addQueryParameter("categories", request.categories.joinToString(","))
                    }
                }
                .build()
            val body = getString(url.toString())
            val dto = json.decodeFromString(SearchResultDto.serializer(), body)
            return dto.toDomain(descriptor.trackerId)
        }
    }

    private val browsable = object : BrowsableTracker {
        override suspend fun browse(category: String?, page: Int): BrowseResult {
            val url = baseUrl("browse").newBuilder()
                .addQueryParameter("page", page.toString())
                .apply { category?.let { addQueryParameter("category", it) } }
                .build()
            val body = getString(url.toString())
            val dto = json.decodeFromString(BrowseResultDto.serializer(), body)
            return dto.toDomain(descriptor.trackerId)
        }

        override suspend fun getForumTree() = null
    }

    private val topic = object : TopicTracker {
        override suspend fun getTopic(id: String): TopicDetail {
            val body = getString(baseUrl("topic/$id").toString())
            val dto = json.decodeFromString(TopicDetailDto.serializer(), body)
            return dto.toDomain(descriptor.trackerId)
        }

        override suspend fun getTopicPage(id: String, page: Int): TopicPage {
            val url = baseUrl("topic/$id/page").newBuilder()
                .addQueryParameter("page", page.toString())
                .build()
            val body = getString(url.toString())
            val dto = json.decodeFromString(TopicDetailDto.serializer(), body)
            return TopicPage(
                topic = dto.toDomain(descriptor.trackerId),
                totalPages = 1,
                currentPage = page,
            )
        }
    }

    private val downloadable = object : DownloadableTracker {
        override suspend fun downloadTorrentFile(id: String): ByteArray =
            getBytes(baseUrl("download/$id").toString())

        override fun getMagnetLink(id: String): String? = null
    }

    private val authenticatable = object : AuthenticatableTracker {
        override suspend fun login(req: LoginRequest): LoginResult {
            val payload = json.encodeToString(
                LoginRequestDto.serializer(),
                LoginRequestDto(
                    username = req.username,
                    password = req.password,
                    captchaSid = req.captcha?.sid,
                    captchaCode = req.captcha?.code,
                ),
            )
            val body = postJson(baseUrl("login").toString(), payload)
            val dto = json.decodeFromString(LoginResultDto.serializer(), body)
            return dto.toDomain()
        }

        override suspend fun logout() {
            postJson(baseUrl("logout").toString(), "{}")
        }

        override suspend fun checkAuth(): AuthState {
            val body = getString(baseUrl("auth").toString())
            val dto = json.decodeFromString(AuthStateDto.serializer(), body)
            return dto.toDomain()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : TrackerFeature> getFeature(featureClass: KClass<T>): T? {
        val caps = descriptor.capabilities
        return when (featureClass) {
            SearchableTracker::class ->
                if (TrackerCapability.SEARCH in caps) searchable as T else null
            BrowsableTracker::class ->
                if (TrackerCapability.BROWSE in caps) browsable as T else null
            TopicTracker::class ->
                if (TrackerCapability.TOPIC in caps) topic as T else null
            DownloadableTracker::class ->
                if (TrackerCapability.TORRENT_DOWNLOAD in caps) downloadable as T else null
            AuthenticatableTracker::class ->
                if (TrackerCapability.AUTH_REQUIRED in caps) authenticatable as T else null
            // The API-backed client does not yet surface these feature families
            // over /v1/{id}/{op}; declaring them here as explicit nulls keeps
            // capability honesty unambiguous (no accidental non-null fall-through).
            HttpDownloadableTracker::class -> null
            CommentsTracker::class -> null
            FavoritesTracker::class -> null
            else -> null
        }
    }

    override suspend fun healthCheck(): Boolean = try {
        httpClient.newCall(Request.Builder().url(baseUrl("health").toString()).get().build())
            .execute()
            .use { it.isSuccessful }
    } catch (_: Throwable) {
        false
    }

    override fun close() {
        // httpClient is a shared singleton owned by DI; closed at process shutdown.
    }

    // ---- HTTP helpers (the OkHttp seam) -------------------------------------

    /** Builds `{apiBaseUrl}/v1/{trackerId}/{op}` as a parseable [okhttp3.HttpUrl]. */
    private fun baseUrl(op: String) =
        (apiBaseUrl.trimEnd('/') + "/v1/" + descriptor.trackerId + "/" + op).toHttpUrl()

    private fun getString(url: String): String =
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) error("API request failed: HTTP ${resp.code} for $url")
            resp.body?.string() ?: error("API request returned empty body for $url")
        }

    private fun getBytes(url: String): ByteArray =
        httpClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) error("API request failed: HTTP ${resp.code} for $url")
            resp.body?.bytes() ?: error("API request returned empty body for $url")
        }

    private fun postJson(url: String, payload: String): String {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("API request failed: HTTP ${resp.code} for $url")
            resp.body?.string() ?: error("API request returned empty body for $url")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }
}

// ---------------------------------------------------------------------------
// Wire DTOs — mirror the lava-api-go `internal/provider/provider.go` structs.
// Kept private to this module: the public surface is the domain models.
// ---------------------------------------------------------------------------

@Serializable
internal data class SearchResultDto(
    val provider: String? = null,
    val page: Int = 0,
    val totalPages: Int = 1,
    val results: List<SearchItemDto> = emptyList(),
) {
    fun toDomain(trackerId: String) = SearchResult(
        items = results.map { it.toDomain(trackerId) },
        totalPages = totalPages,
        currentPage = page,
    )
}

@Serializable
internal data class BrowseResultDto(
    val provider: String? = null,
    val page: Int = 0,
    val totalPages: Int = 1,
    val items: List<SearchItemDto> = emptyList(),
) {
    fun toDomain(trackerId: String) = BrowseResult(
        items = items.map { it.toDomain(trackerId) },
        totalPages = totalPages,
        currentPage = page,
    )
}

@Serializable
internal data class SearchItemDto(
    val id: String,
    val title: String,
    val sizeBytes: Long? = null,
    val seeders: Int? = null,
    val leechers: Int? = null,
    val infoHash: String? = null,
    val magnetLink: String? = null,
    val downloadUrl: String? = null,
    val detailUrl: String? = null,
    val category: String? = null,
) {
    fun toDomain(trackerId: String) = TorrentItem(
        trackerId = trackerId,
        torrentId = id,
        title = title,
        sizeBytes = sizeBytes,
        seeders = seeders,
        leechers = leechers,
        infoHash = infoHash,
        magnetUri = magnetLink,
        downloadUrl = downloadUrl,
        detailUrl = detailUrl,
        category = category,
    )
}

@Serializable
internal data class TopicDetailDto(
    val id: String,
    val title: String,
    val description: String? = null,
    val magnetLink: String? = null,
    val downloadUrl: String? = null,
) {
    fun toDomain(trackerId: String) = TopicDetail(
        torrent = TorrentItem(
            trackerId = trackerId,
            torrentId = id,
            title = title,
            magnetUri = magnetLink,
            downloadUrl = downloadUrl,
        ),
        description = description,
    )
}

@Serializable
internal data class LoginRequestDto(
    val username: String,
    val password: String,
    val captchaSid: String? = null,
    val captchaCode: String? = null,
)

@Serializable
internal data class LoginResultDto(
    val state: String,
    val sessionToken: String? = null,
) {
    fun toDomain() = LoginResult(
        state = parseState(state),
        sessionToken = sessionToken,
    )
}

@Serializable
internal data class AuthStateDto(
    @SerialName("state") val state: String,
    val reason: String? = null,
) {
    fun toDomain(): AuthState = parseState(state, reason)
}

private fun parseState(name: String, reason: String? = null): AuthState =
    when (name.trim().lowercase()) {
        "authenticated" -> AuthState.Authenticated
        "unauthenticated" -> AuthState.Unauthenticated
        "serviceunavailable", "service_unavailable" ->
            AuthState.ServiceUnavailable(reason ?: "service unavailable")
        else -> AuthState.Unauthenticated
    }
