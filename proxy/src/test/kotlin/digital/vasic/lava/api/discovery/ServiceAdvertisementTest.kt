package digital.vasic.lava.api.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration Challenge Test for [ServiceAdvertisement].
 *
 * Verifies that the proxy's mDNS advertisement registers a service that the
 * Android client ([lava.data.impl.service.LocalNetworkDiscoveryServiceImpl])
 * can actually discover. This is a cross-module contract test: if this test
 * passes but the Android client cannot discover the proxy, the test is a bluff.
 */
class ServiceAdvertisementTest {

    @Test
    fun `advertisement registers correct service type`() {
        val port = 18080
        ServiceAdvertisement.start(port)
        try {
            val serviceInfo = ServiceAdvertisement.getServiceInfo()
            assertNotNull("ServiceInfo must not be null after start", serviceInfo)
            assertTrue(
                "Service type must contain '_lava._tcp' so Android NsdManager matches it",
                serviceInfo!!.type.contains("_lava._tcp"),
            )
        } finally {
            ServiceAdvertisement.stop()
        }
    }

    @Test
    fun `advertisement uses correct service name`() {
        val port = 18081
        ServiceAdvertisement.start(port)
        try {
            val serviceInfo = ServiceAdvertisement.getServiceInfo()
            assertNotNull(serviceInfo)
            assertEquals("Lava Proxy", serviceInfo!!.name)
        } finally {
            ServiceAdvertisement.stop()
        }
    }

    @Test
    fun `advertisement exposes correct port`() {
        val port = 18082
        ServiceAdvertisement.start(port)
        try {
            val serviceInfo = ServiceAdvertisement.getServiceInfo()
            assertNotNull(serviceInfo)
            assertEquals(port, serviceInfo!!.port)
        } finally {
            ServiceAdvertisement.stop()
        }
    }

    @Test
    fun `advertisement includes required properties`() {
        val port = 18083
        ServiceAdvertisement.start(port)
        try {
            val serviceInfo = ServiceAdvertisement.getServiceInfo()
            assertNotNull(serviceInfo)
            val props = serviceInfo!!.propertyNames.toList().associateWith {
                serviceInfo.getPropertyString(it)
            }
            assertEquals("/", props["path"])
            assertNotNull("version TXT record must be present", props["version"])
        } finally {
            ServiceAdvertisement.stop()
        }
    }

    /**
     * Sixth Law (Real User Verification): asserts the symmetric TXT-record subset
     * required by SP-2 spec §8.3 so the Go API migration's Phase 10 cross-backend
     * parity infrastructure can describe both backends uniformly.
     *
     * Falsifiability rehearsal: flipping `"engine" to "ktor"` to `"engine" to "wrong"`
     * in [ServiceAdvertisement.buildServiceInfo] makes this test fail with a clear
     * `expected:<ktor> but was:<wrong>` assertion against the `engine` key.
     */
    @Test
    fun `advertised service has symmetric TXT records`() {
        val info = ServiceAdvertisement.buildServiceInfo(port = 8080)
        assertEquals("ktor", info.getPropertyString("engine"))
        assertEquals("h11", info.getPropertyString("protocols"))
        assertEquals("identity", info.getPropertyString("compression"))
        assertEquals("optional", info.getPropertyString("tls"))
        assertNotNull(info.getPropertyString("version"))
    }

    @Test
    fun `stop unregisters all services`() {
        val port = 18084
        ServiceAdvertisement.start(port)
        ServiceAdvertisement.stop()

        val serviceInfo = ServiceAdvertisement.getServiceInfo()
        assertEquals(
            "ServiceInfo must be null after stop",
            null,
            serviceInfo,
        )
    }
}
