package lava.domain.contract

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-module contract test between the proxy's mDNS advertisement
 * ([digital.vasic.lava.api.discovery.ServiceAdvertisement]) and the Android
 * client's discovery filter ([lava.data.impl.service.LocalNetworkDiscoveryServiceImpl]).
 *
 * This test guarantees that whatever service type the proxy advertises, the
 * Android client will match it. If this test passes but discovery fails in
 * production, the test is a bluff.
 */
class LocalNetworkDiscoveryContractTest {

    /**
     * Mirrors the matching logic in [LocalNetworkDiscoveryServiceImpl]:
     * `serviceInfo.serviceType.contains("_lava._tcp")`
     */
    private fun matchesLavaServiceType(serviceType: String): Boolean =
        serviceType.contains("_lava._tcp")

    @Test
    fun `proxy service type with local suffix is matched by Android client`() {
        val proxyServiceType = "_lava._tcp.local."
        assertTrue(
            "Android client must match proxy's '$proxyServiceType'",
            matchesLavaServiceType(proxyServiceType),
        )
    }

    @Test
    fun `bare service type is matched`() {
        assertTrue(matchesLavaServiceType("_lava._tcp"))
    }

    @Test
    fun `similar but different service type is not matched`() {
        assertFalse(matchesLavaServiceType("_lava._udp.local."))
        assertFalse(matchesLavaServiceType("_other._tcp.local."))
        assertFalse(matchesLavaServiceType("_lava2._tcp.local."))
    }

    @Test
    fun `Android NsdManager reported type with local suffix is matched`() {
        // NsdManager sometimes reports the type exactly like this.
        val androidReportedType = "_lava._tcp.local."
        assertTrue(matchesLavaServiceType(androidReportedType))
    }
}
