package lava.api.app.service

/**
 * Advertises the running on-device API over mDNS so other LAN devices discover
 * it (the consuming side is `core/data`'s `LocalNetworkDiscoveryServiceImpl`,
 * which listens for the SAME service types this advertiser registers).
 *
 * Two service types, matching `DiscoveryServiceTypeCatalog` in `core/data`:
 *   - release build → `_lava-api._tcp` (engine=go)
 *   - debug build   → `_lava-api-dev._tcp` (engine=go-dev)
 *
 * The TXT records let the discovery side tag the engine authoritatively
 * (`engine` attribute) without relying on the service-type fallback.
 */
interface MdnsAdvertiser {
    /**
     * Registers the service on [port] with the build-appropriate service type
     * and TXT records. Idempotent at the impl level (a second register after a
     * prior register without unregister is a no-op or re-register).
     */
    fun register(port: Int)

    /** Unregisters any active advertisement. Safe to call when not registered. */
    fun unregister()
}

/**
 * The Engine identity advertised in the `engine` TXT record. Mirrors the
 * `engine=` values the discovery side parses (`go`, `go-dev`).
 */
enum class AdvertisedEngine(val txtValue: String) {
    GO("go"),
    GO_DEV("go-dev"),
}

/**
 * Service types this advertiser may register. Kept here (rather than depending
 * on `:core:data`) so `:api-app` does not pull in the entire client data layer;
 * the literals are the cross-process mDNS protocol contract, identical to
 * `DiscoveryServiceTypeCatalog.SERVICE_TYPE_GO` / `SERVICE_TYPE_GO_DEV`.
 */
object ApiServiceTypes {
    const val GO = "_lava-api._tcp"
    const val GO_DEV = "_lava-api-dev._tcp"
}

/** TXT attribute keys (the wire contract the discovery side reads). */
object ApiTxtKeys {
    const val ENGINE = "engine"
    const val PLATFORM = "platform"
    const val STORAGE = "storage"
    const val VERSION = "version"
}

/**
 * Pure builder for the mDNS TXT record map. Extracted so the wire contract is
 * unit-testable without an Android NsdManager.
 *
 * @param engine the engine identity (`go` / `go-dev`).
 * @param version the embed's reported version name (from `ApiStatus.version`).
 * @return the TXT map: `engine`, `platform=android`, `storage=sqlite`,
 *   `version=<n>`.
 */
fun buildTxtRecords(
    engine: AdvertisedEngine,
    version: String,
): Map<String, String> = linkedMapOf(
    ApiTxtKeys.ENGINE to engine.txtValue,
    ApiTxtKeys.PLATFORM to PLATFORM_ANDROID,
    ApiTxtKeys.STORAGE to STORAGE_SQLITE,
    ApiTxtKeys.VERSION to version,
)

/** The service type for the given [engine]. */
fun serviceTypeFor(engine: AdvertisedEngine): String = when (engine) {
    AdvertisedEngine.GO -> ApiServiceTypes.GO
    AdvertisedEngine.GO_DEV -> ApiServiceTypes.GO_DEV
}

private const val PLATFORM_ANDROID = "android"
private const val STORAGE_SQLITE = "sqlite"
