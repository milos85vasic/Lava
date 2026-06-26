/*
 * Challenge Test C66 — Onboarding "Select all" MUST NOT silently enable
 * auth-requiring providers (LVA-090 / Video #9).
 *
 * DRAFT — authored by the test-engineering stream; NOT yet executed on device.
 * The main stream device-runs this reproduce-first (RED on the un-fixed build,
 * GREEN on the fixed build). No pass is claimed here.
 *
 * OPERATOR-REPORTED DEFECT (video #9, recorded against 1076):
 *   "Onboarding 'Select all' silently enables auth-requiring (Captcha / Form
 *    Login) providers." The Provider-Picker "Select all" toggled EVERY provider
 *    regardless of `AuthType`, including credential-required ones
 *    (RuTracker = CAPTCHA_LOGIN, Kinozal = FORM_LOGIN). A bulk-selected
 *    credential-required provider strands the user on a Configure page they
 *    cannot pass, or persists a credential-less provider that 401s on search.
 *
 * WHAT THE PRODUCTION FIX DID (verified in source — Issue #9,
 * OnboardingViewModel.onToggleAllProviders + requiresNoCredentials()):
 *   "Select all" now auto-selects ONLY providers usable without credentials —
 *   `authType == AuthType.NONE` OR `supportsAnonymous == true`. A
 *   credential-required provider stays unselected; the user must tap it
 *   explicitly (the informed opt-in that routes them through Configure). The
 *   real gate lives in the production `OnboardingViewModel`, so THIS Challenge
 *   drives the full onboarding flow through `MainActivity` to the REAL
 *   ProvidersStep backed by the REAL ViewModel — NOT a hoisted re-implementation
 *   (that would be a §6.J bluff). C41 already covers the rendered select-all
 *   MECHANICS with a hoisted reduction; C66 covers the AUTH-GATE itself.
 *
 * WHAT THE USER DOES (the production surface this Challenge traverses verbatim):
 *   fresh install → MainActivity → "Welcome to Lava" → tap "Get Started" →
 *   "Pick your providers" (the REAL ProvidersStep, real OnboardingViewModel) →
 *   tap the "Select all" control → observe which provider rows became checked.
 *
 * WHY THE COUNT ASSERTION IS HONEST + FALSIFIABLE (§6.J / §6.AB):
 *   The production catalogue mixes credential-required providers
 *   (RuTracker.org = CAPTCHA_LOGIN, Kinozal.tv = FORM_LOGIN) with
 *   no-credentials providers (Internet Archive = NONE, Project Gutenberg =
 *   NONE). After a CORRECT "Select all":
 *     - at least one no-credentials provider is selected  → ON count >= 1
 *     - at least one credential-required provider stays unselected, and the
 *       select-all control's own checkbox is OFF (not ALL selected)
 *       → ON count < TOTAL toggleable count
 *   The pre-fix / mutated behavior selects EVERYTHING → every toggleable node
 *   (every provider row + the select-all control) is ON → ON count == TOTAL →
 *   the `onCount < total` assertion fails. The chief failure signal is on the
 *   rendered checkbox toggle state — user-visible (Sixth Law clause 3).
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix)
 * 1. Apply the mutation in OnboardingViewModel.onToggleAllProviders(): make
 *    "Select all" select EVERY provider unconditionally (the pre-Issue-#9
 *    behavior). Concretely, replace the per-item target with:
 *        val target = if (allReachableSelected) false else true
 *    (i.e. ignore `item.requiresNoCredentials()` on the select branch).
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run THIS Challenge only:
 *    adb shell am instrument -w -e class \
 *      lava.app.challenges.Challenge66SelectAllDoesNotEnableAuthProvidersTest \
 *      digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner
 * 4. Expected failure: selectAll_leavesAuthRequiringProvidersUnselected fails —
 *    after "Select all" every toggleable checkbox is ON, so
 *      assertTrue("... onCount (N) must be < total (N) ...", onCount < total)
 *    throws an AssertionError because onCount == total.
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout OnboardingViewModel.kt).
 * 6. Rebuild + re-run the identical Challenge.
 * 7. Expected pass: after "Select all", the no-credentials providers are
 *    checked and the credential-required ones remain unchecked
 *    (onCount in 1 until total).
 *
 * ### Mutation type
 * NON-CRASHING BREAK — incorrect provider-selection set, no crash (§6.AB.3).
 *
 * ### LVA-008 dependency
 * The onboarding flow renders inside MainActivity. C66 inherits the SAME
 * onboarding navigation that the committed C20 full-flow Challenge relies on
 * (Welcome → "Get Started" → "Pick your providers"). If the gating build routes
 * "Get Started" through the ApiSelection step first, the `waitUntil` for "Pick
 * your providers" fails honestly with a Compose timeout (NOT a false pass), and
 * the device-run operator adapts the navigation (select a reachable API first).
 *
 * // covers-changelog: LVA-090
 * // covers-feature: onboarding
 */
