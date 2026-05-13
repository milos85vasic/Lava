package lava.tracker.api

/**
 * SP-4 Phase F.2 — per-call source of the base URL a tracker feature
 * implementation should hit.
 *
 * For original (non-clone) providers, the implementation returns the
 * source descriptor's `baseUrls[0].url`. For cloned providers, it
 * returns `ClonedProviderEntity.primaryUrl` stamped into [PluginConfig]
 * by `LavaTrackerSdk.clientFor`.
 *
 * The provider is a function, not a constant, so future per-call
 * mirror-fallback can swap it without changing feature-impl signatures.
 *
 * Wiring: per-tracker `*ClientFactory` reads
 * [lava.tracker.registry.cloneBaseUrlOverride] from the incoming
 * `PluginConfig`; if non-null it constructs a `MirrorUrlProvider { override }`,
 * else `MirrorUrlProvider { descriptor.baseUrls[0].url }`. The client
 * passes this provider to every feature impl that issues HTTP calls.
 */
fun interface MirrorUrlProvider {
    fun baseUrl(): String
}
