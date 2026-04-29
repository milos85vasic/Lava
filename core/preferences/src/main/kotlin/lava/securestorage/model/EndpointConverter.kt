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
                is Endpoint.Proxy -> put(TypeKey, "Proxy")
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

    fun fromJson(json: String): Endpoint? {
        return runCatching {
            JSONObject(json).let { jsonObject ->
                when (jsonObject.getString(TypeKey)) {
                    "Proxy" -> Endpoint.Proxy
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
