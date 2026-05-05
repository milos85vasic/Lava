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
import digital.vasic.lava.client.firebase.FirebaseInitializer
import lava.network.api.ImageLoader
import lava.tracker.client.work.MirrorHealthCheckWorker
import javax.inject.Inject

@HiltAndroidApp
class LavaApplication : Application() {
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var workManager: WorkManager

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

        imageLoader.setup()
        MirrorHealthCheckWorker.schedule(workManager)
    }

    companion object {
        private const val TAG = "LavaApplication"
    }
}
