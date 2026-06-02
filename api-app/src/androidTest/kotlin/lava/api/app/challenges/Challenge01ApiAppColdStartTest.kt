/*
 * Challenge C01 (Phase E, 2026-06-02) — :api-app cold-start survival.
 *
 * Verifies that the standalone Lava API app launches from a freshly-cleared
 * install state without crashing and renders its landing screen with the
 * server status visible — the on-device equivalent of the client app's C00.
 *
 * What the user does: taps the Lava API launcher icon on a device where the
 * app was just installed (no prior state). They MUST see the control screen
 * (status word + Start control), not a crash dialog. The §6.Z forensic anchor
 * (Lava-Android-1.2.19-1039 cold-launch crash that all source-compiled tests
 * passed against) is exactly the failure class this Challenge exists to catch
 * for :api-app: an ApiApplication.onCreate / Hilt-graph / setContent-composition
 * failure that bricks the app on first open.
 *
 * Anti-bluff posture (§6.J): the test clears app state (mirroring `pm clear`),
 * launches the REAL MainActivity (which builds the REAL ApiControlScreen wired
 * to the REAL Hilt-injected ApiControlViewModel + singleton ApiEngineController),
 * and asserts the status node renders the real "Stopped" label. It does NOT
 * synthesize a ViewModel or stub the screen.
 *
 * FALSIFIABILITY REHEARSAL (§6.AB.3 / Sixth Law clause 2):
 *
 *   1. In ApiApplication.onCreate(), add `throw IllegalStateException("boom")`
 *      (a deliberate cold-start crash — the §6.Z bug class).
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: the activity never reaches setContent; the
 *      composeRule.waitUntil for the TAG_STATUS node times out and the test
 *      fails with `androidx.compose.ui.test.ComposeTimeoutException` /
 *      "Condition still not satisfied after 10000 ms" — the cold launch is
 *      proven broken.
 *   4. Revert; re-run; the status node renders "Stopped" and the test passes.
 *
 *   Non-crashing mirror rehearsal: change StatusIndicator's stopped mapping to
 *   a blank string resource. The "status text is non-blank + equals Stopped"
 *   assertion then fires — proving the test discriminates a silently-wrong
 *   render, not merely a crash.
 */
package lava.api.app.challenges

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import lava.api.app.MainActivity
import lava.api.app.service.ApiEngineService
import lava.api.app.ui.TAG_START
import lava.api.app.ui.TAG_STATUS
import lava.apiengine.NativeApiEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge01ApiAppColdStartTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun clearStateLikeFreshInstall() {
        // Mirror `pm clear`: wipe the app's private files dir so the embed has
        // no persisted SQLite/TLS and the auth store has no key — the genuine
        // first-launch condition. The auth EncryptedSharedPreferences live in
        // the same dir tree, so this also resets the key store.
        // Reset the process-global native engine a prior Challenge class may
        // have left running. The Go embed's `current` is process-global, but
        // Hilt rebuilds the @Singleton ApiEngineController per test — so without
        // this, the next Start hits mobile.Start's "already running" guard and
        // the start→Running wait times out. runCatching swallows the benign
        // "no server running" when nothing was left over. (Surfaced by C03/C04
        // once the :api-app gate first actually ran them.)
        runCatching { runBlocking { NativeApiEngine().stop() } }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Kill any foreground Service a prior Challenge left running: it holds
        // that prior test's @Singleton controller (Hilt rebuilds the controller
        // per test), and a stale Service would intercept THIS test's start /
        // notification actions, driving the wrong (stale) controller.
        ctx.stopService(Intent(ctx, ApiEngineService::class.java))
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun coldLaunch_rendersLandingScreen_withoutCrash() {
        hiltRule.inject()

        // ===== PRIMARY: the landing screen renders the status node =====
        // If ApiApplication.onCreate / Hilt / setContent crashed, the activity
        // never composes TAG_STATUS and this wait times out (test fails).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(TAG_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_STATUS).assertIsDisplayed()

        // The Start control is present + the status reads the real Stopped
        // label (the genuine initial ApiControlState.Stopped → string resource
        // mapping the user sees on a fresh launch). A blank or wrong status
        // render fails here even though nothing crashed.
        composeRule.onNodeWithTag(TAG_START).assertIsDisplayed()
        val statusText = textOf(TAG_STATUS)
        assertTrue("Status text must be non-blank on cold launch", statusText.isNotBlank())
        assertEquals(
            "Fresh launch MUST render the Stopped status (no server running yet)",
            "Stopped",
            statusText,
        )
    }

    private fun textOf(tag: String): String {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text) ?: return ""
        return texts.joinToString(separator = "") { it.text }
    }
}
