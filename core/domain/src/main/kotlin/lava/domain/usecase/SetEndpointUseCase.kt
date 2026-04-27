package lava.domain.usecase

import lava.data.api.repository.SettingsRepository
import lava.models.settings.Endpoint
import javax.inject.Inject

interface SetEndpointUseCase : suspend (Endpoint) -> Unit

class SetEndpointUseCaseImpl @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : SetEndpointUseCase {
    override suspend operator fun invoke(endpoint: Endpoint) {
        settingsRepository.setEndpoint(endpoint)
    }
}
