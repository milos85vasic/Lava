/*
 * Challenge C06 (2026-06-25, W4 gap-fill) — :api-app Copy-key button actually
 * copies the live access key to the system clipboard and confirms it.
 *
 * What the user does: with the API Running, the screen shows the access key
 * peers must present, next to a "Copy" button. The user taps Copy expecting
 * the key to land on the clipboard (so they can paste it into the other
 * device) and to get a "copied" confirmation. This Challenge drives that exact
 * control on the REAL ApiControlScreen and proves the clipboard really holds
 * the key — not that a callback merely fired.
 *
 * Production path under test (no shortcut): TAG_COPY_KEY OutlinedButton →
 * `onCopyKey(state.authKey)` in ApiControlScreen → real
 * `LocalClipboardManager.setText(AnnotatedString(key))` + dispatch of
 * ApiControlAction.CopyKeyClicked → ApiControlViewModel.onCopyKey() posts
 * ApiControlSideEffect.KeyCopied → the screen's LaunchedEffect shows the
 * "Access key copied" snackbar. The key value itself is the REAL post-start
 * ApiControlState.Running.authKey (from the singleton ApiEngineController, not
 * synthesized).
 *
 * Load-bearing assertions, both on user-/peer-observable state:
 *   (1) PRIMARY — after tapping Copy, the Android system ClipboardManager's
 *       primary clip text EQUALS the access key rendered in TAG_ACCESS_KEY.
 *       This is the actual artifact the user pastes onto the other device; a
 *       Copy that flipped a flag but wrote nothing to the clipboard FAILS here.
 *   (2) The "Access key copied" confirmation snackbar renders (the user-facing
 *       feedback that the action happened).
 *
 * Anti-bluff posture (§6.J): real screen, real VM, real singleton controller,
 * real Android ClipboardManager (a boundary system service, the only permitted
 * mock-point — and here it is NOT mocked, it is the real device clipboard). The
 * PRIMARY signal is the clipboard's actual contents, never "CopyKeyClicked was
 * dispatched".
 *
 * FALSIFIABILITY REHEARSAL (§6.AB.3 / Sixth Law clause 2 — non-crashing break):
 *
 *   1. In ApiControlScreen's RunningDetails onCopyKey lambda, delete the
 *      `clipboard.setText(AnnotatedString(key))` line but KEEP the
 *      `onAction(ApiControlAction.CopyKeyClicked(key))` dispatch — the button
 *      still "looks wired" (the snackbar still shows) but nothing reaches the
 *      clipboard. This is the exact non-crashing "confirmation fires, copy
 *      didn't happen" bug class.
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: assertion (1) fails — the clipboard text is empty or
 *      stale, not the access key, so `assertEquals` throws
 *      "clipboard MUST hold the access key after Copy ... expected:<KEY> but
 *      was:<>".  (Assertion (2) still passes against this break, proving the
 *      snackbar alone is NOT the load-bearing signal — the clipboard is.)
 *   4. Revert; re-run; the clipboard holds the key + the snackbar shows; pass.
 *
 *   Mirror rehearsal: change onCopyKey to write a constant wrong string to the
 *   clipboard. Assertion (1) still fails because the clipboard text != the
 *   rendered key — proving the test discriminates "copied the wrong value",
 *   not merely "copied nothing".
 */
package lava.api.app.challenges

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import lava.api.app.ui.TAG_ACCESS_KEY
import lava.api.app.ui.TAG_COPY_KEY
import lava.api.app.ui.TAG_START
import lava.api.app.ui.TAG_STATUS
import lava.api.app.ui.TAG_URL
import lava.apiengine.NativeApiEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge06CopyKeyTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun freshInstallState() {
        // Reset the process-global native engine a prior Challenge class may
        // have left running (same rationale as C03/C04): the Go embed's
        // `current` is process-global while Hilt rebuilds the @Singleton
        // controller per test, so a leftover Running embed makes the next Start
        // hit mobile.Start's "already running" guard and time out.
        runCatching { runBlocking { NativeApiEngine().stop() } }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.stopService(Intent(ctx, ApiEngineService::class.java))
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        clearClipboard(ctx)
    }

    @Test
    fun tapCopy_putsLiveAccessKeyOnTheSystemClipboard() {
        hiltRule.inject()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        // ===== Start → Running so the access key + Copy button render =====
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(TAG_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_START).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(TAG_URL).fetchSemanticsNodes().isNotEmpty() &&
                textOf(TAG_STATUS) == "Running"
        }

        // The key the user sees (the REAL Running.authKey rendered on screen).
        val renderedKey = textOf(TAG_ACCESS_KEY)
        assertTrue(
            "Precondition: the access key must be rendered while Running (got blank)",
            renderedKey.isNotBlank(),
        )

        // ===== Tap Copy =====
        composeRule.onNodeWithTag(TAG_COPY_KEY).performClick()
        composeRule.waitForIdle()

        // (1) PRIMARY: the Android system clipboard really holds the key the
        // user pastes onto the peer device. Clipboard access must run on the
        // main thread.
        val clipboardText = readClipboardOnMain(ctx)
        assertEquals(
            "PRIMARY: after tapping Copy, the system clipboard MUST hold the access key the " +
                "user pastes onto the peer device",
            renderedKey,
            clipboardText,
        )

        // (2) The user-facing confirmation snackbar renders.
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Access key copied").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "After Copy the user MUST see the 'Access key copied' confirmation",
            composeRule.onAllNodesWithText("Access key copied").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun clearClipboard(ctx: Context) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        }
    }

    /** Reads the primary clip text; clipboard APIs require the main thread. */
    private fun readClipboardOnMain(ctx: Context): String {
        var result = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            result = cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
        }
        return result
    }

    private fun textOf(tag: String): String {
        val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        if (nodes.isEmpty()) return ""
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text) ?: return ""
        return texts.joinToString(separator = "") { it.text }
    }
}
