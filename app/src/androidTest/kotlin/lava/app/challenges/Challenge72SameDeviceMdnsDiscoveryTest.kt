/*
 * Challenge Test C72 — Same-device mDNS discovery of the on-device api-app.
 *
 * Forensic anchor (operator-reported, live Firebase App Distribution build):
 * when the on-device api-app (`digital.vasic.lava.api` / debug
 * `digital.vasic.lava.api.dev`) is running on the SAME Android device as the
 * client (`digital.vasic.lava.client` / debug `.client.dev`), the client's
 * onboarding "Choose your API" screen never lists the local instance — it
 * only ever shows a REMOTE host's advertised API. C37
 * (`Challenge37OnboardingOnDeviceApiSectionTest`) proves the "On this
 * device" section's install/open button wiring but explicitly documents
 * that the FULL cross-app mDNS round-trip was previously verified ONLY
 * manually, on a real connected device, per §6.Z — this Challenge closes
 * that automation gap with a real two-app, same-emulator repro.
 *
 * Why this is honest under §6.J / §6.AK:
 * - Both apps are REAL production APKs: the api-app is launched via the
 *   EXACT `AppLinkContract.EXTRA_START_API` intent contract
 *   `ApiControlViewModel.onStart()`/`MainActivity.handleIntent()` implement,
 *   started via `am start` (the same OS-level entry point a user's tap on
 *   "Open Lava API app" would trigger via `SiblingAppLauncher.intentToOpen()`).
 * - The client screen driven is the SAME production
 *   `OnboardingViewModel` → `LocalNetworkDiscoveryServiceImpl` → real Android
 *   `NsdManager` stack a real user's onboarding flow uses — no fake
 *   discovery service, no synthetic `DiscoveredEndpoint`.
 * - The primary assertion is on RENDERED user-visible state: the discovered
 *   API row's "Android device" subtitle (`discoveredApiLabel(platform =
 *   "android")` → `LABEL_ANDROID_DEVICE`), which can ONLY render if the real
 *   `NsdManager.discoverServices()` callback delivered a resolved service
 *   whose TXT record carries `platform=android` — i.e. the actual api-app
 *   instance running on this same device.
 *
 * Precondition (gate, §6.J clause 5 — no bluffed pass): the api-app debug
 * APK MUST already be installed on the target device/emulator before this
 * Challenge runs (installed by the gate runner alongside the client APK —
 * see `scripts/run-challenge-matrix.sh` usage note below). Absent that,
 * the test cleanly SKIPS via `assumeTrue` rather than reporting a false
 * PASS or a misleading generic failure.
 *
 * Gate runner note: this Challenge requires TWO installed APKs on one
 * emulator, which the single-app `scripts/run-challenge-matrix.sh` does not
 * provision by default. Install the api-app debug APK onto the target AVD
 * first (`adb -s <serial> install -r api-app/build/outputs/apk/debug/api-app-debug.apk`),
 * then run this test class via the normal Challenge matrix invocation.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2, recorded in commit body):
 *
 *   Mutation: in `LocalNetworkDiscoveryServiceImpl.kt`, force `discover()`'s
 *     `callbackFlow` to `close()` immediately after starting the two
 *     `NsdManager.discoverServices()` calls (never emit anything).
 *   Observed-Failure (expected): `onboardingApiSelection_discoversOnDeviceApiAppViaMdns`
 *     fails — the `waitUntil(timeoutMillis = 40_000)` for the "Android
 *     device" substring times out with
 *     `ComposeTimeoutException: Condition still not satisfied after 40000ms`.
 *   Reverted: yes.
 *
 * // covers-feature: onboarding
 */
package lava.app.challenges

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import digital.vasic.lava.client.BuildConfig
import digital.vasic.lava.client.MainActivity
import lava.app.ResetOnboardingPrefsRule
import lava.app.di.ApiSelectionTestFlag
import lava.applink.AppLinkContract
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

