package digital.vasic.lava.client

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import digital.vasic.lava.client.handoff.ApiKeyClient
import lava.applink.CrossAppLauncher
import lava.applink.PackageManagerChecker
import lava.onboarding.OnboardingViewModel

/**
 * Provides the [CrossAppLauncher] and API-key-reader seam needed by
 * [OnboardingViewModel.onLaunchOnDeviceApi] and
 * [OnboardingViewModel.onOnDeviceApiReturned].
 *
 * This module lives in `:app` (not `:feature:onboarding`) because:
 *   1. [PackageManagerChecker] needs an Android [Context].
 *   2. [ApiKeyClient] needs the variant-aware authority from [BuildConfig],
 *      which is generated per-module and is only available in `:app`.
 *   3. The authority is composed from [BuildConfig.API_RELEASE_PACKAGE] +
 *      the debug suffix + ".keyprovider" — §6.R: no literals in source.
 *
 * Hilt aggregates all `@InstallIn(ViewModelComponent::class)` modules into
 * the app component, so [OnboardingViewModel] (in `:feature:onboarding`)
 * resolves the [CrossAppLauncher] and [apiKeyReader] bindings from here.
 * This is the same pattern used by [CloudApiModule] for `@Named("defaultCloudApi")`.
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

    /**
     * Returns a function that reads the API app's access key via
     * [ApiKeyClient]. The authority is variant-aware:
     *   release: [BuildConfig.API_RELEASE_PACKAGE] + ".keyprovider"
     *   debug:   [BuildConfig.API_RELEASE_PACKAGE] + ".dev.keyprovider"
     *
     * §6.R: no literal authority string — derived from [BuildConfig].
     *
     * The function ignores its [authority] argument and always uses the
     * variant-resolved authority from [BuildConfig]. The param exists for
     * test seam compatibility (tests pass any string; the lambda ignores it
     * and the fake replaces it entirely).
     */
    @Provides
    fun apiKeyReader(
        @ApplicationContext context: Context,
    ): (String) -> String? {
        val suffix = if (BuildConfig.DEBUG) ".dev.keyprovider" else ".keyprovider"
        val authority = BuildConfig.API_RELEASE_PACKAGE + suffix
        val client = ApiKeyClient(context, authority)
        return { _ -> client.read()?.key }
    }
}
