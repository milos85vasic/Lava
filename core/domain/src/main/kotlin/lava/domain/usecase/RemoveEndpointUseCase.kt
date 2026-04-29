package lava.domain.usecase

import lava.data.api.repository.EndpointsRepository
import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import javax.inject.Inject

interface RemoveEndpointUseCase : suspend (Endpoint) -> Unit

class RemoveEndpointUseCaseImpl @Inject constructor(
    private val endpointsRepository: EndpointsRepository,
    private val settingsRepository: SettingsRepository,
) : RemoveEndpointUseCase {
    override suspend operator fun invoke(endpoint: Endpoint) {
        endpointsRepository.remove(endpoint)
        if (settingsRepository.getSettings().endpoint == endpoint) {
            // SP-3.2 (2026-04-29): fall back to the rutracker direct
            // connection since `Endpoint.Proxy` was removed from the model.
            settingsRepository.setEndpoint(Endpoint.Rutracker)
        }
    }
}
