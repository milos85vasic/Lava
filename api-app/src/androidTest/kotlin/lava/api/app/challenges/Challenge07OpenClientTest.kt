/*
 * Challenge C07 (2026-06-25, W4 gap-fill) — :api-app "Open Lava client" button
 * actually fires a real cross-app launch Intent through startActivity.
 *
 * What the user does: on the API screen there is always an "Open Lava client"
 * (or "Back to Lava client") button so they can jump to the client. Tapping it
 * MUST hand off to another activity — either the installed client, or (when the
 * client is not installed, as in this fresh test environment) the Firebase
 * download page via an ACTION_VIEW intent. A tap that does nothing, or that
 * silently dead-ends, is the bug class this Challenge catches.
 *
 * Production path under test (no shortcut): TAG_OPEN_CLIENT Button →
 * ApiControlAction.OpenClient → ApiControlViewModel.onOpenClient() resolves via
 * the REAL injected SiblingAppLauncher (PackageManagerSiblingAppLauncher from
 * ApiControlModule): client package not installed in the test env ⇒
 * intentToOpen() == null ⇒ intentToDownload() (ACTION_VIEW @ the Firebase
 * download URL, FLAG_ACTIVITY_NEW_TASK) ⇒ posts ApiControlSideEffect.LaunchClient
 * ⇒ the screen's LaunchedEffect calls `context.startActivity(effect.intent)`.
 *
 * Assertion-surface choice (documented, anti-bluff honest): an in-process
 * instrumentation test cannot read the system "chosen activity" out-of-process,
 * and espresso-intents is deliberately NOT on the :api-app classpath (we did
 * not add a dependency for this gap-fill). The dependency-free, real signal is
 * an Instrumentation.ActivityMonitor: it intercepts the EXACT Intent the
 * production code passes to startActivity, blocks it from actually leaving
 * (block=true, so no browser/chooser is spawned on the device), and records
 * the hit. This is core androidx.test (no new dep) and traverses the SAME
 * onOpenClient → SiblingAppLauncher → startActivity path the button tap fires.
 *
 * Load-bearing assertions, on observable launch behavior:
 *   (1) PRIMARY — after tapping Open-client, an ActivityMonitor whose
 *       IntentFilter matches ONLY ACTION_VIEW records ≥1 hit: a real ACTION_VIEW
 *       launch Intent (the not-installed download path the real launcher
 *       resolves to in this env) reached startActivity. Because the filter is
 *       ACTION_VIEW-specific, a hit proves BOTH "an Intent fired" AND "it was
 *       the ACTION_VIEW download Intent" — a no-op tap records 0 hits and FAILS,
 *       and a tap that fired some OTHER (empty/wrong) Intent also records 0 hits
 *       and FAILS (the filter would not match it). The assertion is therefore
 *       discriminating, not tautological.
 *   (2) No "Could not open the Lava client" failure snackbar — the launch was
 *       accepted (ActivityNotFoundException was NOT thrown), i.e. the user did
 *       not get a dead tap.
 *
 * Anti-bluff posture (§6.J): real screen, real VM, real injected
 * SiblingAppLauncher, real Android PackageManager (boundary system service, not
 * mocked). The PRIMARY signal is "a launch Intent actually fired", not "the
 * OpenClient action was dispatched".
 *
 * FALSIFIABILITY REHEARSAL (§6.AB.3 / Sixth Law clause 2 — non-crashing break):
 *
 *   1. In ApiControlScreen's LaunchedEffect, delete the
 *      `context.startActivity(effect.intent)` call inside the
 *      ApiControlSideEffect.LaunchClient branch (leave the branch present so it
 *      compiles and the side effect is still collected — the exact non-crashing
 *      "button looks wired, nothing launches" bug class).
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: assertion (1) fails — the ActivityMonitor records 0
 *      hits, so `assertTrue(... monitor.hits >= 1 ...)` throws
 *      "PRIMARY: Open-client MUST fire a launch Intent, but startActivity was
 *      never called (monitor hits=0)".
 *   4. Revert; re-run; the monitor records the ACTION_VIEW launch; pass.
 *
 *   Mirror rehearsal: change onOpenClient to post LaunchClient with a bare
 *   `Intent()` (no action). Assertion (1) then fails because the ACTION_VIEW
 *   filter does not match an actionless Intent — the monitor records 0 hits —
 *   proving the test discriminates "fired the wrong/empty intent", not merely
 *   "fired nothing".
 */
package lava.api.app.challenges

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import lava.api.app.MainActivity
import lava.api.app.service.ApiEngineService
import lava.api.app.ui.TAG_OPEN_CLIENT
import lava.api.app.ui.TAG_STATUS
import lava.apiengine.NativeApiEngine
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge07OpenClientTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private var monitor: Instrumentation.ActivityMonitor? = null

    @Before
    fun freshInstallState() {
        runCatching { runBlocking { NativeApiEngine().stop() } }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.stopService(Intent(ctx, ApiEngineService::class.java))
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @After
    fun removeMonitor() {
        monitor?.let { InstrumentationRegistry.getInstrumentation().removeMonitor(it) }
    }

    @Test
    fun tapOpenClient_firesARealLaunchIntentThroughStartActivity() {
        hiltRule.inject()

        // Render: the Open-client button is visible regardless of API state
        // (spec §6.2 Direction 2). Wait for the screen to settle.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(TAG_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Open-client button must be present on the screen",
            composeRule.onAllNodesWithTag(TAG_OPEN_CLIENT).fetchSemanticsNodes().isNotEmpty(),
        )

        // Intercept + block ONLY ACTION_VIEW launches, so a recorded hit proves
        // the production code fired the ACTION_VIEW download Intent specifically
        // (the not-installed path) — not merely "some intent". block=true stops
        // it spawning a real browser/chooser on the device.
        val instr = InstrumentationRegistry.getInstrumentation()
        val mon = instr.addMonitor(
            /* filter = */
            IntentFilter(Intent.ACTION_VIEW),
            /* result = */
            Instrumentation.ActivityResult(Activity.RESULT_OK, null),
            /* block = */
            true,
        )
        monitor = mon

        // ===== Tap Open-client =====
        composeRule.onNodeWithTag(TAG_OPEN_CLIENT).performClick()

        // Wait for the side-effect → startActivity to be intercepted.
        instr.waitForMonitorWithTimeout(mon, 8_000)
        composeRule.waitForIdle()

        // (1) PRIMARY: a real ACTION_VIEW launch Intent reached startActivity.
        // The ACTION_VIEW-specific filter makes this discriminating — a no-op
        // tap or a wrong/empty Intent both record 0 hits and fail here.
        assertTrue(
            "PRIMARY: tapping Open-client MUST fire an ACTION_VIEW launch Intent through " +
                "startActivity (the not-installed download path), but the monitor recorded " +
                "${mon.hits} matching hit(s)",
            mon.hits >= 1,
        )

        // (2) The user did NOT get a dead tap: no launch-failure snackbar.
        assertTrue(
            "Open-client must not surface the 'could not open' failure when a launch Intent fired",
            composeRule
                .onAllNodesWithText("Could not open the Lava client — it may have been uninstalled.")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
    }
}
