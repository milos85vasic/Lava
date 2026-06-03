package lava.api.app.challenges

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Test-only HTTPS client that talks to the REAL on-device Lava API embed over
 * TLS — used by the Phase E Challenge Tests (C02/C03) to issue genuine
 * authenticated requests against the running server and assert on the actual
 * HTTP response (status + JSON body).
 *
 * Anti-bluff TLS posture (§6.J): this client does NOT blanket-disable TLS
 * verification. It builds an [X509TrustManager] that trusts EXACTLY the
 * self-signed leaf the embed persisted at `<filesDir>/lava-embed-cert.pem`
 * (see `lava-api-go/internal/mobile/tls.go`). A man-in-the-middle cert, or a
 * cert from a different embed install, would fail the handshake — which is the
 * whole point: the test proves the embed serves the cert it generated, over
 * real TLS, to a peer that trusts only that cert. Connecting to `127.0.0.1`
 * (an IP SAN baked into the cert by `generateSelfSigned`) means default
 * hostname verification still applies — it is NOT disabled.
 *
 * The embed runs in the same app process as the instrumentation test, so the
 * cert PEM is readable from the app's private files dir.
 */
class OnDeviceApiClient(
    context: Context,
    private val port: Int,
) {
    /** The cert the embed persisted beside its SQLite DB (filesDir). */
    private val certFile: File = File(context.filesDir, CERT_FILE_NAME)

    /** Base URL the embed is reachable at on-device over loopback (an IP SAN). */
    val baseUrl: String = "https://$LOOPBACK:$port"

    private val client: OkHttpClient by lazy { buildTrustingClient() }

    /**
     * A sibling client with a SHORT read timeout, used to observe the auth
     * GATE VERDICT without waiting on a downstream third-party fetch. The gate
     * decides (401-vs-pass) BEFORE any rutracker call, so a rejecting gate
     * returns 401 in well under a second; a SHORT read timeout therefore
     * distinguishes "gate rejected (fast 401)" from "gate accepted, handler now
     * doing downstream work" (read times out). connectTimeout stays generous for
     * the cold-serve TLS handshake; only the response-read window is short.
     */
    private val shortReadClient: OkHttpClient by lazy {
        client.newBuilder().readTimeout(SHORT_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }

    /** Issues GET [path] with no auth header. Caller closes the [Response]. */
    fun get(path: String): Response =
        client.newCall(Request.Builder().url(baseUrl + path).get().build()).execute()

    /**
     * Issues GET [path] presenting [authKey] in [fieldName] — the exact
     * production Lava-Auth wire shape the embed's auth middleware enforces.
     */
    fun getWithKey(path: String, authKey: String, fieldName: String): Response =
        client.newCall(
            Request.Builder()
                .url(baseUrl + path)
                .header(fieldName, authKey)
                .get()
                .build(),
        ).execute()

    /**
     * Like [getWithKey] but with a SHORT read timeout — for asserting the auth
     * GATE VERDICT only. Throws [java.net.SocketTimeoutException] when the gate
     * ACCEPTS the key and the request proceeds into a downstream handler that
     * does not respond within the short window (e.g. a rutracker-backed route on
     * a device where rutracker is unreachable). A REJECTING gate instead returns
     * 401 well within the window, so the caller distinguishes the two.
     */
    fun getWithKeyShortRead(path: String, authKey: String, fieldName: String): Response =
        shortReadClient.newCall(
            Request.Builder()
                .url(baseUrl + path)
                .header(fieldName, authKey)
                .get()
                .build(),
        ).execute()

    private fun buildTrustingClient(): OkHttpClient {
        require(certFile.exists()) {
            "Embed TLS cert not found at ${certFile.absolutePath} — the embed " +
                "must have started (which persists $CERT_FILE_NAME) before this client is built."
        }
        val leaf: X509Certificate = certFile.inputStream().use { input ->
            CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("lava-embed", leaf)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), null)
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // Generous timeouts: the on-device embed's FIRST request after a
            // cold start does a TLS handshake against a just-bound listener on a
            // resource-constrained emulator; 15s readTimeout flaked the HTTP/2
            // stream (SocketTimeoutException). 20s/30s absorbs that cold-serve.
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private companion object {
        // Matches lava-api-go/internal/mobile/tls.go certFileName.
        const val CERT_FILE_NAME = "lava-embed-cert.pem"
        const val LOOPBACK = "127.0.0.1"

        // Short response-read window for the gate-verdict probe. A rejecting
        // auth gate returns 401 sub-second (the gate decides before any
        // downstream call), so 6s cleanly separates "fast 401 = rejected" from
        // "no fast 401 = accepted, handler now doing downstream work".
        const val SHORT_READ_TIMEOUT_SECONDS = 6L
    }
}
