package digital.vasic.lava.client

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import digital.vasic.lava.client.handoff.ApiKeyClient
import lava.domain.usecase.ActiveApiBaseUrlActivator
import lava.domain.usecase.ApiKeyProvider
import lava.network.sse.SseBaseUrlBuilder
import lava.tracker.client.ApiBaseUrlHolder
import javax.inject.Singleton

/**
 * Production binding for [ActiveApiBaseUrlActivator] (operator directive
 * 2026-06-13: full provider availability after cold restart).
 *
 * [lava.domain.usecase.RepopulateProvidersOnStartupUseCase] re-activates the
 * persisted lava-api-go base URL before repopulating the registry. The
 * activation seam lives in `:core:domain` so that module need not depend on
 * `:core:tracker:client`; the concrete process-wide holder
 * ([ApiBaseUrlHolder]) lives in `:core:tracker:client`, which `:app` already
 * depends on — so the binding belongs here, exactly mirroring how
 * `OnboardingViewModel.fetchAndPopulateProviders` calls `ApiBaseUrlHolder.set`
 * before `populateFrom`.
 */
@Module
@InstallIn(SingletonComponent::class)
object StartupProvidersModule {

    @Provides
    @Singleton
    fun provideActiveApiBaseUrlActivator(): ActiveApiBaseUrlActivator =
        ActiveApiBaseUrlActivator { apiBaseUrl, key -> ApiBaseUrlHolder.set(apiBaseUrl, key) }

    /**
     * Singleton-scoped [SseBaseUrlBuilder] (the canonical `https://host:port`
     * builder, `SseBaseUrlBuilder.Https`). It was previously provided ONLY in
     * `feature/search_result`'s ViewModelComponent; [RepopulateProvidersOnStartupUseCase]
     * (injected into [LavaApplication] = SingletonComponent) also needs it to build
     * the lava-api-go base URL at cold start, so it must exist at Singleton scope.
     * ViewModelComponent inherits Singleton bindings, so SearchResultViewModel still
     * resolves it from here (the duplicate ViewModelComponent @Provides was removed).
     */
    @Provides
    fun provideSseBaseUrlBuilder(): SseBaseUrlBuilder = SseBaseUrlBuilder.Https

    /**
     * Production binding for [ApiKeyProvider] (2026-06-14 existing-install
     * key-restore for search). [RepopulateProvidersOnStartupUseCase] calls this
     * at cold start to re-read the per-instance `Lava-Auth` key for the active
     * on-device lava-api-go endpoint when its persisted key is null/blank
     * (existing installs whose key was never packed into the Room Endpoint row).
     *
     * Delegates to [ApiKeyClient] using the SAME variant-aware authority as
     * `MainActivity.buildApiKeyReader()` — `.dev.keyprovider` for debug,
     * `.keyprovider` for release — so no literal authority is embedded (§6.R).
     * The key is never logged (§6.H). Returns null when the api-app is absent,
     * the engine is not running, or the signature permission is denied — in
     * which case the use case leaves the endpoint keyless (no-op) rather than
     * blanking any state.
     *
     * The current on-device api-app exposes a single loopback instance, so the
     * host/port arguments are not used to disambiguate; the seam carries them so
     * a future multi-instance contract stays source-compatible.
     */
    @Provides
    @Singleton
    fun provideApiKeyProvider(
        @ApplicationContext context: Context,
    ): ApiKeyProvider {
        val suffix = if (BuildConfig.DEBUG) ".dev.keyprovider" else ".keyprovider"
        val authority = BuildConfig.API_RELEASE_PACKAGE + suffix
        val client = ApiKeyClient(context, authority)
        return ApiKeyProvider { _, _ -> client.read()?.key }
    }
}
