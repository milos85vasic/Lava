/*
 * Challenge Test C30 — Cloud / remote-server API selection rendered-UI contract.
 *
 * Sibling of C26 (`Challenge26ApiDiscoveryAndConnectivityTest`), which covers
 * the "On your network" (local mDNS discovery) section of the same
 * [ApiSelectionStep] composable. C30 covers the "Cloud / remote server"
 * section added 2026-05-31 (operator request): preset cloud defaults, the
 * manual address text field, the Add-server button enablement gate, and the
 * cloud-error rendering.
 *
 * Why this is honest under §6.J / §6.AB:
 * - The Composable rendered is the SAME production
 *   `lava.onboarding.steps.ApiSelectionStep` the production
 *   `OnboardingScreen` instantiates. No mock screen, no fake step.
 * - The assertions are on user-visible rendered text + interactive state
 *   (section titles, the cloud default's host:port label, the Add-server
 *   button enabled/disabled state, the error copy) — exactly what a tester
 *   sees on the device.
 * - The tap test asserts the production `onSelect` callback fires with the
 *   exact endpoint, mirroring C26's `tapped` capture pattern.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2, recorded in commit body):
 *
 *   Mutation: remove the `Text(text = "Cloud / remote server")` section
 *     header from ApiSelectionStep.kt.
 *   Observed-Failure (expected): `bothSectionsRender_showsLocalAndCloudTitles`
 *     fails — `onNodeWithText("Cloud / remote server")` returns an empty
 *     node-set → `assertIsDisplayed()` throws
 *     "Failed: assertExists ... Reason: Expected exactly '1' node but could
 *     not find any node that satisfies: (Text + EditableText contains
 *     'Cloud / remote server' (ignoreCase: false))".
 *   Reverted: yes.
 */
package lava.app.challenges

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import lava.designsystem.theme.LavaTheme
import lava.models.settings.Endpoint
import lava.onboarding.ApiConnectivityState
import lava.onboarding.steps.ApiSelectionStep
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
class Challenge30CloudApiSelectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    // CHALLENGE: both the local-network and cloud section headers render together,
    // and the preset cloud default's host:port label is visible.
    @Test
    fun bothSectionsRender_showsLocalAndCloudTitles() {
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
                    cloudInput = "",
                    cloudDefaults = listOf(Endpoint.GoApi(host = "lava.app", port = 7777)),
                    cloudError = null,
                    onCloudInputChange = {},
                    onAddCloud = {},
                )
            }
        }

        composeRule.onNodeWithText("On your network").assertIsDisplayed()
        composeRule.onNodeWithText("Cloud / remote server").assertIsDisplayed()
        composeRule.onNodeWithText("lava.app:7777").assertIsDisplayed()
    }

    // CHALLENGE: tapping a cloud-default row invokes onSelect with that exact endpoint.
    @Test
    fun tappingCloudDefault_invokesOnSelect() {
        val endpoint = Endpoint.GoApi(host = "lava.app", port = 7777)
        val tapped = mutableStateOf<Endpoint?>(null)
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = false,
                    discovered = emptyList(),
                    selected = null,
                    connectivity = ApiConnectivityState.Idle,
                    onSelect = { tapped.value = it },
                    onRetryDiscovery = {},
                    onRetryProbe = {},
                    cloudInput = "",
                    cloudDefaults = listOf(endpoint),
                    cloudError = null,
                    onCloudInputChange = {},
                    onAddCloud = {},
                )
            }
        }

        composeRule.onNodeWithText("lava.app:7777").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { tapped.value == endpoint }
        assert(tapped.value == endpoint) {
            "onSelect callback was not invoked with the tapped cloud-default endpoint"
        }
    }

    // CHALLENGE: the Add-server button is disabled while the manual-entry input is blank.
    @Test
    fun addServerButton_disabledWhenInputBlank() {
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
                    cloudInput = "",
                    cloudDefaults = emptyList(),
                    cloudError = null,
                    onCloudInputChange = {},
                    onAddCloud = {},
                )
            }
        }

        composeRule.onNodeWithText("Add server").assertIsNotEnabled()
    }

    // CHALLENGE: the Add-server button is enabled once a non-blank address is typed.
    @Test
    fun addServerButton_enabledWhenInputTyped() {
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
                    cloudInput = "https://h:1",
                    cloudDefaults = emptyList(),
                    cloudError = null,
                    onCloudInputChange = {},
                    onAddCloud = {},
                )
            }
        }

        composeRule.onNodeWithText("Add server").assertIsEnabled()
    }

    // CHALLENGE: a cloud-error message renders to the user.
    @Test
    fun cloudError_renders() {
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
                    cloudInput = "",
                    cloudDefaults = emptyList(),
                    cloudError = "Enter a valid address like https://host:port",
                    onCloudInputChange = {},
                    onAddCloud = {},
                )
            }
        }

        composeRule.onNodeWithText("Enter a valid address like https://host:port").assertIsDisplayed()
    }
}
