package lava.data.provider

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import lava.network.dto.ProviderDescriptorDto
import lava.network.dto.ProvidersResponseDto
import lava.network.serialization.JsonFactory
import lava.tracker.api.RemoteTrackerDescriptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Local store for the discovered provider catalogue, keyed by API base URL so
 * switching API instances does not cross-contaminate, and so the provider list
 * renders on the next cold start without a round-trip (spec §4.2).
 *
 * This module owns the abstraction; a durable (DataStore/Room) binding is wired
 * in the DI/registry phase (out of this layer's scope). The default
 * [InMemoryProviderCatalogStore] is the seam tests + the use case drive against.
 */
interface ProviderCatalogStore {
    fun save(apiBaseUrl: String, providers: List<RemoteTrackerDescriptor>)
    fun load(apiBaseUrl: String): List<RemoteTrackerDescriptor>
}

@Singleton
class InMemoryProviderCatalogStore @Inject constructor() : ProviderCatalogStore {
    private val byApi = ConcurrentHashMap<String, List<RemoteTrackerDescriptor>>()

    override fun save(apiBaseUrl: String, providers: List<RemoteTrackerDescriptor>) {
        byApi[normalize(apiBaseUrl)] = providers
    }

    override fun load(apiBaseUrl: String): List<RemoteTrackerDescriptor> =
        byApi[normalize(apiBaseUrl)] ?: emptyList()

    private fun normalize(apiBaseUrl: String): String = apiBaseUrl.trimEnd('/')
}

/**
 * Dynamic Provider Discovery (2026-06-11, spec §4.2 / plan Task 3.3).
 *
 * Fetches the provider catalogue from the chosen lava-api-go instance over real
 * HTTPS (`GET {apiBaseUrl}/providers`), parses [ProvidersResponseDto], maps
 * each entry to a [RemoteTrackerDescriptor], and write-through-caches the result
 * keyed by `apiBaseUrl`.
 *
 * **TLS + auth boundary (Defect-A fix, 2026-06-12).** The on-device api-app
 * serves `/providers` over a **self-signed LAN cert** and behind the
 * `Lava-Auth` header gate. This repository therefore MUST use the permissive
 * LAN [OkHttpClient] (`@Named("lan")` — accepts the self-signed cert, the same
 * client [lava.network.data.NetworkApiRepository.getApi] uses for
 * `Endpoint.GoApi`), and MUST attach the chosen endpoint's per-instance key via
 * [authKey]. The prior wiring injected the *unqualified* strict-TLS client and
 * sent no key, so every real-device fetch died at the TLS handshake
 * (`CertPathValidatorException: Trust anchor for certification path not found`,
 * Crashlytics `042b9b61` / `provider_catalog_fetch_failed`, 1.3.3-1060) → fell
 * back to the 4 bundled `verified && apiSupported` descriptors. That is the
 * "only 4 providers in onboarding" bug.
 *
 * **Error contract (spec §5):** any TLS/4xx/5xx/timeout/parse failure is captured
 * into `Result.failure` — this method NEVER throws to the caller. The onboarding
 * layer falls back to the bundled descriptors on failure (never a blank screen).
 *
 * The DTO→fields adaptation lives HERE (not in `:core:tracker:api`) because this
 * module is allowed to depend on both `:core:network` and `:core:tracker:api`,
 * whereas `:core:tracker:api` must stay free of a `:core:network` edge — see
 * [RemoteTrackerDescriptor]'s non-cyclic note.
 */
@Singleton
class ProviderCatalogRepository @Inject constructor(
    @Named("lan") private val lanHttpClient: OkHttpClient,
    @Named("authFieldName") private val authFieldName: String,
    private val store: ProviderCatalogStore,
) {
    // Non-injected collaborators with safe defaults so unit tests construct the
    // repo with just (lanHttpClient, authFieldName, store); production binds the
    // same defaults via DI.
    private val json: Json = JsonFactory.create()
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var warn: (String) -> Unit = {}

    /** Test/DI seam to route mapping + fetch warnings to a logger / §6.AC telemetry. */
    fun setWarnSink(sink: (String) -> Unit) {
        warn = sink
    }

    /**
     * @param authKey the chosen [lava.models.settings.Endpoint.GoApi]'s
     *   per-instance key (from `ApiKeyStore` via the client↔api-app link). When
     *   non-null it is attached as the [authFieldName] header so the api-app's
     *   `Lava-Auth` gate admits the request; null for unauthenticated targets.
     */
    suspend fun fetchProviders(
        apiBaseUrl: String,
        authKey: String? = null,
    ): Result<List<RemoteTrackerDescriptor>> =
        withContext(ioDispatcher) {
            runCatching {
                val client =
                    if (authKey != null) lanHttpClient.withAuthKey(authKey, authFieldName) else lanHttpClient
                val url = apiBaseUrl.trimEnd('/') + PROVIDERS_PATH
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("provider discovery failed: HTTP ${response.code} for $url")
                    }
                    val payload = response.body?.string()
                        ?: error("provider discovery failed: empty body for $url")
                    val dto = json.decodeFromString(ProvidersResponseDto.serializer(), payload)
                    val descriptors = dto.providers.map { it.toRemoteDescriptor(warn) }
                    store.save(apiBaseUrl, descriptors)
                    descriptors
                }
            }.onFailure { warn("ProviderCatalogRepository.fetchProviders($apiBaseUrl): ${it.message}") }
        }

    /** Last persisted catalogue for [apiBaseUrl] (empty if never fetched). */
    fun cachedProviders(apiBaseUrl: String): List<RemoteTrackerDescriptor> = store.load(apiBaseUrl)

    /**
     * Returns a derived client that sets the [fieldName] header to [key] on
     * every request — mirrors
     * [lava.network.data.NetworkApiRepositoryImpl.withKeyOverride] (which is
     * `internal` to `:core:network:impl` and so cannot be reused across the
     * module boundary). §6.H: [key] is never logged.
     */
    private fun OkHttpClient.withAuthKey(key: String, fieldName: String): OkHttpClient =
        newBuilder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header(fieldName, key).build())
            }
            .build()

    companion object {
        /**
         * Discovery route — served at the engine ROOT (`/providers`), NOT under
         * `/v1/`: a literal `/v1/providers` collides with the `:provider` wildcard
         * in the API's gin radix tree. Per-provider operations still use
         * `/v1/{id}/{op}`. (API contract route, not a connection address — §6.R exempt.)
         */
        const val PROVIDERS_PATH = "/providers"
    }
}

/**
 * Adapts one wire [ProviderDescriptorDto] into a [RemoteTrackerDescriptor],
 * delegating the tolerant string→enum mapping to
 * [RemoteTrackerDescriptor.from]. Lives in `:core:data` (not `:core:tracker:api`)
 * to avoid the forbidden `:core:tracker:api → :core:network` dependency.
 */
internal fun ProviderDescriptorDto.toRemoteDescriptor(
    warn: (String) -> Unit = {},
): RemoteTrackerDescriptor =
    RemoteTrackerDescriptor.from(
        trackerId = id,
        displayName = displayName,
        capabilities = capabilities,
        authType = authType,
        baseUrls = baseUrls,
        encoding = encoding,
        supportsAnonymous = supportsAnonymous,
        warn = warn,
    )
