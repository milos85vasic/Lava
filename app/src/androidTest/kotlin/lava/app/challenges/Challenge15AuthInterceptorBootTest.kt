/*
 * Challenge Test C15 — App boots with AuthInterceptor wired (Phase 1).
 *
 * Phase 1 of the Phase-1 API auth foundation introduced the
 * AuthInterceptor + LavaAuthBlobProvider chain into the OkHttp interceptor
 * multibind set (Phase 10, commit 540bd9c). The chain decrypts the
 * per-build encrypted UUID at request time, injects it into the
 * Lava-Auth header, and zeroizes the plaintext bytes in finally.
 *
 * This Challenge Test asserts the user-visible outcome of "the app starts
 * and reaches the home screen" while the AuthInterceptor is in the
 * Hilt graph — proving the new wiring does not crash the Application's
 * dependency graph at boot.
 *
 * The full end-to-end auth flow (real Lava-Auth header on an outgoing
 * request to a real lava-api-go service that returns the search-page DTO
 * AND a separate path that returns 426 Upgrade Required for a retired
 * UUID) lands in Phase 16 alongside the §6.I emulator-matrix gate where
 * a real lava-api-go can be booted with deterministic test fixtures. C15
 * here is the Phase-13 acceptance gate that the Hilt graph composes
 * cleanly post-AuthInterceptor.
 *
 * Constitutional binding: Sixth Law clauses 1, 3, 4 — the test
 * traverses the same Application boot path the user triggers when
 * launching the app icon, asserts on user-visible state (the home-tab
 * label rendered), and is the load-bearing gate for the Phase-1
 * Hilt-wiring contract.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In `core/network/impl/.../di/AuthInterceptorModule.kt` change
 *      the `tryLoadGenerated()` fallback to throw instead of return
 *      null:
 *        try {
 *          val cls = Class.forName("lava.auth.LavaAuthGenerated")
 *          ...
 *        } catch (_: ClassNotFoundException) {
 *          throw RuntimeException("Phase 11 generator did not run")
 *        }
 *   2. Run on the gating matrix:
 *        ./gradlew :app:connectedDebugAndroidTest \
 *          --tests "lava.app.challenges.Challenge15AuthInterceptorBootTest"
 *   3. Expected failure: the app crashes during Hilt component
 *      construction; the test fails with a Hilt-wrapped
 *      RuntimeException about LavaAuthGenerated.
 *   4. Revert the change; re-run; the test passes (the stub fallback
 *      keeps the app booting in environments where Phase 11's
 *      generator hasn't produced a LavaAuthGenerated class — e.g. the
 *      operator's first build before populating .env auth keys).
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.MainActivity
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge15AuthInterceptorBootTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appBootsWithAuthInterceptorInOkHttpChain() {
        hiltRule.inject()

        // ===== PRIMARY ASSERTION =====
        // The app's Hilt graph must compose cleanly with the new
        // AuthInterceptor + LavaAuthBlobProvider bindings. If the
        // reflection-based provider lookup fails OR if the @Multibinds
        // wiring conflicts, the Application crashes during MainActivity
        // construction and no UI is rendered. Asserting the home-tab
        // label or the onboarding's "Continue" button proves the boot
        // chain reached its first frame.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            val onHome = composeRule.onAllNodesWithText("Search history")
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Menu")
                    .fetchSemanticsNodes().isNotEmpty()
            val onOnboarding = composeRule.onAllNodesWithText("Continue", ignoreCase = true)
                .fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithText("Select", ignoreCase = true, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            onHome || onOnboarding
        }
    }
}
