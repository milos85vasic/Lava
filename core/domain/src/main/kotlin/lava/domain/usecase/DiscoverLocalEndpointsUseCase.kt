package lava.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lava.data.api.repository.EndpointsRepository
import lava.data.api.repository.SettingsRepository
import lava.data.api.service.DiscoveredEndpoint
import lava.data.api.service.LocalNetworkDiscoveryService
import lava.dispatchers.api.Dispatchers
import lava.models.settings.Endpoint
import javax.inject.Inject

interface DiscoverLocalEndpointsUseCase : suspend () -> DiscoverLocalEndpointsResult

/**
 * Result of the discover-LAN flow.
 *
 * SP-3 widened [Discovered] / [AlreadyConfigured] to carry any [Endpoint]
 * (was [Endpoint.Mirror]-only) so a discovered Go API endpoint can flow
 * through to the connection screen and be selected without
 * down-converting to a Mirror that would route through the wrong
 * scheme/port.
 */
sealed interface DiscoverLocalEndpointsResult {
    data class Discovered(val endpoint: Endpoint) : DiscoverLocalEndpointsResult
    data object NotFound : DiscoverLocalEndpointsResult
    data class AlreadyConfigured(val endpoint: Endpoint) : DiscoverLocalEndpointsResult
}

class DiscoverLocalEndpointsUseCaseImpl @Inject constructor(
    private val discoveryService: LocalNetworkDiscoveryService,
    private val endpointsRepository: EndpointsRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: Dispatchers,
) : DiscoverLocalEndpointsUseCase {

    override suspend fun invoke(): DiscoverLocalEndpointsResult = withContext(dispatchers.io) {
        val discovered = withTimeoutOrNull(5_000) {
            discoveryService.discover().firstOrNull()
        } ?: return@withContext DiscoverLocalEndpointsResult.NotFound

        val endpoint = discovered.toEndpoint()
        val existing = endpointsRepository.observeAll().first()
        val alreadyInRepo = existing.any { it == endpoint }

        if (alreadyInRepo) {
            val currentEndpoint = settingsRepository.getSettings().endpoint
            return@withContext if (currentEndpoint == endpoint) {
                DiscoverLocalEndpointsResult.AlreadyConfigured(endpoint)
            } else {
                // Endpoint exists but is not selected — select it and report discovery
                settingsRepository.setEndpoint(endpoint)
                DiscoverLocalEndpointsResult.Discovered(endpoint)
            }
        }

        endpointsRepository.add(endpoint)

        val currentEndpoint = settingsRepository.getSettings().endpoint
        if (shouldOverrideExistingSelection(currentEndpoint)) {
            settingsRepository.setEndpoint(endpoint)
        }

        DiscoverLocalEndpointsResult.Discovered(endpoint)
    }

    /**
     * Convert an mDNS hit into the right typed [Endpoint] variant.
     *
     * Engine=Go → [Endpoint.GoApi] with the published port from the
     * service advertisement.
     * Engine=Ktor or Unknown → [Endpoint.Mirror] with the BARE host (no
     * embedded port). `NetworkApiRepositoryImpl` routes [Endpoint.Mirror]
     * on a LAN address through `proxyUrl(host, path)`, which appends the
     * legacy Ktor proxy port held in `LEGACY_LAN_PROXY_PORT` (see
     * `core/network/impl/.../NetworkApiRepositoryImpl.kt`).
     *
     * SP-3.3 (2026-04-29) forensic anchor: prior to this commit, the
     * `host` field of [DiscoveredEndpoint] carried the `ip:port` string
     * straight through into [Endpoint.Mirror.host]. That broke routing
     * (Ktor's URL builder treats `ip:port` as a hostname with embedded
     * colon) and fed the user-visible "Mirror has no green icon" symptom
     * because [lava.data.api.service.ConnectionService] then probed the
     * wrong target. Strip the port at conversion time so persisted rows
     * are well-formed.
     */
    private fun DiscoveredEndpoint.toEndpoint(): Endpoint = when (engine) {
        // GoDev shares the GoApi endpoint shape — same protocol (HTTPS),
        // same TLS expectation, just a different host:port. Only debug
        // builds reach this branch (release builds never subscribe to
        // _lava-api-dev._tcp; see DiscoveryServiceTypesModule in :app).
        DiscoveredEndpoint.Engine.Go,
        DiscoveredEndpoint.Engine.GoDev,
        -> Endpoint.GoApi(
            host = host.substringBeforeLast(":").ifEmpty { host },
            port = port,
        )
        DiscoveredEndpoint.Engine.Ktor,
        DiscoveredEndpoint.Engine.Unknown,
        -> Endpoint.Mirror(host = host.substringBeforeLast(":").ifEmpty { host })
    }

    /**
     * Whether to forcibly select the newly discovered endpoint over the
     * current one. Preserves user intent: only override if the user
     * hadn't yet picked a LAN endpoint (their current selection is
     * Proxy, Rutracker, or a remote rutracker mirror).
     */
    private fun shouldOverrideExistingSelection(currentEndpoint: Endpoint): Boolean {
        return when (currentEndpoint) {
            is Endpoint.Rutracker -> true
            is Endpoint.Mirror -> currentEndpoint.host.contains("rutracker")
            is Endpoint.GoApi -> false
        }
    }
}
