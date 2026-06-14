/*
 * Challenge Test C41 — Onboarding "Pick your providers" select-all / deselect-all
 * rendered-UI contract.
 *
 * Feature anchor (landed on master @bb357da8): the operator asked for a single
 * control on the Providers step so the user need not tap each row when the
 * chosen API's catalogue can be dozens of providers. `ProvidersStep.kt` now
 * renders a control tagged `SelectAllProvidersTestTag` ("select_all_providers")
 * whenever there are ≥2 providers. Tapping it:
 *   - if EVERY provider is currently selected  → deselect ALL
 *   - otherwise (any unselected)               → select ALL
 * The production action is `OnboardingAction.ToggleAllProviders`, reduced in
 * `OnboardingViewModel.onToggleAllProviders()`.
 *
 * Why this is honest under §6.J / §6.AB:
 * - The Composable rendered is the SAME production `lava.onboarding.steps.ProvidersStep`
 *   the production `OnboardingScreen` instantiates — no mock screen, no fake step.
 * - The label-toggle + checkbox-binding logic under test
 *   (`allSelected = providers.all { it.selected }` → "Deselect all" else
 *   "Select all"; each row Checkbox `checked = item.selected`) lives INSIDE the
 *   production composable, so this test exercises it directly. The toggle-all
 *   reduction is modelled by a real state hoist that flips every
 *   `ProviderOnboardingItem.selected` exactly as
 *   `OnboardingViewModel.onToggleAllProviders()` does (deselect-all when all
 *   selected, else select-all).
 * - The PRIMARY assertion is on user-visible rendered checkbox state (the
 *   provider-row Checkbox `ToggleableState`), per §6.AB rendering-correctness +
 *   Sixth Law clause 3 — not on a callback count.
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *
 *   1. In ProvidersStep.kt make the select-all control a no-op: change the Row's
 *      `.clickable { onToggleAll() }` to `.clickable { }` AND the control
 *      Checkbox's `onCheckedChange = { onToggleAll() }` to `onCheckedChange = {}`
 *      (the control still renders + is tappable, but the feature does nothing —
 *      the §6.AB non-crashing class).
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: `tappingSelectAll_deselectsThenReselectsEveryProvider`
 *      fails at the first post-tap assertion — every row Checkbox is still ON, so
 *      the deselect-all assertion throws
 *      "Failed to assert the following: (ToggleableState = Off) ... is 'On'".
 *   4. Revert the no-op; re-run; passes.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge41OnboardingSelectAllProvidersTest"
 */
package lava.app.challenges

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertAll
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import lava.designsystem.theme.LavaTheme
import lava.onboarding.ProviderOnboardingItem
import lava.onboarding.steps.ProvidersStep
import lava.onboarding.steps.SelectAllProvidersTestTag
import lava.sdk.api.MirrorUrl
import lava.tracker.api.AuthType
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerDescriptor
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
class Challenge41OnboardingSelectAllProvidersTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun descriptor(id: String, name: String): TrackerDescriptor =
        object : TrackerDescriptor {
            override val trackerId: String = id
            override val displayName: String = name
            override val baseUrls: List<MirrorUrl> =
                listOf(MirrorUrl(url = "https://$id.example", isPrimary = true))
            override val capabilities: Set<TrackerCapability> = setOf(TrackerCapability.SEARCH)
            override val authType: AuthType = AuthType.NONE
            override val encoding: String = "UTF-8"
            override val expectedHealthMarker: String = "marker"
            override val verified: Boolean = true
            override val apiSupported: Boolean = true
        }

    /**
     * Renders the real ProvidersStep with three providers all selected, hoisting
     * the provider list into mutable state and wiring `onToggleAll` to the SAME
     * select-all/deselect-all reduction the production ViewModel applies. Asserts
     * on the rendered Checkbox toggle state of every provider row across both
     * transitions, plus the user-visible control label.
     */
    // CHALLENGE: tapping select-all deselects every row, tapping again reselects
    // every row — primary assertion on rendered Checkbox state (user-visible).
    @Test
    fun tappingSelectAll_deselectsThenReselectsEveryProvider() {
        composeRule.setContent {
            // Real production reduction (mirrors OnboardingViewModel.onToggleAllProviders):
            // if every provider is selected → deselect all; else select all.
            var providers by mutableStateOf(
                listOf(
                    ProviderOnboardingItem(descriptor("alpha", "Alpha Tracker"), selected = true),
                    ProviderOnboardingItem(descriptor("bravo", "Bravo Tracker"), selected = true),
                    ProviderOnboardingItem(descriptor("charlie", "Charlie Tracker"), selected = true),
                ),
            )
            LavaTheme {
                ProvidersStep(
                    providers = providers,
                    hasSelection = providers.any { it.selected },
                    onToggle = { id ->
                        providers = providers.map {
                            if (it.descriptor.trackerId == id) it.copy(selected = !it.selected) else it
                        }
                    },
                    onToggleAll = {
                        val allSelected = providers.all { it.selected }
                        providers = providers.map { it.copy(selected = !allSelected) }
                    },
                    onNext = {},
                )
            }
        }

        // Initial state: every provider row's Checkbox is ON (selected = true),
        // and the control label reads "Deselect all".
        assertEveryProviderRowCheckbox(expectedOn = true)
        composeRule.onNodeWithText("Deselect all").assertExists()

        // TAP select-all → all were selected, so this DESELECTS all rows.
        composeRule.onNodeWithTag(SelectAllProvidersTestTag).performClick()
        composeRule.waitForIdle()
        assertEveryProviderRowCheckbox(expectedOn = false)
        composeRule.onNodeWithText("Select all").assertExists()

        // TAP again → not all selected (none are), so this SELECTS all rows.
        composeRule.onNodeWithTag(SelectAllProvidersTestTag).performClick()
        composeRule.waitForIdle()
        assertEveryProviderRowCheckbox(expectedOn = true)
        composeRule.onNodeWithText("Deselect all").assertExists()
    }

    /**
     * Asserts that EVERY provider-row Checkbox renders with the expected toggle
     * state. The screen has exactly four toggleable nodes: the three provider-row
     * Checkboxes + the one select-all-control Checkbox. The select-all control's
     * Checkbox tracks `allSelected`, which (because we hoist a real all-or-nothing
     * reduction) always equals the rows' state — so all four toggleable nodes
     * share the same state, and asserting all of them ⇔ asserting the rows.
     */
    private fun assertEveryProviderRowCheckbox(expectedOn: Boolean) {
        val expectedState = if (expectedOn) ToggleableState.On else ToggleableState.Off
        val matchesState = SemanticsMatcher("ToggleableState == $expectedState") { node ->
            node.config.getOrNull(SemanticsProperties.ToggleableState) == expectedState
        }
        // 3 provider rows + 1 select-all control = 4 toggleable checkboxes.
        composeRule.onAllNodes(isToggleable())
            .assertCountEquals(4)
            .assertAll(matchesState)
        // Belt-and-braces user-visible cross-check: the three provider NAMES are
        // always rendered regardless of selection (selection only flips the box).
        composeRule.onAllNodesWithText("Tracker", substring = true).assertCountEquals(3)
    }
}
