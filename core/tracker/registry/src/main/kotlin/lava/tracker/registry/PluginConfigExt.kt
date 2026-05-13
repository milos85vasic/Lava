package lava.tracker.registry

import lava.sdk.api.PluginConfig

/**
 * SP-4 Phase F.2 — well-known PluginConfig key used by
 * `LavaTrackerSdk.clientFor` to forward a cloned provider's
 * `primaryUrl` into the source tracker's factory.
 *
 * Per-tracker factories read this via [cloneBaseUrlOverride].
 *
 * Constant rather than literal so the producer (LavaTrackerSdk) and
 * the consumer (each plugin factory) cannot drift on the spelling
 * silently — per §6.R spirit applied to config-key identifiers.
 */
const val CLONE_BASE_URL_CONFIG_KEY: String = "lava.cloneBaseUrl"

/**
 * Convenience accessor: nullable clone URL override stamped by
 * `LavaTrackerSdk.clientFor`. Returns the configured override or
 * null if the factory was invoked outside the clone path (i.e., for
 * an original provider).
 */
val PluginConfig.cloneBaseUrlOverride: String?
    get() = raw[CLONE_BASE_URL_CONFIG_KEY] as? String
