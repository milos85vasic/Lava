package lava.domain.usecase

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import lava.data.api.repository.EndpointsRepository
import lava.data.api.repository.SettingsRepository
import lava.data.api.service.LocalNetworkDiscoveryService
import lava.dispatchers.api.Dispatchers
import lava.models.settings.Endpoint
import javax.inject.Inject

interface DiscoverLocalEndpointsUseCase : suspend () -> DiscoverLocalEndpointsResult

sealed interface DiscoverLocalEndpointsResult {
    data class Discovered(val endpoint: Endpoint.Mirror) : DiscoverLocalEndpointsResult
    data object NotFound : DiscoverLocalEndpointsResult
    data object AlreadyConfigured : DiscoverLocalEndpointsResult
}

class DiscoverLocalEndpointsUseCaseImpl @Inject constructor(
    private val discoveryService: LocalNetworkDiscoveryService,
    private val endpointsRepository: EndpointsRepository,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: Dispatchers,
    private val discoveryTimeoutMs: Long = 5_000,
) : DiscoverLocalEndpointsUseCase {

    override suspend fun invoke(): DiscoverLocalEndpointsResult = withContext(dispatchers.io) {
        val discovered = withTimeoutOrNull(discoveryTimeoutMs) {
            discoveryService.discover().firstOrNull()
        } ?: return@withContext DiscoverLocalEndpointsResult.NotFound

        val mirror = Endpoint.Mirror(discovered.host)

        val existing = endpointsRepository.observeAll().first()
        if (existing.any { it == mirror }) {
            return@withContext DiscoverLocalEndpointsResult.AlreadyConfigured
        }

        endpointsRepository.add(mirror)

        val currentSettings = settingsRepository.getSettings()
        val currentEndpoint = currentSettings.endpoint
        if (currentEndpoint !is Endpoint.Mirror || currentEndpoint.host.contains("rutracker")) {
            settingsRepository.setEndpoint(mirror)
        }

        DiscoverLocalEndpointsResult.Discovered(mirror)
    }
}
