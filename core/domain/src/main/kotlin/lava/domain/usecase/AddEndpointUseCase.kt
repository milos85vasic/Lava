package lava.domain.usecase

import lava.data.api.repository.EndpointsRepository
import lava.models.settings.Endpoint
import javax.inject.Inject

interface AddEndpointUseCase : suspend (String) -> Unit

class AddEndpointUseCaseImpl @Inject constructor(
    private val endpointsRepository: EndpointsRepository,
) : AddEndpointUseCase {
    override suspend operator fun invoke(endpoint: String) {
        endpointsRepository.add(Endpoint.Mirror(endpoint))
    }
}
