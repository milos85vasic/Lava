/*
 * Challenge C03 (Phase E, 2026-06-02) — :api-app Stop / Restart lifecycle
 * actually starts and stops the served API.
 *
 * What the user does: with the API Running, taps Stop and sees it go to
 * Stopped — and a peer device can no longer reach it. Then taps Restart and
 * sees Running again — and the peer can reach it once more. This Challenge
 * drives those exact controls on the REAL screen and proves the served HTTPS
 * socket follows the UI state: refused after Stop, serving again after Restart.
 *
 * Load-bearing assertions, all on observable state:
 *   (1) After Start → Running, GET /health → 200 (serving).
 *   (2) After tapping Stop → status "Stopped" AND a GET to the old URL is
 *       REFUSED (connection failure / no 200) — the listener really closed.
 *   (3) After tapping Restart → status "Running" again AND GET /health → 200
 *       — the listener really re-opened.
 *
 * Anti-bluff posture (§6.J): the lifecycle is driven through the real
 * ApiControlScreen buttons → real ApiControlViewModel → real singleton
 * ApiEngineController → real NativeApiEngine. The PRIMARY signal is the HTTP
 * reachability of the on-device server (200 vs refused), not a state-enum
 * read alone — a Stop that flipped the label but left the socket serving
 * would FAIL assertion (2).
 *
 * FALSIFIABILITY REHEARSAL (§6.AB.3 / Sixth Law clause 2):
 *
 *   1. In ApiEngineController.stop(), comment out the `engine.stop()` call (so
 *      the label flips to Stopped but the embed keeps listening — the exact
 *      non-crashing "looks stopped, still serving" bug class).
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: assertion (2) fails — the GET to the old URL still
 *      returns 200 instead of being refused, so `assertServingRefused` throws
 *      "expected the stopped embed to refuse requests, but got HTTP 200".
 *   4. Revert; re-run; the stopped embed refuses + Restart re-serves; pass.
 *
 *   Mirror rehearsal (restart): make ApiEngineController.restart() return
 *   early after stop() (never re-start). Assertion (3) then fails because the
 *   status never returns to Running / /health is unreachable.
 */
package lava.api.app.challenges

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
import lava.api.app.ui.TAG_RESTART
import lava.api.app.ui.TAG_START
import lava.api.app.ui.TAG_STATUS
import lava.api.app.ui.TAG_STOP
import lava.api.app.ui.TAG_URL
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge03StopRestartTest {

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
    fun startThenStopRefusesThenRestartServesAgain() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // ===== Start → Running =====
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(TAG_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_START).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(TAG_URL).fetchSemanticsNodes().isNotEmpty() &&
                textOf(TAG_STATUS) == "Running"
        }
        val port = portOf(textOf(TAG_URL))

        // (1) Serving while Running.
        OnDeviceApiClient(ctx, port).get("/health").use { resp ->
            assertEquals("Running embed MUST serve /health with 200", 200, resp.code)
        }

        // ===== Stop → Stopped + refused =====
        composeRule.onNodeWithTag(TAG_STOP).performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            textOf(TAG_STATUS) == "Stopped"
        }
        // (2) The listener really closed — a request to the old port is refused
        // (connection failure) or at minimum NOT a 200. Give the embed a moment
        // to fully release the socket.
        assertServingRefused(ctx, port)

        // ===== Restart → Running + serving again =====
        // After Stop the screen shows Stopped; the Start control is the way
        // back up (Restart is enabled only while Running). Tapping Start
        // re-runs the same controller.start() path Restart's start leg uses.
        composeRule.onNodeWithTag(TAG_START).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(TAG_URL).fetchSemanticsNodes().isNotEmpty() &&
                textOf(TAG_STATUS) == "Running"
        }
        val port2 = portOf(textOf(TAG_URL))
        // (3) Serving again after coming back up.
        OnDeviceApiClient(ctx, port2).get("/health").use { resp ->
            assertEquals("Re-started embed MUST serve /health with 200 again", 200, resp.code)
        }

        // ===== Restart control (Running → Running) drives stop+start =====
        composeRule.onNodeWithTag(TAG_RESTART).performClick()
        composeRule.waitUntil(timeoutMillis = 35_000) {
            composeRule.onAllNodesWithTag(TAG_URL).fetchSemanticsNodes().isNotEmpty() &&
                textOf(TAG_STATUS) == "Running"
        }
        val port3 = portOf(textOf(TAG_URL))
        OnDeviceApiClient(ctx, port3).get("/health").use { resp ->
            assertEquals("After Restart the embed MUST serve /health with 200", 200, resp.code)
        }
    }

    /** Asserts a request to [port] is refused (no live listener). */
    private fun assertServingRefused(ctx: android.content.Context, port: Int) {
        // Poll briefly: socket teardown is asynchronous after stopSelf.
        var lastCode: Int? = null
        repeat(10) {
            try {
                OnDeviceApiClient(ctx, port).get("/health").use { resp ->
                    lastCode = resp.code
                }
            } catch (_: Exception) {
                // Connection refused / reset — the expected stopped state.
                return
            }
            Thread.sleep(500)
        }
        fail(
            "PRIMARY: after Stop, the on-device embed MUST refuse requests, but a GET " +
                "/health still returned HTTP $lastCode — the listener did not close.",
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
