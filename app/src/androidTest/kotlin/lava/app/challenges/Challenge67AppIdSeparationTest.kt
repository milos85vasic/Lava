/*
 * Challenge Test C67 — debug (.dev) and release builds are distinguishable by
 * applicationId, installable side-by-side (LVA-091 / Video #10).
 *
 * DRAFT — authored by the test-engineering stream; NOT yet executed on device.
 * The main stream device-runs this. No pass is claimed here.
 *
 * OPERATOR-REPORTED OBSERVATION (video #10, UNCONFIRMED, recorded against 1076):
 *   "App-ID co-mingling (debug .dev + release both labeled 'Lava')." The
 *    operator noted both the debug APK and the release APK show the same
 *    home-screen display name "Lava".
 *
 * WHAT THIS CHALLENGE CAN PROVE (device-checkable, honest):
 *   The DEBUG build's applicationId carries the `.dev` suffix
 *   (`digital.vasic.lava.client.dev`; verified in app/build.gradle.kts:207
 *   `applicationIdSuffix = ".dev"`), DISTINCT from the release applicationId
 *   (`digital.vasic.lava.client`; app/build.gradle.kts:51). Distinct
 *   applicationIds are what let Android install the two builds SIDE-BY-SIDE
 *   without conflict. This Challenge asserts the running (debug) build's package
 *   name, which is the mechanical fact that determines side-by-side
 *   installability.
 *
 * WHAT THIS CHALLENGE CANNOT PROVE (stated honestly per §6.J — no bluff):
 *   It cannot prove the two builds are visually distinguishable on the home
 *   screen by LABEL. The display name is `@string/app_name` = "Lava"
 *   (app/src/main/res/values/strings.xml:2; AndroidManifest android:label at
 *   line 44), and the debug variant does NOT override it — so BOTH variants
 *   show "Lava". That shared label IS the operator's "co-mingling" observation,
 *   and it is EXPECTED Android behavior: the display label comes from the
 *   manifest, not the applicationId. This Challenge therefore CONFIRMS LVA-091
 *   is cosmetic (distinct ids, shared label) rather than a packaging conflict.
 *   If the operator later requires a DISTINCT debug label (e.g. "Lava dev"),
 *   that is a NEW production change (a per-variant manifestPlaceholder /
 *   resValue label override) and this Challenge's `appLabel...` assertion would
 *   then be updated to expect the differentiated label — at which point it
 *   becomes the reproduce-first guard for that change.
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law clause 3):
 *   The assertions are on measurable, device-observable state: the live
 *   `targetContext.packageName` and the resolved application label — facts a
 *   real install exposes, not "a mock was called". The test runs in the same
 *   instrumentation process as the app-under-test, so `targetContext` is the
 *   real installed (debug) package.
 *
 * ## §6.AK Reproduce-First Protocol
 *
 * ### RED run (before fix / on a regression)
 * 1. Apply the mutation in app/build.gradle.kts: remove
 *    `applicationIdSuffix = ".dev"` from the `debug { … }` block (so the debug
 *    build shares the release applicationId — the co-mingling regression that
 *    would BREAK side-by-side install).
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run THIS Challenge only:
 *    adb shell am instrument -w -e class \
 *      lava.app.challenges.Challenge67AppIdSeparationTest \
 *      digital.vasic.lava.client.dev.test/androidx.test.runner.AndroidJUnitRunner
 *    (note: with the suffix removed the test package id also changes; the run
 *    target becomes digital.vasic.lava.client.test — the operator uses the
 *    matching test package for the RED run.)
 * 4. Expected failure: debugBuild_hasDevApplicationIdSuffix fails —
 *    assertEquals("digital.vasic.lava.client.dev", packageName) throws
 *    "expected:<digital.vasic.lava.client.dev> but was:<digital.vasic.lava.client>".
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout app/build.gradle.kts).
 * 6. Rebuild + re-run the identical Challenge.
 * 7. Expected pass: the debug package name carries the `.dev` suffix.
 *
 * ### Mutation type
 * NON-CRASHING BREAK — incorrect packaging identity, no crash (§6.AB.3).
 *
 * ### LVA-008 dependency
 * NONE — no UI / no NavHost; reads the installed package identity only.
 *
 * // covers-changelog: LVA-091
 * // covers-feature: app
 */
package lava.app.challenges

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Challenge67AppIdSeparationTest {

    // The release applicationId (app/build.gradle.kts:51). The debug build under
    // test must NOT share this — it carries the ".dev" suffix.
    private val releaseApplicationId = "digital.vasic.lava.client"

    // The debug applicationId = release + applicationIdSuffix ".dev"
    // (app/build.gradle.kts:207).
    private val debugApplicationId = "$releaseApplicationId.dev"

    // ─────────────────────────────────────────────────────────────────────────
    // The running (debug) build's applicationId carries the ".dev" suffix,
    // making it DISTINCT from the release applicationId — the mechanical fact
    // that allows both to be installed side-by-side without conflict.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun debugBuild_hasDevApplicationIdSuffix() {
        val packageName =
            InstrumentationRegistry.getInstrumentation().targetContext.packageName

        // PRIMARY ASSERTION — the live installed package name is the .dev id.
        assertEquals(
            "The debug build's applicationId MUST carry the '.dev' suffix so it " +
                "installs side-by-side with the release build (LVA-091). A bare " +
                "release id here would mean the two builds collide on one " +
                "package and cannot co-exist.",
            debugApplicationId,
            packageName,
        )

        // PRIMARY ASSERTION — the debug id is genuinely DISTINCT from release.
        assertTrue(
            "Debug applicationId ($packageName) must differ from the release " +
                "applicationId ($releaseApplicationId) for side-by-side install.",
            packageName != releaseApplicationId && packageName.endsWith(".dev"),
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HONEST DOCUMENTATION ASSERTION — both variants share the display label
    // "Lava" (@string/app_name). This CONFIRMS the operator's video #10
    // observation is the EXPECTED, cosmetic shared-label behavior (label comes
    // from the manifest, not the applicationId), not a packaging conflict.
    //
    // If a distinct debug label is later required, this assertion is updated to
    // expect the differentiated label and becomes the guard for that change.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun appLabel_isLava_sharedAcrossVariants_documented() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        // Resolve the display label the user actually sees on the home screen.
        // Reading `ctx.applicationInfo.loadLabel(pm)` off the Context's CACHED
        // ApplicationInfo returned the raw, unresolved "@string/app_name"
        // manifest value on device (its `nonLocalizedLabel` carried the literal
        // ref, and loadLabel/getApplicationLabel short-circuit to it before any
        // resource lookup). Re-fetch a fresh ApplicationInfo from the
        // PackageManager — that instance has `labelRes` populated, so
        // getApplicationLabel resolves it against the app's resource table to
        // "Lava". (getApplicationLabel(info) just calls info.loadLabel(pm), so
        // the fix is the FRESH info object, not the call site.)
        val resolvedAppInfo = pm.getApplicationInfo(ctx.packageName, 0)
        val label = pm.getApplicationLabel(resolvedAppInfo).toString()

        assertEquals(
            "The application display label is '@string/app_name' = 'Lava' and " +
                "is NOT overridden per-variant, so debug and release share it. " +
                "This is the EXPECTED, cosmetic shared-label behavior the " +
                "operator observed in video #10 — distinct applicationIds, " +
                "shared label. (Update this assertion if a distinct debug label " +
                "is introduced.)",
            "Lava",
            label,
        )
    }
}
