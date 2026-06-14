package lava.domain.usecase

import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import lava.network.sse.SseBaseUrlBuilder
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
) {
    suspend operator fun invoke(): Boolean {
        val endpoint = runCatching { settingsRepository.getSettings().endpoint }.getOrNull()
        val goApi = endpoint as? Endpoint.GoApi ?: return false
        val apiBaseUrl = apiBaseUrlBuilder.build(goApi.host, goApi.port)
        return fetchProvidersUseCase(apiBaseUrl, goApi.key).fold(
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
            // blank list). NEVER throws — FetchProvidersUseCase already captured
            // the failure into Result.failure; we just decline to repopulate.
            onFailure = { false },
        )
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
