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
        // 2026-05-05 (10th anti-bluff invocation, Phase R6): the version is asserted
        // against ServiceAdvertisement.API_VERSION (now internal) — single source of
        // truth. Earlier the test asserted the literal "1.0.1" and silently went
        // stale through 3 version bumps; the build_and_release.sh release-prep run
        // for Lava-Android-1.2.3 caught the drift, prompting this de-duplication.
        assertEquals(ServiceAdvertisement.API_VERSION, info.getPropertyString("version"))
    }

    /**
     * Regression test for the 2026-05-05 (10th anti-bluff invocation) drift:
     * the proxy's `apiVersionName` (build.gradle.kts) and the hardcoded
     * `ServiceAdvertisement.API_VERSION` constant MUST track each other until
     * the SP-2 BuildConfig wiring lands. This test asserts the API_VERSION
     * constant matches the apiVersionName captured at gradle-build time via
     * the `LAVA_PROXY_API_VERSION_NAME` system property the proxy module
     * sets when running tests.
     *
     * Falsifiability: if the proxy build.gradle.kts bumps `apiVersionName`
     * without bumping `API_VERSION` (or vice-versa), this test fails with
     * a clear `expected:<X.Y.Z> but was:<A.B.C>` mismatch — preventing the
     * silent drift the original literal-string assertion went through 3
     * versions before catching.
     *
     * The system property is plumbed via proxy/build.gradle.kts:
     *   tasks.test { systemProperty("LAVA_PROXY_API_VERSION_NAME", apiVersionName) }
     */
    @Test
    fun `API_VERSION constant tracks proxy apiVersionName`() {
        val gradleVersionName = System.getProperty("LAVA_PROXY_API_VERSION_NAME")
        assertNotNull(
            "LAVA_PROXY_API_VERSION_NAME system property MUST be plumbed " +
                "via proxy/build.gradle.kts tasks.test{} block — see the test KDoc",
            gradleVersionName,
        )
        assertEquals(
            "ServiceAdvertisement.API_VERSION must match proxy/build.gradle.kts " +
                "apiVersionName until SP-2 BuildConfig wiring (see proxy CLAUDE.md " +
                "follow-up). Bumping one without the other is a release-time drift " +
                "that the 1.0.1→1.0.4 silent gap exposed on 2026-05-05.",
            gradleVersionName,
            ServiceAdvertisement.API_VERSION,
        )
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
