package lava.api.app

import android.app.Application
import android.util.Log
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.perf.ktx.performance
import dagger.hilt.android.HiltAndroidApp
import lava.analytics.firebase.FirebaseInitializer
import lava.api.app.auth.ApiKeyStore
import lava.api.app.control.ApiEngineController
import lava.common.analytics.AnalyticsTracker
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

    @Inject
    lateinit var analytics: AnalyticsTracker

    override fun onCreate() {
        super.onCreate()
        controllerHolder = controller
        keyStoreHolder = keyStore

        // §6.AC / §6.O: wire Firebase Crashlytics + Analytics for the on-device
        // API app so its catch/error/fallback paths surface to the same Firebase
        // project as the client app. FirebaseApp is auto-initialized by
        // FirebaseInitProvider (a ContentProvider the google-services plugin
        // contributes) BEFORE Application.onCreate() — so NO manual
        // initializeApp() (manual init was a 2026-05-05 Crashlytics incident
        // root cause). FirebaseInitializer is the shared resilient initializer
        // from :core:analytics-firebase; each SDK block is independently guarded
        // so a Firebase failure never crashes the API server app.
        FirebaseInitializer.initialize(
            crashlytics = { runCatching { Firebase.crashlytics }.getOrNull() },
            analytics = { runCatching { Firebase.analytics }.getOrNull() },
            performance = { runCatching { Firebase.performance }.getOrNull() },
            isDebug = BuildConfig.DEBUG,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            applicationId = BuildConfig.APPLICATION_ID,
            warn = { msg, t -> Log.w(TAG, msg, t) },
        )
        // Distinguish api-app crashes from client crashes in the shared
        // Firebase project (both share one dashboard per the plan's OA-2).
        runCatching {
            Firebase.crashlytics.setCustomKey("artifact", "api-app")
        }.onFailure { Log.w(TAG, "setCustomKey(artifact) failed", it) }
    }

    companion object {
        private const val TAG = "ApiApplication"

        @Volatile
        var controllerHolder: ApiEngineController? = null
            private set

        @Volatile
        var keyStoreHolder: ApiKeyStore? = null
            private set
    }
}
