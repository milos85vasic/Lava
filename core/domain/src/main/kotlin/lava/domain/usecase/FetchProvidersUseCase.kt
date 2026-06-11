package lava.domain.usecase

import lava.data.provider.ProviderCatalogRepository
import lava.tracker.api.RemoteTrackerDescriptor
import javax.inject.Inject

/**
 * Dynamic Provider Discovery (2026-06-11, spec §4.2 / plan Task 3.3).
 *
 * Domain entry point for fetching the provider catalogue from the chosen API
 * instance after the onboarding ApiSelection connectivity probe succeeds.
 * Thin wrapper over [ProviderCatalogRepository]; preserves the
 * `Result<List<RemoteTrackerDescriptor>>` contract so the caller can render the
 * dynamic list on success and fall back to the bundled descriptors on failure
 * (spec §5) — never throwing.
 */
class FetchProvidersUseCase @Inject constructor(
    private val repository: ProviderCatalogRepository,
) {
    suspend operator fun invoke(
        apiBaseUrl: String,
        authKey: String? = null,
    ): Result<List<RemoteTrackerDescriptor>> =
        repository.fetchProviders(apiBaseUrl, authKey)
}
