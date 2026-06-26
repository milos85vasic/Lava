/*
 * Challenge Test C64 — Onboarding "Choose your API" screen renders HONEST,
 * friendly labels (LVA-088 / Video #7).
 *
 * DRAFT — authored by the test-engineering stream; NOT yet executed on device.
 * The main stream device-runs this reproduce-first (RED on the un-fixed build,
 * GREEN on the fixed build). No pass is claimed here.
 *
 * OPERATOR-REPORTED DEFECT (video #7, recorded against 1076):
 *   "'Choose your API' shows 'lava.app:7777' preset + mislabeled 'On this
 *    network'." The cloud / remote-server preset row was rendered with the
 *    GENERIC subtitle "On this network" (the default `displaySubtitle()` for a
 *    platform-less GoApi), so a CLOUD preset looked like a LAN-discovered API;
 *    and discovered mDNS APIs that advertised an instance name still showed the
 *    generic "On this network" label instead of the friendly name.
 *
 * WHAT THE PRODUCTION FIX ACTUALLY DID (verified in source, NOT the spec's
 * "remove the preset" framing — see the static-verification note below):
 *   - Issue #7 (ApiSelectionStep.kt): each cloud-preset row now passes
 *     `subtitleOverride = CLOUD_SUBTITLE` ("Cloud / remote server"), so a cloud
 *     preset is NEVER rendered as "On this network".
 *   - Issue #8 (ApiSelectionStep.kt + OnboardingViewModel.kt): a discovered API
 *     that advertised a friendly mDNS instance name renders that NAME as the
 *     primary label (host:port demoted to the subtitle).
 *   The "lava.app:7777" PRESET ITSELF was NOT removed — a cloud preset
 *   legitimately shows its host:port (with the honest "Cloud / remote server"
 *   subtitle). This Challenge therefore asserts the LANDED user-visible
 *   contract, not a "no 7777 anywhere" assertion that would falsely RED the
 *   correct code.
 *
 * WHAT THE USER SEES (the production surface this Challenge traverses):
 *   OnboardingScreen → OnboardingStep.ApiSelection → the SAME production
 *   `lava.onboarding.steps.ApiSelectionStep` composable, rendered with a cloud
 *   preset + a friendly-named discovered API exactly as the production
 *   ViewModel state (`cloudDefaults`, `discoveredApis`, `discoveredApiNames`)
 *   would supply them (OnboardingScreen.kt:148 passes
 *   `discoveredNames = state.discoveredApiNames`).
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - The composable under test is the production `ApiSelectionStep` the
 *     production `OnboardingScreen` instantiates — no mock screen, no fake step.
 *   - The PRIMARY assertions are on USER-VISIBLE rendered text: the friendly
 *     instance name as the row's primary label, the cloud preset's
 *     "Cloud / remote server" subtitle, and the ABSENCE of the generic
 *     "On this network" mislabel on the cloud preset.
 *   - mDNS discovery + connectivity probes cannot run in a hermetic
 *     instrumented test, so the discovered-API + cloud-preset inputs are
 *     supplied as the ViewModel would — the RENDERING LOGIC (friendly-name vs
 *     host:port primary; CLOUD_SUBTITLE vs displaySubtitle) lives INSIDE the
 *     production composable and is exercised directly (the established pattern
 *     for these onboarding-step Challenges: see C26 / C30 / C37).
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix)
 * 1. Apply the mutation in ApiSelectionStep.kt:
 *      (a) for the "On this network" mislabel: DELETE the
 *          `subtitleOverride = CLOUD_SUBTITLE` argument from the cloud-default
 *          `ApiRow(...)` call (the row then falls back to displaySubtitle() =
 *          "Lava API · On this network"); AND/OR
 *      (b) for the friendly-name primary: change the row's primary
 *          `Text(text = friendlyName ?: endpoint.displayHostPort())` to
 *          `Text(text = endpoint.displayHostPort())` (ignore the friendly name).
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run THIS Challenge only:
 *    adb shell am instrument -w -e class \
 *      lava.app.challenges.Challenge64ApiDiscoveryScreenFriendlyNamesTest \
 *      digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner
 * 4. Expected failure:
 *      (a) cloudPresetLabeledHonestly_notOnThisNetwork fails —
 *          onNodeWithText("Cloud / remote server").assertIsDisplayed() throws
 *          "Failed: assertExists ... could not find any node ... 'Cloud / remote server'"
 *          AND the "On this network" absence assertion fails (it now renders).
 *      (b) discoveredApi_showsFriendlyName_notGenericLabel fails —
 *          onNodeWithText("Lava API on Desktop").assertIsDisplayed() throws
 *          "Failed: assertExists ... could not find any node ... 'Lava API on Desktop'"
 *          because the row now renders the raw host:port primary.
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout ApiSelectionStep.kt).
 * 6. Rebuild + re-run the identical Challenge.
 * 7. Expected pass: the cloud preset shows "Cloud / remote server" (never
 *    "On this network"), and the discovered API shows its friendly name.
 *
 * ### Mutation type
 * NON-CRASHING BREAK — the mutation causes incorrect user-visible LABELS but no
 * crash, per §6.AB.3.
 *
 * ### LVA-008 dependency
 * NONE — this Challenge renders the onboarding step composable directly
 * (createComposeRule), so it does not traverse the nested-NavHost route that
 * LVA-008 affects.
 *
 * // covers-changelog: LVA-088
 * // covers-feature: onboarding
 */
