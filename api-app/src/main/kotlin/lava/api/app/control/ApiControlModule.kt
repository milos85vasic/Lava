package lava.api.app.control

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import lava.api.app.BuildConfig
import lava.api.app.auth.ApiKeyStore
import lava.api.app.auth.ApiKeyStoreImpl
import lava.api.app.net.NetworkInterfaceLanIpProvider
import lava.api.app.service.AdvertisedEngine
import lava.api.app.service.AndroidApiServiceStarter
import lava.api.app.service.ApiServiceStarter
import lava.api.app.service.MdnsAdvertiser
import lava.api.app.service.NsdMdnsAdvertiser
import lava.apiengine.ApiEngine
import lava.apiengine.NativeApiEngine
import lava.applink.PackageManagerSiblingAppLauncher
import lava.applink.SiblingAppLauncher
import javax.inject.Singleton

/**
 * Hilt wiring for the on-device API control surface.
 *
 * Provides the [ApiEngineController] as a `@Singleton` so the foreground
 * [lava.api.app.service.ApiEngineService] and the [ApiControlViewModel] share
 * the SAME instance + the same [ApiEngineController.state] StateFlow — the VM is
 * the lifecycle driver, the Service manages OS locks + the notification off that
 * shared state.
 *
 * §6.R/§6.H: no connection address, port, or credential literal appears here —
 * the bind address + port default live in [lava.apiengine.ApiConfig]; the auth
 * key is minted + persisted by [ApiKeyStore]; the SQLite path is derived from
 * the app's private files dir.
 */
@Module
@InstallIn(SingletonComponent::class)
interface ApiControlModule {

    @Binds
    fun bindServiceStarter(impl: AndroidApiServiceStarter): ApiServiceStarter

    companion object {
        private const val SQLITE_FILE = "lava-api.db"
        private const val DEV_SUFFIX = ".dev"

        /**
         * Placeholder download URL for the Lava client, used when
         * [BuildConfig.LAVA_CLIENT_APP_DOWNLOAD_URL] is blank (CI / fresh
         * checkout with no `.env`). §6.R: constant not secret.
         */
        private const val CLIENT_FALLBACK_URL = "https://lava.app/download/client"

        /**
         * Provides a [SiblingAppLauncher] for the Lava client app.
         *
         * Candidate ids: release first (preferred), dev second.
         * Download URL: from [BuildConfig.LAVA_CLIENT_APP_DOWNLOAD_URL] (.env)
         * — Firebase App Distribution, never `market://`.
         * §6.R: both ids are build constants (package ids, not secrets).
         */
        @Provides
        @Singleton
        fun provideClientLauncher(@ApplicationContext context: Context): SiblingAppLauncher {
            val isDev = context.packageName.endsWith(DEV_SUFFIX)
            val releaseId = BuildConfig.CLIENT_RELEASE_PACKAGE
            val devId = "$releaseId.dev"
            return PackageManagerSiblingAppLauncher.from(
                context = context,
                // On a debug api-app build prefer the .dev client; on release prefer release.
                candidatePackageIds = if (isDev) listOf(devId, releaseId) else listOf(releaseId, devId),
                downloadUrl = BuildConfig.LAVA_CLIENT_APP_DOWNLOAD_URL,
                fallbackDownloadUrl = CLIENT_FALLBACK_URL,
            )
        }

        @Provides
        @Singleton
        fun provideEngine(): ApiEngine = NativeApiEngine()

        @Provides
        @Singleton
        fun provideKeyStore(@ApplicationContext context: Context): ApiKeyStore =
            ApiKeyStoreImpl.create(context)

        @Provides
        @Singleton
        fun provideAdvertiser(
            @ApplicationContext context: Context,
            engine: ApiEngine,
        ): MdnsAdvertiser {
            val isDev = context.packageName.endsWith(DEV_SUFFIX)
            return NsdMdnsAdvertiser(
                context = context,
                engine = if (isDev) AdvertisedEngine.GO_DEV else AdvertisedEngine.GO,
                versionProvider = { engine.status().version },
            )
        }

        @Provides
        @Singleton
        fun provideController(
            @ApplicationContext context: Context,
            engine: ApiEngine,
            advertiser: MdnsAdvertiser,
            keyStore: ApiKeyStore,
        ): ApiEngineController = ApiEngineController(
            engine = engine,
            advertiser = advertiser,
            keyStore = keyStore,
            lanIpProvider = NetworkInterfaceLanIpProvider()::invoke,
            sqlitePathProvider = { context.filesDir.resolve(SQLITE_FILE).absolutePath },
        )
    }
}
