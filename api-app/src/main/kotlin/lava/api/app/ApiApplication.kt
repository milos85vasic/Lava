package lava.api.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import lava.api.app.auth.ApiKeyStore
import lava.api.app.control.ApiEngineController
import javax.inject.Inject

/**
 * Application entry point for the standalone Lava API app.
 *
 * Hilt root. Also publishes the singleton [ApiEngineController] and [ApiKeyStore]
 * into process-wide companion holders so [lava.api.app.handoff.ApiKeyProvider]
 * can reach them without requiring the ContentProvider to be
 * `@AndroidEntryPoint` (ContentProviders run before activities/services and
 * adding Hilt entry-point wiring there adds lifecycle overhead for a read-only
 * provider). The holder pattern is the same as how other Lava modules expose
 * singletons to non-Hilt contexts.
 *
 * §6.H: [keyStoreHolder] is set by Hilt injection during [onCreate]; it is
 * never exposed via a public getter that returns the raw key — consumers call
 * `keyStore.getOrCreate()` which handles concurrency and §6.H hygiene
 * internally. The holder itself is `@Volatile` for safe publication across
 * threads (ContentProvider.onCreate() may be called from a non-main thread on
 * some API levels).
 */
@HiltAndroidApp
class ApiApplication : Application() {

    @Inject
    lateinit var controller: ApiEngineController

    @Inject
    lateinit var keyStore: ApiKeyStore

    override fun onCreate() {
        super.onCreate()
        controllerHolder = controller
        keyStoreHolder = keyStore
    }

    companion object {
        @Volatile
        var controllerHolder: ApiEngineController? = null
            private set

        @Volatile
        var keyStoreHolder: ApiKeyStore? = null
            private set
    }
}
