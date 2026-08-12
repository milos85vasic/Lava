package lava.domain.usecase

import kotlinx.coroutines.flow.first
import lava.common.analytics.AnalyticsTracker
import lava.data.api.repository.EndpointsRepository
import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import lava.network.sse.SseBaseUrlBuilder
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.registry.TrackerRegistry
import javax.inject.Inject

/**
 * Provider-availability restore on app startup (operator directive 2026-06-13:
 * "we used to have only 4 providers — ALL new providers MUST be available
 * app-wide").
 *
 * ## The gap this closes
 *
 * The dynamic `GET /providers` catalogue (5 native + 7 curated = 12 providers)
 * is loaded into the [TrackerRegistry] ONLY by the onboarding flow's in-session
 * [FetchProvidersUseCase] → [TrackerRegistry.populateFrom]. After a cold restart
 * — no onboarding re-run — `MenuViewModel.loadProviders()` and
 * `ProviderConfigViewModel` read `sdk.listAvailableTrackers()` with NO re-fetch,
 * so the registry has reverted to the compiled-in BUNDLED set. The user who
 * configured an API instance during onboarding then sees only the bundled
 * providers in Settings, NOT the full catalogue. That is the "only 4 providers"
 * report.
 *
 * ## What this does
 *
 * Invoked once at process startup (from `:app`), BEFORE any settings/menu screen
 * lists providers. It:
 *
 *  1. Reads the persisted active endpoint via [SettingsRepository.getSettings].
 *  2. If — and only if — that endpoint is an [Endpoint.GoApi] (the user picked a
 *     lava-api-go instance during onboarding), it re-activates that base URL
 *     (so each dynamic [lava.tracker.client.ApiBackedTrackerClient] targets the
 *     chosen instance, via the [activator] seam) and re-fetches the catalogue
 *     through the SAME real [FetchProvidersUseCase] →
 *     [lava.data.provider.ProviderCatalogRepository] the onboarding flow uses,
 *     passing the endpoint's per-instance [Endpoint.GoApi.key].
 *  3. On success it calls [TrackerRegistry.populateFrom] so the dynamic set is
 *     reflected EVERYWHERE the SDK reads (`listAvailableTrackers()` — onboarding
 *     re-entry, Settings/Menu, ProviderConfig, search).
 *
 * ## Graceful degradation (§6.AB "never blank")
 *
 * If the active endpoint is not a [Endpoint.GoApi], or the API is unreachable,
 * or the fetch fails for any reason, this is a NO-OP: the bundled providers
 * remain registered (the registry already holds them as the offline fallback),
 * so the user is never shown a blank or broken provider list. This mirrors the
 * onboarding flow's fallback posture exactly. This use case NEVER throws.
 *
 * ## Why a domain use case (not duplicated onboarding logic)
 *
 * It REUSES the existing [FetchProvidersUseCase] (+ its real
 * `ProviderCatalogRepository`), the existing [SseBaseUrlBuilder] base-URL seam,
 * and the existing [TrackerRegistry.populateFrom] — no parallel fetch/populate
 * path. The only new collaborator is [ActiveApiBaseUrlActivator], a thin seam so
 * `:core:domain` does not take a hard dependency on `:core:tracker:client`'s
 * `ApiBaseUrlHolder` object (the production binding lives in `:app`).
 *
 * ## Cold-start race + silent-failure closure (LVA-093 / LVA-094, 2026-08)
 *
 * This use case runs off the main thread in a fire-and-forget coroutine
 * (`LavaApplication.onCreate()`'s `startupScope.launch { ... }`) so it cannot
 * block app startup. Before LVA-093/LVA-094 closed, nothing awaited that
 * coroutine's completion (a search fired immediately after launch could
 * silently resolve against the stale bundled registry — LVA-093) and a
 * catalogue-fetch failure was swallowed with no telemetry and no retry
 * (LVA-094). Both are now closed:
 *
 *  - [providersReadyGate] is marked ready — via [StartupProvidersGate.markReady]
 *    — in a `finally` block wrapping the ENTIRE body, so every return path
 *    (non-GoApi fast path, fetch success, fetch failure) releases any
 *    consumer awaiting readiness (the search entry point,
 *    [lava.search.result.SearchResultViewModel]) exactly once.
 *  - [fetchWithRetry] performs a single retry on a failed catalogue fetch and
 *    records a §6.AC non-fatal via [analytics] for EVERY failed attempt
 *    (including the final one), so a cold-start repopulation failure is no
 *    longer invisible to the operator.
 *
 * @return `true` if the dynamic catalogue was successfully fetched and the
 *   registry repopulated; `false` if it degraded to bundled (non-GoApi endpoint,
 *   unreachable API, or fetch failure). The Boolean is for test assertions and
 *   optional telemetry — the user-visible outcome is the populated registry.
 */
class RepopulateProvidersOnStartupUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val fetchProvidersUseCase: FetchProvidersUseCase,
    private val trackerRegistry: TrackerRegistry,
    private val activator: ActiveApiBaseUrlActivator,
    private val apiBaseUrlBuilder: SseBaseUrlBuilder,
    // 2026-06-14 existing-install HEAL (search-routing fix). See
    // [reconcileActiveEndpoint].
    private val endpointsRepository: EndpointsRepository,
    // 2026-06-14 existing-install KEY-RESTORE (search-auth fix). See
    // [restoreActiveEndpointKey].
    private val apiKeyProvider: ApiKeyProvider,
    // LVA-093 (2026-08): unblocks a search fired before this cold-start
    // attempt has concluded. See [StartupProvidersGate].
    private val providersReadyGate: StartupProvidersGate,
    // LVA-094 (2026-08): §6.AC non-fatal telemetry for the catalogue-fetch
    // failure path. See [fetchWithRetry].
    private val analytics: AnalyticsTracker,
) {
    suspend operator fun invoke(): Boolean {
        try {
            // Restore the per-instance Lava-Auth key onto the active endpoint BEFORE
            // anything reads it for search (existing installs whose persisted
            // settings.endpoint carries host:port but NO key — the key was never
            // packed into the Room Endpoint row, only into the same-session
            // settings.endpoint that a pre-2026-06-14 onboarding wrote without it).
            restoreActiveEndpointKey()
            // Heal the active endpoint BEFORE reading it (existing installs whose
            // onboarding pre-dates the 2026-06-14 settings.endpoint write).
            reconcileActiveEndpoint()
            val endpoint = runCatching { settingsRepository.getSettings().endpoint }.getOrNull()
            val goApi = endpoint as? Endpoint.GoApi ?: return false
            val apiBaseUrl = apiBaseUrlBuilder.build(goApi.host, goApi.port)
            return fetchWithRetry(apiBaseUrl, goApi).fold(
                onSuccess = { descriptors ->
                    // Set the ACTIVE lava-api-go base URL the registry's
                    // ApiBackedTrackerClient factory reads BEFORE populateFrom, so
                    // each dynamic client targets the chosen instance — identical
                    // ordering to OnboardingViewModel.fetchAndPopulateProviders.
                    // Pass the per-endpoint key too so the cold-start-built dynamic
                    // clients authenticate their /v1/{id}/{op} calls — otherwise a
                    // restart would 401 every search ("Something went wrong") even
                    // though the same-session onboarding worked (2026-06-14 fix).
                    activator.activate(apiBaseUrl, goApi.key)
                    trackerRegistry.populateFrom(descriptors)
                    true
                },
                // §6.AB: a fetch failure leaves the bundled registry intact (never a
                // blank list). NEVER throws — [fetchWithRetry] already captured the
                // failure into Result.failure (+ recorded it, LVA-094); we just
                // decline to repopulate.
                onFailure = { false },
            )
        } finally {
            // LVA-093: release any search awaiting readiness. Runs on EVERY exit
            // path of this function (early `return false`, success, failure, or
            // an unexpected throw) so the gate can never be left stuck open.
            providersReadyGate.markReady()
        }
    }

    /**
     * LVA-094: the catalogue fetch used to be a single attempt whose failure
     * was swallowed by the caller with `onFailure = { false }` — no
     * telemetry, no retry, degraded for the rest of the process lifetime with
     * no trace of WHY. This adds:
     *  1. a single retry attempt — a transient blip (the LAN link not fully
     *     up yet at cold start, a momentary DNS hiccup) resolves on the
     *     second try without any operator involvement;
     *  2. a §6.AC non-fatal recorded for EVERY failed attempt (including the
     *     final one), so the failure is visible in the telemetry feed instead
     *     of vanishing into a silently-returned `false`.
     * Still NEVER throws — mirrors [FetchProvidersUseCase]'s own contract.
     */
    private suspend fun fetchWithRetry(
        apiBaseUrl: String,
        goApi: Endpoint.GoApi,
    ): Result<List<RemoteTrackerDescriptor>> {
        val first = fetchProvidersUseCase(apiBaseUrl, goApi.key)
        if (first.isSuccess) return first
        recordFetchFailure(first.exceptionOrNull(), attempt = 1)

        val retry = fetchProvidersUseCase(apiBaseUrl, goApi.key)
        if (retry.isFailure) {
            recordFetchFailure(retry.exceptionOrNull(), attempt = 2)
        }
        return retry
    }

    /** §6.AC: records the startup catalogue-fetch failure so it is triageable
     * remotely instead of silently degrading with no trace. */
    private fun recordFetchFailure(cause: Throwable?, attempt: Int) {
        val error = cause
            ?: IllegalStateException("startup provider repopulation failed with no captured cause")
        analytics.recordNonFatal(
            error,
            mapOf(
                AnalyticsTracker.Params.FEATURE to "startup_provider_repopulate",
                AnalyticsTracker.Params.MODULE to "core:domain",
                AnalyticsTracker.Params.OPERATION to "fetchProviders",
                AnalyticsTracker.Params.ERROR to "startup_provider_repopulate_failed",
                AnalyticsTracker.Params.ERROR_CLASS to error::class.simpleName.orEmpty(),
                AnalyticsTracker.Params.ERROR_MESSAGE to (error.message?.take(512) ?: ""),
                ATTEMPT_PARAM to attempt.toString(),
            ),
        )
    }

    /**
     * Heal the active [settings.endpoint] for installs whose onboarding ran on a
     * build PRE-DATING the 2026-06-14 fix (when [OnboardingViewModel.onSelectApi]
     * began persisting the chosen API to `settings.endpoint`, not only the Room
     * endpoint list).
     *
     * Root cause of the operator-reported "search → problem reaching the trackers
     * in any scenario" (2026-06-14): the home search resolves its target host from
     * `settings.endpoint` (NetworkApiRepositoryImpl.endpoint()), which stayed at
     * the unreachable default `Endpoint.GoApi("lava-api.local")` because the old
     * onboarding never wrote it — while the user's REAL chosen server lived only in
     * the Room [EndpointsRepository] list. On-device Chucker proof: `/providers` →
     * &lt;lan-ip&gt; (200), `/search` → lava-api.local (UnknownHostException).
     *
     * Heal rule (conservative, literal-free — §6.R): if the persisted active
     * endpoint is NOT one of the user's actual added servers (no Room GoApi shares
     * its host:port) AND the Room list DOES contain a GoApi, adopt the most-recently
     * added Room GoApi as the active endpoint. This fires ONLY for the orphan-default
     * case: a freshly onboarded (post-fix) install has settings.endpoint already in
     * the Room list → no-op; a never-onboarded install has an empty Room list →
     * no-op. NEVER throws.
     */
    /**
     * Restore the per-instance `Lava-Auth` key onto the persisted active
     * [Endpoint.GoApi] for EXISTING installs whose key was never persisted.
     *
     * Root cause of the operator-reported cold-start search 401 ("Something went
     * wrong" / "problem reaching the trackers") on installs that onboarded BEFORE
     * the key was carried end-to-end: the per-instance key lives only on
     * `settings.endpoint` (via [lava.securestorage.model.EndpointConverter]) and
     * in the Room [EndpointsRepository] list — but the Room row packs only
     * host:port + platform/storage, NOT the key. So when [reconcileActiveEndpoint]
     * adopts a Room GoApi (existing-install heal path), or when an old onboarding
     * persisted a keyless `settings.endpoint`, the active endpoint is KEYLESS →
     * the dynamic `ApiBackedTrackerClient`s built at cold start have no
     * `Lava-Auth` value → `/v1/{provider}/search` returns 401.
     *
     * The key CAN be re-read at runtime from the on-device api-app's key
     * ContentProvider (the same source the onboarding handoff uses). The
     * [apiKeyProvider] seam does that read in `:app` (it owns the Android
     * ContentProvider authority); `:core:domain` stays Android-free.
     *
     * Restore rule (conservative, literal-free — §6.R): fires ONLY when the
     * active endpoint is an [Endpoint.GoApi] whose [Endpoint.GoApi.key] is
     * null/blank. For a freshly onboarded (post-fix) install the key is already
     * present → no-op. For a non-GoApi active endpoint → no-op. If the provider
     * returns null (api-app not co-installed, not running, or permission denied)
     * → no-op (search will still surface its own error; we never blank state).
     * NEVER throws. NEVER logs the key (§6.H).
     */
    private suspend fun restoreActiveEndpointKey() {
        runCatching {
            val active = settingsRepository.getSettings().endpoint as? Endpoint.GoApi ?: return
            if (!active.key.isNullOrBlank()) return
            val restored = apiKeyProvider.keyFor(active.host, active.port)
            if (!restored.isNullOrBlank()) {
                settingsRepository.setEndpoint(active.copy(key = restored))
            }
        }
    }

    private suspend fun reconcileActiveEndpoint() {
        runCatching {
            val active = settingsRepository.getSettings().endpoint as? Endpoint.GoApi
            val roomGoApis = endpointsRepository.observeAll().first()
                .filterIsInstance<Endpoint.GoApi>()
            if (roomGoApis.isEmpty()) return
            val activeIsKnownServer = active != null &&
                roomGoApis.any { it.host == active.host && it.port == active.port }
            if (!activeIsKnownServer) {
                // Adopt the onboarded server so search (settings.endpoint) targets
                // the right host:port + per-instance key instead of the orphan
                // default. distinctBy in EndpointsRepositoryImpl already de-dups by
                // server identity, so last() is the richest (keyed) entry.
                settingsRepository.setEndpoint(roomGoApis.last())
            }
        }
    }

    private companion object {
        /** §6.AC context key: which attempt (1st or 2nd/retry) failed. */
        const val ATTEMPT_PARAM = "attempt"
    }
}

