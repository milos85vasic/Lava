package lava.search.result

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import lava.network.sse.SseBaseUrlBuilder
import lava.network.sse.SseClientFactory

/**
 * LVA-071 (2026-06-09). Hilt bindings for the search-result feature.
 *
 * Provides the [SseClientFactory] + [SseBaseUrlBuilder] that
 * [SearchResultViewModel.observeSseSearch] consumes via constructor
 * injection. Production wiring is unchanged behaviour: the default factory
 * yields a fresh `SseClient()` (the same call the VM made inline before
 * this cycle) and the HTTPS base-URL builder produces the exact
 * `https://host:port` URL the VM built inline before. The injection seam
 * exists so the SSE error → Error → retry path can be driven hermetically
 * against a `MockWebServer` (see `SearchResultSseErrorRetryTest`).
 *
 * Tests bypass Hilt and construct the VM directly, passing fakes for both
 * dependencies — exactly the OnboardingHiltModule pattern.
 */
@Module
@InstallIn(ViewModelComponent::class)
object SearchResultHiltModule {

    @Provides
    fun sseClientFactory(): SseClientFactory = SseClientFactory.Default

    @Provides
    fun sseBaseUrlBuilder(): SseBaseUrlBuilder = SseBaseUrlBuilder.Https
}
