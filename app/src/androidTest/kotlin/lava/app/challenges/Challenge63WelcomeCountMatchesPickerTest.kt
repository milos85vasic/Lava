/*
 * Challenge Test C63 — the Welcome screen does NOT claim a stale/contradictory
 * provider count (LVA-087, video #6).
 *
 * DRAFT authored 2026-06-26 (§6.AK cycle-coverage spec §2.6 LVA-087). This file
 * is authored statically — it has NOT been executed on a device by its author
 * (the device + Android Gradle are owned by another stream). The main stream
 * device-runs it reproduce-first per §6.AK clause 2.
 *
 * OPERATOR-REPORTED DEFECT (video #6 against 1076, LVA-087, P2):
 *   "Welcome claims '4 providers available' but picker lists ~12." The Welcome
 *   header had a stale count ("4 providers available") that no longer matched
 *   the now-larger provider catalogue shown on the picker.
 *
 * HOW THE FIX ACTUALLY LANDED (verified against production source, 166ef2e7):
 *   `WelcomeStep(providerCount: Int?)` (WelcomeStep.kt:31) renders:
 *     - providerCount != null → "$providerCount providers available"  (line 67)
 *     - providerCount == null → "Multiple content providers available" (line 69)
 *   `OnboardingViewModel` sets:
 *     `welcomeProviderCount = if (apiSelectionEnabled) null else items.size`
 *     (OnboardingViewModel.kt:471)
 *   `apiSelectionEnabled` defaults to TRUE in production
 *     (OnboardingHiltModule.kt:26: `fun apiSelectionEnabled(): Boolean = true`).
 *   THEREFORE, in the shipped flow the Welcome screen OMITS the numeric count
 *   (shows "Multiple content providers available") precisely BECAUSE the picker
 *   list is repopulated from the chosen API's catalogue on a LATER step, so any
 *   number on Welcome would be premature and contradict the picker — exactly the
 *   operator-reported mismatch. The LVA-087 fix did not make the count "dynamic
 *   and matching"; it REMOVED the premature count in the API-selection flow.
 *
 * WHAT THIS CHALLENGE ASSERTS (the landed fix, on a real device):
 *   Drive the REAL onboarding wizard (onboardingComplete = false, NO bypass) to
 *   the Welcome step and assert the header shows the count-free copy
 *   ("Multiple content providers available") and does NOT display any stale
 *   numeric "N providers available" count that would contradict the picker.
 *
 * WHAT THIS CHALLENGE DOES NOT (yet) ASSERT — DOCUMENTED COVERAGE GAP:
 *   The spec's literal "capture the count → advance to picker → assert count ==
 *   picker entry count" journey is NOT reproducible against the production
 *   default flow because (a) the default (apiSelectionEnabled=true) flow shows
 *   NO numeric count on Welcome, and (b) reaching the picker requires selecting
 *   + connectivity-probing an API on the intervening ApiSelection step, which a
 *   device Challenge cannot deterministically seed (mDNS discovery / cloud
 *   entry). The numeric-count-matches-picker variant only exists in the legacy
 *   apiSelectionEnabled=false flow (Welcome → Providers directly), which is no
 *   longer wired via a test Hilt override (the TestOnboardingHiltModule
 *   feature-flag override was removed in the 61st §6.L cycle). Per §6.AK
 *   clause 6, this Challenge truthfully covers the LANDED behavior rather than
 *   bluffing a journey the production default flow cannot take.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 *   - Real production code: MainActivity → onboarding gate → OnboardingScreen →
 *     OnboardingViewModel → WelcomeStep. No screen/VM is mocked.
 *   - PRIMARY assertion is on USER-VISIBLE rendered text: the Welcome header
 *     copy. The reproduce-first mutation below makes a numeric count appear and
 *     this assertion catches it.
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix / with the defect re-introduced)
 * 1. Apply the mutation: in
 *    feature/onboarding/.../OnboardingViewModel.kt, change
 *      `welcomeProviderCount = if (apiSelectionEnabled) null else items.size`
 *    to
 *      `welcomeProviderCount = 4`
 *    (the exact stale "4 providers available" the operator reported), so the
 *    Welcome header renders a fixed numeric count. NON-CRASHING.
 *    (Equivalent: hardcode the WelcomeStep text branch to "4 providers available".)
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run ONLY this Challenge:
 *    `adb shell am instrument -w -e class lava.app.challenges.Challenge63WelcomeCountMatchesPickerTest digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner`
 * 4. Expected failure: the assertion that "Multiple content providers available"
 *    is shown FAILS (the header now reads "4 providers available"), AND the
 *    assertion that NO "4 providers available" node exists FAILS. The message:
 *      "Welcome MUST NOT claim a fixed/stale numeric provider count in the
 *       API-selection flow; found '4 providers available'."
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout OnboardingViewModel.kt).
 * 6. Rebuild and re-run the identical Challenge.
 * 7. Expected pass: Welcome shows "Multiple content providers available" and no
 *    numeric "N providers available" count.
 *
 * ### Mutation type
 * NON-CRASHING BREAK — the mutation reproduces the stale-count copy the operator
 * saw, not a crash (§6.AB.3).
 *
 * ### LVA-008 dependency
 * This Challenge stays on the Welcome step (a non-nested onboarding route) and
 * never navigates into a nested NavHost, so the LVA-008 teardown is unlikely to
 * fire; the [LenientTeardownRule] is retained defensively (outside the compose
 * rule) so only a genuine header-copy assertion decides pass/fail.
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge63WelcomeCountMatchesPickerTest"
 *
 * // covers-feature: onboarding
 * // covers-changelog: Welcome no longer claims a stale provider count that contradicts the picker (LVA-087)
 */
