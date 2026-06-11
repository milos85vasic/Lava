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
 *
 * Per-class override (2026-06-11, dynamic provider discovery C39/C40): a
 * single global `@TestInstallIn` cannot vary the bound value per test class,
 * so the bound value is read from [ApiSelectionTestFlag] — a settable holder.
 * It DEFAULTS to `false` (preserving the legacy-flow Challenges above);
 * Challenge39/Challenge40, which REQUIRE the ApiSelection step to reach
 * "Choose your API", flip it to `true` in their setup before the activity
 * composes. The holder is reset between tests so the default is restored.
 */
@Module
@TestInstallIn(
    components = [ViewModelComponent::class],
    replaces = [OnboardingHiltModule::class],
)
object TestOnboardingHiltModule {

    @Provides
    @Named("apiSelectionEnabled")
    fun apiSelectionEnabled(): Boolean = ApiSelectionTestFlag.enabled

    // NOTE: this @TestInstallIn replaces ONLY OnboardingHiltModule. The
    // SseBaseUrlBuilder the OnboardingViewModel needs is provided by
    // SearchResultHiltModule (still installed in androidTest), so it is NOT
    // re-provided here — re-binding it would be a Dagger duplicate.
}

/**
 * Per-test-class control of the `apiSelectionEnabled` Hilt flag bound by
 * [TestOnboardingHiltModule]. Defaults to `false` (legacy Welcome → Providers
 * flow the C00/C01/C20/… Challenges assert against); C39/C40 set it `true` to
 * exercise the production Welcome → ApiSelection → Providers flow, then reset.
 *
 * A plain mutable holder (not a Hilt binding) because a single global
 * `@TestInstallIn` replacement module cannot vary its provided value per class.
 */
object ApiSelectionTestFlag {
    @Volatile
    var enabled: Boolean = false

    fun reset() {
        enabled = false
    }
}
