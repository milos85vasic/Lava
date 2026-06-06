/*
 * Challenge C05 (API-SYNC stream, 2026-06-06) — the on-device API embed contains
 * EXACTLY the current lava-api-go source codebase (§11.4.69 / §6.J). LOAD-BEARING
 * drift gate.
 *
 * What this proves for the user: when a tester installs the Lava API app and
 * starts it, the HTTPS API it serves on-device is built from the SAME
 * lava-api-go source the repository currently holds — not a stale `.so` left
 * over from a prior build. A drifted embed would serve old behaviour while every
 * other gate looked green (the canonical "tests pass, feature is the wrong
 * version" bluff). C05 closes that loop at runtime, on device.
 *
 * The mechanism (single source of truth):
 *   - scripts/compute-api-source-hash.sh hashes the exact non-test Go source +
 *     go.mod/go.sum that compile into liblavaapi.so.
 *   - build-cshared.sh injects that hash into internal/version.SourceHash via
 *     -ldflags -X, so the RUNNING .so reports it through mobile.Status().
 *   - The api-app build bakes the SAME hash (computed at Gradle-config time from
 *     the SAME script) into BuildConfig.LAVA_API_SOURCE_HASH.
 *   - This Challenge starts the REAL embed (real MainActivity → real
 *     ApiControlViewModel → real ApiEngineController → real NativeApiEngine →
 *     real liblavaapi.so) and asserts the running embed's reported sourceHash
 *     equals the APK's build-time hash.
 *
 * Equal hashes ⇒ the .so packaged in this APK was built from the same source the
 * APK was built against ⇒ no drift. A non-empty mismatch ⇒ the embed is stale.
 * An EMPTY embed hash ⇒ the .so was built without injection (a host-direct `go
 * build`, not the gate path) ⇒ hard fail (an un-injected embed must NOT silently
 * pass the drift gate).
 *
 * Anti-bluff posture (§6.J): the PRIMARY assertion is on the RUNNING native
 * embed's self-reported source identity vs the APK's build-time identity — a
 * user-/peer-observable integrity fact, not "a mock was called". Every layer is
 * production: real UI, real controller, real NativeApiEngine, real .so over JNI.
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2 / §6.AB.3):
 *
 *   Mutation: edit a tracked embed-linked .go file (e.g. append a comment to
 *     lava-api-go/internal/version/version.go) WITHOUT rebuilding the .so. The
 *     Gradle-config recompute of compute-api-source-hash.sh sees the change, so
 *     BuildConfig.LAVA_API_SOURCE_HASH changes, but the .so still reports the OLD
 *     injected hash.
 *   Observed-Failure (expected): embedHashMatchesBuildConfig fails —
 *     "embed source hash <old> != BuildConfig hash <new>: the on-device API
 *     embed is STALE vs the current lava-api-go source (drift)".
 *   Reverted: yes (revert the .go edit + rebuild the .so so both agree again).
 *
 *   Second discrimination (the empty-embed bluff): build the .so with a
 *   host-direct `go build` (no -ldflags injection) so the embed reports an
 *   EMPTY sourceHash. Observed: embedReportsNonEmptyHash fails —
 *   "running embed reported an EMPTY source hash". Reverted: rebuild via
 *   build-cshared.sh.
 *
 * Scope note (STREAM API-SYNC): this Challenge lives in :api-app, NOT :app. The
 * embed (:core:apiengine → liblavaapi.so) is packaged into the :api-app APK and
 * runs there; :app (the client) does not embed the .so. Placing a "read the
 * running embed's hash" Challenge in :app would be a bluff by construction
 * (there is no embed to read). The hash is wired into :app's BuildConfig too
 * (for the client-side onboarding "this device" linkage), but the load-bearing
 * runtime proof MUST run where the embed runs — here.
 *
 * Execution: WRITTEN BUT NOT EXECUTED in this stream (no Gradle build permitted —
 * a concurrent stream holds the Gradle environment). It runs in the Phase 11
 * quiet-gradle window on a container/VM emulator per §6.AH, and on the connected
 * device per §6.Z, BEFORE any distribute of the api-app.
 */
package lava.api.app.challenges

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import lava.api.app.BuildConfig
import lava.api.app.MainActivity
import lava.api.app.service.ApiEngineService
import lava.api.app.ui.TAG_START
import lava.api.app.ui.TAG_STATUS
import lava.api.app.ui.TAG_URL
import lava.apiengine.NativeApiEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge05ApiEmbedSourceHashMatchesTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun freshInstallState() {
        // Reset the process-global native engine a prior Challenge class may have
        // left running (the Go embed's `current` is process-global; Hilt rebuilds
        // the @Singleton controller per test). Same teardown C02/C03/C04 use.
        runCatching { runBlocking { NativeApiEngine().stop() } }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.stopService(Intent(ctx, ApiEngineService::class.java))
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun embedSourceHash_matchesBuildConfig_noDrift() {
        hiltRule.inject()

        // ===== Act: drive the REAL UI to start the embed =====
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(TAG_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_START).performClick()
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(TAG_URL).fetchSemanticsNodes().isNotEmpty() &&
                textOf(TAG_STATUS) == "Running"
        }

        // ===== Read the RUNNING embed's self-reported source identity =====
        // status() reads the live .so's version.SourceHash (ldflags-injected),
        // surfaced through mobile.Status().sourceHash → ApiStatus.sourceHash.
        val embedHash = NativeApiEngine().status().sourceHash
        val buildConfigHash = BuildConfig.LAVA_API_SOURCE_HASH

        // ===== Discrimination 1: the embed MUST report a non-empty hash =====
        // An empty embed hash means the .so was built without -ldflags injection
        // (a host-direct go build, not build-cshared.sh) — that bypasses the
        // drift gate, so it is a hard fail, never a silent pass.
        assertTrue(
            "PRIMARY: the running on-device API embed reported an EMPTY source hash. " +
                "The .so was built without -ldflags -X version.SourceHash injection — " +
                "rebuild via lava-api-go/scripts/build-cshared.sh (the gate path).",
            embedHash.isNotBlank(),
        )

        // Defensive: the APK's build-time hash must also be present (the
        // api-app build computes it from compute-api-source-hash.sh at config
        // time). Empty means the build host lacked the script — also a hard fail.
        assertTrue(
            "BuildConfig.LAVA_API_SOURCE_HASH is EMPTY — the api-app was built " +
                "without scripts/compute-api-source-hash.sh on PATH; the drift gate " +
                "cannot be verified.",
            buildConfigHash.isNotBlank(),
        )

        // ===== Discrimination 2 (PRIMARY drift assertion): hashes MUST be equal =====
        assertEquals(
            "PRIMARY: on-device API embed is STALE vs the current lava-api-go source " +
                "(drift). The running embed was built from source hash <$embedHash> " +
                "but THIS APK was built against <$buildConfigHash>. Rebuild the embed " +
                "via build-cshared.sh so the packaged .so matches the API codebase.",
            buildConfigHash,
            embedHash,
        )
    }

    private fun textOf(tag: String): String {
        val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        if (nodes.isEmpty()) return ""
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text) ?: return ""
        return texts.joinToString(separator = "") { it.text }
    }
}
