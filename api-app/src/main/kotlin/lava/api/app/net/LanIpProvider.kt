package lava.api.app.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Enumerates the host's non-loopback IPv4 addresses — the addresses a LAN peer
 * actually reaches the embedded API at.
 *
 * Sixth-Law clause 3 alignment: these are the real interface addresses, not a
 * normalized/synthesized value; the URL the UI shows and the cert SANs the
 * embed generates both depend on these being the true reachable IPs.
 */
fun interface LanIpProvider {
    operator fun invoke(): List<String>
}

/** Production [LanIpProvider] reading the device's network interfaces. */
class NetworkInterfaceLanIpProvider : LanIpProvider {
    override fun invoke(): List<String> =
        runCatching {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .mapNotNull { it.hostAddress }
                .distinct()
                .toList()
        }.getOrDefault(emptyList())
}
