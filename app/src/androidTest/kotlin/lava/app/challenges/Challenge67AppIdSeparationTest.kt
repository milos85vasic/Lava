/*
 * Challenge Test C67 — debug (.dev) and release builds are distinguishable by
 * applicationId AND by display label, installable side-by-side (LVA-091 / Video #10).
 *
 * OPERATOR-REPORTED OBSERVATION (video #10, UNCONFIRMED, recorded against 1076):
 *   "App-ID co-mingling (debug .dev + release both labeled 'Lava')." The
 *    operator noted both the debug APK and the release APK show the same
 *    home-screen display name "Lava".
 *
 * WHAT THIS CHALLENGE PROVES (device-checkable):
 *   1. The DEBUG build's applicationId carries the `.dev` suffix
 *      (`digital.vasic.lava.client.dev`; app/build.gradle.kts's `debug { }` block
 *      sets `applicationIdSuffix = ".dev"`), DISTINCT from the release
 *      applicationId (`digital.vasic.lava.client`). Distinct applicationIds are
 *      what let Android install the two builds SIDE-BY-SIDE without conflict.
 *   2. The DEBUG build's display LABEL is ALSO distinct: `app/src/debug/res/values/strings.xml`
 *      overrides `app_name` to "Lava DEV" (the release/main value in
 *      `app/src/main/res/values/strings.xml` is "Lava"). This resource override
 *      predates this test and the operator's video by roughly two months
 *      (April 2026 rebrand). Per Android resource-overlay rules, the `debug`
 *      source set's `strings.xml` value wins for the debug build variant, so
 *      the two variants ARE already visually distinguishable by label.
 *   3. The debug launcher icon background is also overridden to solid green
 *      (`app/src/debug/res/drawable/ic_launcher_background.xml`) vs the
 *      release/main build's real artwork background — a second, independent
 *      visual distinguisher.
 *
 *   The operator's video #10 observation could not confirm co-mingling visually
 *   (frames 0001/0005 showed a single Lava icon launched) and was marked
 *   UNCONFIRMED pending an on-device check. This Challenge supplies that check:
 *   debug and release are NOT co-mingled — distinct applicationId, distinct
 *   label, distinct icon background, all already shipped.
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
 * 1a. applicationId regression: remove `applicationIdSuffix = ".dev"` from the
 *     `debug { … }` block in app/build.gradle.kts.
 * 1b. label regression: delete (or blank) the `app_name` override in
 *     `app/src/debug/res/values/strings.xml` so the debug build falls back to
 *     the shared "Lava" string.
 * 2. Build the androidTest APK via `./gradlew :app:assembleDebugAndroidTest`.
 * 3. Install + run THIS Challenge only via `scripts/run-challenge-matrix.sh
 *    --test-class lava.app.challenges.Challenge67AppIdSeparationTest`.
 * 4. Expected failures:
 *      (a) with mutation 1a: debugBuild_hasDevApplicationIdSuffix fails —
 *          assertEquals("digital.vasic.lava.client.dev", packageName) throws
 *          "expected:<...client.dev> but was:<...client>".
 *      (b) with mutation 1b: appLabel_debugVariantIsDistinctFromRelease fails —
 *          assertNotEquals(releaseLabel, debugLabel) throws because both now
 *          resolve to "Lava".
 *
 * ### GREEN run (after fix)
 * 5. Revert the mutation (git checkout app/build.gradle.kts app/src/debug/res/values/strings.xml).
 * 6. Rebuild + re-run the identical Challenge.
 * 7. Expected pass: distinct applicationId AND distinct label.
 *
 * ### Mutation type
 * NON-CRASHING BREAK — incorrect packaging/labeling identity, no crash (§6.AB.3).
 *
 * ### LVA-008 dependency
 * NONE — no UI / no NavHost; reads the installed package identity + resolved
 * application label only.
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
    // The debug build's display label is DISTINCT from the release label
    // ("Lava DEV" vs "Lava") — confirming LVA-091's video #10 observation does
    // NOT reproduce: the two variants are already visually distinguishable.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun appLabel_debugVariantIsDistinctFromRelease() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = ctx.packageManager
        // Reading `ctx.applicationInfo.loadLabel(pm)` off the Context's CACHED
        // ApplicationInfo returned the raw, unresolved "@string/app_name"
        // manifest value on device (its `nonLocalizedLabel` carried the literal
        // ref, and loadLabel/getApplicationLabel short-circuit to it before any
        // resource lookup). Re-fetch a fresh ApplicationInfo from the
        // PackageManager — that instance has `labelRes` populated, so
        // getApplicationLabel resolves it against the app's resource table.
        val resolvedAppInfo = pm.getApplicationInfo(ctx.packageName, 0)
        val debugLabel = pm.getApplicationLabel(resolvedAppInfo).toString()

        // PRIMARY ASSERTION — the debug label is the distinct "Lava DEV" value
        // from app/src/debug/res/values/strings.xml, NOT the shared release
        // value "Lava".
        assertEquals(
            "The debug build's application label MUST be the distinct debug " +
                "override ('Lava DEV' from app/src/debug/res/values/strings.xml), " +
                "not the shared release label 'Lava' — otherwise debug and " +
                "release ARE co-mingled on the home screen (the LVA-091 " +
                "regression this assertion guards against).",
            "Lava DEV",
            debugLabel,
        )

        // PRIMARY ASSERTION — the debug label is genuinely distinct from the
        // release label, not merely happens to differ today.
        assertTrue(
            "Debug application label ('$debugLabel') must differ from the " +
                "release label ('Lava') for the two variants to be visually " +
                "distinguishable on a device with both installed.",
            debugLabel != "Lava",
        )
    }
}
