/*
 * Challenge Test C26 — RuTracker (Main) is absent from the Server list.
 *
 * Forensic anchor (2026-05-12, operator-reported): after a clean
 * uninstall/reinstall, the "Main" rutracker.org server entry STILL
 * appeared in the Server list. Two persistence paths can carry it
 * back:
 *   1. Room DB: existing endpoint row from pre-v1.2.15 installs.
 *   2. Android Auto Backup: settings shared-prefs restored with
 *      Endpoint.Rutracker as the stored selection.
 *
 * Fixes applied:
 *   - EndpointsRepositoryImpl.observeAll() filters out Endpoint.Rutracker
 *     from the emitted list AND purges any legacy Rutracker row from the
 *     DAO on each observe() start.
 *   - PreferencesStorageImpl.getSettings() migrates a persisted
 *     Endpoint.Rutracker to a sentinel Endpoint.GoApi and clears the
 *     stored key.
 *   - AndroidManifest excludes settings.xml SharedPreferences from
 *     full-backup + cloud-backup + device-transfer via
 *     `@xml/backup_rules` and `@xml/data_extraction_rules`.
 *
 * This test verifies the user-visible outcome: after completing
 * onboarding and reaching the main app, navigating to the Menu's
 * Server section MUST NOT show any entry titled "Main" or any entry
 * whose host is "rutracker.org".
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In core/data/.../EndpointsRepositoryImpl.kt revert the
 *      `.filterNot { it is Endpoint.Rutracker }` and remove the
 *      `purgeRutrackerLegacy()` call from observeAll().
 *   2. In core/preferences/.../PreferencesStorageImpl.kt revert the
 *      `getSettings()` migration to the original `?: Endpoint.Rutracker`.
 *   3. On the gating emulator, do `am force-stop` + clear data on
 *      the app, then add a Rutracker row to the DB via DAO injection
 *      (or restore from backup if available), then re-run this test.
 *   4. Expected failure: the assertion below fires because "Main" or
 *      "rutracker.org" is found in the Compose tree.
 *   5. Revert; re-run; test passes.
 */
package lava.app.challenges

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.OnboardingBypassRule
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge26RutrackerMainAbsentFromServerListTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val onboardingBypass = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun serverList_doesNotShowMain_orRutrackerHost() {
        hiltRule.inject()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithText("Menu")
            .fetchSemanticsNodes()
            .firstOrNull()
        runCatching { composeRule.onNodeWithText("Menu").performClick() }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Server").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onAllNodesWithText("Main", substring = false).assertCountEquals(0)
        composeRule.onAllNodesWithText("rutracker.org", ignoreCase = true, substring = true)
            .assertCountEquals(0)
    }
}
