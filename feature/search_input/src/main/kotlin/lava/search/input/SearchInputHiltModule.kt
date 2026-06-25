package lava.search.input

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import lava.tracker.client.LavaTrackerSdk

/**
 * Hilt bindings for the search-input feature.
 *
 * 2026-06-25 video-cluster root-cause fix. Provides the
 * [ProviderDisplayNameResolver] backed by the live tracker registry
 * (`LavaTrackerSdk.listAvailableTrackers()`) so the chip bar shows the
 * human-readable display name of each ONBOARDED provider. The SDK is wired
 * app-wide at SingletonComponent scope; ViewModelComponent inherits it.
 */
@Module
@InstallIn(ViewModelComponent::class)
object SearchInputHiltModule {

    @Provides
    internal fun provideProviderDisplayNameResolver(
        sdk: LavaTrackerSdk,
    ): ProviderDisplayNameResolver = object : ProviderDisplayNameResolver {
        override fun displayNames(): Map<String, String> =
            sdk.listAvailableTrackers().associate { it.trackerId to it.displayName }
    }
}
