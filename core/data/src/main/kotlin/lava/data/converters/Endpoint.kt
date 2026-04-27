package lava.data.converters

import lava.database.entity.EndpointEntity
import lava.models.settings.Endpoint

internal fun Endpoint.toEntity() = EndpointEntity(
    id = when (this) {
        is Endpoint.Proxy -> "Proxy"
        is Endpoint.Rutracker -> "Rutracker"
        is Endpoint.Mirror -> "Mirror($host)"
    },
    type = when (this) {
        is Endpoint.Proxy -> "Proxy"
        is Endpoint.Rutracker -> "Rutracker"
        is Endpoint.Mirror -> "Mirror"
    },
    host = host,
)

internal fun EndpointEntity.toModel() = when (type) {
    "Proxy" -> Endpoint.Proxy
    "Rutracker" -> Endpoint.Rutracker
    "Mirror" -> Endpoint.Mirror(host)
    else -> null
}
