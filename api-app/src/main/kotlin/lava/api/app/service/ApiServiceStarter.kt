package lava.api.app.service

import android.content.Context
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Abstraction over "bring the foreground [ApiEngineService] up / take it down".
 *
 * Exists so [lava.api.app.control.ApiControlViewModel] can be unit-tested
 * WITHOUT an Android [Context]: tests substitute a recording fake and assert on
 * the emitted [lava.api.app.control.ApiControlState], not on Android service
 * plumbing (Second Law — mock only at the OS boundary).
 */
interface ApiServiceStarter {
    /**
     * Ensures the foreground Service is running so it holds the Wi-Fi/wake
     * locks the embed needs while serving. Idempotent.
     */
    fun ensureRunning()

    /** Asks the Service to stop (the Service self-stops once the engine stops). */
    fun stop()
}

/**
 * Production [ApiServiceStarter] backed by [ContextCompat.startForegroundService]
 * + [Context.stopService]. This is the ONLY Android-service boundary the control
 * surface touches; everything above it (the VM, the controller) is plain Kotlin.
 */
@Singleton
class AndroidApiServiceStarter @Inject constructor(
    @ApplicationContext private val context: Context,
) : ApiServiceStarter {

    override fun ensureRunning() {
        ContextCompat.startForegroundService(context, ApiEngineService.startIntent(context))
    }

    override fun stop() {
        context.stopService(ApiEngineService.stopIntent(context))
    }
}
