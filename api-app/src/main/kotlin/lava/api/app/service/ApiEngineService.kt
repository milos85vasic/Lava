package lava.api.app.service

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import lava.api.app.MainActivity
import lava.api.app.R
import lava.api.app.control.ApiControlState
import lava.api.app.control.ApiEngineController
import lava.common.analytics.AnalyticsTracker
import javax.inject.Inject

/**
 * Foreground [Service] hosting the embedded Lava API.
 *
 * Responsibilities (Phase D-infra):
 *  - Owns the Android-free [ApiEngineController] and drives it via intent
 *    actions ([ACTION_START] / [ACTION_STOP] / [ACTION_RESTART]).
 *  - Holds the OS locks the embed needs while serving: a Wi-Fi MulticastLock
 *    (so mDNS multicast reaches the LAN), a Wi-Fi high-perf lock (so the radio
 *    stays up), and a partial WakeLock (so the CPU keeps serving while the
 *    screen is off). All three are released the moment the API stops.
 *  - Posts the ongoing foreground notification reflecting the live state
 *    (reachable URL + request count) with Stop + Restart actions and a
 *    content-intent back to [MainActivity].
 *
 * The notification copy here is intentionally minimal; Phase D-ui refines it.
 *
 * State collection runs on a service-scoped [CoroutineScope] cancelled in
 * [onDestroy].
 */
@AndroidEntryPoint
class ApiEngineService : Service() {

    /**
     * The SHARED singleton controller (provided by [lava.api.app.control.ApiControlModule]).
     * The same instance backs [lava.api.app.control.ApiControlViewModel], so the
     * Service and the UI observe one [ApiEngineController.state] StateFlow. The
     * VM is the lifecycle DRIVER (start/stop/restart); the Service only manages
     * the OS locks + the foreground notification off the shared state.
     */
    @Inject
    lateinit var controller: ApiEngineController

    /** §6.AC — non-fatal telemetry for OS-denied foreground-service starts. */
    @Inject
    lateinit var analytics: AnalyticsTracker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // True once the engine has been observed Running in THIS Service instance.
    // The collector self-stops the Service only on a GENUINE running→stopped
    // transition — NOT on the INITIAL Stopped a freshly-created Service sees when
    // it was started to handle ACTION_START/ACTION_RESTART while the engine is
    // currently stopped (otherwise the Service self-destructs and cancels the
    // action's controller.restart()/start() coroutine before it can drive the
    // engine up — the notification Restart-after-Stop defect C04 surfaced).
    private var sawRunning = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel()

