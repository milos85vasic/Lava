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
import javax.inject.Named

/**
 * Provides the [CrossAppLauncher] and variant-aware package bindings needed
 * by [OnboardingViewModel.onLaunchOnDeviceApi].
 *
 * This module lives in `:app` (not `:feature:onboarding`) because:
 *   1. [PackageManagerChecker] needs an Android [Context].
 *   2. [BuildConfig] is per-module — only `:app` has the app BuildConfig.
 *   3. [BuildConfig.API_TARGET_PACKAGE] is variant-aware (debug → .dev,
 *      release → base id) — §6.R: no literals in source.
 *
 * Hilt aggregates all `@InstallIn(ViewModelComponent::class)` modules into
 * the app component, so [OnboardingViewModel] (in `:feature:onboarding`)
 * resolves these bindings from here.
 * This is the same pattern used by [CloudApiModule] for `@Named("defaultCloudApi")`.
 *
 * IMPORTANT-3a fix: [apiTargetPackage] uses [BuildConfig.API_TARGET_PACKAGE]
 * (debug = `digital.vasic.lava.api.dev`, release = `digital.vasic.lava.api`)
 * so a debug client launches the installed .dev API app instead of redirecting
 * to the Play Store for the release app.
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
     * IMPORTANT-3a: variant-aware target package for the API app.
     * debug build → "digital.vasic.lava.api.dev" (side-loaded .dev app)
     * release build → "digital.vasic.lava.api" (Play Store app)
     *
     * §6.R: value comes from [BuildConfig.API_TARGET_PACKAGE] which is set
     * per build type in app/build.gradle.kts — never a literal in source.
     */
    @Provides
    @Named("apiTargetPackage")
    fun apiTargetPackage(): String = BuildConfig.API_TARGET_PACKAGE
}
