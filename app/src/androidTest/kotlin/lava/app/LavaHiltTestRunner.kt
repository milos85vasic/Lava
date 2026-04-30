/*
 * SP-3a Step 6 (2026-04-30): custom AndroidJUnitRunner that swaps in
 * dagger.hilt.android.testing.HiltTestApplication so @HiltAndroidTest
 * classes can inject. Without this runner, the production
 * `LavaApplication` (annotated `@HiltAndroidApp`) is started, which
 * prevents Hilt from rebuilding its component graph for the
 * instrumented test environment.
 *
 * Wired via `android.defaultConfig.testInstrumentationRunner =
 * "lava.app.LavaHiltTestRunner"` in :app/build.gradle.kts. This is the
 * canonical pattern documented in Hilt's testing guide (see
 * https://dagger.dev/hilt/testing).
 */
package lava.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

class LavaHiltTestRunner : AndroidJUnitRunner() {

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
