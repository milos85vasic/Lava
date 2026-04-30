package lava.tracker.client.work

import androidx.work.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Configuration test for [MirrorHealthCheckWorker]. Verifies the periodic
 * request the worker schedules carries the constraints the SP-3a Phase 4
 * spec mandates (15-minute interval, CONNECTED network).
 *
 * Sixth Law type: VM-CONTRACT — primary assertion is on the WorkRequest
 * configuration that WorkManager uses to fire the worker. End-to-end probe
 * behaviour is covered by the SDK test [LavaTrackerSdkMirrorHealthTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MirrorHealthCheckWorkerTest {

    // VM-CONTRACT
    @Test
    fun buildRequest_uses_15_minute_interval() {
        val request = MirrorHealthCheckWorker.buildRequest()
        assertNotNull(request.workSpec.intervalDuration)
        // PeriodicWorkRequestBuilder converts MINUTES to millis.
        assertEquals(15L * 60 * 1000, request.workSpec.intervalDuration)
    }

    // VM-CONTRACT
    @Test
    fun buildRequest_requires_connected_network() {
        val request = MirrorHealthCheckWorker.buildRequest()
        assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
    }

    // VM-CONTRACT
    @Test
    fun buildRequest_does_not_require_charging() {
        val request = MirrorHealthCheckWorker.buildRequest()
        assertTrue(
            "Worker MUST run on battery so users see fresh mirror health when unplugged",
            !request.workSpec.constraints.requiresCharging(),
        )
    }

    // VM-CONTRACT
    @Test
    fun unique_name_is_stable_across_calls() {
        // Stability matters because ExistingPeriodicWorkPolicy.KEEP keys on the
        // unique name; a renamed constant would silently start a parallel
        // schedule on the next app upgrade.
        assertEquals("lava.tracker.mirror-health-check", MirrorHealthCheckWorker.UNIQUE_NAME)
    }

    // VM-CONTRACT
    @Test
    fun interval_constant_matches_spec() {
        assertEquals(15L, MirrorHealthCheckWorker.INTERVAL_MINUTES)
    }
}
