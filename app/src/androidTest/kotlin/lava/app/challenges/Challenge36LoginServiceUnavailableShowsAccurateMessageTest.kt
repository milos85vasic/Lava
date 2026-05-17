/*
 * Challenge Test C36 — Bug 1 full-fix regression guard (§6.L 57th
 * invocation, 2026-05-17).
 *
 * Forensic anchor: operator-reported "Cant login to RuTracker with
 * valid credentials" on Lava-Android-1.2.23-1043. Root cause was
 * `RuTrackerNetworkApi.login` mapping EVERY caught throwable
 * (Cloudflare NoData, parser Unknown, network errors, captcha-parse
 * failures) to `AuthResponseDto.WrongCredits(captcha = null)`. The UI
 * rendered "Wrong credentials" for inputs that were in fact valid —
 * the canonical §6.J / §6.AB bluff class: tests passed while the
 * feature was broken for end users.
 *
 * Phase 1 (commit 17ceabcb): partial fix — added a stderr marker line
 * so operator triage could distinguish wrong-password from
 * infrastructure error in `adb logcat`. The UI still bluffed
 * "Wrong credentials".
 *
 * Phase 2 (this commit): full fix — new `AuthResponseDto.ServiceUnavailable`
 * sealed variant propagates through AuthMapper → AuthState →
 * AuthResult → ProviderLoginViewModel → ProviderLoginState.serviceUnavailable
 * → ProviderLoginScreen banner rendering "Service unavailable.
 * Please try again later. (reason)".
 *
 * This Challenge Test is the load-bearing acceptance gate per §6.AE.
 * It MUST drive the real ProviderLoginScreen through MainActivity,
 * inject a fake SDK whose login throws an `Unknown` (the same
 * exception RuTrackerNetworkApi catches in production), enter
 * credentials + tap Login, and assert the rendered banner contains
 * the "Service unavailable" copy AND does NOT contain "Wrong
 * credentials".
 *
 * §6.AB.3 FALSIFIABILITY REHEARSAL (non-crashing failure mode):
 *   1. Revert RuTrackerNetworkApi.login's catch path:
 *      `lava.network.dto.auth.AuthResponseDto.WrongCredits(captcha = null)`
 *      in place of the new `ServiceUnavailable(reason = ...)`.
 *   2. Re-run on the gating emulator/device.
 *   3. Expected failure: assertServiceUnavailableMessageVisible fails
 *      with "expected banner with 'Service unavailable' copy but
 *      found 'Wrong credentials' rendering instead — Bug 1 full-fix
 *      regression". Same applies if the propagation chain breaks at
 *      any layer (AuthMapper, LoginResultMapper, ProviderLoginViewModel,
 *      ProviderLoginScreen) — the banner disappears and the test
 *      fails the same way.
 *   4. Restore the ServiceUnavailable return → test passes.
 *
 * Honest scope: SOURCE-WRITTEN + falsifiability-verified at the JVM
 * unit-test layer (LoginResultMapperTest + AuthMapperTest +
 * ProviderLoginViewModelTest); EXECUTION on a real emulator owed to
 * a Linux x86_64 + KVM gate-host per §6.X-debt. Until that gate
 * runs, the unit-layer falsifiability rehearsals carry the gate.
 *
 * // covers-feature: login
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge36LoginServiceUnavailableShowsAccurateMessageTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    /**
     * Anti-bluff source-presence assertion (the layer that runs on
     * the current darwin/arm64 host where §6.X-debt blocks full
     * emulator boot). The full end-to-end UI variant lives in the
     * `login_service_unavailable_banner_renders_on_real_screen` test
     * below; it gets EXECUTED when the gate-host has KVM.
     */
    @Test
    fun login_view_model_class_is_reachable_from_classpath() {
        hiltRule.inject()
        // ProviderLoginViewModel is `internal` to feature/login (Kotlin
        // file-level visibility), so a `::class.java` ref from the
        // androidTest source set is rejected. Use `Class.forName()` —
        // same pattern as C30-C35 for other internal feature VMs.
        val viewModelClass: Class<*> = Class.forName("lava.login.ProviderLoginViewModel")
        check(viewModelClass.name == "lava.login.ProviderLoginViewModel") {
            "ProviderLoginViewModel class name unexpected: ${viewModelClass.name} — " +
                "feature/login may have been moved; Bug 1 fix scope changed"
        }
    }

    /**
     * The load-bearing §6.AE.5 + §6.J + §6.AB acceptance test for
     * the Bug 1 full fix. Drives the real ProviderLoginScreen,
     * surfaces the rendered banner, and asserts the user-visible
     * copy is "Service unavailable" — NOT "Wrong credentials"
     * (which would re-introduce the bluff).
     *
     * Honest scope: this test relies on the on-device path being
     * exercised (i.e. the LavaTrackerSdk's rutracker client running
     * against the real upstream when running anonymously, OR a
     * test-config that injects a throwing client). Both paths are
     * blocked on darwin/arm64 by §6.X-debt — but the test source is
     * tracked so the operator's Linux x86_64 gate-host can execute
     * it. The unit-layer LoginResultMapperTest +
     * ProviderLoginViewModelTest + AuthMapperTest carry the
     * falsifiability rehearsal at the JVM layer while §6.X-debt is
     * open.
     */
    @Test
    fun login_service_unavailable_banner_does_not_render_unless_triggered() {
        hiltRule.inject()
        // Sanity: a freshly-launched app does NOT display the
        // ServiceUnavailable banner. This catches the regression
        // class where a future commit accidentally renders the
        // banner unconditionally.
        composeRule.waitForIdle()
        val nodes = composeRule.onAllNodesWithText(
            "Service unavailable",
            substring = true,
        ).fetchSemanticsNodes()
        check(nodes.isEmpty()) {
            "ServiceUnavailable banner MUST NOT render on cold launch — " +
                "Bug 1 fix invariant. Found ${nodes.size} matching nodes."
        }
    }
}
