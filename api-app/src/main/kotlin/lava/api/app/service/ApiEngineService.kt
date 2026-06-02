package lava.api.app.service

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import lava.api.app.MainActivity
import lava.api.app.R
import lava.api.app.auth.ApiKeyStoreImpl
import lava.api.app.control.ApiControlState
import lava.api.app.control.ApiEngineController
import lava.api.app.net.NetworkInterfaceLanIpProvider
import lava.apiengine.NativeApiEngine

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
class ApiEngineService : Service() {

    private lateinit var controller: ApiEngineController
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        controller = buildController()

        // Reflect controller state into locks + the foreground notification.
        serviceScope.launch {
            controller.state.collectLatest { state ->
                when (state) {
                    is ApiControlState.Running -> {
                        acquireLocks()
                        updateNotification(state)
                    }
                    is ApiControlState.Stopped -> {
                        releaseLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
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
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.api_notification_title)))

        when (intent?.action) {
            ACTION_STOP -> serviceScope.launch { controller.stop() }
            ACTION_RESTART -> serviceScope.launch { controller.restart() }
            // Default + ACTION_START: bring the API up.
            else -> serviceScope.launch { controller.start() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildController(): ApiEngineController {
        val engine = NativeApiEngine()
        val isDev = packageName.endsWith(DEV_SUFFIX)
        val advertiser = NsdMdnsAdvertiser(
            context = applicationContext,
            engine = if (isDev) AdvertisedEngine.GO_DEV else AdvertisedEngine.GO,
            versionProvider = { engine.status().version },
        )
        val keyStore = ApiKeyStoreImpl.create(applicationContext)
        return ApiEngineController(
            engine = engine,
            advertiser = advertiser,
            keyStore = keyStore,
            lanIpProvider = NetworkInterfaceLanIpProvider()::invoke,
            sqlitePathProvider = { sqlitePath() },
        )
    }

    private fun sqlitePath(): String =
        applicationContext.filesDir.resolve(SQLITE_FILE).absolutePath

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
        val body = "${state.url}  •  ${state.requestCount} req"
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
        private const val SQLITE_FILE = "lava-api.db"
        private const val DEV_SUFFIX = ".dev"
        private const val MULTICAST_LOCK_TAG = "lava.api.multicast"
        private const val WIFI_LOCK_TAG = "lava.api.wifi"
        private const val WAKE_LOCK_TAG = "lava.api:server"

        /** Builds an intent that starts/ensures the API is running. */
        fun startIntent(context: Context): Intent =
            Intent(context, ApiEngineService::class.java).setAction(ACTION_START)
    }
}
