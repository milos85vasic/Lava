package lava.data.api.service

import javax.inject.Qualifier

/**
 * Hilt qualifier for the list of mDNS service types the discovery service
 * subscribes to. The app layer provides a `List<String>` based on its
 * build flavor — see [DiscoveryServiceTypeCatalog] for the canonical sets.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DiscoveryServiceTypes

/**
 * Canonical mDNS service-type sets exposed by the api layer so the app
 * module can select between them based on its build flavor without
 * needing visibility into the internal Impl.
 *
 * - [SERVICE_TYPES_RELEASE]: subscribed by ALL builds — the legacy Ktor
 *   proxy + the production Go API. A stray DEV advertiser on a
 *   production user's LAN MUST NOT be subscribed to here.
 * - [SERVICE_TYPES_DEBUG]: release set + the developer DEV instance
 *   advertised by a side-by-side lava-api-go process on a different
 *   port (typically 8543; see `docker-compose.dev.yml`).
 */
object DiscoveryServiceTypeCatalog {
    const val SERVICE_TYPE_KTOR = "_lava._tcp"
    const val SERVICE_TYPE_GO = "_lava-api._tcp"
    const val SERVICE_TYPE_GO_DEV = "_lava-api-dev._tcp"

    val SERVICE_TYPES_RELEASE: List<String> = listOf(SERVICE_TYPE_KTOR, SERVICE_TYPE_GO)
    val SERVICE_TYPES_DEBUG: List<String> = SERVICE_TYPES_RELEASE + SERVICE_TYPE_GO_DEV
}