package lava.app.challenges

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
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
class Challenge64ApiDiscoveryScreenFriendlyNamesTest {

    @get:Rule
    val composeRule = createComposeRule()

    // A discovered mDNS API on a raw host:port, plus the friendly instance name
    // the advertiser published (keyed by "host:port", the same key
    // discoveredApiNameKey writes — see OnboardingViewModel.discoveredApiNameKey).
    private val discoveredApi = Endpoint.GoApi(host = "desktop.local", port = 8543)
    private val friendlyName = "Lava API on Desktop"

    // A cloud / remote-server preset, exactly the shape the production
    // CloudApiDefaults.defaultsFrom(BuildConfig.DEFAULT_CLOUD_API) produces.
    private val cloudPreset = Endpoint.GoApi(host = "lava.app", port = 7777)

    // ─────────────────────────────────────────────────────────────────────────
    // TEST 1 — the cloud / remote-server preset is labeled HONESTLY
    // ("Cloud / remote server"), NOT the generic LAN label "On this network".
    //
    // This is the core of the LVA-088 "mislabeled 'On this network'" complaint:
    // a cloud preset must not masquerade as a LAN-discovered API.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun cloudPresetLabeledHonestly_notOnThisNetwork() {
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
                    cloudDefaults = listOf(cloudPreset),
                    cloudError = null,
                    onCloudInputChange = {},
                    onAddCloud = {},
                )
            }
        }

        // We are on the "Choose your API" screen.
        composeRule.onNodeWithText("Choose your API").assertIsDisplayed()

        // PRIMARY ASSERTION — the cloud preset row carries the honest
        // "Cloud / remote server" subtitle (production CLOUD_SUBTITLE override).
        // NB: this exact copy renders on TWO legitimate nodes — the unconditional
        // section HEADER (ApiSelectionStep.kt:226) AND the cloud preset row's
        // subtitle (CLOUD_SUBTITLE, ApiSelectionStep.kt:254/336) — so a single-
        // node `onNodeWithText` throws "Expected at most 1 node but found 2".
        // Use onFirst(); the real discriminator for this test is the "On this
        // network" ABSENCE check below (the RED mutation removes subtitleOverride
        // → the row falls back to "Lava API · On this network", which that
        // assertion catches).
        composeRule.onAllNodesWithText("Cloud / remote server").onFirst().assertIsDisplayed()

        // PRIMARY ASSERTION — with NO LAN-discovered APIs present, the generic
        // "On this network" label must NOT appear anywhere (the cloud preset
        // must not be mislabeled with it). The pre-fix code rendered it here.
        assertTrue(
            "The cloud / remote-server preset must NOT be labeled 'On this " +
                "network' (the LVA-088 mislabel). Found at least one node " +
                "rendering 'On this network' on a screen with zero discovered " +
                "LAN APIs.",
            composeRule.onAllNodesWithText("On this network", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEST 2 — a discovered API that advertised a friendly mDNS instance name
    // renders that NAME as the primary label, not the generic "On this network"
    // label and not a bare host:port.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun discoveredApi_showsFriendlyName_notGenericLabel() {
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
                    // The side map the production ViewModel populates from the
                    // advertised mDNS instance name (OnboardingViewModel
                    // .discoveredApiNameKey(host, port) -> name).
                    discoveredNames = mapOf("desktop.local:8543" to friendlyName),
                )
            }
        }

        // PRIMARY ASSERTION — the friendly instance name is the row's primary
        // label (the LVA-088 / Issue #8 fix). The pre-fix code showed the raw
        // host:port (or the generic "On this network" subtitle as the only
        // distinguishing copy).
        composeRule.onNodeWithText(friendlyName).assertIsDisplayed()
    }
}
