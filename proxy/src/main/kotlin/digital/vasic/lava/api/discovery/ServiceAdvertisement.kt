package digital.vasic.lava.api.discovery

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

object ServiceAdvertisement {
    private const val SERVICE_TYPE = "_lava._tcp.local."
    private const val SERVICE_NAME = "Lava Proxy"

    // TODO(SP-2): wire `apiVersionName` from proxy/build.gradle.kts into BuildConfig and
    //  read it here at runtime instead of hardcoding. Today this string is purely
    //  cosmetic for clients (they read it but do not gate behavior on it). See
    //  docs/superpowers/specs/2026-04-28-sp2-go-api-migration-design.md §8.3.
    //
    // Made `internal` (was `private`) on 2026-05-05 (10th anti-bluff invocation,
    // Phase R6) so ServiceAdvertisementTest can reference this single source of
    // truth instead of duplicating the literal string. Eliminates a release-time
    // drift class — the test cannot pass against a stale API_VERSION because
    // there is no second copy of the value to drift from.
    internal const val API_VERSION = "1.0.4"

    private var jmDNS: JmDNS? = null
    private var registeredServiceInfo: ServiceInfo? = null

    /**
     * Exposes the last registered service info for testing and verification.
     */
    fun getServiceInfo(): ServiceInfo? = registeredServiceInfo

    /**
     * Builds the [ServiceInfo] that will be (or was) advertised on mDNS.
     *
     * Exposed as a static factory so unit tests can assert on the TXT record
     * contents without standing up a real JmDNS instance. Production callers
     * should invoke [start]; this factory is also used internally by [start].
     *
     * The TXT records published here are the symmetric subset required by
     * SP-2 spec §8.3 so that cross-backend parity infrastructure (Phase 10
     * of the Go API migration) can describe both the legacy Ktor proxy and
     * the future Go service uniformly.
     */
    fun buildServiceInfo(port: Int): ServiceInfo {
        val txt: Map<String, String> = mapOf(
            // Legacy keys preserved for existing Android client compatibility.
            "path" to "/",
            // SP-2 §8.3 symmetric TXT records.
            "engine" to "ktor",
            "version" to API_VERSION,
            "protocols" to "h11",
            "compression" to "identity",
            "tls" to "optional",
        )
        return ServiceInfo.create(
            SERVICE_TYPE,
            SERVICE_NAME,
            port,
            0,
            0,
            txt,
        )
    }

    fun start(port: Int) {
        try {
            val address = System.getenv("ADVERTISE_HOST")?.let { InetAddress.getByName(it) }
                ?: InetAddress.getLocalHost()
            jmDNS = JmDNS.create(address, SERVICE_NAME)
            val serviceInfo = buildServiceInfo(port)
            jmDNS?.registerService(serviceInfo)
            registeredServiceInfo = serviceInfo
            println("mDNS service registered: $SERVICE_NAME on $address:$port")
        } catch (e: Exception) {
            println("Failed to register mDNS service: ${e.message}")
            e.printStackTrace()
        }
    }

    fun stop() {
        try {
            jmDNS?.unregisterAllServices()
            jmDNS?.close()
            jmDNS = null
            registeredServiceInfo = null
            println("mDNS service unregistered")
        } catch (e: Exception) {
            println("Failed to unregister mDNS service: ${e.message}")
        }
    }
}
