package lava.securestorage.model

import lava.models.settings.Endpoint
import org.json.JSONObject

internal object EndpointConverter {
    private const val TypeKey = "type"
    private const val HostKey = "host"
    private const val PortKey = "port"

    // Sub-project 2 (on-device API): additive keys for the mDNS-advertised
    // platform/storage TXT attributes carried on Endpoint.GoApi. Written only
    // when non-null so legacy GoApi rows stay byte-identical; read with
    // opt-null fallback so legacy rows deserialize unchanged.
    private const val PlatformKey = "platform"
    private const val StorageKey = "storage"

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
                    // Only emit when present so legacy GoApi rows (which never
                    // carried these) round-trip byte-identically.
                    platform?.let { put(PlatformKey, it) }
                    storage?.let { put(StorageKey, it) }
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
                        // Sub-project 2 additive fields; legacy rows lack them →
                        // null (rendered exactly as a host/server instance).
                        // Use has()+getString rather than optString(key, null)
                        // because org.json's two-arg optString returns "" (not
                        // null) for a missing key, which would mis-classify a
                        // legacy host row as carrying an empty platform.
                        platform = if (jsonObject.has(PlatformKey)) jsonObject.getString(PlatformKey) else null,
                        storage = if (jsonObject.has(StorageKey)) jsonObject.getString(StorageKey) else null,
                    )
                    else -> null
                }
            }
        }.getOrNull()
    }
}
