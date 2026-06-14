package lava.api.app.handoff

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import lava.api.app.ApiApplication
import lava.api.app.auth.ApiKeyStore
import lava.api.app.control.ApiEngineController
import lava.api.app.service.MdnsAdvertiser
import lava.apiengine.FakeApiEngine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression test for the 2026-06-14 search key-handoff defect (5th-layer root
 * cause of "search does not work in any scenario").
 *
 * Unlike [ApiKeyProviderTest], this test does NOT use [ApiKeyProvider.withFakes].
 * `withFakes` substitutes the key/port lambdas wholesale and therefore BYPASSES
 * the production default lambdas + the [ApiApplication.controllerHolder] /
 * [ApiApplication.keyStoreHolder] resolution path — which is EXACTLY the path
 * that shipped broken. A test that only exercises `withFakes` is a §6.J bluff:
 * it stays green while the real holder-resolution path serves an empty cursor
 * forever (the client then reads a null key and every /v1 search 401's).
 *
 * This test instead drives the REAL default lambdas through the REAL companion
 * holders, simulating the production lifecycle ordering Android actually uses:
 *
 *   1. ContentProvider.onCreate() runs FIRST, while the holders are still null
 *      (Application.onCreate() has not run yet).
 *   2. Application.onCreate() THEN publishes the controller + keyStore into the
 *      companion holders (here via the [ApiApplication.setHoldersForTest] seam).
 *   3. The engine reaches Running.
 *   4. A client query() reads the live key + port.
 *
 * The SUT is a real [ApiKeyProvider] (no mocking of the SUT). The only fakes are
 * BELOW the SUT: a real [ApiEngineController] wired to [FakeApiEngine] (the
 * Third-Law behaviorally-equivalent engine double) + a recording advertiser +
 * an in-memory key store. PRIMARY assertion is on the cursor contents — the
 * exact `{access_key, loopback_port}` a real Lava client reads via
 * ContentResolver.
 *
 * FALSIFIABILITY REHEARSAL (recorded in commit Bluff-Audit, 2026-06-14):
 *   Mutation: revert [ApiKeyProvider.onCreate] to the pre-fix version that
 *     CACHED the key/port lambdas, gated on
 *     `ApiApplication.controllerHolder/keyStoreHolder != null`. Because
 *     ContentProvider.onCreate() runs BEFORE Application.onCreate(), the holders
 *     are null at onCreate() time, so the cached lambdas stay `{ null }` for the
 *     whole process.
 *   Observed: `running_engine_exposes_real_key_via_holders` fails with
 *     "expected:<1> but was:<0>" (empty cursor — the holders set AFTER onCreate
 *     are never consulted because the lambdas were cached at onCreate time).
 *   Reverted: yes (the lazy fix restores PASS).
 *
 * @Config: `application = Application::class` overrides Robolectric's default
 * (instantiating the @HiltAndroidApp ApiApplication, which injects an
 * EncryptedSharedPreferences-backed ApiKeyStore unavailable on the JVM). We
 * drive the companion holders directly via the test seam instead — no Hilt /
 * AndroidKeyStore needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33], application = Application::class)
class ApiKeyProviderRealHolderTest {

    private class RecordingAdvertiser : MdnsAdvertiser {
        override fun register(port: Int) = Unit
        override fun unregister() = Unit
    }

    private class FakeKeyStore(private val key: String) : ApiKeyStore {
        override fun getOrCreate(): String = key
        override val fieldName: String = "Lava-Auth"
    }

    @After
    fun resetHolders() {
        // The holders are process-wide statics; reset so a later test in the
        // same JVM does not see this test's controller/keyStore (§ cross-test
        // leakage). Mirrors the pre-onCreate state.
        ApiApplication.setHoldersForTest(null, null)
    }

    @Test
    fun running_engine_exposes_real_key_via_holders() {
        val expectedKey = "dGVzdC1rZXktMTYtYnl0ZXM="
        val expectedPort = 8443

        // ── Step 1: ContentProvider.onCreate() runs FIRST, holders still null. ──
        // This is the exact ordering Android uses: providers init before the
        // Application. With the FIX, onCreate() does NOT cache anything; with the
        // pre-fix bug it would cache the null-resolving lambdas here.
        ApiApplication.setHoldersForTest(null, null)
        val provider = ApiKeyProvider()
        provider.attachInfoForTest(ApplicationProvider.getApplicationContext())
        provider.onCreate()

        // ── Step 2: Application.onCreate() publishes the real singletons. ──
        val controller = ApiEngineController(
            engine = FakeApiEngine(version = "embed-test-9.9.9"),
            advertiser = RecordingAdvertiser(),
            keyStore = FakeKeyStore(expectedKey),
            lanIpProvider = { listOf("192.168.1.42") },
            sqlitePathProvider = { "/data/lava-api.db" },
            port = expectedPort,
        )
        ApiApplication.setHoldersForTest(controller, FakeKeyStore(expectedKey))

        // ── Step 3: the engine reaches Running. ──
        runBlocking { controller.start() }

        // ── Step 4: the client query() reads the live key + port. ──
        val cursor = provider.query(provider.contentUri(), null, null, null, null)

        // PRIMARY (user-visible): the client receives ONE row with the real key
        // + the real running port — the exact bytes ApiKeyClient.read() turns
        // into the Lava-Auth header that authenticates every /v1 search request.
        assertEquals(1, cursor.count)
        cursor.moveToFirst()
        assertEquals(
            expectedKey,
            cursor.getString(cursor.getColumnIndexOrThrow(ApiKeyProvider.COL_ACCESS_KEY)),
        )
        assertEquals(
            expectedPort,
            cursor.getInt(cursor.getColumnIndexOrThrow(ApiKeyProvider.COL_LOOPBACK_PORT)),
        )
    }

    @Test
    fun engine_not_running_exposes_empty_cursor_via_holders() {
        // Holders ARE published, but the controller is Stopped (never started).
        // The real resolveRunningKey()/resolveRunningPort() must gate on the
        // Running state and emit an empty cursor — the client then falls back
        // gracefully rather than reading a stale/absent key.
        val controller = ApiEngineController(
            engine = FakeApiEngine(),
            advertiser = RecordingAdvertiser(),
            keyStore = FakeKeyStore("dGVzdC1rZXk="),
            lanIpProvider = { listOf("192.168.1.42") },
            sqlitePathProvider = { "/data/lava-api.db" },
            port = 8443,
        )
        ApiApplication.setHoldersForTest(controller, FakeKeyStore("dGVzdC1rZXk="))

        val provider = ApiKeyProvider()
        provider.attachInfoForTest(ApplicationProvider.getApplicationContext())
        provider.onCreate()

        val cursor = provider.query(provider.contentUri(), null, null, null, null)

        // PRIMARY: not-running engine ⇒ empty cursor (no key leaked while stopped).
        assertEquals(0, cursor.count)
    }
}
