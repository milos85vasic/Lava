package lava.apiengine

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral-equivalence tests for [FakeApiEngine] (Anti-Bluff Pact, Third
 * Law). Each test asserts a branch the production [NativeApiEngine] /
 * `internal/mobile` exhibits and that a real caller depends on. The fake is a
 * "real" ApiEngine instance here — it is the System Under Test, never mocked
 * (Seventh Law clause 4).
 */
class FakeApiEngineTest {

    private val config = ApiConfig(
        bindAddr = "0.0.0.0",
        port = 8443,
        sqlitePath = "/tmp/lava-test.db",
        authSharedKey = "dGVzdC1rZXk=",
        authFieldName = "Lava-Auth",
    )

    @Test
    fun `status is stopped before any start`() {
        val engine = FakeApiEngine()
        val status = engine.status()
        assertEquals("stopped", status.state)
        assertFalse("auth gate must be off when stopped", status.authEnabled)
        assertNull(status.authKey)
        assertEquals(0, status.port)
    }

    @Test
    fun `start reports running with config bindAddr port and auth on`() = runTest {
        val engine = FakeApiEngine()

        val result = engine.start(config)

        assertTrue("start must succeed", result.isSuccess)
        val started = result.getOrThrow()
        // Primary assertion: user-visible state reflects a RUNNING server with
        // the requested config — the canary that the start path actually flips
        // the engine to running rather than returning a stale stopped doc.
        assertEquals("running", started.state)
        assertEquals("0.0.0.0", started.bindAddr)
        assertEquals(8443, started.port)
        assertTrue("auth gate must be on while running", started.authEnabled)
        assertEquals("dGVzdC1rZXk=", started.authKey)
        // status() must agree with the start result.
        assertEquals("running", engine.status().state)
    }

    @Test
    fun `start while running fails with already-running error`() = runTest {
        val engine = FakeApiEngine()
        engine.start(config)

        val second = engine.start(config)

        assertTrue("second start must fail", second.isFailure)
        val msg = second.exceptionOrNull()?.message
        assertNotNull(msg)
        assertTrue(
            "error must indicate already-running, was: $msg",
            msg!!.contains("already running"),
        )
        // The first server stays up — a failed second start does not stop it.
        assertEquals("running", engine.status().state)
    }

    @Test
    fun `stop without running fails and status stays stopped`() = runTest {
        val engine = FakeApiEngine()

        val result = engine.stop()

        assertTrue("stop without a server must fail", result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("no server running") == true,
        )
        assertEquals("stopped", engine.status().state)
    }

    @Test
    fun `stop after start reverts status to stopped`() = runTest {
        val engine = FakeApiEngine()
        engine.start(config)
        assertEquals("running", engine.status().state)

        val result = engine.stop()

        assertTrue("stop must succeed when running", result.isSuccess)
        val status = engine.status()
        assertEquals("stopped", status.state)
        assertFalse(status.authEnabled)
        assertNull(status.authKey)
        assertEquals(0, status.port)
    }

    @Test
    fun `restart is allowed after stop`() = runTest {
        val engine = FakeApiEngine()
        engine.start(config)
        engine.stop()

        val restart = engine.start(config.copy(port = 9999))

        assertTrue(restart.isSuccess)
        assertEquals(9999, engine.status().port)
        assertEquals("running", engine.status().state)
    }

    @Test
    fun `injected error propagates as failure on start`() = runTest {
        val engine = FakeApiEngine()
        engine.failWith(ApiEngineException("mobile: listen 0.0.0.0:8443: address already in use"))

        val result = engine.start(config)

        assertTrue("injected error must surface as failure", result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message?.contains("address already in use") == true,
        )
        // The engine did not flip to running on a failed start.
        assertEquals("stopped", engine.status().state)
    }

    @Test
    fun `auth key is generated when config supplies none`() = runTest {
        val engine = FakeApiEngine()

        val started = engine.start(config.copy(authSharedKey = null)).getOrThrow()

        assertTrue("auth must be on even with no supplied key", started.authEnabled)
        assertNotNull("a key must be surfaced for the host app to display", started.authKey)
    }

    @Test
    fun `request count reflects simulated traffic while running`() = runTest {
        val engine = FakeApiEngine()
        engine.start(config)

        engine.recordRequests(3)

        assertEquals(3, engine.status().requestCount)
    }
}
