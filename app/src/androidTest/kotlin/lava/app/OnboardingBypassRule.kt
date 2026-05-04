/*
 * Phase 1.2 (2026-05-04) — OnboardingBypassRule.
 *
 * A JUnit `TestRule` that pre-populates `PreferencesStorage` so a
 * Compose UI Challenge Test starts in the main app's bottom-tab nav
 * (Search/Forum/Topics/Menu) instead of the OnboardingScreen's "Select
 * Provider" list.
 *
 * Why this exists (forensic anchor — clauses 6.G/6.J/6.L):
 *
 *   The 2026-05-04 emulator rehearsal revealed that the existing
 *   Challenge Tests C1–C8 (added in SP-3a Step 6) were ALL bluffs:
 *   they assumed the app starts on a screen with a "Menu" tab, but
 *   the post-mandatory-onboarding UI (commit 7a0317d) starts on the
 *   "Select Provider" screen. Empirical run of C1 against
 *   CZ_API34_Phone failed with "could not find any node that
 *   satisfies: (Text contains 'Menu')". The tests had compiled green
 *   for weeks because the pre-push gate runs source-only compile,
 *   not connectedAndroidTest.
 *
 *   This rule closes the gap. C1, C4, C5, C6, C7, C8 — tests that do
 *   NOT specifically exercise the onboarding flow — apply this rule
 *   and start in the main app. C2 (RuTracker auth) and C3 (RuTor
 *   anonymous) DO traverse the real onboarding flow and MUST NOT use
 *   this rule.
 *
 * Hilt access pattern:
 *
 *   The rule uses `EntryPointAccessors.fromApplication(...)` to grab
 *   `PreferencesStorage` and `AuthService` from the singleton
 *   component. The application is `HiltTestApplication` per
 *   `LavaHiltTestRunner`. Hilt's @InstallIn(SingletonComponent::class)
 *   bindings are reachable from this entry point.
 *
 * Falsifiability rehearsal protocol (Sixth Law clause 2):
 *
 *   1. Comment out `prefs.setOnboardingComplete(true)` in `before()`.
 *   2. Re-run any C1-style test that uses this rule on a real emulator.
 *   3. Expected failure: `composeRule.onNodeWithText("Menu").performClick()`
 *      raises "could not find any node" because the Onboarding screen
 *      is showing instead.
 *   4. Revert; re-run; the test reaches the bottom-tab nav.
 *
 *   Same protocol with `auth.signalAuthorized(...)` mutated → Search
 *   tab renders "Authorization required" empty state.
 */
package lava.app

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.runBlocking
import lava.auth.api.AuthService
import lava.securestorage.PreferencesStorage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

class OnboardingBypassRule : TestRule {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BypassEntryPoint {
        fun preferencesStorage(): PreferencesStorage
        fun authService(): AuthService
    }

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
                val entry = EntryPointAccessors.fromApplication(app, BypassEntryPoint::class.java)
                runBlocking {
                    entry.preferencesStorage().setOnboardingComplete(true)
                    // Phase 1.5 alignment: signal a generic authorized state
                    // so SearchViewModel + ObserveAuthStateUseCase see Authorized
                    // and the Search tab renders the search-history empty state
                    // instead of "Authorization required to search".
                    entry.authService().signalAuthorized(
                        name = "InstrumentationTest",
                        avatarUrl = null,
                    )
                }
                try {
                    base.evaluate()
                } finally {
                    runBlocking {
                        entry.preferencesStorage().setOnboardingComplete(false)
                        entry.preferencesStorage().clearSignaledAuthState()
                        entry.authService().logout()
                    }
                }
            }
        }
    }
}
