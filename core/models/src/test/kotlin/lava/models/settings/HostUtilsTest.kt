package lava.models.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anti-bluff tests for [isLocalHost].
 *
 * These tests verify that LAN/private IP addresses are correctly identified
 * so that the app uses HTTP (not HTTPS) for local proxies. If a local IP
 * is misclassified, the connection will fail and the feature is broken.
 */
class HostUtilsTest {

    @Test
    fun `localhost is local`() {
        assertTrue("localhost".isLocalHost())
    }

    @Test
    fun `127_0_0_1 is local`() {
        assertTrue("127.0.0.1".isLocalHost())
    }

    @Test
    fun `192_168_x_x is local`() {
        assertTrue("192.168.0.1".isLocalHost())
        assertTrue("192.168.1.100".isLocalHost())
        assertTrue("192.168.0.213".isLocalHost())
    }

    @Test
    fun `192_168_with_port is local`() {
        assertTrue("192.168.0.213:8080".isLocalHost())
    }

    @Test
    fun `10_x_x_x is local`() {
        assertTrue("10.0.0.1".isLocalHost())
        assertTrue("10.255.255.255".isLocalHost())
    }

    @Test
    fun `172_16_through_172_31 is local`() {
        assertTrue("172.16.0.1".isLocalHost())
        assertTrue("172.20.0.1".isLocalHost())
        assertTrue("172.31.255.255".isLocalHost())
    }

    @Test
    fun `172_15_and_172_32_are_not_local`() {
        assertFalse("172.15.0.1".isLocalHost())
        assertFalse("172.32.0.1".isLocalHost())
    }

    @Test
    fun `local_hostname_with_port_is_local`() {
        val host = "localhost:8080"
        val result = host.isLocalHost()
        System.err.println("DEBUG TEST: isLocalHost('$host') = $result")
        assertTrue(result)
    }

    @Test
    fun `public_IPs_are_not_local`() {
        assertFalse("8.8.8.8".isLocalHost())
        assertFalse("1.1.1.1".isLocalHost())
        assertFalse("104.16.249.249".isLocalHost())
    }

    @Test
    fun `public_domains_are_not_local`() {
        assertFalse("lava-app.tech".isLocalHost())
        assertFalse("rutracker.org".isLocalHost())
        assertFalse("example.com".isLocalHost())
    }

    @Test
    fun `mDNS_local_domains_are_local`() {
        assertTrue("lava-proxy.local".isLocalHost())
        assertTrue("myhost.local.".isLocalHost())
    }

    @Test
    fun `IPv6_loopback_is_local`() {
        assertTrue("::1".isLocalHost())
    }

    @Test
    fun `IPv6_link_local_is_local`() {
        assertTrue("fe80::1".isLocalHost())
    }
}
