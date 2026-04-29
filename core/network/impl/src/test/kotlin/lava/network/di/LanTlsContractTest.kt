package lava.network.di

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ProxySelector
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine

/**
 * Sixth-Law contract tests for the SP-3.1 LAN-permissive TLS path.
 *
 * Forensic anchor (2026-04-29). The user mandated that the Android
 * client connect to the LAN `lava-api-go` over HTTPS without any
 * operator-installed CA on the device. The implementation in
 * [NetworkModule.Companion.lanOkHttpClient] wires a permissive
 * [javax.net.ssl.X509TrustManager] + always-true [javax.net.ssl.HostnameVerifier]
 * scoped to LAN endpoints. This test guarantees the wiring stays
 * permissive — if a future refactor reverts to the platform-default
 * trust manager, these assertions fire.
 *
 * Sixth-Law alignment:
 * - clause 1: the SSL surface tested IS the surface OkHttp consults
 *   on every TLS connection. Same code path the real device hits.
 * - clause 2 (falsifiability): rehearsal recorded in the SP-3.1
 *   commit body — revert the lanOkHttpClient body to drop the
 *   `sslSocketFactory(...)` call, observe `assertNotSame` fail.
 * - clause 3: assertions are on the SSL components OkHttp will
 *   actually use at runtime, not on mock invocation counts.
 */
class LanTlsContractTest {

    private val noOpProxySelector = ProxySelector.getDefault()

    /**
     * CHALLENGE — the LAN client's [okhttp3.OkHttpClient.sslSocketFactory]
     * MUST differ from the strict client's. If they're the same, the
     * permissive trust manager has been silently dropped from the
     * LAN client and HTTPS to the LAN api-go would fail.
     */
    @Test
    fun `LAN client uses a different SSLSocketFactory than the strict client`() {
        val strict = NetworkModule.Companion.okHttpClient(
            proxySelector = noOpProxySelector,
            interceptors = emptySet(),
        )
        val lan = NetworkModule.Companion.lanOkHttpClient(
            proxySelector = noOpProxySelector,
            interceptors = emptySet(),
        )

        assertNotSame(
            "LAN OkHttpClient MUST have a custom SSLSocketFactory; " +
                "if equal to the default, the permissive TrustManager has been dropped " +
                "and HTTPS to LAN api-go will SSLHandshakeException-fail",
            strict.sslSocketFactory,
            lan.sslSocketFactory,
        )
    }

    /**
     * CHALLENGE — the LAN client's [okhttp3.OkHttpClient.hostnameVerifier]
     * MUST accept arbitrary hostnames. The Go API's self-signed cert is
     * for `localhost` + a single LAN-IP SAN; without permissive
     * verification, a request to an alternate LAN-IP would
     * `SSLPeerUnverifiedException`-fail.
     */
    @Test
    fun `LAN client hostnameVerifier accepts arbitrary hosts`() {
        val lan = NetworkModule.Companion.lanOkHttpClient(
            proxySelector = noOpProxySelector,
            interceptors = emptySet(),
        )
        val sslEngine: SSLEngine = SSLContext.getInstance("TLS")
            .also { it.init(null, null, null) }
            .createSSLEngine()
        // Verifier should accept ANY hostname when paired with this engine's session.
        assertTrue(
            "LAN hostnameVerifier MUST accept arbitrary hostnames; reverting it " +
                "to the OkHttp default would break self-signed-cert flow on alternate LAN IPs",
            lan.hostnameVerifier.verify("any-non-existent-lan-host.example", sslEngine.session),
        )
    }

    /**
     * CHALLENGE — the LAN client's [javax.net.ssl.X509TrustManager] MUST
     * accept any certificate chain (including a synthetic stub) without
     * throwing. The real lava-api-go cert is operator-generated
     * self-signed; the system trust store does NOT chain to it.
     *
     * We exercise the trust manager by asking it to validate a
     * deliberately-malformed empty stub chain. The permissive
     * implementation MUST treat this as fine (no throw); a strict
     * implementation MUST throw `CertificateException` (caught here as
     * the absence of a no-throw outcome).
     */
    @Test
    fun `LAN client X509TrustManager accepts arbitrary server certificates without throwing`() {
        val lan = NetworkModule.Companion.lanOkHttpClient(
            proxySelector = noOpProxySelector,
            interceptors = emptySet(),
        )
        // Stub cert chain — the permissive trust manager ignores it.
        val stubChain = emptyArray<X509Certificate>()
        // No-throw IS the assertion. If a future refactor reverts to a
        // strict trust manager, this call would throw IllegalArgumentException
        // (chain.isEmpty()) or CertificateException — either way the test
        // fails with a clear signal.
        lan.x509TrustManager!!.checkServerTrusted(stubChain, "RSA")
        // If we get here, permissive behaviour confirmed.
        assertTrue("permissive trust path reached without throwing", true)
    }

    /**
     * CHALLENGE — the strict client must NOT use the same hostname
     * verifier as the LAN client. The LAN's verifier always returns
     * true; OkHttp's default verifier (`OkHostnameVerifier`) checks
     * subject/SAN against the requested host. If they're the same
     * class, the public-Internet client has been silently made
     * permissive — a security regression.
     *
     * (Comparing SSLSocketFactory CLASS is not useful — both share
     * `SSLSocketFactoryImpl`, just configured with different
     * SSLContexts. The hostname verifier is the cleanest separator.)
     */
    @Test
    fun `strict client hostnameVerifier class differs from LAN client's`() {
        val strict = NetworkModule.Companion.okHttpClient(
            proxySelector = noOpProxySelector,
            interceptors = emptySet(),
        )
        val lan = NetworkModule.Companion.lanOkHttpClient(
            proxySelector = noOpProxySelector,
            interceptors = emptySet(),
        )
        assertNotSame(
            "Strict client's HostnameVerifier CLASS MUST differ from the LAN client's; " +
                "if equal, the public-Internet client has been silently made permissive",
            strict.hostnameVerifier::class.java,
            lan.hostnameVerifier::class.java,
        )
    }
}