        // Reflect controller state into locks + the foreground notification.
        serviceScope.launch {
            controller.state.collectLatest { state ->
                when (state) {
                    is ApiControlState.Running -> {
                        sawRunning = true
                        acquireLocks()
                        updateNotification(state)
                    }
                    is ApiControlState.Stopped -> {
                        releaseLocks()
                        // Only tear the Service down on a real running→stopped
                        // transition; never on the initial Stopped a fresh
                        // Service sees while handling an ACTION_RESTART/START.
                        if (sawRunning) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        }
                    }
                    is ApiControlState.Error -> {
                        releaseLocks()
                        updateNotificationText(state.message)
                    }
                    ApiControlState.Starting,
                    ApiControlState.Stopping,
                    -> updateNotificationText(getString(R.string.api_notification_title))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Promote to foreground immediately so the OS does not kill us before
        // the first state emission (notification body is refined as state
        // arrives).
        //
        // DEFENSIVE GUARD (type-agnostic): the service type is now `specialUse`
        // (no cumulative runtime budget), but we still wrap startForeground so
        // that if ANY foreground-service-start restriction is hit on ANY OEM /
        // OS version (e.g. a future policy, or a mis-declared variant), we
        // degrade to a clean stop instead of the FATAL
        // ForegroundServiceStartNotAllowedException that crashed the prior
        // dataSync build at this exact line (Crashlytics 9ba8502e…).
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.api_notification_title)),
            )
        } catch (e: ForegroundServiceStartNotAllowedException) {
            // §6.AC — the OS denied the foreground promotion. Record as a non-fatal
            // so operators can see OS-version + OEM patterns in Crashlytics.
            // §6.H: no credentials in context (none are available here).
            analytics.recordNonFatal(
                e,
                mapOf(
                    AnalyticsTracker.Params.FEATURE to "api_engine",
                    AnalyticsTracker.Params.MODULE to "ApiEngineService",
                    AnalyticsTracker.Params.OPERATION to "onStartCommand",
                    AnalyticsTracker.Params.ERROR to "foreground_start_not_allowed",
                ),
            )
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            // Notification-button actions drive the SHARED controller directly
            // (the user tapped Stop/Restart on the ongoing notification, which
            // may be the only surface visible).
            ACTION_STOP -> serviceScope.launch { controller.stop() }
            ACTION_RESTART -> serviceScope.launch { controller.restart() }
            // ACTION_START (and the default) is a pure foreground-promotion: the
            // VM (or the notification) is the lifecycle driver, so the Service
            // must NOT also call controller.start() here — a second start while
            // already running would trip the engine's already-running guard. We
            // only promote-to-foreground (done above) to hold the OS locks.
            else -> Unit
        }
        return START_STICKY
    }

    /**
     * Foreground-service timeout callback (API 35+).
     *
     * The system calls this when a TIME-BUDGETED foreground-service type (e.g.
     * dataSync / mediaProcessing) reaches its cumulative limit. The current
     * type is `specialUse`, which is NOT budgeted, so this should not normally
     * fire — but we override it defensively so that on ANY device/OEM that DOES
     * impose a timeout we stop gracefully within the few-second window the OS
     * allows, instead of triggering the
     * ForegroundServiceDidNotStopInTimeException class that the prior dataSync
     * build hit (Crashlytics b9baeaede…).
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ── Locks ────────────────────────────────────────────────────────────

    private fun acquireLocks() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (multicastLock == null && wifi != null) {
            multicastLock = wifi.createMulticastLock(MULTICAST_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null && wifi != null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wakeLock == null) {
            val power = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    // ── Notification ─────────────────────────────────────────────────────

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.api_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.api_notification_channel_description)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(state: ApiControlState.Running) {
        // Body: the reachable URL a peer device hits + the live request count.
        val body = getString(
            R.string.api_notification_running_body,
            state.url,
            state.requestCount,
        )
        updateNotificationText(body)
    }

    private fun updateNotificationText(body: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, buildNotification(body))
    }

    private fun buildNotification(body: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            pendingFlags(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ApiEngineService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        val restartIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ApiEngineService::class.java).setAction(ACTION_RESTART),
            pendingFlags(),
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.api_notification_title))
            .setContentText(body)
            .setSmallIcon(lava.designsystem.R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(0, getString(R.string.api_notification_action_stop), stopIntent)
            .addAction(0, getString(R.string.api_notification_action_restart), restartIntent)
            .build()
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    companion object {
        const val ACTION_START = "lava.api.app.action.START"
        const val ACTION_STOP = "lava.api.app.action.STOP"
        const val ACTION_RESTART = "lava.api.app.action.RESTART"

        private const val CHANNEL_ID = "lava.api.app.server"
        private const val NOTIFICATION_ID = 0x1A7A // "LAVA"-ish
        private const val MULTICAST_LOCK_TAG = "lava.api.multicast"
        private const val WIFI_LOCK_TAG = "lava.api.wifi"
        private const val WAKE_LOCK_TAG = "lava.api:server"

        /** Builds an intent that promotes the API Service to the foreground. */
        fun startIntent(context: Context): Intent =
            Intent(context, ApiEngineService::class.java).setAction(ACTION_START)

        /** Builds an intent that asks the Service to stop the API. */
        fun stopIntent(context: Context): Intent =
            Intent(context, ApiEngineService::class.java).setAction(ACTION_STOP)
    }
}
