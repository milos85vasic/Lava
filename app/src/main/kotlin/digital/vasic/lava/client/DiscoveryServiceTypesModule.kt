package digital.vasic.lava.client

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.data.api.service.DiscoveryServiceTypeCatalog
import lava.data.api.service.DiscoveryServiceTypes
import javax.inject.Singleton

/**
 * Provides the mDNS service-type list the discovery service subscribes to.
 *
 * The list is selected from the canonical constants in
 * `LocalNetworkDiscoveryServiceImpl` based on `BuildConfig.DEBUG`:
 *
 * - **Debug builds** (applicationIdSuffix `.dev`) get
 *   `SERVICE_TYPES_DEBUG` which adds `_lava-api-dev._tcp` to the
 *   release set. This lets a developer run a parallel lava-api-go
 *   process advertising the dev type (typically on port 8543 — see
 *   `docker-compose.dev.yml`) and have only the debug-flavored APK
 *   discover it.
 * - **Release builds** get `SERVICE_TYPES_RELEASE` and ignore any
 *   `_lava-api-dev._tcp` advertisement on the LAN, so a stray DEV
 *   advertiser on a production user's network cannot redirect their
 *   traffic to an unintended endpoint.
 */
@Module
@InstallIn(SingletonComponent::class)
object DiscoveryServiceTypesModule {

    @Provides
    @Singleton
    @DiscoveryServiceTypes
    fun provideDiscoveryServiceTypes(): List<String> = if (BuildConfig.DEBUG) {
        DiscoveryServiceTypeCatalog.SERVICE_TYPES_DEBUG
    } else {
        DiscoveryServiceTypeCatalog.SERVICE_TYPES_RELEASE
    }
}
