/*
 * Phase E (2026-06-02): custom AndroidJUnitRunner that swaps in
 * dagger.hilt.android.testing.HiltTestApplication so the @HiltAndroidTest
 * Challenge classes can inject. Without this runner, the production
 * `ApiApplication` (annotated `@HiltAndroidApp`) is started, which prevents
 * Hilt from rebuilding its component graph for the instrumented test
 * environment.
 *
 * Wired via `android.defaultConfig.testInstrumentationRunner =
 * "lava.api.app.LavaApiHiltTestRunner"` in :api-app/build.gradle.kts. This is
 * the canonical pattern documented in Hilt's testing guide
 * (https://dagger.dev/hilt/testing) and mirrors :app's LavaHiltTestRunner.
 */
package lava.api.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class LavaApiHiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
