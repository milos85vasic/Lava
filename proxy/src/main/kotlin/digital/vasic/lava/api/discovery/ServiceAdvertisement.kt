package digital.vasic.lava.api.discovery

import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

object ServiceAdvertisement {
    private const val SERVICE_TYPE = "_lava._tcp.local."
    private const val SERVICE_NAME = "Lava Proxy"
    private var jmDNS: JmDNS? = null
    private var registeredServiceInfo: ServiceInfo? = null

    /**
     * Exposes the last registered service info for testing and verification.
     */
    fun getServiceInfo(): ServiceInfo? = registeredServiceInfo

    fun start(port: Int) {
        try {
            val address = System.getenv("ADVERTISE_HOST")?.let { InetAddress.getByName(it) }
                ?: InetAddress.getLocalHost()
            jmDNS = JmDNS.create(address, SERVICE_NAME)
            val serviceInfo = ServiceInfo.create(
                SERVICE_TYPE,
                SERVICE_NAME,
                port,
                0,
                0,
                mapOf(
                    "path" to "/",
                    "version" to "1.0.0",
                ),
            )
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
