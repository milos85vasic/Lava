package digital.vasic.lava.client

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import lava.domain.usecase.ActiveApiBaseUrlActivator
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
}
