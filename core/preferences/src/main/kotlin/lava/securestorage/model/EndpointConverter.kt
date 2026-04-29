package lava.securestorage.model

import lava.models.settings.Endpoint
import org.json.JSONObject

internal object EndpointConverter {
    private const val TypeKey = "type"
    private const val HostKey = "host"
    private const val PortKey = "port"

    fun Endpoint.toJson(): String {
        return JSONObject().apply {
            when (this@toJson) {
                is Endpoint.Rutracker -> put(TypeKey, "Rutracker")
                is Endpoint.Mirror -> {
                    put(TypeKey, "Mirror")
                    put(HostKey, host)
                }
                is Endpoint.GoApi -> {
                    put(TypeKey, "GoApi")
                    put(HostKey, host)
                    put(PortKey, port)
                }
            }
        }.toString()
    }

    /**
     * SP-3.2 back-compat: a legacy persisted record with `type=Proxy`
     * is migrated to `Endpoint.Rutracker` (the new default), since
     * `Endpoint.Proxy` no longer exists in the model. Returning `null`
     * for the legacy record would surface as a missing-default crash
     * the next time the user opens the app after upgrade — bad UX.
     * Mapping to Rutracker keeps the user's app usable; they can pick
     * a different endpoint via the Connections screen.
     */
    fun fromJson(json: String): Endpoint? {
        return runCatching {
            JSONObject(json).let { jsonObject ->
                when (jsonObject.getString(TypeKey)) {
                    "Proxy" -> Endpoint.Rutracker
                    "Rutracker" -> Endpoint.Rutracker
                    "Mirror" -> Endpoint.Mirror(jsonObject.getString(HostKey))
                    "GoApi" -> Endpoint.GoApi(
                        host = jsonObject.getString(HostKey),
                        // Tolerate older persisted records that pre-date SP-3 and
                        // omit the port: fall back to the default :8443.
                        port = jsonObject.optInt(PortKey, Endpoint.GoApi.DEFAULT_PORT),
                    )
                    else -> null
                }
            }
        }.getOrNull()
    }
}
