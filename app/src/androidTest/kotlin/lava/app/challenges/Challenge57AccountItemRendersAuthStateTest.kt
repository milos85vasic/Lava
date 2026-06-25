/*
 * Challenge Test C57 — Account row (feature/account AccountItem) renders
 * its real auth-state-driven content and reacts to a real auth-state
 * change. UI-audit gap W6 / matrix row "account"
 * (docs/qa/2026-06-25-ui-coverage-audit.md row "account": GAP — no UI
 * test; only AccountViewModel unit coverage).
 *
 * HONEST FINDING (recorded here per §6.J no-bluff honesty):
 *   As of 2026-06-25 the production `AccountItem` composable is NOT
 *   mounted by any production screen — it is an orphan feature module
 *   (only AccountItem.kt + a @Preview; not referenced by MenuScreen or
 *   any navigation graph). There is therefore NO in-app navigation path a
 *   user can take to reach it today. C57 does NOT pretend otherwise: it
 *   does not claim the account row is reachable via the nav. Instead it
 *   exercises the REAL production composable + REAL hiltViewModel()-
 *   resolved AccountViewModel + REAL ObserveAuthStateUseCase /
 *   LogoutUseCase end-to-end on a device, hosted by a thin
 *   @AndroidEntryPoint test activity (AccountTestHostActivity) that
 *   composes the exact production `AccountItem(onLoginClick)` overload.
 *   This guarantees the account COMPONENT works for a user the moment it
 *   is wired into a screen — the rendering + the auth-state reactivity +
 *   the logout/login affordances are all proven against real code.
 *
 * WHAT THIS TEST ASSERTS (primary = user-visible rendered state):
 *   1. AUTHORIZED rendering: OnboardingBypassRule signals the real
 *      AuthService Authorized("InstrumentationTest"). The real
 *      AccountViewModel observes this via ObserveAuthStateUseCase and the
 *      AccountItem authorized branch renders the user name
 *      "InstrumentationTest" (a Body text node) + the logout IconButton
 *      (contentDescription "Logout", R.string.designsystem_action_logout).
 *      A real authorized user sees their name + a logout control.
 *   2. AUTH-STATE REACTIVITY (the user-visible state machine): driving a
 *      REAL AuthService.logout() flips ObserveAuthStateUseCase's emission
 *      to AuthState.Unauthorized. The real AccountItem re-composes its
 *      unauthorized branch: the name disappears and the "Login"
 *      TextButton (R.string.account_item_login_action) renders. This is
 *      the exact user-visible transition that happens when a session ends.
 *   3. The chief assertion is on rendered Compose text that flips with
 *      the real auth state — name present ⇒ authorized; "Login" present +
 *      name absent ⇒ unauthorized. A real user watching their account row
 *      sees exactly this change.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Every layer is REAL production code: AccountItem() (the public
 *     production composable) → hiltViewModel() → the real AccountViewModel
 *     (Orbit) → the real ObserveAuthStateUseCase + LogoutUseCase → the
 *     real AuthService. No mocked ViewModel, no faked AuthState, no
 *     Robolectric. The only test-scoped element is the @AndroidEntryPoint
 *     host activity whose sole job is to call setContent { AccountItem }.
 *   - The auth state is produced + mutated through the real AuthService
 *     SharedFlow (OnboardingBypassRule.signalAuthorized + the test's
 *     logout()), so the rendered transition is driven by real
 *     observation, not by setting a state value directly.
 *   - Both branches of AccountItem (Authorized name+logout /
 *     Unauthorized Login button) are asserted, so a regression that
 *     breaks either branch's rendering, OR breaks the reactivity of
 *     ObserveAuthStateUseCase, fails the test.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing defect):
 *   MUTATION — break the unauthorized rendering branch without crashing:
 *     1. In feature/account/.../AccountItem.kt, in the stateful
 *        AccountItem composable's `when (state)` render, change the
 *        Unauthorized branch's TextButton text from
 *          stringResource(R.string.account_item_login_action)
 *        to a wrong/blank label, e.g.
 *          text = ""
 *        (the row still renders — no crash — but the "Login" affordance
 *        no longer carries the production label).
 *     2. Re-run on the gating emulator:
 *          ./gradlew :app:connectedDebugAndroidTest --tests \
 *            "lava.app.challenges.Challenge57AccountItemRendersAuthStateTest"
 *     3. Expected failure: after logout the waitUntil for the "Login"
 *        node times out; the assertion
 *          "After a real logout the AccountItem MUST render its
 *           Unauthorized branch with the 'Login' affordance — the
 *           auth-state-driven rendering is broken if 'Login' is absent."
 *        fires because the "Login" node never appears.
 *     4. Restore the production label; re-run; both branches render and
 *        the test passes.
 *
 *   (Symmetric mutation for branch 1: break Authorized rendering by
 *   removing the `Body(text = state.name, …)` call → the name node never
 *   appears → step-1 assertion fails. Documented for completeness; the
 *   logout-branch mutation above is the primary recorded rehearsal.)
 *
 * HONEST SCOPE (§6.J / §6.AH gate-host deferral):
 *   SOURCE-WRITTEN + DISCRIMINATION-SCANNER-VERIFIED on the current
 *   darwin/arm64 host. NOT yet EXECUTED against an emulator — per §6.AH
 *   the emulator MUST run inside a Container/VM via the Containers
 *   submodule, a path that does not yet boot on this macOS host
 *   (§6.AH-debt). The §6.AE.5 per-AVD attestation row is produced when
 *   the operator runs scripts/run-challenge-matrix.sh on an eligible
 *   gate-host. Device-exec status: PENDING (gate-host deferred).
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest --tests \
 *     "lava.app.challenges.Challenge57AccountItemRendersAuthStateTest"
 *
 * // covers-feature: account
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import lava.app.AccountTestHostActivity
import lava.app.OnboardingBypassRule
import lava.auth.api.AuthService
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge57AccountItemRendersAuthStateTest {

    // ── Hilt entry point to reach the production AuthService singleton ──
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface AuthEntryPoint {
        fun authService(): AuthService
    }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // Signals the REAL AuthService Authorized("InstrumentationTest") so
    // the account row starts in its authorized branch — the same signal
    // ProviderLoginViewModel emits after a real login.
    @get:Rule(order = 1)
    val bypassRule = OnboardingBypassRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<AccountTestHostActivity>()

    @Test
    fun accountItem_rendersAuthorized_thenReactsToRealLogout() {
        hiltRule.inject()

        val authorizedName = "InstrumentationTest" // set by OnboardingBypassRule.signalAuthorized
        val logoutCd = "Logout"                    // R.string.designsystem_action_logout
        val loginLabel = "Login"                   // R.string.account_item_login_action

        // Step 1 — AUTHORIZED branch: the real AccountViewModel observes
        // the Authorized state and AccountItem renders the user name +
        // the logout IconButton.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(authorizedName).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "AccountItem MUST render the authorized user's name \"$authorizedName\" when the " +
                "real AuthService is Authorized — the Authorized branch (Body(text=state.name)) " +
                "is broken, or ObserveAuthStateUseCase is not emitting Authorized, if this fails.",
            composeRule.onAllNodesWithText(authorizedName).fetchSemanticsNodes().isNotEmpty(),
        )
        // The logout affordance MUST be present in the authorized branch.
        assertTrue(
            "AccountItem's Authorized branch MUST render the logout IconButton " +
                "(contentDescription \"$logoutCd\").",
            composeRule.onAllNodesWithContentDescription(logoutCd).fetchSemanticsNodes().isNotEmpty(),
        )

        // Step 2 — drive a REAL auth-state change: AuthService.logout().
        // This flips ObserveAuthStateUseCase to Unauthorized; the real
        // AccountViewModel reduces it and AccountItem re-composes.
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val authService = EntryPointAccessors
            .fromApplication(app, AuthEntryPoint::class.java)
            .authService()
        runBlocking { authService.logout() }

        // Step 3 — PRIMARY ASSERTION: the UNAUTHORIZED branch renders. The
        // name disappears and the "Login" TextButton appears — the exact
        // user-visible transition when a session ends.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(loginLabel).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText(authorizedName).fetchSemanticsNodes().isEmpty()
        }
        assertTrue(
            "After a real logout the AccountItem MUST render its Unauthorized branch with the " +
                "'$loginLabel' affordance — the auth-state-driven rendering is broken if " +
                "'$loginLabel' is absent.",
            composeRule.onAllNodesWithText(loginLabel).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "After a real logout the authorized name \"$authorizedName\" MUST no longer render — " +
                "AccountItem is not reacting to the Unauthorized auth state if the name persists.",
            composeRule.onAllNodesWithText(authorizedName).fetchSemanticsNodes().isEmpty(),
        )

        // Step 4 (secondary, user-action coverage): tapping "Login" fires
        // AccountAction.LoginClick → the real ViewModel posts OpenLogin →
        // the host's onLoginClick sets openLoginInvoked. Confirms the
        // unauthorized affordance is wired, not just rendered.
        composeRule.onAllNodesWithText(loginLabel).onFirst().performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.activity.openLoginInvoked.value
        }
        assertTrue(
            "Tapping 'Login' MUST invoke the account row's onLoginClick (AccountAction.LoginClick " +
                "→ OpenLogin side effect) — the unauthorized affordance is rendered but dead if not.",
            composeRule.activity.openLoginInvoked.value,
        )
    }
}
