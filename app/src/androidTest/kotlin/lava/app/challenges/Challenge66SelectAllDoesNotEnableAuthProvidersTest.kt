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

        // ── Real Select-All semantics (verified from OnboardingViewModel.kt) ──
        // The picker loads with ALL providers selected (ProviderOnboardingItem.selected
        // defaults to true, OnboardingState.kt:18). onToggleAllProviders is a TOGGLE
        // (OnboardingViewModel.kt:591): when every NO-CRED provider is already selected
        // (true at the all-selected default), one tap CLEARS everything; the next tap
        // selects ONLY the no-credentials providers (auth-requiring stay off — the
        // Issue-#9 / LVA-090 gate, requiresNoCredentials()). So from the default we tap
        // TWICE to reach the select-only-no-cred state the operator's video #9 concerns.
        // (The earlier single-tap model device-RED'd with onCount==0 — it was observing
        // the toggle's CLEAR branch, not a broken Select-All; see the §6.AK c66 diag.)
        val total = composeRule.onAllNodes(isToggleable()).fetchSemanticsNodes().size

        // Tap 1 — from the all-selected default this CLEARS everything.
        composeRule.onNodeWithTag(SelectAllProvidersTestTag).performClick()
        composeRule.waitForIdle()
        val afterClear = composeRule.onAllNodes(isToggleable().and(isOn)).fetchSemanticsNodes().size

        // Tap 2 — nothing is "all-no-cred-selected" now, so this selects ONLY the
        // no-credentials providers.
        composeRule.onNodeWithTag(SelectAllProvidersTestTag).performClick()
        composeRule.waitForIdle()
        val afterSelectNoCred = composeRule.onAllNodes(isToggleable().and(isOn)).fetchSemanticsNodes().size

        val diag = "total=$total afterClear=$afterClear afterSelectNoCred=$afterSelectNoCred"

        // SECONDARY — the toggle's CLEAR branch: from the all-selected default, the
        // first tap takes every checkbox OFF (documents the verified toggle semantics;
        // an honest signal if the default ever stops being all-selected).
        assertTrue(
            "From the all-selected default, the first 'Select all' tap must CLEAR " +
                "every checkbox (verified toggle semantics). DIAG: $diag",
            afterClear == 0,
        )

        // PRIMARY (positive) — the select-no-cred gesture enables the usable
        // (no-credentials) providers, so the user makes progress.
        assertTrue(
            "After the select-all-no-cred gesture, at least one no-credentials " +
                "provider must be selected (Internet Archive / Project Gutenberg / " +
                "RuTor). DIAG: $diag",
            afterSelectNoCred >= 1,
        )

        // PRIMARY (the LVA-090 gate) — 'Select all' must NOT bulk-enable the
        // auth-requiring providers: the ON count must be STRICTLY LESS than total
        // (the credential-required providers + the select-all control stay off). The
        // pre-Issue-9 defect selected EVERY provider unconditionally →
        // afterSelectNoCred == total.
        assertTrue(
            "'Select all' must NOT silently enable auth-requiring providers. After " +
                "the select-no-cred gesture, the ON count ($afterSelectNoCred) must be " +
                "STRICTLY LESS than total ($total) — credential-required providers " +
                "(RuTracker = Captcha, Kinozal = Form) must remain unchecked. " +
                "afterSelectNoCred == total means every provider was bulk-enabled " +
                "(the LVA-090 defect). DIAG: $diag",
            afterSelectNoCred in 1 until total,
        )

        // Belt-and-braces — a credential-required provider row is actually present,
        // so the gate above is meaningful (not vacuously true on an all-no-cred set).
        assertTrue(
            "Expected at least one credential-required provider ('Captcha Login' / " +
                "'Form Login' subtitle) so the auth-gate is meaningful. Found none — " +
                "the catalogue under test must include RuTracker / Kinozal.",
            composeRule.onAllNodesWithText("Captcha Login", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Form Login", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }
}
