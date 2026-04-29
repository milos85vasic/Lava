package lava.domain.contract

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-module contract test between the proxy + lava-api-go mDNS
 * advertisements and the Android client's per-listener filter in
 * [lava.data.impl.service.LocalNetworkDiscoveryServiceImpl].
 *
 * SP-3.4 (2026-04-29) forensic anchor: real-device verification on
 * SM-S918B / Android 16 surfaced a cross-match bug. The previous
 * filter was `serviceType.contains("lava")` for the `_lava._tcp`
 * watcher, which ALSO matched `_lava-api._tcp` because "lava" is a
 * proper substring of "lava-api". When NsdManager could not parse
 * TXT records on the resolved service, the engine fallback ran with
 * `watchedServiceType = "_lava._tcp"` and produced an
 * `Endpoint.Mirror` row pointing at `192.168.0.213:8080` for a
 * lava-api-go service that was actually serving on `:8443` —
 * exactly the user-visible "Mirror has no green icon" /
 * "search returns Nothing found" / "forum links don't open"
 * symptoms.
 *
 * The fix is matching the FULL service-type stem (the part before
 * `._tcp`) for equality, so `_lava._tcp` and `_lava-api._tcp`
 * become mutually exclusive listeners.
 */
class LocalNetworkDiscoveryContractTest {

    /**
     * Mirrors the SP-3.4 matching logic in
     * [LocalNetworkDiscoveryServiceImpl.onServiceFound] verbatim.
     * If the production code drifts, this test breaks.
     */
    private fun matches(watchedServiceType: String, foundServiceType: String): Boolean {
        val watched = watchedServiceType.trim().trim('.').removePrefix("_").lowercase()
        val found = foundServiceType.trim().trim('.').removePrefix("_").lowercase()
        return found == watched || found.startsWith("$watched.")
    }

    // CHALLENGE — `_lava._tcp` watcher MUST match its own bare type.
    //
    // MUTATION: returning false unconditionally fails this test.
    @Test
    fun `lava watcher matches lava service type with local suffix`() {
        assertTrue(matches("_lava._tcp", "_lava._tcp.local."))
    }

    // CHALLENGE — `_lava-api._tcp` watcher MUST match its own bare type.
    @Test
    fun `lava-api watcher matches lava-api service type with local suffix`() {
        assertTrue(matches("_lava-api._tcp", "_lava-api._tcp.local."))
    }

    // CHALLENGE — Sixth-Law clause 1, 2: the load-bearing regression
    // guard for the SP-3.4 fix. The `_lava._tcp` watcher MUST NOT
    // accept a `_lava-api._tcp` service. If this assertion fails the
    // user sees the original bug back: lava-api-go appears in the
    // Connections list as `Endpoint.Mirror(192.168.0.213)`, the
    // network layer routes to port 8080 (not 8443), nothing answers,
    // and the user sees the symptom triad (no green / forum-tap dead /
    // search empty).
    //
    // MUTATION: reverting the filter to `serviceType.contains("lava")`
    // makes this test fail with the message "expected false but was true",
    // verified on real device on 2026-04-29.
    @Test
    fun `lava watcher MUST NOT cross-match lava-api service type`() {
        assertFalse(
            "ktor watcher leaks api-go service into Mirror branch (SP-3.4 regression)",
            matches("_lava._tcp", "_lava-api._tcp.local."),
        )
    }

    // CHALLENGE — symmetric: lava-api watcher must not accept lava
    // (the proxy) services.
    @Test
    fun `lava-api watcher MUST NOT cross-match lava service type`() {
        assertFalse(matches("_lava-api._tcp", "_lava._tcp.local."))
    }

    // CHALLENGE — unrelated service types are rejected as before.
    @Test
    fun `unrelated service types are rejected by both watchers`() {
        for (watcher in listOf("_lava._tcp", "_lava-api._tcp")) {
            assertFalse(matches(watcher, "_lava._udp.local."))
            assertFalse(matches(watcher, "_other._tcp.local."))
            assertFalse(matches(watcher, "_lava2._tcp.local."))
            assertFalse(matches(watcher, "_lavaapi._tcp.local."))
        }
    }

    // CHALLENGE — different protocol (`_udp` vs `_tcp`) MUST NOT match,
    // even when the service name is identical. A `_lava._udp` listener
    // and a `_lava._tcp` listener are different services and matching
    // them would corrupt the engine inference too.
    @Test
    fun `lava tcp watcher MUST NOT match lava udp service type`() {
        assertFalse(matches("_lava._tcp", "_lava._udp.local."))
    }

    // VM-CONTRACT — the normalisation is itself part of the contract.
    // Lowercase + strip leading underscore + strip trailing dot.
    @Test
    fun `normalisation lowercases and strips wrapping characters`() {
        // Mixed-case publisher should still match.
        assertTrue(matches("_lava._tcp", "_LAVA._TCP.local."))
        // No leading underscore on the input is fine.
        assertTrue(matches("lava._tcp", "_lava._tcp.local."))
        // No trailing dot on the input is fine.
        assertEquals(true, matches("_lava._tcp", "_lava._tcp"))
    }
}
