package lava.network.di

import coil.ImageLoaderFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import lava.network.api.ImageLoader
import lava.network.api.NetworkApi
import lava.network.data.ImageLoaderFactoryImpl
import lava.network.data.NetworkApiRepository
import lava.network.data.NetworkApiRepositoryImpl
import lava.network.impl.DelegatingProxySelector
import lava.network.impl.ImageLoaderImpl
import lava.network.impl.SwitchingNetworkApi
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.net.ProxySelector
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Module
@InstallIn(SingletonComponent::class)
internal interface NetworkModule {

    @Binds
    @Singleton
    fun imageLoader(impl: ImageLoaderImpl): ImageLoader

    @Binds
    @Singleton
    fun imageLoaderFactory(impl: ImageLoaderFactoryImpl): ImageLoaderFactory

    @Multibinds
    fun interceptors(): Set<@JvmSuppressWildcards Interceptor>

    @Binds
    @Singleton
    fun networkApi(impl: SwitchingNetworkApi): NetworkApi

    @Binds
    @Singleton
    fun networkApiRepository(impl: NetworkApiRepositoryImpl): NetworkApiRepository

    @Binds
    @Singleton
    fun proxySelector(impl: DelegatingProxySelector): ProxySelector

    companion object {
        /**
         * Default OkHttp client. Uses the JVM/Android system trust store
         * (system CAs only — no user-installed CA acceptance). Used for
         * all public-Internet requests:
         *   - `Endpoint.Rutracker` direct to `rutracker.org`
         *   - `Endpoint.Rutracker` and remote `Endpoint.Mirror` instances
         *
         * MUST NOT be used for LAN endpoints; the LAN client below is
         * for those.
         */
        @Provides
        @Singleton
        fun okHttpClient(
            proxySelector: ProxySelector,
            interceptors: Set<@JvmSuppressWildcards Interceptor>,
        ): OkHttpClient {
            return OkHttpClient.Builder()
                .proxySelector(proxySelector)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .apply { interceptors.forEach(::addInterceptor) }
                .build()
        }

        /**
         * LAN-scoped OkHttp client. Permissive TLS — accepts any
         * server certificate, any hostname. Used **only** for endpoints
         * the routing layer has classified as LAN:
         *   - `Endpoint.GoApi(host, port)` (lava-api-go on RFC 1918 / `*.local`)
         *   - `Endpoint.Mirror(host)` where `host.isLocalHost()`
         *
         * SP-3.1 forensic anchor (2026-04-29). The user mandate is that
         * the Android client connects to the LAN `lava-api-go` over
         * HTTPS without any operator-installed CA on the device. The
         * Go API uses an operator-generated self-signed cert (per
         * `lava-api-go/scripts/gen-cert.sh`); the system trust store
         * does not chain to it. The previous SP-3.0 design relied on
         * `network_security_config.xml`'s `<certificates src="user" />`
         * + manual `Settings → Security → Install certificate`, which
         * is a release blocker per Sixth Law clause 4 (the user MUST
         * be able to use the feature without device-settings detours).
         *
         * Trust boundary: the LAN is treated as a trusted perimeter,
         * the same threat model as `usesCleartextTraffic="true"` for
         * RFC 1918 IPs — already permitted on the legacy proxy path
         * (`Endpoint.Mirror` to LAN IPs uses `http://`). Encryption is
         * still in effect (TLS 1.3 over QUIC/H2), the relaxation is on
         * authentication only. An attacker on the same Wi-Fi could
         * MitM with their own cert; the project's threat model
         * (operator-controlled LAN, app for operator's own use) treats
         * that as out of scope.
         *
         * For Internet-exposed deployments of `lava-api-go`, deploy
         * with a real chain (Let's Encrypt / operator's CA via ACME)
         * and serve from a public hostname. The client routes those
         * through `okHttpClient` (above), which validates strictly.
         */
        @Provides
        @Singleton
        @Named("lan")
        fun lanOkHttpClient(
            proxySelector: ProxySelector,
            interceptors: Set<@JvmSuppressWildcards Interceptor>,
        ): OkHttpClient {
            val permissiveTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    // No-op: we are the client, never the server.
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    // No-op: trust any LAN-side server cert (see KDoc trust-boundary rationale).
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val trustManagers = arrayOf<TrustManager>(permissiveTrustManager)
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, trustManagers, SecureRandom())
            }
            return OkHttpClient.Builder()
                .proxySelector(proxySelector)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .apply { interceptors.forEach(::addInterceptor) }
                .sslSocketFactory(sslContext.socketFactory, permissiveTrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        }
    }
}
