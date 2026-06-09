package lava.network.impl

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Anti-bluff unit tests for [DelegatingProxySelector].
 *
 * [DelegatingProxySelector] is the [ProxySelector] OkHttp uses to decide
 * which proxy (if any) a request is routed through. Its production behaviour
 * is the okhttp/okhttp#6877 workaround: delegate to the JVM-default selector,
 * but if that default throws (some Android/JVM ProxySelectors do under
 * concurrency or odd VPN states), fall back to a DIRECT connection
 * (`Proxy.NO_PROXY`) instead of letting the exception bubble up and abort the
 * request entirely.
 *
 * The user-visible behaviour these tests assert on:
 *  - a healthy default selector's proxy list is forwarded VERBATIM (so a
 *    user's configured system proxy is honoured — wrong here = traffic routed
 *    direct when it should go through the proxy, or vice-versa);
 *  - a throwing default selector degrades to a DIRECT connection rather than
 *    a failed request (wrong here = total connectivity loss the moment the
 *    platform selector hiccups);
 *  - [DelegatingProxySelector.connectFailed] is forwarded to the default so
 *    the platform can demote a dead proxy.
 *
 * No mocking of the SUT — a real [DelegatingProxySelector] instance is
 * exercised. Only the JVM-default [ProxySelector] (an external boundary) is
 * swapped, and it is always restored in [tearDown].
 *
 * Falsifiability rehearsal (§6.J clause 2 / §6.N Bluff-Audit) — see commit body.
 */
class DelegatingProxySelectorTest {

    private lateinit var originalDefault: ProxySelector

    @Before
    fun setUp() {
        originalDefault = ProxySelector.getDefault()
    }

    @After
    fun tearDown() {
        ProxySelector.setDefault(originalDefault)
    }

    /** A default selector that returns a fixed proxy list and records calls. */
    private class FixedDefault(private val result: List<Proxy>) : ProxySelector() {
        val selectedUris = mutableListOf<URI?>()
        val connectFailedCalls = mutableListOf<Triple<URI?, SocketAddress?, IOException?>>()
        override fun select(uri: URI?): List<Proxy> {
            selectedUris.add(uri)
            return result
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            connectFailedCalls.add(Triple(uri, sa, ioe))
        }
    }

    /** A default selector whose select() throws — the okhttp#6877 failure mode. */
    private class ThrowingDefault : ProxySelector() {
        var connectFailedInvoked = false
        override fun select(uri: URI?): List<Proxy> =
            throw IllegalArgumentException("platform ProxySelector blew up")
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            connectFailedInvoked = true
        }
    }

    @Test
    fun `forwards the default selector's proxy list verbatim`() {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("proxy.example", 3128))
        val fixed = FixedDefault(listOf(proxy))
        ProxySelector.setDefault(fixed)

        val uri = URI("https://rutracker.org/forum/index.php")
        val result = DelegatingProxySelector().select(uri)

        // User-visible: the user's configured proxy is honoured, unchanged.
        assertEquals("must forward the default selector's list verbatim", listOf(proxy), result)
        assertSame("the exact Proxy instance must survive", proxy, result.single())
        // Secondary: the original URI reached the platform selector untouched.
        assertEquals(listOf(uri), fixed.selectedUris)
    }

    @Test
    fun `forwards an explicit NO_PROXY (direct) decision from the default`() {
        val fixed = FixedDefault(listOf(Proxy.NO_PROXY))
        ProxySelector.setDefault(fixed)

        val result = DelegatingProxySelector().select(URI("https://example.org/"))

        // User-visible: a default that says "go direct" stays direct.
        assertEquals(listOf(Proxy.NO_PROXY), result)
    }

    @Test
    fun `falls back to NO_PROXY when the default selector throws`() {
        ProxySelector.setDefault(ThrowingDefault())

        val result = DelegatingProxySelector().select(URI("https://rutracker.org/"))

        // User-visible: connectivity survives a platform-selector failure by
        // degrading to a direct connection rather than aborting the request.
        assertEquals(
            "a throwing default must degrade to a single DIRECT route",
            listOf(Proxy.NO_PROXY),
            result,
        )
    }

    @Test
    fun `falls back to NO_PROXY for a null uri without throwing`() {
        // getDefault().select(null) throws IllegalArgumentException on the JVM;
        // the runCatching fallback must absorb that into a DIRECT route.
        ProxySelector.setDefault(ThrowingDefault())

        val result = DelegatingProxySelector().select(null)

        assertEquals(listOf(Proxy.NO_PROXY), result)
    }

    @Test
    fun `connectFailed is delegated to the default selector`() {
        val fixed = FixedDefault(listOf(Proxy.NO_PROXY))
        ProxySelector.setDefault(fixed)

        val uri = URI("https://rutracker.org/")
        val sa = InetSocketAddress("rutracker.org", 443)
        val ioe = IOException("connection refused")
        DelegatingProxySelector().connectFailed(uri, sa, ioe)

        // User-visible (over time): the platform learns the proxy is dead so it
        // can demote it for subsequent requests. The delegation MUST happen.
        assertEquals("connectFailed must be forwarded exactly once", 1, fixed.connectFailedCalls.size)
        val (gotUri, gotSa, gotIoe) = fixed.connectFailedCalls.single()
        assertSame(uri, gotUri)
        assertSame(sa, gotSa)
        assertSame(ioe, gotIoe)
    }

    @Test
    fun `connectFailed forwards even when the default would otherwise be DIRECT`() {
        val fixed = FixedDefault(listOf(Proxy.NO_PROXY))
        ProxySelector.setDefault(fixed)

        DelegatingProxySelector().connectFailed(
            URI("https://example.org/"),
            InetSocketAddress("example.org", 443),
            IOException("reset"),
        )

        assertTrue("connectFailed must reach the default", fixed.connectFailedCalls.isNotEmpty())
    }
}
