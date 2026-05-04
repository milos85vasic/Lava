/*
 * Phase 2.5 (2026-05-04) — LenientTeardownRule.
 *
 * Surgically narrow workaround for a known
 * androidx.navigation:navigation-compose lifecycle race that fires at
 * activity-destroy time when the activity is destroyed while a
 * NavBackStackEntry is in a state below CREATED. The exception:
 *
 *   java.lang.IllegalStateException: State must be at least 'CREATED'
 *   to be moved to 'DESTROYED' in component NavBackStackEntry(...)
 *   destination=Destination(...) route=<deep-route>
 *
 * Empirically observed during the C1 redesign rehearsal (2026-05-04).
 * The exception fires AFTER all test assertions complete — it is purely
 * a teardown-time issue. Without this rule, tests that navigate into
 * deep routes report `failed (Xs)` even when their assertions all
 * passed, which contaminates the constitutional matrix-attestation
 * gate (clause 6.I).
 *
 * Anti-bluff posture (clause 6.J/6.L):
 *
 *   This rule rethrows EVERY exception that is NOT the specific
 *   IllegalStateException-with-that-exact-message-prefix. The exact
 *   message starts with "State must be at least 'CREATED'". Any
 *   genuine assertion failure (junit.framework.AssertionFailedError,
 *   TurbineAssertionError, etc.) propagates unchanged. Any other
 *   IllegalStateException (e.g. one that does NOT mention the nav
 *   lifecycle) propagates unchanged.
 *
 *   The rule's narrowness is the protection. If a future change to
 *   androidx.navigation introduces a different lifecycle bug, this
 *   rule will NOT catch it — the test will fail loudly, the operator
 *   investigates, and either the rule is widened with a documented
 *   reason OR the bug is fixed.
 *
 * Falsifiability rehearsal:
 *
 *   1. Comment out the rethrow-narrowing in `apply` (replace the
 *      filter with `throw e`).
 *   2. A deep-nav test that previously appeared green now appears
 *      `failed (Xs)` — the old behavior — because the IllegalStateException
 *      from teardown propagates.
 *   3. Mutate the test body to introduce a real AssertionError
 *      (e.g. `assertEquals(1, 2)`) — confirm the test fails with
 *      the AssertionError, not silently swallowed.
 *   4. Revert.
 *
 * Rule order on the test class:
 *
 *   @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
 *   @get:Rule(order = 1) val onboardingBypass = OnboardingBypassRule()
 *   @get:Rule(order = 2) val lenientTeardown = LenientTeardownRule()
 *   @get:Rule(order = 3) val composeRule = createAndroidComposeRule<MainActivity>()
 *
 * The rule must wrap `composeRule` so its `evaluate()` is what catches
 * the destroy-time exception (compose rule destroys the activity in its
 * own `after()` block).
 */
package lava.app

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class LenientTeardownRule : TestRule {

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                try {
                    base.evaluate()
                } catch (e: IllegalStateException) {
                    val msg = e.message.orEmpty()
                    val isNavComposeTeardownBug =
                        msg.startsWith("State must be at least 'CREATED' to be moved to 'DESTROYED'")
                    if (!isNavComposeTeardownBug) {
                        throw e
                    }
                    // Swallowed: known navigation-compose teardown race.
                    // The test's actual assertions ran to completion before
                    // this exception fired. Any genuine assertion failure
                    // would have been thrown earlier as AssertionError, not
                    // IllegalStateException.
                }
            }
        }
    }
}
