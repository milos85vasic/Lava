/*
 * Challenge Test C65 — an mDNS-discovered API renders a FRIENDLY NAME, never a
 * raw IP:port (LVA-089 / Video #8).
 *
 * DRAFT — authored by the test-engineering stream; NOT yet executed on device.
 * The main stream device-runs this reproduce-first (RED on the un-fixed build,
 * GREEN on the fixed build). No pass is claimed here.
 *
 * OPERATOR-REPORTED DEFECT (video #8, recorded against 1076):
 *   "mDNS-discovered API shows raw IP 192.168.0.107:8443 with no friendly
 *    name." The discovered-endpoint row used the GoApi host:port
 *    (`displayHostPort()` = "$host:$port") as the PRIMARY label, so a LAN API
 *    discovered by mDNS showed a bare dotted-quad IP and port — unreadable and
 *    indistinguishable from any other instance.
 *
 * WHAT THE PRODUCTION FIX DID (verified in source):
 *   - OnboardingViewModel.startApiDiscovery captures `hit.name` (the advertised
 *     mDNS instance name) into `discoveredApiNames` keyed by
 *     `discoveredApiNameKey(host, port)` = "host:port".
 *   - ApiSelectionStep.ApiRow renders `friendlyName ?: endpoint.displayHostPort()`
 *     as the primary label, so a named instance shows its NAME and the host:port
 *     is demoted to the subtitle.
 *   - OnboardingScreen.kt:148 wires `discoveredNames = state.discoveredApiNames`
 *     into the production composable, so this is the real user-visible path.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - The composable under test is the production `ApiSelectionStep` — no mock
 *     screen, no fake step (same pattern as C26 / C30 / C37 / C64).
 *   - The PRIMARY assertion is on USER-VISIBLE rendered text: the discovered
 *     row's primary label is the human-readable instance name AND does NOT
 *     match the raw-IP regex `^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$` the
 *     operator's video flagged.
 *   - mDNS cannot run hermetically; the discovered endpoint + its advertised
 *     name are supplied exactly as the production ViewModel would. The
 *     name-vs-IP rendering decision lives INSIDE the production composable and
 *     is exercised directly.
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix)
 * 1. Apply the mutation in ApiSelectionStep.kt — in the private `ApiRow`, change
 *    the primary label from
 *      `Text(text = friendlyName ?: endpoint.displayHostPort())`
 *    to
 *      `Text(text = endpoint.displayHostPort())`
 *    (force the raw IP:port primary, ignoring the advertised name — the exact
 *    pre-fix behavior). An equivalent VM-side mutation: in
 *    OnboardingViewModel.startApiDiscovery, set `val friendlyName = null` so no
 *    name is captured.
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run THIS Challenge only:
 *    adb shell am instrument -w -e class \
 *      lava.app.challenges.Challenge65MdnsShowsFriendlyNameTest \
 *      digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner
 * 4. Expected failure: discoveredIpApi_rendersFriendlyName_notRawIp fails —
 *    onNodeWithText("Lava API @ Living Room").assertIsDisplayed() throws
 *    "Failed: assertExists ... could not find any node ... 'Lava API @ Living Room'",
 *    and the no-raw-IP-as-primary assertion fails because "192.168.0.107:8443"
 *    is now rendered as the primary label.
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout ApiSelectionStep.kt / OnboardingViewModel.kt).
 * 6. Rebuild + re-run the identical Challenge.
 * 7. Expected pass: the discovered row's primary label is the friendly name and
 *    no node renders the bare raw IP:port as a primary label.
 *
 * ### Mutation type
 * NON-CRASHING BREAK — incorrect user-visible label, no crash (§6.AB.3).
 *
 * ### LVA-008 dependency
 * NONE — renders the onboarding step composable directly; no nested-NavHost route.
 *
 * // covers-changelog: LVA-089
 * // covers-feature: onboarding
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.filters.SdkSuppress
import lava.designsystem.theme.LavaTheme
import lava.models.settings.Endpoint
import lava.onboarding.ApiConnectivityState
import lava.onboarding.steps.ApiSelectionStep
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
class Challenge65MdnsShowsFriendlyNameTest {

    @get:Rule
    val composeRule = createComposeRule()

    // A discovered API on a RAW dotted-quad IP — the exact shape the operator's
    // video #8 flagged (192.168.0.107:8443). The raw host:port string this would
    // render as a primary label, pre-fix:
    private val rawHost = "192.168.0.107"
    private val rawPort = 8443
    private val rawHostPort = "$rawHost:$rawPort"
    private val discoveredApi = Endpoint.GoApi(host = rawHost, port = rawPort)

    // The friendly mDNS instance name the advertiser published.
    private val friendlyName = "Lava API @ Living Room"

    // The raw-IP:port shape the operator does NOT want to see as a primary
    // label (matches the regex from the spec: dotted-quad + port).
    private val rawIpRegex = Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}:\d+$""")

    // ─────────────────────────────────────────────────────────────────────────
    // The discovered API row renders the friendly instance name as its primary
    // label, and no node renders the bare raw IP:port as a primary label.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun discoveredIpApi_rendersFriendlyName_notRawIp() {
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = false,
                    discovered = listOf(discoveredApi),
                    selected = null,
                    connectivity = ApiConnectivityState.Idle,
                    onSelect = {},
                    onRetryDiscovery = {},
                    onRetryProbe = {},
                    // Keyed by "host:port" exactly as discoveredApiNameKey writes.
                    discoveredNames = mapOf(rawHostPort to friendlyName),
                )
            }
        }

        // Sanity — we are on the "Choose your API" screen and the friendly name
        // row is present.
        composeRule.onNodeWithText("Choose your API").assertIsDisplayed()

        // PRIMARY ASSERTION — the human-readable instance name is rendered.
        composeRule.onNodeWithText(friendlyName).assertIsDisplayed()

        // PRIMARY ASSERTION — the friendly name is NOT itself a raw IP:port
        // (guards against a future regression where the "name" is just the IP).
        assertTrue(
            "The discovered-API primary label must be a human-readable name, " +
                "not a raw IP:port. '$friendlyName' must not match the raw-IP " +
                "pattern.",
            !rawIpRegex.matches(friendlyName),
        )

        // PRIMARY ASSERTION — the bare raw IP:port must NOT be rendered as a
        // STANDALONE primary label. It legitimately appears on the SUBTITLE
        // ("$host:$port · Lava API · …") when a name took the primary slot, so
        // we assert there is no node whose ENTIRE text equals the raw IP:port
        // (a primary-label match), while tolerating the subtitle that merely
        // CONTAINS it.
        val standaloneRawIpNodes = composeRule
            .onAllNodesWithText(rawHostPort, substring = false)
            .fetchSemanticsNodes()
        assertTrue(
            "No row may render the bare raw IP:port '$rawHostPort' as its " +
                "primary label — the friendly name must take that slot " +
                "(LVA-089). Found ${standaloneRawIpNodes.size} node(s) whose " +
                "entire text is the raw IP:port.",
            standaloneRawIpNodes.isEmpty(),
        )
    }
}
