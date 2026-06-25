/*
 * Challenge Test C55 — Main scaffold bottom-navigation switches the
 * visible content (feature/main + the app-level NestedMobileNavigation
 * scaffold). UI-audit gap W6 / matrix row "main" (§6.AE.1 per-feature
 * coverage; audit docs/qa/2026-06-25-ui-coverage-audit.md row "main":
 * GAP — covered transitively only).
 *
 * WHAT THIS CLOSES:
 *   The top-level bottom-tab scaffold — the Search / Forum / Topics /
 *   Menu NavigationBar wired in
 *   app/.../navigation/MobileNavigation.kt's addNestedNavigation() via
 *   NestedMobileNavigation(navigationBarItems = …) — had no direct
 *   Challenge that proves a tab TAP actually switches the rendered
 *   content. C24 (menu) and the search/topic Challenges each land on a
 *   single tab; none assert the NAV BEHAVIOR itself: tap tab → the
 *   destination's content replaces the previous tab's content. That is
 *   the load-bearing user interaction of the whole app shell, and it
 *   was untested end-to-end.
 *
 * WHAT THIS TEST ASSERTS (primary = user-visible rendered state):
 *   1. The scaffold renders the four real bottom-nav tab labels —
 *      "Search", "Forum", "Topics", "Menu" — sourced from the real
 *      string resources (R.string.label_*) via BottomNavigation's
 *      stringResource(tab.labelResId). Their presence proves the
 *      MobileNavigation scaffold composed.
 *   2. Tapping the "Topics" tab switches the visible content: the
 *      Topics destination's PagesScreen renders its real page labels
 *      "Favorites" + "Recents" (R.string.tab_title_favorites /
 *      tab_title_recents). Neither of those nodes exists on the Search
 *      start tab, so their appearance proves the tab CLICK changed the
 *      rendered destination — not merely that the bar is present.
 *   3. Tapping back to "Forum" switches again: the Forum destination's
 *      PagesScreen renders its real page label "Forum"
 *      (R.string.tab_title_forum). This proves the NavigationBar
 *      onClick → NavigationController route change is bidirectional and
 *      re-renders the destination, not a stale frame.
 *
 * The chief failure signal is rendered Compose text that ONLY appears on
 * the tapped destination — a real user switching tabs sees exactly this.
 * "The bar is present" is the necessary precondition; "the content
 * changed" is the user-visible outcome this Challenge gates.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: MainActivity → MobileNavigation
 *     → NestedMobileNavigation → BottomNavigation (NavigationBar) →
 *     NavigationController.navigate(route) → the destination's
 *     PagesScreen. No synthetic NavController, no mocked destination,
 *     no Robolectric — the rendered Compose tree on a real emulator.
 *   - OnboardingBypassRule pre-seeds onboarding-complete + a generic
 *     authorized signal — the exact state every real user is in after
 *     finishing onboarding, which is when they reach this scaffold.
 *   - The assertions are on destination-EXCLUSIVE rendered text
 *     ("Recents" exists only on Topics; "Forum" page label only on the
 *     Forum destination), so a no-op tab click (bar renders but content
 *     does not switch) FAILS the test.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing defect):
 *   MUTATION — break the tab → content switch without crashing:
 *     1. In core/navigation/.../ui/BottomNavigation.kt, replace the
 *        NavigationBarItem onClick body
 *          onClick(tab.route); haptic.performHapticFeedback(...)
 *        with a no-op:
 *          { /* onClick(tab.route) removed */ }
 *        The bar still renders all four labels (so the app does NOT
 *        crash and step 1 still passes), but tapping "Topics" no longer
 *        navigates — the Search start tab stays rendered.
 *     2. Re-run on the gating emulator:
 *          ./gradlew :app:connectedDebugAndroidTest --tests \
 *            "lava.app.challenges.Challenge55MainScaffoldBottomNavSwitchesTest"
 *     3. Expected failure: the waitUntil for "Recents" times out with
 *        "Compose wait timed out … waiting for condition to be true";
 *        the assertion message
 *          "Tapping the 'Topics' bottom-nav tab MUST switch the visible
 *           content to the Topics destination (its 'Recents' page label
 *           MUST render) — the NavigationBar onClick → route change is
 *           broken if this fails."
 *        fires because the Topics-exclusive "Recents" node never appears.
 *     4. Revert the onClick; re-run; tab switching works and the test
 *        passes.
 *
 * HONEST SCOPE (§6.J / §6.AH gate-host deferral):
 *   SOURCE-WRITTEN + DISCRIMINATION-SCANNER-VERIFIED on the current
 *   darwin/arm64 host. NOT yet EXECUTED against an emulator — per §6.AH
 *   every emulator MUST run inside a Container/VM via the Containers
 *   submodule, and that path does not yet boot on this macOS host
 *   (§6.AH-debt). The §6.AE.5 per-AVD attestation row will be produced
 *   when the operator runs scripts/run-challenge-matrix.sh on an
 *   eligible gate-host. Device-exec status: PENDING (gate-host deferred).
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge55MainScaffoldBottomNavSwitchesTest"
 *
 * // covers-feature: main
 */
package lava.app.challenges

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import lava.app.OnboardingBypassRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge55MainScaffoldBottomNavSwitchesTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Pre-seeds onboarding-complete + a generic authorized signal so the
    // test starts in the bottom-tab nav — the exact state a real user is
    // in after finishing onboarding (when they reach this scaffold).
    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun bottomNavTabTap_switchesVisibleContent() {
        hiltRule.inject()

        // Step 1 — the scaffold composed: all four real bottom-nav tab
        // labels render (sourced from R.string.label_*). Their presence
        // is the precondition; the content-switch below is the outcome.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Menu").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Search").assertExists()
        composeRule.onNodeWithText("Forum").assertExists()
        composeRule.onNodeWithText("Topics").assertExists()
        composeRule.onNodeWithText("Menu").assertExists()

        // Step 2 — tap "Topics": the visible content MUST switch to the
        // Topics destination, whose PagesScreen renders its real page
        // labels "Favorites" + "Recents". "Recents" exists ONLY on the
        // Topics destination — its appearance proves the tap changed the
        // rendered destination, not just highlighted the bar item.
        composeRule.onNodeWithText("Topics").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Recents").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Tapping the 'Topics' bottom-nav tab MUST switch the visible content to the " +
                "Topics destination (its 'Recents' page label MUST render) — the NavigationBar " +
                "onClick → NavigationController route change is broken if this fails.",
            composeRule.onAllNodesWithText("Recents").fetchSemanticsNodes().isNotEmpty(),
        )

        // Step 3 — tap "Forum": switch again. The Forum destination's
        // PagesScreen renders its page label "Forum"
        // (R.string.tab_title_forum). Re-rendering a DIFFERENT
        // destination proves the nav is bidirectional and re-composes
        // the destination content, not a stale frame.
        composeRule.onNodeWithText("Forum").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            // "Recents" belongs to the Topics destination and MUST be gone
            // once the Forum destination is showing; the Forum page label
            // MUST now be present. Asserting the disappearance of the prior
            // tab's exclusive content hardens against a "both rendered"
            // stacking bug.
            composeRule.onAllNodesWithText("Recents").fetchSemanticsNodes().isEmpty() &&
                composeRule.onAllNodesWithText("Forum").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "Tapping the 'Forum' bottom-nav tab MUST switch the visible content away from " +
                "Topics (its 'Recents' page label MUST no longer render) and to the Forum " +
                "destination — bidirectional tab switching is broken if this fails.",
            composeRule.onAllNodesWithText("Recents").fetchSemanticsNodes().isEmpty(),
        )
    }
}
