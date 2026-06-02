/*
 * Challenge C02 (Phase E, 2026-06-02) — :api-app boots + serves a REAL
 * authenticated HTTPS API on-device. LOAD-BEARING acceptance gate.
 *
 * What the user does: opens the Lava API app, taps Start, and watches the
 * screen flip to Running with a reachable URL + an access key. A peer device
 * on the LAN then hits that URL over HTTPS, presenting the access key in the
 * Lava-Auth header, and gets real JSON back. This Challenge is that peer: it
 * drives the REAL UI to start the embed, reads the access key the screen
 * displays, then — from the test process — issues genuine HTTPS requests to
 * the on-device server and asserts on the actual HTTP response.
 *
 * Three load-bearing assertions, all on user-/peer-observable HTTP state:
 *   (a) GET /health (the pre-auth liveness route) → 200 + real JSON body
 *       (`true`/`false`/object — the embed's LivenessHandler output).
 *   (b) GET an auth-gated production route WITHOUT the key → 401. The embed's
 *       Lava-Auth gate (internal/auth.NewMiddleware) rejects unauthenticated
 *       callers — proving the API is not wide open.
 *   (c) GET the SAME auth-gated route WITH the access key read from the UI →
 *       NOT 401. The key the screen shows is the production base64-UUID the
 *       embed's middleware HMACs + accepts, so the gate lets the request
 *       through (the downstream status depends on third-party rutracker
 *       reachability, which is NOT under test — the gate acceptance is).
 *
 * Why /index for (b)/(c): it is a GET registered behind the auth middleware
 * (router.go) so it exercises the real gate; we assert ONLY on the gate's
 * verdict (401 vs not-401), never on rutracker.org being reachable, so the
 * test is deterministic on-device regardless of outbound network.
 *
 * TLS: the request trusts EXACTLY the self-signed leaf the embed persisted
 * (OnDeviceApiClient) — not a blanket trust-all. A wrong cert fails the
 * handshake. Connecting to 127.0.0.1 (an IP SAN the embed bakes in) keeps
 * default hostname verification ON.
 *
 * Anti-bluff posture (§6.J): every layer is production — real MainActivity,
 * real ApiControlScreen, real ApiControlViewModel, real singleton
 * ApiEngineController, real NativeApiEngine embed serving real TLS. The
 * PRIMARY assertions are HTTP status + response body on the wire, never
 * "the controller was called".
 *
 * FALSIFIABILITY REHEARSAL (§6.AB.3 / Sixth Law clause 2):
 *
 *   Rehearsal 1 (assertion a): in lava-api-go internal/observability
 *   LivenessHandler, return http.StatusInternalServerError. Re-run:
 *   assertion (a) fails with "health MUST return 200, got 500" — the served
 *   /health is proven broken.
 *
 *   Rehearsal 2 (assertion c): in internal/auth.NewMiddleware, replace the
 *   active-clients lookup so the supplied key is NOT matched (e.g. compare
 *   against an empty map). Re-run: assertion (c) fails because the keyed
 *   request now returns 401 — proving the test discriminates a broken auth
 *   gate that wrongly rejects the real key.
 *
 *   Rehearsal 3 (assertion b): in internal/router.Build, register /index
 *   BEFORE the auth middleware (like /health). Re-run: assertion (b) fails
 *   because the no-key request now returns 200/5xx instead of 401 — proving
 *   the test discriminates a route that silently lost its auth gate.
 *
 *   Each reverted after rehearsal; unmutated code is what ships.
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
import lava.api.app.MainActivity
import lava.api.app.service.ApiEngineService
import lava.api.app.ui.TAG_ACCESS_KEY
import lava.api.app.ui.TAG_START
import lava.api.app.ui.TAG_STATUS
import lava.api.app.ui.TAG_URL
import lava.apiengine.NativeApiEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class Challenge02ApiAppBootAndServeTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun freshInstallState() {
        // Reset the process-global native engine a prior Challenge class may
        // have left running. The Go embed's `current` is process-global, but
        // Hilt rebuilds the @Singleton ApiEngineController per test — so without
        // this, the next Start hits mobile.Start's "already running" guard and
        // the start→Running wait times out. runCatching swallows the benign
        // "no server running" when nothing was left over. (Surfaced by C03/C04
        // once the :api-app gate first actually ran them.)
        runCatching { runBlocking { NativeApiEngine().stop() } }
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // Kill any foreground Service a prior Challenge left running: it holds
        // that prior test's @Singleton controller (Hilt rebuilds the controller
        // per test), and a stale Service would intercept THIS test's start /
        // notification actions, driving the wrong (stale) controller.
        ctx.stopService(Intent(ctx, ApiEngineService::class.java))
        ctx.filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    @Test
    fun start_thenServeRealHttps_healthAndAuthGatedRoute() {
        hiltRule.inject()

        // ===== Act: drive the REAL UI to start the embed =====
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(TAG_STATUS).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_START).performClick()

        // Wait until the screen reaches Running (status word "Running" + the
        // reachable URL rendered). This is the real user-visible signal that
        // the embed bound its port and is serving.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(TAG_URL).fetchSemanticsNodes().isNotEmpty() &&
                textOf(TAG_STATUS) == "Running"
        }

        val url = textOf(TAG_URL)
        val accessKey = textOf(TAG_ACCESS_KEY)
        assertTrue("Reachable URL must be https on the running embed", url.startsWith("https://"))
        assertTrue("Access key must be displayed while Running", accessKey.isNotBlank())

        val port = portOf(url)
        val client = OnDeviceApiClient(
            InstrumentationRegistry.getInstrumentation().targetContext,
            port,
        )

        // ===== Assert (a): /health → 200 + real JSON body =====
        client.get("/health").use { resp ->
            assertEquals(
                "PRIMARY: /health MUST return 200 from the running on-device embed",
                200,
                resp.code,
            )
            val body = resp.body?.string().orEmpty()
            assertTrue(
                "/health body must be non-empty JSON (got: '$body')",
                body.isNotBlank(),
            )
            // LivenessHandler writes a JSON document; assert it parses as JSON
            // shape (object or boolean), not HTML/an error page.
            assertTrue(
                "/health body must look like JSON (got: '$body')",
                body.trimStart().firstOrNull() in setOf('{', 't', 'f', '"'),
            )
        }

        // ===== Assert (b): auth-gated route WITHOUT key → 401 =====
        client.get(AUTH_GATED_ROUTE).use { resp ->
            assertEquals(
                "PRIMARY: $AUTH_GATED_ROUTE without the Lava-Auth key MUST be 401 " +
                    "(the embed's auth gate rejects unauthenticated peers)",
                401,
                resp.code,
            )
        }

        // ===== Assert (c): SAME route WITH the UI-displayed key → NOT 401 =====
        // The key the screen shows is the production base64-UUID the embed
        // HMACs + accepts; presenting it in the Lava-Auth header passes the
        // gate. We assert the gate ACCEPTS (status != 401) — the downstream
        // rutracker reachability is a third-party concern not under test.
        client.getWithKey(AUTH_GATED_ROUTE, accessKey, LAVA_AUTH_HEADER).use { resp ->
            assertNotEquals(
                "PRIMARY: $AUTH_GATED_ROUTE WITH the access key read from the UI MUST " +
                    "pass the auth gate (status != 401). The displayed key is the real " +
                    "credential the embed honours.",
                401,
                resp.code,
            )
        }
    }

    private fun portOf(url: String): Int =
        url.substringAfterLast(':').substringBefore('/').toInt()

    private fun textOf(tag: String): String {
        val nodes = composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes()
        if (nodes.isEmpty()) return ""
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text) ?: return ""
        return texts.joinToString(separator = "") { it.text }
    }

    private companion object {
        // GET route registered BEHIND the auth middleware in
        // lava-api-go/internal/router.go (handlers.go: router.GET("/index", ...)).
        // It exercises the real Lava-Auth gate; we assert only on the gate verdict.
        const val AUTH_GATED_ROUTE = "/index"

        // The embed's default credential header (ApiKeyStore.DEFAULT_FIELD_NAME /
        // ApiConfig.authFieldName default). The displayed status also carries it
        // via ApiControlState.Running.authFieldName, but loopback-on-device uses
        // the same default the embed enforces.
        const val LAVA_AUTH_HEADER = "Lava-Auth"
    }
}