package lava.app.challenges

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import lava.onboarding.steps.SelectAllProvidersTestTag
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge66SelectAllDoesNotEnableAuthProvidersTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Matches a toggleable node whose rendered state is ON (checked).
    private val isOn = SemanticsMatcher("ToggleableState == On") { node ->
        node.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
    }

    @Test
    fun selectAll_leavesAuthRequiringProvidersUnselected() {
        hiltRule.inject()

        // Step 1 — Welcome.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2 — reach the REAL "Pick your providers" step. (See the LVA-008 /
        // navigation note in the class KDoc — a timeout here is an honest
        // failure, never a false pass.)
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Pick your providers").assertExists()

        // Wait until loadProviders() has populated the picker with a known
        // NONE-auth provider row (Internet Archive) BEFORE tapping "Select all".
        // Tapping before the catalogue loads counts zero toggleable rows → an
        // ambiguous onCount==0 (the device RED seen 2026-06-26). This guard makes
        // a genuinely-missing no-cred provider fail HONESTLY (timeout: "Internet
        // Archive" never rendered) rather than masquerading as a Select-all
        // result, and removes the load-timing flake.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Internet Archive", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // The select-all control renders only when there are >= 2 providers
        // (true for the production catalogue). Tap it.
        composeRule.onNodeWithTag(SelectAllProvidersTestTag).performClick()
        composeRule.waitForIdle()

        // Count toggleable nodes (= every provider-row Checkbox + the select-all
        // control Checkbox) and how many are ON after "Select all".
        val total = composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().size
        val onCount = composeRule.onAllNodes(isToggleable().and(isOn)).fetchSemanticsNodes().size

        // §6.AK diagnostic — capture WHAT the picker rendered after select-all so a
        // device RED is self-explaining (total==1 → only the control is toggleable
        // i.e. rows are not checkbox-semantic = test-query gap; total>=N but
        // onCount==0 → select-all did not propagate to the checkboxes).
        val archiveRows = composeRule.onAllNodesWithText("Internet Archive", substring = true).fetchSemanticsNodes().size
        val gutenbergRows = composeRule.onAllNodesWithText("Gutenberg", substring = true).fetchSemanticsNodes().size
        val rutrackerRows = composeRule.onAllNodesWithText("RuTracker", substring = true).fetchSemanticsNodes().size
        val diag = "total=$total onCount=$onCount rows[Archive=$archiveRows Gutenberg=$gutenbergRows RuTracker=$rutrackerRows]"

        // PRIMARY ASSERTION (positive) — "Select all" actually selected the
        // no-credentials providers, so the user makes progress.
        assertTrue(
            "After tapping 'Select all', at least one no-credentials provider " +
                "must be selected (the Internet Archive / Project Gutenberg " +
                "NONE-auth providers). DIAG: $diag",
            onCount >= 1,
        )

        // PRIMARY ASSERTION (the LVA-090 gate) — NOT every provider got
        // selected: the credential-required providers (RuTracker = CAPTCHA_LOGIN,
        // Kinozal = FORM_LOGIN) must stay unselected, and the select-all control
        // itself is OFF (not all selected). The pre-fix behavior selected
        // everything, making onCount == total.
        assertTrue(
            "'Select all' must NOT silently enable auth-requiring providers. " +
                "After 'Select all', ON checkbox count ($onCount) must be " +
                "STRICTLY LESS than the total toggleable count ($total) — at " +
                "least one credential-required provider (and the select-all " +
                "control) must remain unchecked. onCount == total means every " +
                "provider was bulk-enabled (the LVA-090 defect).",
            onCount < total,
        )

        // Belt-and-braces user-visible cross-check: a known credential-required
        // provider row is rendered on this screen (its presence is what the gate
        // is protecting the user from auto-enabling).
        composeRule.onAllNodesWithText("Captcha Login", substring = true)
            .fetchSemanticsNodes()
            .let { captchaSubtitleNodes ->
                assertTrue(
                    "Expected at least one credential-required provider " +
                        "(a 'Captcha Login' or 'Form Login' subtitle) to be " +
                        "present so the auth-gate is meaningful. Found none — " +
                        "the catalogue under test may lack an auth-requiring " +
                        "provider; the device-run operator must use a catalogue " +
                        "that includes RuTracker / Kinozal.",
                    captchaSubtitleNodes.isNotEmpty() ||
                        composeRule.onAllNodesWithText("Form Login", substring = true)
                            .fetchSemanticsNodes().isNotEmpty(),
                )
            }
    }
}
