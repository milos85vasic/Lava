/*
 * Challenge Test C0 — Phase 1.1 acceptance gate (2026-05-04).
 *
 * Verifies that the multi-tracker session-signaled authorized state
 * survives process death. Without this guarantee, a user who completes
 * an AuthType.NONE provider's onboarding (Internet Archive Continue
 * tap, Project Gutenberg Continue tap) and then force-quits the app
 * would face "Authorization required to search" again on next launch
 * — that's the bug Phase 1.1 was written to prevent.
 *
 * Forensic anchor (root CLAUDE.md §6.G + §6.J):
 *
 *   Pre-Phase-1.1 state — `AuthServiceImpl.sessionSignaledState` was
 *   an in-memory `@Volatile var` only. The legacy
 *   `preferencesStorage.getAccount()` knows about RuTracker accounts;
 *   it does NOT know about archive.org/gutenberg signals. So a user
 *   on archive.org who force-quits the app loses authorization on
 *   restart, even though the production code path that signaled them
 *   in the first place is still intact.
 *
 *   Phase 1.1 fix — added `getSignaledAuthState`/`saveSignaledAuthState`/
 *   `clearSignaledAuthState` to `PreferencesStorage`, with a separate
 *   SharedPreferences file (`signaled_auth.xml`) so the multi-tracker
 *   signal does NOT collide with the legacy account schema.
 *   `AuthServiceImpl.signalAuthorized` writes to BOTH in-memory AND
 *   persisted; `getAuthState()` cold-start path reads from persisted
 *   when in-memory is null; `logout()` clears both.
 *
 * Anti-bluff posture (clauses 6.J/6.L):
 *
 *   This test is HONESTLY SHALLOW. The truly load-bearing assertion
 *   ("user-visible Search-history empty state survives a real
 *   `am force-stop`") cannot be made from inside an in-process
 *   instrumentation test, because the test process holds the
 *   instrumentation connection — killing the app kills the test too.
 *
 *   What this test IS: a real-stack assertion that the production UI
 *   flow writes the signaled state to the production
 *   `PreferencesStorage` on the real device. Combined with the JVM
 *   real-stack test in `core/auth/impl/src/test/.../AuthServiceImplPersistenceTest.kt`
 *   (which exercises the cold-start restoration path against the
 *   production `AuthServiceImpl`), the two together cover the full
 *   force-stop-survival contract.
 *
 *   What this test IS NOT: a substitute for an end-to-end UiAutomator
 *   `am force-stop` + relaunch flow. That deeper coverage is owed
 *   pending a future Phase 1.1.7 once the test harness can drive the
 *   shell from outside the test process.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *
 *   1. In core/auth/impl/.../AuthServiceImpl.kt, comment out
 *      `preferencesStorage.saveSignaledAuthState(name = name, avatarUrl = avatarUrl)`
 *      inside `signalAuthorized()`.
 *   2. Re-run on the gating emulator matrix.
 *   3. Expected failure: the post-Continue assertion
 *      `prefs.getSignaledAuthState() != null` fails because the
 *      production code path that should have written to disk no
 *      longer does.
 *   4. Revert; re-run; assertion passes.
 *
 *   Mirror rehearsal: comment out
 *      `preferencesStorage.clearSignaledAuthState()` inside `logout()`.
 *   The third assertion ("logout clears persisted state") MUST then
 *   fire because the persisted state survives logout.
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import digital.vasic.lava.client.MainActivity
import kotlinx.coroutines.runBlocking
import lava.app.ResetOnboardingPrefsRule
import lava.auth.api.AuthService
import lava.securestorage.PreferencesStorage
import lava.securestorage.SignaledAuthState
import lava.tracker.archiveorg.ArchiveOrgDescriptor
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ until Compose BOM update fixes the AndroidPrefetchScheduler-needs-Looper crash on API 36. See .lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json
@HiltAndroidTest
class Challenge00CrashSurvivalTest {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PersistenceEntryPoint {
        fun preferencesStorage(): PreferencesStorage
        fun authService(): AuthService
    }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    @get:Rule(order = 2)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun entry(): PersistenceEntryPoint {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return EntryPointAccessors.fromApplication(app, PersistenceEntryPoint::class.java)
    }

    @After
    fun tearDown() {
        // Wipe persistence to leave the device in a known state for
        // the next test on the matrix. Match OnboardingBypassRule's
        // teardown shape so test ordering does not matter.
        runBlocking {
            entry().preferencesStorage().clearSignaledAuthState()
            entry().preferencesStorage().setOnboardingComplete(false)
            entry().authService().logout()
        }
    }

    @Test
    fun continueOnArchiveOrg_persists_signaledAuthState_to_disk() {
        hiltRule.inject()

        // Clause 6.G gate: only run when the descriptor is verified.
        assumeTrue(
            "ArchiveOrgDescriptor.verified must be true for this test to apply (clause 6.G)",
            ArchiveOrgDescriptor.verified,
        )

        val prefs = entry().preferencesStorage()

        // Pre-condition: nothing persisted (the app was just installed
        // by the matrix runner; PreferencesStorage starts empty).
        runBlocking { assertNull(prefs.getSignaledAuthState()) }

        // ===== Act 1: Drive the real onboarding flow on the real device =====
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Pick your providers").fetchSemanticsNodes().isNotEmpty()
        }
        arrayOf("RuTracker.org", "RuTor.info", "Kinozal.tv", "NNM-Club", "Project Gutenberg").forEach { name ->
            try {
                composeRule.onNodeWithText(name).performClick()
            } catch (_: AssertionError) { }
        }
        composeRule.onNodeWithText("Next").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Configure Internet Archive").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Continue").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("All set!").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Start Exploring").performClick()

        // Timeout bumped 20s → 40s (2026-05-17) after Bug 2 fix added
        // providerConfigRepository.ensureDefault() to onTestAndContinue.
        // Per-provider extra DB write × N providers can stack; the
        // additional latency on Pixel_8/API35 host AVD pushed the
        // wait-for-home over the 20s wire. 40s leaves headroom while
        // still failing fast on real regressions.
        composeRule.waitUntil(timeoutMillis = 40_000) {
            composeRule.onAllNodesWithText("Search history").fetchSemanticsNodes().isNotEmpty()
        }

        // ===== Assert 1: PRIMARY — persisted SignaledAuthState exists =====
        // This is the production-stack assertion that
        // AuthServiceImpl.signalAuthorized called
        // preferencesStorage.saveSignaledAuthState during the UI flow.
        // Falsifiable: drop the saveSignaledAuthState call → assert fails.
        val persistedAfterContinue: SignaledAuthState? =
            runBlocking { prefs.getSignaledAuthState() }
        assertNotNull(
            "PRIMARY: SignaledAuthState MUST be persisted on disk after Continue tap. " +
                "Phase 1.1 production-stack guarantee for force-stop survival.",
            persistedAfterContinue,
        )
        // The exact persisted name comes from
        // `ProviderLoginViewModel.onSubmitClick` AuthType.NONE branch:
        //   "Anonymous (${provider.displayName})"
        // Substring check protects against minor display-name changes
        // while still asserting the bridge fired with a meaningful name.
        val name = persistedAfterContinue!!.name
        org.junit.Assert.assertTrue(
            "Persisted name '$name' MUST contain the provider's display name 'Internet Archive'",
            name.contains("Internet Archive"),
        )

        // ===== Act 2: Logout — production-stack call =====
        runBlocking { entry().authService().logout() }

        // ===== Assert 2: SECONDARY — logout cleared the persisted state =====
        // Falsifiable: drop the clearSignaledAuthState call from logout()
        // → this assertion fires because the persisted state survives.
        runBlocking {
            assertNull(
                "Logout MUST clear the persisted SignaledAuthState — otherwise " +
                    "force-quitting after logout would silently restore the user as " +
                    "authorized, which is the regression Phase 1.1 Task 1.1.5 prevents.",
                prefs.getSignaledAuthState(),
            )
        }
    }
}
