/*
 * Challenge Test C35 — Connection feature (feature/connection, package
 * lava.connection) renders its real connection-management dialog when the
 * user opens it (§6.AE.1 per-feature coverage; §6.AB.3 rendered-state).
 *
 * REWRITE (2026-06-25, W3 UI-coverage-audit): the previous C35 was a
 * §6.AB.3 BLUFF — it only did `Class.forName("lava.connection.ConnectionsViewModel")`
 * (classpath presence). A blank/broken connection dialog would have passed
 * it. The classpath assertion is REMOVED. This rewrite drives the EXACT
 * production composable a user reaches: `ConnectionItem(requestShowDialog =
 * true)` opens the real `ModalBottomDialog { ConnectionsList() }`
 * (ConnectionItem.kt:64-66), and ConnectionsList unconditionally renders the
 * "Server" header + the Reload/Edit affordances (ConnectionsList.kt:74-108)
 * regardless of endpoint data. This test asserts on that real user-visible
 * rendered content.
 *
 * Everything in feature/connection that renders the chip/list is either
 * `internal` (ConnectionStatusIcon, Endpoint.title) or resolves its ViewModel
 * via `viewModel()` = hiltViewModel() (ConnectionItem, ConnectionsList). So,
 * unlike the topic-list features, the connection surface cannot be rendered
 * in a bare createComposeRule. It IS rendered via the real production
 * ConnectionsViewModel by hosting `ConnectionItem(requestShowDialog = true)`
 * inside createAndroidComposeRule<MainActivity> — MainActivity is
 * @AndroidEntryPoint, so hiltViewModel() resolves the real ViewModel (no
 * mocks; §Second-Law-compliant). The `requestShowDialog = true` parameter is
 * the SAME production entry the Menu uses to open the connections sheet.
 *
 * WHAT THIS CHALLENGE ASSERTS (rendered, user-visible state):
 *   The production ConnectionsList dialog renders the "Server" header a real
 *   user reads when they open the connections sheet, plus the "Edit
 *   connections list" affordance — i.e. the dialog actually composes and is
 *   not blank.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. In ConnectionItem.kt, delete the `ModalBottomDialog(... content = {
 *      ConnectionsList() })` block and replace with
 *      `if (showDialog) { /* no dialog */ }` (non-crashing — the chip still
 *      renders, requestShowDialog still flips showDialog, nothing throws).
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `connectionDialog_rendersServerHeader` fails —
 *      "could not find any node that satisfies: (Text + EditableText
 *      contains 'Server')" — the connection-management dialog a real user
 *      opens is gone.
 *   4. Restore the ModalBottomDialog block; re-run; passes.
 *
 * Honest scope (§6.J / §6.AH): SOURCE-WRITTEN + COMPILE-VERIFIED. EXECUTION
 * against a booted emulator is GATE-HOST-DEFERRED — this macOS host cannot
 * boot the Containers-driven emulator and host-direct is forbidden (§6.AH).
 * This Challenge MUST NOT be recorded as a passing attestation row until it
 * has EXECUTED on a Linux x86_64 + KVM gate-host.
 *
 * // covers-feature: connection
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.connection.ConnectionItem
import lava.designsystem.theme.LavaTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge35ConnectionItemReachableTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    // CHALLENGE: opening the connections sheet (the production
    // ConnectionItem -> ConnectionsList path, ViewModel resolved by real Hilt)
    // renders the "Server" header + the edit affordance a real user sees.
    @Test
    fun connectionDialog_rendersServerHeader() {
        composeRule.setContent {
            LavaTheme {
                // requestShowDialog = true is the same production entry the
                // Menu uses to open the connections sheet on first compose.
                ConnectionItem(requestShowDialog = true)
            }
        }

        // The real header the connections sheet shows (connection_item_title
        // = "Server" in feature/connection strings.xml).
        composeRule.onNodeWithText("Server").assertIsDisplayed()
        // The real edit affordance the list always renders in non-edit mode.
        composeRule.onNodeWithContentDescription("Edit connections list").assertIsDisplayed()
    }
}
