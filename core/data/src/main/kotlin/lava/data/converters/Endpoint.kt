package lava.data.converters

import lava.database.entity.EndpointEntity
import lava.models.settings.Endpoint
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * SP-3 (2026-04-29): adds the [Endpoint.GoApi] case. The Room schema does
 * not include a `port` column; rather than introduce a Room migration in
 * the same patch as the SP-3 wiring, the port is packed into the `host`
 * string as `host:port`. The id is qualified differently so a host/port
 * pair stored as a GoApi endpoint can never primary-key-collide with a
 * legacy Mirror at the same host.
 *
 * LVA-030 (2026-06-09): the additive [Endpoint.GoApi] fields `platform`,
 * `storage`, and `key` were SILENTLY DROPPED by this Room converter — only
 * `host:port` was persisted, so an on-device endpoint's per-instance `key`
 * (used by `AuthInterceptor` to send the right `Lava-Auth` value) and its
 * `platform`/`storage` labels vanished the next time the Connections LIST
 * was read back, breaking auth on that endpoint and mis-rendering its row.
 * (The preferences-side `lava.securestorage.model.EndpointConverter` — the
 * ACTIVE-endpoint persister — already round-trips all three fields; only the
 * Room-side LIST persister here lost them, so the active endpoint kept its
 * key while the same endpoint re-selected from the list did not.)
 *
 * Rather than add a Room migration (the table is still id/type/host), the
 * additive fields are appended to the packed `host` value after a `#`
 * sentinel as a percent-encoded query string (`host:port#k=…&p=…&s=…`).
 * Endpoints that carry NONE of the additive fields encode as the bare
 * `host:port` exactly as before, so every already-persisted row and the
 * existing id semantics stay byte-identical. The `#`/`=`/`&` characters
 * never appear in a host or port, so the split on the first `#` is
 * unambiguous; values are percent-encoded so a `key` containing those
 * characters round-trips intact.
 */
private const val GoApiExtrasSentinel = '#'
private const val GoApiKeyField = "k"
private const val GoApiPlatformField = "p"
private const val GoApiStorageField = "s"

private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun dec(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

/**
 * Pack a [Endpoint.GoApi] into the single `host` column. Bare `host:port`
 * when no additive field is set (back-compat), else `host:port#k=…&p=…&s=…`
 * with each present field percent-encoded. Field order is stable so the
 * packed value (and therefore the primary-key id) is deterministic.
 */
private fun Endpoint.GoApi.packHost(): String {
    val base = "$host:$port"
    val extras = buildList {
        key?.let { add("$GoApiKeyField=${enc(it)}") }
        platform?.let { add("$GoApiPlatformField=${enc(it)}") }
        storage?.let { add("$GoApiStorageField=${enc(it)}") }
    }
    return if (extras.isEmpty()) base else "$base$GoApiExtrasSentinel${extras.joinToString("&")}"
}

internal fun Endpoint.toEntity() = EndpointEntity(
    id = when (this) {
        is Endpoint.Rutracker -> "Rutracker"
        is Endpoint.Mirror -> "Mirror($host)"
        // Include the full packed value (additive fields included) so two
        // on-device endpoints at the same host:port differing only by key
        // do not collide on Room's PRIMARY KEY and lose one key on REPLACE.
        is Endpoint.GoApi -> "GoApi(${packHost()})"
    },
    type = when (this) {
        is Endpoint.Rutracker -> "Rutracker"
        is Endpoint.Mirror -> "Mirror"
        is Endpoint.GoApi -> "GoApi"
    },
    host = when (this) {
        is Endpoint.GoApi -> packHost()
        else -> host
    },
)

/**
 * SP-3.2 back-compat: a legacy `type=Proxy` row is migrated to
 * [Endpoint.Rutracker] on read, then dropped from the persisted set
 * the next time `EndpointsRepositoryImpl.observeAll()` reseeds.
 * Returning `null` would silently leak a no-longer-renderable entry
 * in the Connections list.
 */
internal fun EndpointEntity.toModel(): Endpoint? = when (type) {
    "Proxy" -> Endpoint.Rutracker
    "Rutracker" -> Endpoint.Rutracker
    "Mirror" -> Endpoint.Mirror(host)
    "GoApi" -> {
        // Split off the optional `#k=…&p=…&s=…` additive segment first; the
        // leading part is always the legacy `host:port` form, so legacy rows
        // (no `#`) parse byte-identically to the pre-LVA-030 converter.
        val sentinelIdx = host.indexOf(GoApiExtrasSentinel)
        val hostPort = if (sentinelIdx >= 0) host.substring(0, sentinelIdx) else host
        val extrasRaw = if (sentinelIdx >= 0) host.substring(sentinelIdx + 1) else ""

        val sep = hostPort.lastIndexOf(':')
        val h: String
        val p: Int
        if (sep > 0) {
            h = hostPort.substring(0, sep)
            p = hostPort.substring(sep + 1).toIntOrNull() ?: Endpoint.GoApi.DEFAULT_PORT
        } else {
            h = hostPort
            p = Endpoint.GoApi.DEFAULT_PORT
        }

        val fields = if (extrasRaw.isEmpty()) {
            emptyMap()
        } else {
            extrasRaw.split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq <= 0) null else pair.substring(0, eq) to dec(pair.substring(eq + 1))
            }.toMap()
        }

        Endpoint.GoApi(
            host = h,
            port = p,
            platform = fields[GoApiPlatformField],
            storage = fields[GoApiStorageField],
            key = fields[GoApiKeyField],
        )
    }
    else -> null
}