/**
 * Thin seam that activates the chosen lava-api-go base URL for the dynamic
 * [lava.tracker.client.ApiBackedTrackerClient]s the registry builds.
 *
 * Production binding (in `:app`) delegates to
 * `lava.tracker.client.ApiBaseUrlHolder.set(baseUrl, key)` — keeping `:core:domain`
 * free of a `:core:tracker:client` dependency edge. Tests substitute a capturing
 * fake to assert the activation happened with the right base URL + key.
 */
fun interface ActiveApiBaseUrlActivator {
    fun activate(apiBaseUrl: String, key: String?)
}

/**
 * Thin seam that re-reads the per-instance `Lava-Auth` access key for an
 * on-device lava-api-go endpoint at app startup.
 *
 * Production binding (in `:app`) delegates to the api-app's key
 * ContentProvider via `digital.vasic.lava.client.handoff.ApiKeyClient` (the same
 * variant-aware authority `MainActivity.buildApiKeyReader()` uses) — keeping
 * `:core:domain` free of an Android ContentProvider dependency edge. Tests
 * substitute a fake returning a known key (or null) to assert the restore
 * happened — or did not, when the provider has nothing to give.
 *
 * Returns the access key for the running api-app instance, or `null` when the
 * api-app is not co-installed, not running, or the signature permission is
 * denied. The key is NEVER logged (§6.H).
 *
 * @param host the active endpoint's host (for a future multi-instance match; the
 *   current on-device api-app exposes a single loopback instance so the binding
 *   ignores it, but the seam carries it so the contract is host-aware).
 * @param port the active endpoint's port (same rationale as [host]).
 */
fun interface ApiKeyProvider {
    fun keyFor(host: String, port: Int): String?
}
