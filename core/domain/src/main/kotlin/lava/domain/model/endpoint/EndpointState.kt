package lava.domain.model.endpoint

import lava.models.settings.Endpoint

data class EndpointState(
    val endpoint: Endpoint,
    val selected: Boolean,
    val status: EndpointStatus,
)
