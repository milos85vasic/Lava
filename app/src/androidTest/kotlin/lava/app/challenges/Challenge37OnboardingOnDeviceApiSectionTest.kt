/*
 * Challenge Test C37 — Onboarding "On this device" API section rendered-UI contract.
 *
 * Third sibling of C26 (`Challenge26ApiDiscoveryAndConnectivityTest`, the
 * "On your network" mDNS section) and C30 (`Challenge30CloudApiSelectionTest`,
 * the "Cloud / remote server" section). C37 covers the "On this device"
 * section added 2026-06-03 (client↔api-app linking feature): the section that
 * launches the on-device Lava API app (or routes the user to install it).
 *
 * Why this is honest under §6.J / §6.AB:
 * - The Composable rendered is the SAME production
 *   `lava.onboarding.steps.ApiSelectionStep` the production `OnboardingScreen`
 *   instantiates — no mock screen, no fake step.
 * - Assertions are on user-visible rendered text + the real production
 *   callback: the section header "On this device", the install-state-driven
 *   button label ("Open Lava API app" when the API app is installed vs
 *   "Install Lava API app" when not), and that tapping the button invokes the
 *   production `onLaunchOnDeviceApi` callback (mirroring C26/C30's capture
 *   pattern).
 *
 * Scope boundary (operator-run on device): the FULL two-app round-trip — the
 * client launching the api-app with EXTRA_START_API, the api-app auto-starting
 * + "Back to Lava", and the client's loopback auto-connect to 127.0.0.1:<port>
 * with the per-endpoint key — is exercised end-to-end ONLY on the real
 * connected device (Samsung S23 Ultra, R5CW33CBVQV) per §6.Z. This isolated-
 * composable Challenge proves the rendered onboarding surface + the button
 * wiring; the on-device run proves the cross-app flow. Neither is claimed to
 * have executed until the operator's §6.Z evidence is captured.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2, recorded in commit body):
 *
 *   Mutation: in ApiSelectionStep.kt make the on-device Button label constant
 *     `text = "Open Lava API app"` (ignore onDeviceApiInstalled).
 *   Observed-Failure (expected): `notInstalled_showsInstallLabel` fails —
 *     onNodeWithText("Install Lava API app") finds no node →
 *     "Failed: assertExists ... could not find any node ... 'Install Lava API app'".
 *   Reverted: yes.
 */
package lava.app.challenges

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import lava.designsystem.theme.LavaTheme
import lava.onboarding.ApiConnectivityState
import lava.onboarding.steps.ApiSelectionStep
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
class Challenge37OnboardingOnDeviceApiSectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    // CHALLENGE: when the API app is installed, the section header renders and
    // the launch button reads "Open Lava API app".
    @Test
    fun onDeviceSection_rendersOpenLabel_whenInstalled() {
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = false,
                    discovered = emptyList(),
                    selected = null,
                    connectivity = ApiConnectivityState.Idle,
                    onSelect = {},
                    onRetryDiscovery = {},
                    onRetryProbe = {},
                    onLaunchOnDeviceApi = {},
                    onDeviceApiInstalled = true,
                )
            }
        }

        composeRule.onNodeWithText("On this device").assertIsDisplayed()
        composeRule.onNodeWithText("Open Lava API app").assertIsDisplayed()
    }

    // CHALLENGE: when the API app is NOT installed, the button reads
    // "Install Lava API app" (the install-or-open state contract).
    @Test
    fun notInstalled_showsInstallLabel() {
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = false,
                    discovered = emptyList(),
                    selected = null,
                    connectivity = ApiConnectivityState.Idle,
                    onSelect = {},
                    onRetryDiscovery = {},
                    onRetryProbe = {},
                    onLaunchOnDeviceApi = {},
                    onDeviceApiInstalled = false,
                )
            }
        }

        composeRule.onNodeWithText("Install Lava API app").assertIsDisplayed()
    }

    // CHALLENGE: tapping the on-device launch button invokes the production
    // onLaunchOnDeviceApi callback (the wiring that, in production, fires the
    // explicit api-app launch Intent with EXTRA_START_API).
    @Test
    fun tappingOnDeviceLaunch_invokesCallback() {
        val launched = mutableStateOf(false)
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = false,
                    discovered = emptyList(),
                    selected = null,
                    connectivity = ApiConnectivityState.Idle,
                    onSelect = {},
                    onRetryDiscovery = {},
                    onRetryProbe = {},
                    onLaunchOnDeviceApi = { launched.value = true },
                    onDeviceApiInstalled = true,
                )
            }
        }

        composeRule.onNodeWithContentDescription("api-ondevice-launch").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { launched.value }
        assert(launched.value) {
            "onLaunchOnDeviceApi callback was not invoked when the on-device launch button was tapped"
        }
    }
}
