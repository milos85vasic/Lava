package digital.vasic.lava.client

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import lava.applink.PackageManagerSiblingAppLauncher
import lava.applink.SiblingAppLauncher

/**
 * Provides the [SiblingAppLauncher] needed by
 * [lava.onboarding.OnboardingViewModel.onLaunchOnDeviceApi].
 *
 * This module lives in `:app` (not `:feature:onboarding`) because:
 *   1. [android.content.pm.PackageManager] needs an Android [Context].
 *   2. [BuildConfig] is per-module — only `:app` has the app BuildConfig.
 *   3. [BuildConfig.API_TARGET_PACKAGE] is variant-aware (debug → .dev,
 *      release → base id) — §6.R: no literals in source.
 *   4. [BuildConfig.LAVA_API_APP_DOWNLOAD_URL] is from .env — §6.R.
 *
 * Both the API-app candidate package ids and the download URL are wired from
 * BuildConfig so the launcher never contains source literals (§6.R).
 *
 * The production variant-aware list: release id first (preferred), dev id
 * second. [PackageManagerSiblingAppLauncher] checks them in order and returns
 * the first installed one, so a device with both gets the release app.
 *
 * The fallback placeholder URL is used ONLY when LAVA_API_APP_DOWNLOAD_URL is
 * blank (CI / fresh checkout with no `.env`). It is the same placeholder
 * documented in [lava.applink.PackageManagerSiblingAppLauncher].
 */
@Module
@InstallIn(ViewModelComponent::class)
object OnboardingAppLinkModule {

    /** Placeholder for unconfigured builds (no .env). §6.R: constant, not secret. */
    private const val API_APP_FALLBACK_URL = "https://lava.app/download/api-app"

    @Provides
    fun siblingAppLauncherForApiApp(
        @ApplicationContext context: Context,
    ): SiblingAppLauncher = PackageManagerSiblingAppLauncher.from(
        context = context,
        // IMPORTANT-3a: release id first so release builds prefer the release
        // app; debug variant (.dev) is the fallback for dev devices.
        // §6.R: IDs come from BuildConfig, never from source literals.
        candidatePackageIds = listOf(
            BuildConfig.API_RELEASE_PACKAGE,
            BuildConfig.API_TARGET_PACKAGE,
        ).distinct(), // remove duplicate when release == target (release build)
        downloadUrl = BuildConfig.LAVA_API_APP_DOWNLOAD_URL,
        fallbackDownloadUrl = API_APP_FALLBACK_URL,
    )
}
