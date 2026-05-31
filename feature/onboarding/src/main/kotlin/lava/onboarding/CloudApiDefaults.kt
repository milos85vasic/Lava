package lava.onboarding

import lava.models.settings.Endpoint

/**
 * Pure helpers for the onboarding "Choose your API" Cloud / remote-server
 * section (2026-05-31 operator request).
 *
 * A cloud Lava-API is always an HTTPS host:port endpoint, which is exactly
 * the shape of [Endpoint.GoApi]. The user may type an address manually
 * (`https://lava.app:7777`, `lava.app:7777`, or bare `lava.app`) or tap a
 * pre-installed default. Both paths produce an [Endpoint.GoApi] and then reuse
 * the EXISTING SelectApi → connectivity-probe → persist → advance flow, so no
 * new persistence or probe plumbing is introduced.
 *
 * §6.R: this file contains NO host/port literals. The default-option string is
 * injected from build-time config (`.env` → `BuildConfig.DEFAULT_CLOUD_API`),
 * parsed here. The only constant referenced is [Endpoint.GoApi.DEFAULT_PORT],
 * which already lives on the model type.
 *
 * §6.J: [parse] is a pure function with an exhaustive unit test
 * (CloudApiDefaultsTest) covering scheme stripping, explicit port, default
 * port, and malformed-input rejection — the Challenge for the cloud section
 * asserts the user-visible outcome (advance on add) on a real emulator.
 */
object CloudApiDefaults {

    /**
     * Parse a user-typed or configured cloud address into an [Endpoint.GoApi].
     *
     * Accepted shapes (case-insensitive scheme, surrounding whitespace ignored):
     *   - `https://host:port`  → GoApi(host, port)
     *   - `http://host:port`   → GoApi(host, port)   (scheme stripped; GoApi is HTTPS by transport)
     *   - `host:port`          → GoApi(host, port)
     *   - `https://host`       → GoApi(host, DEFAULT_PORT)
     *   - `host`               → GoApi(host, DEFAULT_PORT)
     *
     * Returns `null` when the input is blank, has an empty host, has a
     * non-numeric or out-of-range (1..65535) port, or carries a path/query
     * (a cloud API address is host:port only — a path means the user pasted
     * something that is not a bare API origin).
     */
    fun parse(raw: String): Endpoint.GoApi? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        // Strip a leading scheme if present (https:// or http://). GoApi is
        // HTTPS at the transport layer regardless; the scheme is only UX sugar.
        val schemeStripped = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed.substring("https://".length)
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed.substring("http://".length)
            "://" in trimmed -> return null // unknown scheme → reject rather than mis-route
            else -> trimmed
        }
        if (schemeStripped.isEmpty()) return null

        // A cloud API origin is host[:port] only — no path/query/fragment.
        if (schemeStripped.any { it == '/' || it == '?' || it == '#' }) return null

        val colonCount = schemeStripped.count { it == ':' }
        return when (colonCount) {
            0 -> {
                // bare host → default port
                if (schemeStripped.isBlank()) null
                else Endpoint.GoApi(host = schemeStripped, port = Endpoint.GoApi.DEFAULT_PORT)
            }
            1 -> {
                val host = schemeStripped.substringBefore(':')
                val portStr = schemeStripped.substringAfter(':')
                val port = portStr.toIntOrNull()
                if (host.isBlank() || port == null || port !in 1..65535) null
                else Endpoint.GoApi(host = host, port = port)
            }
            // More than one ':' would be an IPv6 literal or malformed — out of
            // scope for the cloud-default UX (which targets DNS hostnames).
            else -> null
        }
    }

    /**
     * Build the pre-installed default-option list from the build-time config
     * string (`BuildConfig.DEFAULT_CLOUD_API`). Blank/unparseable config →
     * empty list (the section still renders the manual-entry field). Multiple
     * defaults may be comma-separated for future expansion.
     */
    fun defaultsFrom(configured: String?): List<Endpoint.GoApi> {
        if (configured.isNullOrBlank()) return emptyList()
        return configured.split(',')
            .mapNotNull { parse(it) }
    }
}
