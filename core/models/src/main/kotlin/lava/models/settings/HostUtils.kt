package lava.models.settings

/**
 * Returns `true` if [host] appears to be a local/private IP address or hostname.
 *
 * Matches:
 * - IPv4 loopback: `127.*`
 * - IPv4 private: `10.*`, `192.168.*`, `172.16.*`–`172.31.*`
 * - IPv6 loopback: `::1`
 * - IPv6 link-local: `fe80:*`
 * - IPv6 unique local: `fc00:*`–`fdff:*`
 * - Local hostnames: `localhost`, `*.local`
 */
fun String.isLocalHost(): Boolean {
    val original = this

    // IPv6 check first — must happen before port stripping because IPv6 uses colons
    val lowercaseOriginal = original.lowercase()
    if (lowercaseOriginal == "::1") return true
    if (lowercaseOriginal.startsWith("fe80:")) return true
    if (lowercaseOriginal.startsWith("fc") || lowercaseOriginal.startsWith("fd")) {
        val prefix = lowercaseOriginal.take(4)
        val hex = prefix.toIntOrNull(16)
        if (hex != null && hex in 0xfc00..0xfdff) return true
    }

    // Strip port if present (e.g., a LAN IP with an embedded port suffix).
    val hostWithoutPort = original.substringBeforeLast(":")
    val lowercase = hostWithoutPort.lowercase()

    if (lowercase == "localhost" || lowercase.endsWith(".local") || lowercase.endsWith(".local.")) {
        return true
    }

    // IPv4 checks
    hostWithoutPort.split(".").let { parts ->
        if (parts.size == 4) {
            val ints = parts.mapNotNull { it.toIntOrNull() }
            if (ints.size == 4) {
                if (ints[0] == 127) return true
                if (ints[0] == 10) return true
                if (ints[0] == 192 && ints[1] == 168) return true
                if (ints[0] == 172 && ints[1] in 16..31) return true
            }
        }
    }

    return false
}
