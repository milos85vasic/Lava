package digital.vasic.lava.client

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import lava.applink.CrossAppLauncher
import lava.applink.PackageManagerChecker
import lava.onboarding.OnboardingViewModel

/**
 * Provides the [CrossAppLauncher] needed by
 * [OnboardingViewModel.onLaunchOnDeviceApi].
 *
 * This module lives in `:app` (not `:feature:onboarding`) because:
 *   1. [PackageManagerChecker] needs an Android [Context].
 *   2. [BuildConfig] is per-module — only `:app` has the app BuildConfig.
 *   3. The authority is composed from [BuildConfig.API_RELEASE_PACKAGE] +
 *      the debug suffix + ".keyprovider" — §6.R: no literals in source.
 *
 * Hilt aggregates all `@InstallIn(ViewModelComponent::class)` modules into
 * the app component, so [OnboardingViewModel] (in `:feature:onboarding`)
 * resolves the [CrossAppLauncher] binding from here.
 * This is the same pattern used by [CloudApiModule] for `@Named("defaultCloudApi")`.
 *
 * Note: [OnboardingViewModel.apiKeyReader] uses a Kotlin function type which
 * Hilt cannot inject directly (function types are not Dagger-injectable).
 * In production, [OnboardingViewModel] builds an [ApiKeyClient] internally
 * using the [apiKeyAuthority] String binding provided below. Tests pass a
 * direct lambda via the constructor default.
 *
 * Test override: instrumented tests that need a fake PackageChecker should
 * replace this module via `@TestInstallIn` (same pattern as
 * [lava.app.di.TestOnboardingHiltModule] for `apiSelectionEnabled`).
 */
@Module
@InstallIn(ViewModelComponent::class)
object OnboardingAppLinkModule {

    @Provides
    fun crossAppLauncher(
        @ApplicationContext context: Context,
    ): CrossAppLauncher = CrossAppLauncher(PackageManagerChecker(context))
}
