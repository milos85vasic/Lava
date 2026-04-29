package lava.data.converters

import lava.database.entity.EndpointEntity
import lava.models.settings.Endpoint

/**
 * SP-3 (2026-04-29): adds the [Endpoint.GoApi] case. The Room schema does
 * not include a `port` column; rather than introduce a Room migration in
 * the same patch as the SP-3 wiring, the port is packed into the `host`
 * string as `host:port`. The id is qualified differently so a host/port
 * pair stored as a GoApi endpoint can never primary-key-collide with a
 * legacy Mirror at the same host.
 */
internal fun Endpoint.toEntity() = EndpointEntity(
    id = when (this) {
        is Endpoint.Proxy -> "Proxy"
        is Endpoint.Rutracker -> "Rutracker"
        is Endpoint.Mirror -> "Mirror($host)"
        is Endpoint.GoApi -> "GoApi($host:$port)"
    },
    type = when (this) {
        is Endpoint.Proxy -> "Proxy"
        is Endpoint.Rutracker -> "Rutracker"
        is Endpoint.Mirror -> "Mirror"
        is Endpoint.GoApi -> "GoApi"
    },
    host = when (this) {
        is Endpoint.GoApi -> "$host:$port"
        else -> host
    },
)

internal fun EndpointEntity.toModel(): Endpoint? = when (type) {
    "Proxy" -> Endpoint.Proxy
    "Rutracker" -> Endpoint.Rutracker
    "Mirror" -> Endpoint.Mirror(host)
    "GoApi" -> {
        val sep = host.lastIndexOf(':')
        if (sep > 0) {
            val h = host.substring(0, sep)
            val p = host.substring(sep + 1).toIntOrNull() ?: Endpoint.GoApi.DEFAULT_PORT
            Endpoint.GoApi(h, p)
        } else {
            // Defensive: an entity persisted without a port is still
            // recoverable via the spec default :8443.
            Endpoint.GoApi(host)
        }
    }
    else -> null
}
