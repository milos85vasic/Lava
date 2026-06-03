package lava.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.testing.TestInstallIn
import lava.onboarding.OnboardingHiltModule
import javax.inject.Named

/**
 * Test-only Hilt override of [OnboardingHiltModule] (60th §6.L invocation,
 * 2026-05-18 Wave 2 — feature-flag back-compat for existing Challenges).
 *
 * Production binds `apiSelectionEnabled = true` so the new ApiSelection
 * step is the first user interaction after Welcome. Existing Compose UI
 * Challenge Tests (C00, C01, C20, C21, C24, C25) were written for the
 * pre-60th Welcome → Providers flow and would time out waiting for the
 * downstream UI they expect. Until Wave 3 lands updated Challenges,
 * instrumented tests use `apiSelectionEnabled = false` so the legacy
 * navigation is preserved end-to-end.
 *
 * Wave 3 plan (next cycle) will:
 *  1. Add Challenge26 that drives the new ApiSelection step under
 *     `apiSelectionEnabled = true` with a TestLocalNetworkDiscoveryService
 *     seeding deterministic hits + a deterministic isReachable fake.
 *  2. Update C00/C01/C20/C21/C24/C25 to traverse the new step.
 *  3. Remove this test override + remove the feature flag itself.
 */
@Module
@TestInstallIn(
    components = [ViewModelComponent::class],
    replaces = [OnboardingHiltModule::class],
)
object TestOnboardingHiltModule {

    @Provides
    @Named("apiSelectionEnabled")
    fun apiSelectionEnabled(): Boolean = false
}
