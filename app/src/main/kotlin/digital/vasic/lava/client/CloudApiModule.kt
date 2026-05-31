package digital.vasic.lava.client

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import javax.inject.Named

/**
 * Provides the onboarding "Choose your API" Cloud-section default origin
 * (2026-05-31 operator request) into the ViewModel graph.
 *
 * §6.R compliance: the value originates in the gitignored `.env`
 * (`LAVA_DEFAULT_CLOUD_API`, placeholder `https://lava.app:7777` documented in
 * `.env.example`), is baked into `digital.vasic.lava.client.BuildConfig
 * .DEFAULT_CLOUD_API` by the app `buildConfigField` (no source literal), and is
 * surfaced here as a Hilt binding. This module lives in `:app` because
 * `BuildConfig` is per-module — the feature module cannot see the app's
 * `BuildConfig`. Hilt aggregates all `@InstallIn(ViewModelComponent::class)`
 * modules into the app's component, so `OnboardingViewModel` (in
 * `:feature:onboarding`) resolves the `@Named("defaultCloudApi")` injection from
 * this app-side provider.
 *
 * Empty string when unconfigured — `CloudApiDefaults.defaultsFrom` then yields
 * no preset and the cloud section renders only the manual-entry field.
 */
@Module
@InstallIn(ViewModelComponent::class)
object CloudApiModule {

    @Provides
    @Named("defaultCloudApi")
    fun defaultCloudApi(): String = BuildConfig.DEFAULT_CLOUD_API
}
