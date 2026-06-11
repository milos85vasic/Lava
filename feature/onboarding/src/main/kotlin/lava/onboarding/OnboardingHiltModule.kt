package lava.onboarding

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import javax.inject.Named

/**
 * Hilt bindings for the onboarding feature.
 *
 * 60th §6.L invocation (2026-05-18): provides the `apiSelectionEnabled`
 * Boolean flag the [OnboardingViewModel] needs. Tests bypass Hilt and
 * construct the VM directly, passing `false` to preserve pre-60th flow
 * assertions; production uses this binding (default `true`).
 *
 * @Named qualifier required so Hilt can distinguish from any other
 * Boolean injection point that might be added later.
 */
@Module
@InstallIn(ViewModelComponent::class)
object OnboardingHiltModule {

    @Provides
    @Named("apiSelectionEnabled")
    fun apiSelectionEnabled(): Boolean = true

    // NOTE (2026-06-11): the SseBaseUrlBuilder the OnboardingViewModel uses to
    // address the chosen lava-api-go instance is ALREADY provided in the
    // ViewModelComponent by SearchResultHiltModule.sseBaseUrlBuilder()
    // (SseBaseUrlBuilder.Https). Providing it here too is a Dagger duplicate
    // binding — so it is intentionally NOT re-bound. The OnboardingViewModel's
    // Kotlin default (SseBaseUrlBuilder.Https) keeps direct-construction tests
    // compiling; Hilt resolves the shared binding in production.
}
