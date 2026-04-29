package lava.data.impl.service

import lava.models.settings.Endpoint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SP-3.3 (2026-04-29) Sixth-Law tests for [connectTarget] — the pure
 * mapping that translates an [Endpoint] into the host:port a TCP probe
 * should hit. This is the load-bearing logic behind the green-link
 * indicator in the Connections list; if it picks the wrong port the
 * user sees an endpoint that "doesn't go green" even though the
 * service is actually up.
 *
 * Sixth-Law clauses 1, 3:
 *   1. The same `connectTarget` extension is invoked from
 *      [ConnectionServiceImpl.isReachable] in production — no test-only
 *      shim, no mocked branch.
 *   3. The primary assertion is on the (host, port) pair that drives
 *      the actual TCP socket the user's reachability probe opens.
 *
 * Falsifiability rehearsals (Sixth-Law clause 2) recorded inline per
 * test as a `// MUTATION` comment. Running the test against the
 * mutated impl MUST produce the documented failure.
 */
class ConnectionTargetTest {

    // CHALLENGE — GoApi must probe its declared port (8443 by default
    // per SP-2 §8.1). The historical defect probed port 443.
    //
    // MUTATION: returning `host to 443` for GoApi makes this test fail
    // with "expected 8443 but was 443" on the port assertion.
    @Test
    fun `GoApi probes declared port`() {
        val (host, port) = Endpoint.GoApi("192.168.0.213", 8443).connectTarget()
        assertEquals("192.168.0.213", host)
        assertEquals(8443, port)
    }

    // CHALLENGE — non-default GoApi port survives.
    //
    // MUTATION: hard-coding port=8443 instead of `port` makes this fail
    // with "expected 9443 but was 8443".
    @Test
    fun `GoApi probes non-default port`() {
        val (host, port) = Endpoint.GoApi("10.0.0.42", 9443).connectTarget()
        assertEquals("10.0.0.42", host)
        assertEquals(9443, port)
    }

    // CHALLENGE — Mirror on a LAN IP without an embedded port routes
    // to the legacy Ktor proxy default (8080), not to port 80 which
    // is what the pre-SP-3.3 path silently produced.
    //
    // MUTATION: returning `h to 80` for LAN Mirror fails with
    // "expected 8080 but was 80".
    @Test
    fun `LAN Mirror without port defaults to 8080`() {
        val (host, port) = Endpoint.Mirror("192.168.0.213").connectTarget()
        assertEquals("192.168.0.213", host)
        assertEquals(8080, port)
    }

    // CHALLENGE — Mirror with an explicit `host:port` is parsed and
    // used; the colon is NOT passed through to a hostname (which would
    // fail DNS / would be treated as an opaque label that never matches
    // the running service).
    //
    // MUTATION: dropping the parse and using `host` directly fails
    // with the host being "192.168.0.213:9090" (containing a colon),
    // which the assertion catches.
    @Test
    fun `LAN Mirror with explicit port parses host port pair`() {
        val (host, port) = Endpoint.Mirror("192.168.0.213:9090").connectTarget()
        assertEquals("192.168.0.213", host)
        assertEquals(9090, port)
    }

    // CHALLENGE — public-host Mirror (e.g. a public rutracker mirror)
    // probes port 443.
    //
    // MUTATION: returning `host to 8080` for non-LAN Mirror fails the
    // assertion with "expected 443 but was 8080".
    @Test
    fun `Remote Mirror probes 443`() {
        val (host, port) = Endpoint.Mirror("rutracker.host.example").connectTarget()
        assertEquals("rutracker.host.example", host)
        assertEquals(443, port)
    }

    // CHALLENGE — Rutracker direct probes port 443 on rutracker.org.
    //
    // MUTATION: returning `host to 80` for Rutracker fails with
    // "expected 443 but was 80".
    @Test
    fun `Rutracker direct probes 443`() {
        val (host, port) = Endpoint.Rutracker.connectTarget()
        assertEquals("rutracker.org", host)
        assertEquals(443, port)
    }

    // VM-CONTRACT — the [parseHostPort] helper is itself part of the
    // contract: an invalid port suffix (non-numeric) MUST be treated as
    // "no port", not as 0 or as a parsed-stuck-on-toIntOrNull. This is
    // a regression guard for inputs like "192.168.0.213:abc" that an
    // adversarial mDNS publisher could emit.
    //
    // MUTATION: replacing `toIntOrNull()` with `toInt()` makes this
    // throw NumberFormatException; replacing with `0` makes the second
    // assertion fail with "expected null but was 0".
    @Test
    fun `parseHostPort returns null for non-numeric port`() {
        val (h, p) = parseHostPort("192.168.0.213:abc")
        assertEquals("192.168.0.213", h)
        assertEquals(null, p)
    }

    // VM-CONTRACT — bare hostname without a colon parses to (host, null).
    //
    // MUTATION: always returning (s, 0) fails the second assertion.
    @Test
    fun `parseHostPort returns null for bare host`() {
        val (h, p) = parseHostPort("192.168.0.213")
        assertEquals("192.168.0.213", h)
        assertEquals(null, p)
    }
}
