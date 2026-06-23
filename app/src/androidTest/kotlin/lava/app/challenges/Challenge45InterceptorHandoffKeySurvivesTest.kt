/*
 * Challenge Test C45 — AuthInterceptor's "only-if-absent" guard: a per-endpoint
 * handoff key pre-set on the request by ApiBackedTrackerClient.withAuth() MUST
 * survive to the wire intact; the interceptor MUST NOT overwrite it with the
 * build-time UUID (H1 / candidate 1072 regression guard, on-device equivalent
 * of the JVM unit proof in AuthInterceptorHandoffKeyTest.kt).
 *
 * ROOT CAUSE (the H1 bug, now fixed):
 * Before the fix, AuthInterceptor.intercept() called
 *   `chain.request().newBuilder().header(fieldName, headerValue)`
 * unconditionally, using OkHttp's REPLACE semantics.
 * ApiBackedTrackerClient.withAuth() set the per-endpoint handoff key BEFORE
 * the request reached interceptors (line 127 of ApiBackedTrackerClient.kt).
 * The interceptor fired after and silently overwrote that key with the
 * build-time UUID — so every /v1 request against the on-device api-app carried
 * the wrong credential and was rejected with HTTP 401 → "Something went wrong".
 *
 * FIX (AuthInterceptor.kt line 71-73):
 *   if (chain.request().header(fieldName) != null) {
 *       return chain.proceed(chain.request())  // handoff key already set — skip
 *   }
 * The interceptor is now a no-op when the request already carries the auth
 * header; it only attaches the build-time UUID when no per-endpoint key is
 * present (the remote cloud-API path).
 *
 * WHY THIS IS NOT A BLUFF (§6.J / Sixth Law / §6.AB):
 * - The SUT is the PRODUCTION OkHttpClient injected by Hilt — the same client
 *   the running app uses. AuthInterceptor is in its interceptor chain via the
 *   multibind set provided by AuthInterceptorModule. No mock of the interceptor,
 *   no bypass of the chain.
 * - The ONLY faked boundary is the network socket, replaced by MockWebServer
 *   running INSIDE this instrumented test process ON THE DEVICE. This is genuine
 *   on-device execution — the request traverses the full OkHttp interceptor
 *   chain before hitting the mock socket.
 * - PRIMARY assertion: the header VALUE recorded by MockWebServer on the wire
 *   (the packet that reached the "server"). Never "a mock was called N times".
 * - The discriminator test proves the header is LOAD-BEARING: a request WITHOUT
 *   the pre-set key triggers a 401 from the same dispatcher — demonstrating that
 *   the passing case above genuinely depends on the guard working.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL (non-crashing → the production bug):
 *
 *   1. In core/network/impl/src/main/kotlin/lava/network/impl/AuthInterceptor.kt
 *      remove (or comment out) the "only-if-absent" guard — lines 71-73:
 *        // if (chain.request().header(fieldName) != null) {
 *        //     return chain.proceed(chain.request())
 *        // }
 *      This reverts to the pre-fix unconditional `.header(fieldName, headerValue)`
 *      which overwrites any pre-existing handoff key with the build-time UUID.
 *   2. Re-run on the gating emulator:
 *        ./gradlew :app:connectedDebugAndroidTest \
 *          --tests "lava.app.challenges.Challenge45InterceptorHandoffKeySurvivesTest\
 *                   #handoffKeyPreSetOnRequest_survivesToWire_interceptorDoesNotOverwrite"
 *   3. Expected failure: MockWebServer records the build-time UUID value (not
 *      the handoff key) in the Lava-Auth header. The dispatcher returns HTTP 401
 *      because the UUID does not equal HANDOFF-KEY-C45-TEST. The assertion
 *        assertEquals("Handoff key MUST survive the AuthInterceptor…", HANDOFF_KEY, …)
 *      fails with:
 *        org.junit.ComparisonFailure: expected:<[HANDOFF-KEY-C45-TEST]>
 *                                      but was:<[<base64-encoded-build-time-UUID>]>
 *      proving the guard is load-bearing and the test is not a bluff.
 *   4. Revert the guard; re-run; the handoff key survives, the dispatcher
 *      returns 200, and both tests pass.
 *
 * ⚠ SCOPE / HONEST LIMITATION (§6.J disclosure, verified on-device 2026-06-23):
 * The falsifiability rehearsal above fires ONLY when the LavaAuthGenerated blob is
 * NON-EMPTY (real .env at build time). When the blob is empty, AuthInterceptor.intercept()
 * early-exits as a no-op BEFORE the only-if-absent guard, so removing the guard does not
 * change behaviour and the handoff key passes through regardless — i.e. this on-device run
 * proves the search-auth FLOW (the handoff key value reaches the wire, dispatcher → 200) but
 * does NOT, in an empty-blob build, isolate the H1 interceptor-overwrite. The DEFINITIVE,
 * environment-independent H1 falsifiability proof is the JVM unit test
 * core/network/impl AuthInterceptorHandoffKeyTest (injects a non-empty blob stub → interceptor
 * active → guard removed → ComparisonFailure expected:<HANDOFF-KEY> but was:<base64 build-time
 * UUID>; reverted → PASS). On-device attestation: .lava-ci-evidence/thinker-c00-smoke/2026-06-23-C45-on-device-PASS.json (all_passed:true, gating:true).
 *
 * Operator command (device run via the §6.AE Containers matrix):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     --tests "lava.app.challenges.Challenge45InterceptorHandoffKeySurvivesTest"
 *
 * // covers-feature: network
 */
