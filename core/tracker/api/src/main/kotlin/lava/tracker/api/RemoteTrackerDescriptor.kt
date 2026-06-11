package lava.tracker.api

import lava.sdk.api.MirrorUrl

/**
 * Dynamic Provider Discovery (2026-06-11, spec §4.2). A [TrackerDescriptor]
 * whose metadata was vended by the chosen lava-api-go instance via
 * `GET /v1/providers`, rather than compiled into the client as a singleton.
 *
 * **Non-cyclic decision (deliberate).** `:core:tracker:api` depends ONLY on
 * `lava.sdk:api` — it does NOT (and must not) depend on `:core:network`, where
 * `ProviderDescriptorDto` lives. Therefore [from] takes the descriptor's PLAIN
 * FIELDS (the wire types `List<String>` / `String`), not the DTO. The
 * DTO→fields adaptation happens in
 * `lava.data.provider.ProviderCatalogRepository` (`:core:data`, which is allowed
 * to depend on both modules). This keeps the tolerant string→enum mapping next
 * to the enums it targets ([TrackerCapability], [AuthType]) without introducing
 * a `:core:tracker:api → :core:network` edge.
 *
 * `apiSupported = true` and `verified = true`: the API instance vouches for this
 * provider — it routes `/v1/{id}/…` and has exercised the provider server-side
 * (capability honesty is enforced API-side per spec §4.1 / constitutional 6.E).
 */
data class RemoteTrackerDescriptor(
    override val trackerId: String,
    override val displayName: String,
    override val baseUrls: List<MirrorUrl>,
    override val capabilities: Set<TrackerCapability>,
    override val authType: AuthType,
    override val encoding: String,
    override val expectedHealthMarker: String = "",
    override val verified: Boolean = true,
    override val supportsAnonymous: Boolean = false,
    override val apiSupported: Boolean = true,
) : TrackerDescriptor {

    companion object {
        /**
         * Tolerant capability-name parse. Case-insensitive match against the
         * [TrackerCapability] enum. Returns `null` for an unknown name so the
         * caller can drop it (NEVER throw) — the API may advertise capabilities
         * a given client build predates.
         */
        fun parseCapability(name: String): TrackerCapability? =
            TrackerCapability.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

        /**
         * Tolerant auth-type parse. Returns `null` for an unknown name so the
         * caller can fall back to a safe default ([AuthType.NONE]).
         */
        fun parseAuthType(name: String): AuthType? =
            AuthType.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }

        /**
         * Build a [RemoteTrackerDescriptor] from the wire fields of one
         * `GET /v1/providers` entry.
         *
         * - `capabilities`: unknown enum names are DROPPED with a [warn]
         *   callback, never throwing.
         * - `authType`: an unknown name falls back to [AuthType.NONE] with a
         *   [warn] (the safe default — NONE never auto-grants credentialed
         *   access; the user simply sees no login form).
         * - `baseUrls`: the first url is marked primary; index becomes priority.
         *
         * @param warn invoked once per dropped/unknown value. The production
         *   caller routes this to the app logger / §6.AC non-fatal telemetry;
         *   the default no-op keeps this module dependency-free + unit-testable.
         */
        fun from(
            trackerId: String,
            displayName: String,
            capabilities: List<String>,
            authType: String,
            baseUrls: List<String>,
            encoding: String,
            supportsAnonymous: Boolean,
            warn: (String) -> Unit = {},
        ): RemoteTrackerDescriptor {
            val mappedCapabilities = capabilities.mapNotNull { raw ->
                parseCapability(raw) ?: run {
                    warn("RemoteTrackerDescriptor[$trackerId]: dropping unknown capability '$raw'")
                    null
                }
            }.toSet()

            val mappedAuthType = parseAuthType(authType) ?: run {
                warn("RemoteTrackerDescriptor[$trackerId]: unknown authType '$authType', defaulting to NONE")
                AuthType.NONE
            }

            val mappedBaseUrls = baseUrls.mapIndexed { index, url ->
                MirrorUrl(url = url, isPrimary = index == 0, priority = index)
            }

            return RemoteTrackerDescriptor(
                trackerId = trackerId,
                displayName = displayName,
                baseUrls = mappedBaseUrls,
                capabilities = mappedCapabilities,
                authType = mappedAuthType,
                encoding = encoding,
                supportsAnonymous = supportsAnonymous,
                verified = true,
                apiSupported = true,
            )
        }
    }
}
