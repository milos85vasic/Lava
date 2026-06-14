package lava.search.result

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * Hilt bindings for the search-result feature.
 *
 * Empty by design: the multi-provider search path
 * ([SearchResultViewModel.observeStreamMultiSearch]) drives the
 * [lava.tracker.client.LavaTrackerSdk] directly, and `SseBaseUrlBuilder`
 * is provided app-wide at SingletonComponent scope
 * (app/StartupProvidersModule) — ViewModelComponent inherits it, so a
 * duplicate binding here would be a Hilt double-binding error. The module
 * is retained as the feature's binding anchor for future ViewModel-scoped
 * dependencies.
 */
@Module
@InstallIn(ViewModelComponent::class)
object SearchResultHiltModule
