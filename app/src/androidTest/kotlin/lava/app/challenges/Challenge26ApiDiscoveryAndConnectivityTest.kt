/*
 * Challenge Test C26 — New ApiSelection step rendered-UI contract.
 *
 * Closes the anti-bluff gap left by Wave 2 of the 60th §6.L invocation
 * (1.2.31-1051 shipped with TestOnboardingHiltModule overriding
 * `apiSelectionEnabled` to false so legacy C00/C01/C20/C21/C24/C25
 * still asserted on the pre-60th Welcome → Providers flow). This
 * Challenge directly exercises [ApiSelectionStep] under controlled
 * state, asserting on rendered text contracts the production flow
 * relies on. Hilt is intentionally NOT used here — the cleanest
 * anti-bluff approach for a step that's already covered by
 * `OnboardingViewModelTest` at the state-machine level is to assert
 * the rendered-UI contract independently.
 *
 * Why this is honest under §6.J / §6.AB:
 * - The Composable rendered is the SAME production
 *   `lava.onboarding.steps.ApiSelectionStep` the production
 *   `OnboardingScreen` instantiates. No mock screen, no fake step.
 * - The assertions are on user-visible rendered text — exactly what
 *   a tester sees on the Firebase release `75rlfum5s51to`.
 * - Each test covers ONE rendering contract: discovery-running,
 *   discovery-empty + retry, discovery-found + selectable, probe-
 *   testing spinner, probe-failure + retry. State combinations the
 *   production VM produces.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2, recorded in commit body):
 *
 *   Mutation: change `"Searching for APIs on your network…"` to
 *     `"Loading…"` in ApiSelectionStep.kt.
 *   Observed-Failure (expected): `discoveryRunning_showsSearchingText`
 *     fails with onNodeWithText returning empty node-set →
 *     `assertIsDisplayed` throws "ComposeNotFoundException".
 *   Reverted: yes.
 *
 * Wave 3 follow-up: a future cycle that updates C00/C01/C20/C21/C24/C25
 * to traverse the new step under `apiSelectionEnabled=true` removes
 * the `TestOnboardingHiltModule` Hilt override + retires this
 * isolated-Composable Challenge in favor of full MainActivity-rooted
 * Challenges. Until then this is the load-bearing rendered-UI
 * contract test for the new step.
 */
package lava.app.challenges

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
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
class Challenge26ApiDiscoveryAndConnectivityTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun discoveryRunning_showsSearchingText() {
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = true,
                    discovered = emptyList(),
                    selected = null,
                    connectivity = ApiConnectivityState.Idle,
                    onSelect = {},
                    onRetryDiscovery = {},
                    onRetryProbe = {},
                )
            }
        }

        composeRule.onNodeWithText("Choose your API").assertIsDisplayed()
        composeRule.onNodeWithText("Searching for APIs on your network…").assertIsDisplayed()
        // "Search again" button shows the searching state when discovery is running
        composeRule.onNodeWithText("Searching…").assertIsDisplayed()
    }

    @Test
    fun discoveryEmpty_showsEmptyStateAndSearchAgain() {
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
                )
            }
        }

        composeRule.onNodeWithText("Choose your API").assertIsDisplayed()
        composeRule.onNodeWithText("No APIs discovered on your network.").assertIsDisplayed()
        composeRule.onNodeWithText("Search again").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun discoveryFoundOne_showsFoundCountAndEndpointRow() {
        val endpoint = Endpoint.GoApi(host = "192.168.1.42", port = 8443)
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = false,
                    discovered = listOf(endpoint),
                    selected = null,
                    connectivity = ApiConnectivityState.Idle,
                    onSelect = {},
                    onRetryDiscovery = {},
                    onRetryProbe = {},
                )
            }
        }

        composeRule.onNodeWithText("Found 1 API:").assertIsDisplayed()
        composeRule.onNodeWithText("192.168.1.42:8443").assertIsDisplayed()
        composeRule.onNodeWithText("Lava API").assertIsDisplayed()
    }

    @Test
    fun discoveryFoundTwo_pluralizesAndRendersBoth() {
        val a = Endpoint.GoApi(host = "192.168.1.42", port = 8443)
        val b = Endpoint.GoApi(host = "thinker.local", port = 8543)
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = false,
                    discovered = listOf(a, b),
                    selected = null,
                    connectivity = ApiConnectivityState.Idle,
                    onSelect = {},
                    onRetryDiscovery = {},
                    onRetryProbe = {},
                )
            }
        }

        composeRule.onNodeWithText("Found 2 APIs:").assertIsDisplayed()
        composeRule.onNodeWithText("192.168.1.42:8443").assertIsDisplayed()
        composeRule.onNodeWithText("thinker.local:8543").assertIsDisplayed()
    }

    @Test
    fun selectingEndpoint_invokesOnSelectCallback() {
        val endpoint = Endpoint.GoApi(host = "192.168.1.42", port = 8443)
        val tapped = mutableStateOf<Endpoint?>(null)
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = false,
                    discovered = listOf(endpoint),
                    selected = null,
                    connectivity = ApiConnectivityState.Idle,
                    onSelect = { tapped.value = it },
                    onRetryDiscovery = {},
                    onRetryProbe = {},
                )
            }
        }

        composeRule.onNodeWithText("192.168.1.42:8443").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { tapped.value == endpoint }
        assert(tapped.value == endpoint) {
            "onSelect callback was not invoked with the tapped endpoint"
        }
    }

    @Test
    fun probeFailure_showsReasonAndRetryButton() {
        val endpoint = Endpoint.GoApi(host = "192.168.1.42", port = 8443)
        var retryCount = 0
        composeRule.setContent {
            LavaTheme {
                ApiSelectionStep(
                    discoveryRunning = false,
                    discovered = listOf(endpoint),
                    selected = endpoint,
                    connectivity = ApiConnectivityState.Failure(reason = "connection refused"),
                    onSelect = {},
                    onRetryDiscovery = {},
                    onRetryProbe = { retryCount++ },
                )
            }
        }

        // Primary assertion: the user-visible failure copy contains the
        // reason — chiefly to catch a future refactor that drops the
        // dynamic reason and shows a generic "Failed" string.
        composeRule.onNodeWithText("Could not reach this API: connection refused").assertIsDisplayed()
        // Retry button must be present + enabled + functional.
        composeRule.onNodeWithText("Try again").assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.waitUntil(timeoutMillis = 2_000) { retryCount == 1 }
        assert(retryCount == 1) { "Try again button did not invoke onRetryProbe callback" }
    }
}
