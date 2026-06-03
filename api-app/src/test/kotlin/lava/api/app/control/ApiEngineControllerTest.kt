package lava.api.app.control

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import lava.api.app.auth.ApiKeyStore
import lava.api.app.service.MdnsAdvertiser
import lava.apiengine.ApiEngineException
import lava.apiengine.FakeApiEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-Bluff load-bearing test for the lifecycle core.
 *
 * Wires the REAL [ApiEngineController] to the REAL [FakeApiEngine] (which
 * reproduces the embed's caller-visible branches per the Third Law) plus a
 * recording [MdnsAdvertiser] fake and an in-memory [ApiKeyStore] fake. No part
 * of the controller is mocked — the SUT is a real instance.
 *
 * PRIMARY assertions are on the emitted [ApiControlState] (the user-visible
 * surface the Service + UI render), not on call counts.
 *
 * Bluff-Audit (recorded in commit body): mutating ApiEngineController.stop()'s
 * onSuccess to NOT set _state = Stopped makes `stop emits Stopped` fail with
 * "expected Stopped but was Running"; mutating onStarted to skip
 * advertiser.register makes `start registers mDNS` fail.
 */
class ApiEngineControllerTest {

    private class RecordingAdvertiser : MdnsAdvertiser {
        var registeredPort: Int? = null
        var registerCalls = 0
        var unregisterCalls = 0
        override fun register(port: Int) {
            registeredPort = port
            registerCalls++
        }
        override fun unregister() {
            unregisterCalls++
        }
    }

    private class FakeKeyStore(private val key: String = "dGVzdC1rZXktMTYtYnl0ZXM=") : ApiKeyStore {
        override fun getOrCreate(): String = key
        override val fieldName: String = "Lava-Auth"
    }

    private fun controller(
        engine: FakeApiEngine = FakeApiEngine(version = "embed-test-9.9.9"),
        advertiser: RecordingAdvertiser = RecordingAdvertiser(),
        keyStore: ApiKeyStore = FakeKeyStore(),
        lanIps: List<String> = listOf("192.168.1.42", "10.0.0.7"),
    ): Triple<ApiEngineController, RecordingAdvertiser, FakeApiEngine> {
        val c = ApiEngineController(
            engine = engine,
            advertiser = advertiser,
            keyStore = keyStore,
            lanIpProvider = { lanIps },
            sqlitePathProvider = { "/data/lava-api.db" },
            port = 8443,
        )
        return Triple(c, advertiser, engine)
    }

    @Test
    fun `start emits Running with url and lan ips`() = runTest {
        val (c, advertiser, _) = controller()

        c.start()

        val state = c.state.value
        assertTrue("expected Running but was $state", state is ApiControlState.Running)
        state as ApiControlState.Running
        // PRIMARY: user-visible reachable URL built from the real first LAN IP +
        // running port + reported scheme.
        assertEquals("https://192.168.1.42:8443", state.url)
        assertEquals(listOf("192.168.1.42", "10.0.0.7"), state.lanIps)
        assertEquals(8443, state.port)
        assertEquals("dGVzdC1rZXktMTYtYnl0ZXM=", state.authKey)
        assertEquals("Lava-Auth", state.authFieldName)
        // Secondary: the advertiser was registered on the running port.
        assertEquals(8443, advertiser.registeredPort)
        assertEquals(1, advertiser.registerCalls)
    }

    // Bug B regression (operator-reported 2026-06-04): re-launching the API app
    // (e.g. tapping "Open Lava API app" from the client while it is already
    // serving) MUST surface the live Running state, NOT bind the listener a
    // second time — a second bind crashes with "listen 0.0.0.0:8443; bind:
    // address already in use". start() is idempotent: a redundant start on a
    // Running engine is a no-op that neither re-binds nor re-advertises.
    @Test
    fun `start is idempotent — redundant start does not re-bind or re-advertise`() = runTest {
        val (c, advertiser, _) = controller()

        c.start()
        assertTrue("first start → Running", c.state.value is ApiControlState.Running)
        assertEquals("first start registers exactly once", 1, advertiser.registerCalls)

        // Redundant start while already Running (the relaunch path).
        c.start()

        // PRIMARY: state stays Running (the UI shows the live engine).
        assertTrue(
            "state MUST stay Running after a redundant start; was ${c.state.value}",
            c.state.value is ApiControlState.Running,
        )
        // The load-bearing signal: NO second bind/advertise. register() runs only
        // from onStarted() after a real engine.start(); registerCalls staying 1
        // proves the second start() short-circuited before re-binding (which is
        // the 'address already in use' crash this guard prevents).
        assertEquals(
            "a redundant start MUST NOT re-bind/re-advertise (idempotent)",
            1,
            advertiser.registerCalls,
        )
    }

    @Test
    fun `stop emits Stopped and unregisters mDNS`() = runTest {
        val (c, advertiser, _) = controller()
        c.start()
        assertTrue(c.state.value is ApiControlState.Running)

        c.stop()

        // PRIMARY: user-visible state is back to Stopped.
        assertEquals(ApiControlState.Stopped, c.state.value)
        // Secondary: the stale advertisement was torn down.
        assertTrue("expected unregister to be called", advertiser.unregisterCalls >= 1)
    }

    @Test
    fun `restart passes through Starting then Running`() = runTest {
        val (c, _, _) = controller()
        c.start()

        // Collect every state transition on the background scope so we observe
        // the intermediate Stopping/Starting states during restart, not just
        // the terminal value.
        val collected = mutableListOf<ApiControlState>()
        backgroundScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            c.state.collect { collected += it }
        }

        c.restart()

        assertTrue("expected Stopping to be traversed: $collected", collected.any { it is ApiControlState.Stopping })
        assertTrue("expected Starting to be traversed: $collected", collected.any { it is ApiControlState.Starting })
        assertTrue("expected to end Running: ${c.state.value}", c.state.value is ApiControlState.Running)
    }

    @Test
    fun `engine start failure emits Error with message`() = runTest {
        val engine = FakeApiEngine()
        engine.failWith(ApiEngineException("mobile: bind failed on 0.0.0.0:8443"))
        val (c, advertiser, _) = controller(engine = engine)

        c.start()

        val state = c.state.value
        assertTrue("expected Error but was $state", state is ApiControlState.Error)
        state as ApiControlState.Error
        assertEquals("mobile: bind failed on 0.0.0.0:8443", state.message)
        // A failed start MUST NOT advertise a non-existent server.
        assertEquals(0, advertiser.registerCalls)
    }

    @Test
    fun `engine stop failure emits Error`() = runTest {
        val engine = FakeApiEngine()
        val (c, _, _) = controller(engine = engine)
        c.start()
        engine.failWith(ApiEngineException("mobile: shutdown timed out"))

        c.stop()

        val state = c.state.value
        assertTrue("expected Error but was $state", state is ApiControlState.Error)
        state as ApiControlState.Error
        assertEquals("mobile: shutdown timed out", state.message)
    }

    @Test
    fun `start passes the keystore auth key into the engine config`() = runTest {
        // The auth key reported in Running comes from the embed's echoed
        // ApiStatus.authKey, which FakeApiEngine sets to the config's
        // authSharedKey — proving the controller actually supplied the key.
        val (c, _, _) = controller(keyStore = FakeKeyStore("c29tZS1zcGVjaWZpYy1rZXk="))
        c.start()
        val state = c.state.value as ApiControlState.Running
        assertEquals("c29tZS1zcGVjaWZpYy1rZXk=", state.authKey)
        assertFalse("auth key must not be blank", state.authKey.isBlank())
    }
}
