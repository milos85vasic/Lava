/*
 * Challenge C04 (Phase E, 2026-06-02) — :api-app foreground-notification
 * actions drive the same Stop / Restart lifecycle the UI buttons do.
 *
 * What the user does: with the API Running, the ongoing foreground
 * notification shows Stop + Restart actions (the only surface visible when the
 * app is backgrounded). Tapping the notification's Stop button stops the
 * served API; tapping Restart re-serves it. This Challenge proves those
 * notification actions are wired to the real lifecycle.
 *
 * Assertion-surface choice (documented per task): asserting on the system
 * notification shade from inside an in-process instrumentation test is
 * infeasible (it requires UiAutomator driving SystemUI out-of-process). So
 * this Challenge fires the EXACT Service-action Intents the notification's
 * PendingIntents carry — ApiEngineService.ACTION_STOP and ACTION_RESTART,
 * built via the same ApiEngineService factory the notification uses — and
 * asserts the resulting user-/peer-observable state: the served HTTPS socket
 * follows the action. The notification PendingIntents in
 * ApiEngineService.buildNotification target exactly these actions on exactly
 * this Service, so firing the intents traverses the SAME onStartCommand →
 * controller.stop()/restart() production path the button tap triggers.
 *
 * Load-bearing assertions (on the wire, not call-counts):
 *   (1) After Start (UI) → Running, GET /health → 200.
 *   (2) After ACTION_STOP (notification's Stop) → the served socket is
 *       REFUSED — the embed really stopped.
 *   (3) After ACTION_RESTART (notification's Restart) → GET /health → 200
 *       again — the embed really came back up.
 *
 * Anti-bluff posture (§6.J): real Service, real singleton controller, real
 * embed. The PRIMARY signal is HTTP reachability after the notification-action
 * intent, not "the service received the intent".
 *
 * FALSIFIABILITY REHEARSAL (§6.AB.3 / Sixth Law clause 2):
 *
 *   1. In ApiEngineService.onStartCommand, change the ACTION_STOP branch to a
 *      no-op (delete `serviceScope.launch { controller.stop() }`) — the
 *      notification's Stop button now does nothing while still looking wired.
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: assertion (2) fails — after firing ACTION_STOP the
 *      embed keeps serving, so `assertServingRefused` throws "expected the
 *      stopped embed to refuse requests, but got HTTP 200".
 *   4. Revert; re-run; Stop refuses + Restart re-serves; pass.
 *
 *   Mirror rehearsal: make the ACTION_RESTART branch a no-op. Assertion (3)
 *   then fails because /health never recovers after restart.
 */
package lava.api.app.challenges

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import lava.api.app.MainActivity
import lava.api.app.service.ApiEngineService
import lava.api.app.ui.TAG_START
import lava.api.app.ui.TAG_STATUS
import lava.api.app.ui.TAG_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge04NotificationActionsTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun freshInstallState() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun notificationStopAndRestartActions_driveServedSocket() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // ===== Start via the UI → Running + serving =====
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(TAG_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_START).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(TAG_URL).fetchSemanticsNodes().isNotEmpty() &&
                textOf(TAG_STATUS) == "Running"
        }
        val port = portOf(textOf(TAG_URL))
        OnDeviceApiClient(ctx, port).get("/health").use { resp ->
            assertEquals("Running embed MUST serve /health with 200", 200, resp.code)
        }

        // ===== Fire the notification's STOP action =====
        // ApiEngineService.buildNotification's stop PendingIntent carries
        // exactly this action on exactly this Service.
        ctx.startService(ApiEngineService.stopIntent(ctx))
        composeRule.waitUntil(timeoutMillis = 20_000) {
            textOf(TAG_STATUS) == "Stopped"
        }
        // (2) The served socket really closed.
        assertServingRefused(ctx, port)

        // ===== Fire the notification's RESTART action =====
        ctx.startService(restartIntent(ctx))
        composeRule.waitUntil(timeoutMillis = 35_000) {
            composeRule.onAllNodesWithTag(TAG_URL).fetchSemanticsNodes().isNotEmpty() &&
                textOf(TAG_STATUS) == "Running"
        }
        val port2 = portOf(textOf(TAG_URL))
        // (3) Serving again after the notification Restart action.
        OnDeviceApiClient(ctx, port2).get("/health").use { resp ->
            assertEquals(
                "After the notification Restart action the embed MUST serve /health with 200",
                200,
                resp.code,
            )
        }
    }

    /** The intent the notification's Restart PendingIntent carries. */
    private fun restartIntent(ctx: android.content.Context): Intent =
        Intent(ctx, ApiEngineService::class.java).setAction(ApiEngineService.ACTION_RESTART)

    private fun assertServingRefused(ctx: android.content.Context, port: Int) {
        var lastCode: Int? = null
        repeat(10) {
            try {
                OnDeviceApiClient(ctx, port).get("/health").use { resp -> lastCode = resp.code }
            } catch (_: Exception) {
                return
            }
            Thread.sleep(500)
        }
        fail(
            "PRIMARY: after the notification Stop action, the embed MUST refuse requests, " +
                "but a GET /health still returned HTTP $lastCode — the action did not stop serving.",
        )
    }

    private fun portOf(url: String): Int =
        url.substringAfterLast(':').substringBefore('/').toInt()

    private fun textOf(tag: String): String {
        val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        if (nodes.isEmpty()) return ""
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text) ?: return ""
        return texts.joinToString(separator = "") { it.text }
    }
}
