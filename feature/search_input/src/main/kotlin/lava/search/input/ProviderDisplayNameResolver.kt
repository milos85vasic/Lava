package lava.search.input

/**
 * Outermost provider-catalogue boundary for [SearchInputViewModel].
 *
 * 2026-06-25 video-cluster root-cause fix. The search-input chip bar is built
 * from the user's ONBOARDED providers ([lava.credentials.ProviderConfigRepository]
 * — the source of truth onboarding writes to), NOT a hardcoded list. This
 * resolver supplies the human-readable display name for each onboarded provider
 * id (e.g. "yts" → "YTS"), sourced from the live tracker registry
 * (`LavaTrackerSdk.listAvailableTrackers()`). Providers with no registry entry
 * fall back to their raw id at the call site.
 *
 * Kept as a narrow seam (not the whole SDK) so the ViewModel unit test can
 * fake the catalogue boundary without standing up a real
 * [lava.tracker.client.LavaTrackerSdk] + [lava.tracker.registry.TrackerRegistry]
 * — faking at this outermost boundary is permitted by the Anti-Bluff Pact
 * Second Law; the SUT (the ViewModel) stays real.
 */
internal interface ProviderDisplayNameResolver {
    /** Snapshot of provider id → display name for every registered provider. */
    fun displayNames(): Map<String, String>
}