@SdkSuppress(maxSdkVersion = 35)
@HiltAndroidTest
class Challenge72SameDeviceMdnsDiscoveryTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val resetPrefs = ResetOnboardingPrefsRule()

    // This Challenge REQUIRES the ApiSelection step (apiSelectionEnabled =
    // true) to reach "Choose your API" — mirrors the C39/C40 pattern.
    @get:Rule(order = 2)
    val apiSelectionRule: ExternalResource = object : ExternalResource() {
        override fun before() { ApiSelectionTestFlag.enabled = true }
        override fun after() { ApiSelectionTestFlag.reset() }
    }

    @get:Rule(order = 3)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun launchOnDeviceApiAppBeforeActivityLaunches() {
        // Gate: the api-app debug package must already be installed on this
        // device/emulator (installed by the gate runner alongside the
        // client APK — see the class KDoc). A device without it cleanly
        // SKIPS rather than reporting a bluffed pass or a confusing crash.
        assumeTrue(
            "api-app package ($API_APP_DEBUG_PACKAGE) must be installed on " +
                "this device/emulator for the same-device mDNS discovery " +
                "Challenge (clause 6.G / §6.AK — install it alongside the " +
                "client APK before running this test class)",
            apiAppInstalled(),
        )

        // Grant POST_NOTIFICATIONS up front (API 33+) so MainActivity's
        // requestNotificationPermissionThenStart() does not block on a
        // system permission dialog this test does not drive.
        runShellCommand(
            "pm grant $API_APP_DEBUG_PACKAGE android.permission.POST_NOTIFICATIONS",
        )

        // Launch the api-app's MainActivity with the EXACT production
        // intent contract (AppLinkContract.EXTRA_START_API) a real user's
        // tap on "Install/Open Lava API app" (SiblingAppLauncher.intentToOpen()
        // + the extra added by OnboardingViewModel.onLaunchOnDeviceApi) would
        // send — auto-starts the embed + registers the mDNS advertisement.
        runShellCommand(
            "am start -n $API_APP_DEBUG_PACKAGE/$API_APP_MAIN_ACTIVITY " +
                "--ez ${AppLinkContract.EXTRA_START_API} true",
        )

        // Real device timing: give the embedded engine + NsdManager
        // registration time to complete before the client starts
        // discovering. Not synthetic — the engine start is an async
        // coroutine chain (ApiEngineController.start() -> engine.start() ->
        // advertiser.register()) with no client-observable completion signal
        // from this test process.
        Thread.sleep(ENGINE_STARTUP_WAIT_MS)

        // Bring the CLIENT back to the foreground, mirroring the real user
        // action of switching back to Lava after visiting the on-device API
        // app (e.g. the back button or the recent-apps switcher). The
        // engine + mDNS advertisement keep running in the background
        // regardless — that is the whole point of the on-device design.
        //
        // Without this step, [composeRule]'s MainActivity — already
        // launched by the rule BEFORE this @Before method runs — is left
        // STOPPED once the `am start` above brought api-app's own UI to
        // the foreground, and a stopped Activity's Compose hierarchy is
        // detached from the semantics-owner registry. Every subsequent
        // composeRule query then fails with "No compose hierarchies found
        // in the app", regardless of whether mDNS discovery itself works —
        // masking the feature this Challenge exists to verify.
        //
        // Uses UiDevice.pressBack() (a genuine system BACK key event
        // injected at the input-dispatcher level, via uiautomator — NOT
        // Espresso.pressBack(), NOT a fresh Intent-based re-launch). api-app
        // above was started with FLAG_ACTIVITY_NEW_TASK, so it is the ROOT
        // of its own brand-new task; BACK finishes that task's root activity
        // and returns control to whatever task was previously in front —
        // the client's task, per normal Android multitasking.
        //
        // Three alternatives were tried and rejected, each with device
        // evidence:
        //   - `adb shell am start` re-launch: EMPIRICALLY desynced
        //     ActivityScenario's own stage-tracking (`ActivityScenario.close()`
        //     hanging on waitForActivityToBecomeAnyOf([DESTROYED]) with "last
        //     lifecycle transition = PAUSED", and once a "Current state was
        //     null unexpectedly" NPE) even though the @Test body's own
        //     assertions completed without failure every time.
        //   - In-process Context.startActivity(FLAG_ACTIVITY_REORDER_TO_FRONT):
        //     identical ActivityScenario desync — any fresh Intent delivered
        //     to MainActivity mid-test confuses its bookkeeping on this suite,
        //     regardless of how the Intent is sent.
        //   - Espresso.pressBack(): throws NoActivityResumedException — it
        //     requires an activity of the INSTRUMENTED app to already be in
        //     RESUMED state, which is false here by design (api-app currently
        //     holds the foreground; that is the whole point of this step).
        //
        // UiDevice.pressBack() sends the key event through the system input
        // pipeline (like a hardware back-button press) with NO dependency on
        // which app currently owns the foreground and NO Intent delivered to
        // MainActivity — the scenario ActivityScenario is actually designed
        // to observe (a real user's back-navigation, not a competing launch).
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()

        // Settle time for the window-manager transition to complete before
        // the @Test body starts querying MainActivity's Compose semantics —
        // mirrors the same real-device-timing rationale as
        // ENGINE_STARTUP_WAIT_MS above (no client-observable completion
        // signal for a window-focus transition from this test process).
        Thread.sleep(FOREGROUND_SWITCH_SETTLE_MS)
    }

    @After
    fun stopOnDeviceApiApp() {
        // Observed (2 runs): after this test brings the client back to the
        // foreground mid-test (real-device app-switch simulation), the
        // ComposeTestRule's own automatic teardown — ActivityScenario.close()
        // finishing MainActivity and waiting for the DESTROYED transition —
        // intermittently hangs ("Activity never becomes requested state
        // [DESTROYED], last lifecycle transition = PAUSED") or NPEs
        // ("Current state was null unexpectedly"). This is ActivityScenario's
        // own stage-tracking, not a defect in the feature under test — the
        // @Test method's own assertions complete with no failure in every
        // observed run; only the rule-level teardown that runs AFTER the
        // @Test body is affected. ActivityScenario was not designed with a
        // competing foreground app mid-test as a primary scenario. Finishing
        // the activity explicitly HERE — in this @After method, which per
        // JUnit's rule-wrapping order runs BEFORE composeRule's own
        // Rule-level after() — gives the system a normal finish()->onDestroy()
        // window to complete before composeRule's automatic teardown makes
        // its own attempt, so that attempt finds the activity already
        // destroyed (a no-op) instead of racing a pending transition.
        runCatching {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                if (!composeRule.activity.isFinishing && !composeRule.activity.isDestroyed) {
                    composeRule.activity.finish()
                }
            }
        }
        Thread.sleep(ACTIVITY_FINISH_SETTLE_MS)

        // Best-effort cleanup so a re-run (or the next Challenge in the
        // matrix) does not inherit a live foreground service / mDNS
        // advertisement from this test.
        runCatching { runShellCommand("am force-stop $API_APP_DEBUG_PACKAGE") }
    }

    @Test
    fun onboardingApiSelection_discoversOnDeviceApiAppViaMdns() {
        hiltRule.inject()

        // Step 1: Welcome screen.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Welcome to Lava").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Get Started").performClick()

        // Step 2: ApiSelection "Choose your API" — real mDNS discovery via
        // the production LocalNetworkDiscoveryServiceImpl / NsdManager stack.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Choose your API").fetchSemanticsNodes().isNotEmpty()
        }

        // Primary assertion (Sixth Law clause 3): the rendered discovered-row
        // subtitle for a platform=android TXT record is
        // "Lava API · On this network · Android device" (discoveredApiLabel
        // / LABEL_ANDROID_DEVICE). This text can ONLY render if the real
        // NsdManager discovery on THIS device found + resolved the api-app
        // instance ALSO running on this device — the exact scenario the
        // operator reported as broken.
        composeRule.waitUntil(timeoutMillis = DISCOVERY_TIMEOUT_MS) {
            composeRule.onAllNodesWithText("Android device", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Android device", substring = true).let { node ->
            node.assertExists(
                "The on-device api-app ($API_APP_DEBUG_PACKAGE) was started and " +
                    "advertising over mDNS, but the client's onboarding " +
                    "'Choose your API' screen never rendered an 'Android " +
                    "device' row — same-device mDNS discovery is broken.",
            )
        }
    }

    private fun apiAppInstalled(): Boolean = runCatching {
        InstrumentationRegistry.getInstrumentation().targetContext.packageManager
            .getPackageInfo(API_APP_DEBUG_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Runs a shell command via [android.app.UiAutomation.executeShellCommand]
     * and blocks until the shell process closes its stdout (i.e. the command
     * has completed), returning the captured output. Used instead of a
     * fire-and-forget shell call so `pm grant` completes before `am start`,
     * and `am start` completes before this method returns.
     */
    private fun runShellCommand(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
    }

    private companion object {
        // §6.R: the api-app debug package id is derived the same way
        // production code derives it (BuildConfig.API_TARGET_PACKAGE is the
        // variant-aware id OnboardingAppLinkModule wires into
        // SiblingAppLauncher — debug -> ".dev" suffix, release -> base id) —
        // no literal package-id string here.
        val API_APP_DEBUG_PACKAGE: String = BuildConfig.API_TARGET_PACKAGE

        // The api-app's MainActivity fully-qualified class name. Not a
        // connection literal (§6.R governs addresses/ports/credentials/
        // secrets) — this is a component name, the same category as the
        // provider/package identifiers already exempted throughout
        // SiblingAppLauncher / AppLinkContract.
        const val API_APP_MAIN_ACTIVITY = "lava.api.app.MainActivity"

        const val ENGINE_STARTUP_WAIT_MS = 8_000L
        const val DISCOVERY_TIMEOUT_MS = 40_000L
        const val ACTIVITY_FINISH_SETTLE_MS = 2_000L
        const val FOREGROUND_SWITCH_SETTLE_MS = 3_000L
    }
}
