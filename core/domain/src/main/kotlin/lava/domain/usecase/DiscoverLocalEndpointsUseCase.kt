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
     * Engine=Go → [Endpoint.GoApi] with the published port (default 8443).
     * Engine=Ktor or Unknown → [Endpoint.Mirror] using the host:port form,
     * which `NetworkApiRepositoryImpl` routes through the legacy LAN proxy
     * code-path. The DiscoveredEndpoint.host already carries the
     * `host:port` string from `LocalNetworkDiscoveryServiceImpl`.
     */
    private fun DiscoveredEndpoint.toEndpoint(): Endpoint = when (engine) {
        DiscoveredEndpoint.Engine.Go -> Endpoint.GoApi(
            host = host.substringBeforeLast(":").ifEmpty { host },
            port = port,
        )
        DiscoveredEndpoint.Engine.Ktor,
        DiscoveredEndpoint.Engine.Unknown,
        -> Endpoint.Mirror(host)
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