package lava.app.challenges

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import lava.app.LenientTeardownRule
import lava.app.di.ApiSelectionTestFlag
import lava.securestorage.PreferencesStorage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge63WelcomeCountMatchesPickerTest {

    // ── Rules ────────────────────────────────────────────────────────────────
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    // NB: NO OnboardingBypassRule — this Challenge DRIVES the real onboarding
    // wizard. The @Before block sets onboardingComplete = false so MainActivity
    // shows the OnboardingScreen (Welcome step) on launch.
    //
    // Set the API-selection-flow flag BEFORE composeRule launches MainActivity
    // (lower @Rule order = outer = before() runs first), so the test build
    // renders the SHIPPED apiSelectionEnabled=true Welcome copy ("Multiple
    // content providers available") rather than the legacy numeric-count flow
    // (the TestOnboardingHiltModule binds apiSelectionEnabled from
    // ApiSelectionTestFlag, which DEFAULTS false). Mirrors C39/C40. Without
    // this the count-free assertion device-fails because the legacy flow shows
    // "N providers available". reset() restores the default for sibling tests.
    @get:Rule(order = 1)
    val apiSelectionFlow = object : ExternalResource() {
        override fun before() { ApiSelectionTestFlag.enabled = true }
        override fun after() { ApiSelectionTestFlag.reset() }
    }

    @get:Rule(order = 2)
    val lenientTeardown = LenientTeardownRule()

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    // The real, grepped Welcome header copy (WelcomeStep.kt).
    private val WELCOME_TITLE = "Welcome to Lava"
    private val GET_STARTED = "Get Started"
    private val COUNT_FREE_COPY = "Multiple content providers available"
    private val STALE_COUNT_COPY = "4 providers available"

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface OnboardingResetEntryPoint {
        fun preferencesStorage(): PreferencesStorage
    }

    private fun prefs(): PreferencesStorage {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        return EntryPointAccessors.fromApplication(app, OnboardingResetEntryPoint::class.java)
            .preferencesStorage()
    }

    @Before
    fun setUp() {
        hiltRule.inject()
        // Force the onboarding wizard to show on launch (the inverse of
        // OnboardingBypassRule). The initial OnboardingStep is Welcome.
        runBlocking { prefs().setOnboardingComplete(false) }
    }

    @After
    fun tearDown() {
        // Leave onboarding incomplete (the fresh-install default); harmless for
        // sibling tests, all of which set their own onboarding-complete state.
        runCatching { runBlocking { prefs().setOnboardingComplete(false) } }
    }

    @Test
    fun welcomeOmitsStaleProviderCount_inApiSelectionFlow() {
        // The onboarding Welcome step renders on launch.
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText(WELCOME_TITLE).fetchSemanticsNodes().isNotEmpty()
        }

        // Sanity: the Welcome step's primary CTA is present (we are on Welcome,
        // not some other screen).
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(GET_STARTED).fetchSemanticsNodes().isNotEmpty()
        }

        // PRIMARY ASSERTION (part 1) — the Welcome header shows the count-free
        // copy. In the production default (apiSelectionEnabled = true) flow the
        // numeric count is intentionally omitted because the picker list is
        // repopulated from the chosen API's catalogue on a later step.
        assertTrue(
            "Welcome MUST show the count-free copy '$COUNT_FREE_COPY' in the " +
                "API-selection flow (the numeric count is omitted because the " +
                "picker is repopulated from the chosen API catalogue later). " +
                "If this fails, the Welcome screen is claiming a premature count.",
            composeRule.onAllNodesWithText(COUNT_FREE_COPY).fetchSemanticsNodes().isNotEmpty(),
        )

        // PRIMARY ASSERTION (part 2) — the discriminating check: the Welcome
        // header MUST NOT display the stale "4 providers available" count the
        // operator reported. The reproduce-first mutation re-introduces exactly
        // this string, and this assertion catches it.
        assertTrue(
            "Welcome MUST NOT claim a fixed/stale numeric provider count in the " +
                "API-selection flow; found '$STALE_COUNT_COPY'.",
            composeRule.onAllNodesWithText(STALE_COUNT_COPY).fetchSemanticsNodes().isEmpty(),
        )
    }
}
