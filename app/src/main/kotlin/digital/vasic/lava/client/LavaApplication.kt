package digital.vasic.lava.client

import android.app.Application
import android.os.StrictMode
import android.util.Log
import androidx.work.WorkManager
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.perf.ktx.performance
import dagger.hilt.android.HiltAndroidApp
import digital.vasic.lava.client.crash.NavTeardownCrashReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import lava.analytics.firebase.FirebaseInitializer
import lava.common.analytics.AnalyticsTracker
import lava.common.analytics.rethrowIfCancellation
import lava.domain.usecase.RepopulateProvidersOnStartupUseCase
import lava.network.api.ImageLoader
import lava.tracker.client.work.MirrorHealthCheckWorker
import javax.inject.Inject

@HiltAndroidApp
class LavaApplication : Application() {
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var workManager: WorkManager

    @Inject
    lateinit var analytics: AnalyticsTracker

    // Provider-availability restore on cold start (operator directive
    // 2026-06-13). After a process restart the in-memory tracker registry has
    // reverted to the bundled provider set; this re-fetches the chosen
    // lava-api-go instance's full /providers catalogue so Settings, search, and
    // onboarding re-entry all see ALL providers — not just the bundled subset.
    @Inject
    lateinit var repopulateProviders: RepopulateProvidersOnStartupUseCase

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build(),
            )
        }
        super.onCreate()
        // FirebaseApp is auto-initialized by `FirebaseInitProvider` (a
        // ContentProvider declared by the google-services plugin) before
        // Application.onCreate() is even called. Manual `initializeApp(this)`
        // was redundant and a 2026-05-05 Crashlytics incident root cause —
        // see .lava-ci-evidence/crashlytics-resolved/2026-05-05-firebase-init-hardening.md
        // for the post-mortem. The initializer below is defensively wrapped
        // so a single SDK failure cannot kill the app.
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

        // LVA-008 (§6.AC, operator-accepted 2026-06-09): tag the known upstream
        // androidx-navigation teardown crash with attributable context before the
        // process dies, so it surfaces in Crashlytics as a known/triageable defect
        // rather than a mystery fatal. Instruments only — never swallows the crash.
        NavTeardownCrashReporter.install(analytics)

        imageLoader.setup()
        MirrorHealthCheckWorker.schedule(workManager)

        // Re-populate the dynamic provider catalogue from the persisted active
        // lava-api-go endpoint on every cold start, off the main thread, BEFORE
        // the user opens Settings/search. Degrades to the bundled set (no-op) if
        // no GoApi endpoint is configured or the API is unreachable — never
        // blocks onCreate, never throws into the process. §6.AC telemetry on the
        // unexpected-failure path.
        startupScope.launch {
            try {
                repopulateProviders()
            } catch (e: Throwable) {
                e.rethrowIfCancellation()
                analytics.recordNonFatal(
                    e,
                    mapOf(AnalyticsTracker.Params.ERROR to "startup_provider_repopulate_failed"),
                )
            }
        }
    }

    companion object {
        private const val TAG = "LavaApplication"
    }
}