package lava.app.challenges

import androidx.test.filters.SdkSuppress
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject
import javax.inject.Named

/**
 * On-device wire-level regression guard for the H1 "only-if-absent" fix in
 * [lava.network.impl.AuthInterceptor].
 *
 * Uses the Hilt-injected production [OkHttpClient] (AuthInterceptor is already
 * in its interceptor chain) and a [MockWebServer] running on the device.
 * Simulates what [lava.tracker.client.ApiBackedTrackerClient.withAuth] does:
 * pre-sets the [Lava-Auth] header on the [Request] BEFORE it enters the
 * interceptor chain. Asserts the recorded wire value equals the handoff key —
 * not the build-time UUID the interceptor would have written unconditionally
 * before the fix.
 *
 * §6.AB.3 / §6.J FALSIFIABILITY REHEARSAL — see class KDoc above.
 */
@SdkSuppress(maxSdkVersion = 35) // Forward-compat skip on API 36+ per the project's
// Espresso/Compose-on-API36 incident
// (.lava-ci-evidence/sixth-law-incidents/2026-05-05-pixel9a-espresso-api36-incompatibility.json).
@HiltAndroidTest
class Challenge45InterceptorHandoffKeySurvivesTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    /**
     * The production OkHttpClient — the same one the running app uses for
     * all non-LAN requests. AuthInterceptor is installed into its interceptor
     * chain by [lava.network.di.AuthInterceptorModule] via the multibind set.
     * We never reference AuthInterceptor directly (it is internal to
     * lava.network.impl); we simply use the already-wired client.
     */
    @Inject
    lateinit var httpClient: OkHttpClient

    /**
     * The HTTP header name the interceptor reads and writes.
     * Provided by [lava.network.di.NetworkModule.authFieldName]; defaults to
     * "Lava-Auth" when no .env override is present.
     */
    @Inject
    @Named("authFieldName")
    lateinit var authFieldName: String

    private lateinit var server: MockWebServer

    /** A stable handoff key that is NOT a base64-encoded UUID of any kind. */
    private val HANDOFF_KEY = "HANDOFF-KEY-C45-TEST"

    @Before
    fun setUp() {
        hiltRule.inject()
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * PRIMARY REGRESSION TEST — the H1 fix.
     *
     * Simulates the on-device api-app path:
     *   ApiBackedTrackerClient.withAuth() → sets Lava-Auth = handoff key
     *   → request enters OkHttp chain → AuthInterceptor fires
     *   → guard sees header already present → skips the UUID attachment
     *   → handoff key reaches the wire intact.
     *
     * §6.J clause 3: primary assertion is on [RecordedRequest.getHeader] —
     * the bytes that arrived at the mock server socket on the device.
     */
    @Test
    fun handoffKeyPreSetOnRequest_survivesToWire_interceptorDoesNotOverwrite() {
        // MockWebServer gate: 200 only if the handoff key arrived intact;
        // 401 if anything else (e.g. the build-time UUID) arrived instead.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader(authFieldName) == HANDOFF_KEY) {
                    MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setBody("""{"status":"ok","received":"$HANDOFF_KEY"}""")
                } else {
                    MockResponse()
                        .setResponseCode(401)
                        .setBody(
                            """{"error":"wrong_key","got":"${request.getHeader(authFieldName)}"}""",
                        )
                }
        }

        // Simulate ApiBackedTrackerClient.withAuth(): set the per-endpoint
        // handoff key on the request BEFORE it enters the interceptor chain.
        val request = Request.Builder()
            .url(server.url("/v1/rutracker/search"))
            .header(authFieldName, HANDOFF_KEY)
            .build()

        val response = httpClient.newCall(request).execute()
        val recordedRequest = server.takeRequest()
        val wireName = authFieldName // capture for assertion messages

        // PRIMARY assertion (§6.J clause 3 / Sixth Law clause 3):
        // the header value that reached the wire endpoint must be the
        // handoff key — not whatever the interceptor would have written.
        assertEquals(
            "The Hilt-wired OkHttpClient's AuthInterceptor MUST NOT overwrite a " +
                "pre-existing $wireName handoff key with the build-time UUID — " +
                "this is the on-device H1 regression guard",
            HANDOFF_KEY,
            recordedRequest.getHeader(wireName),
        )

        // SECONDARY assertion: the server accepted the key (200, not 401).
        // If the interceptor overwrote the key with the UUID, the dispatcher
        // would have returned 401 and this would also fail — belt-and-suspenders
        // that the guard is genuinely working end-to-end.
        assertEquals(
            "MockWebServer MUST return 200 when the correct handoff key arrived on the wire; " +
                "401 means the interceptor overwrote it with the build-time UUID",
            200,
            response.code,
        )
        response.close()
    }

    /**
     * DISCRIMINATOR — proves the handoff key is load-bearing.
     *
     * When NO handoff key is pre-set (the remote cloud-API path, where
     * ApiBackedTrackerClient is constructed with authKey=null), the same
     * MockWebServer dispatcher returns 401 because whatever the interceptor
     * attached (the build-time UUID) is not the HANDOFF_KEY sentinel.
     *
     * This confirms that the passing test above genuinely depends on the
     * handoff key surviving — not on a permissive server that accepts anything.
     *
     * Note: the interceptor MAY or MAY NOT attach a build-time UUID depending
     * on whether LavaAuthGenerated is present on the classpath (Phase-11 state).
     * In either case, the dispatcher's gate (== HANDOFF_KEY) is NOT met when no
     * key is pre-set, so the discriminator returns 401.  We assert on the 401.
     */
    @Test
    fun noPreSetKey_serverRejects_provingHandoffKeyIsLoadBearing() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader(authFieldName) == HANDOFF_KEY) {
                    MockResponse().setResponseCode(200)
                } else {
                    MockResponse().setResponseCode(401)
                }
        }

        // No pre-set handoff key — the remote cloud-API path.
        val request = Request.Builder()
            .url(server.url("/v1/rutracker/search"))
            .build()

        val response = httpClient.newCall(request).execute()
        response.close()

        assertEquals(
            "Without a pre-set handoff key the dispatcher MUST reject with 401 — " +
                "proving the 200 in the main test genuinely requires the key on the wire",
            401,
            response.code,
        )
    }
}
