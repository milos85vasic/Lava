package lava.tracker.registry

import lava.sdk.registry.PluginRegistry
import lava.tracker.api.RemoteTrackerDescriptor
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor

interface TrackerRegistry : PluginRegistry<TrackerDescriptor, TrackerClient> {
    fun trackersWithCapability(capability: TrackerCapability): List<TrackerDescriptor>

    /**
     * Dynamic Provider Discovery (2026-06-11, spec §4.2 / plan Task 4.2).
     *
     * Replaces the dynamic (API-vended) provider set with one
     * `ApiBackedTrackerClient` per [descriptors] entry, keyed by
     * [RemoteTrackerDescriptor.trackerId], into the SAME registry the SDK reads
     * via `list()` / `listAvailableTrackers()`. Re-invocation REPLACES the
     * previous dynamic set (it does not accumulate).
     *
     * An EMPTY [descriptors] list keeps/restores the bundled fallback — the
     * compiled-in provider factories — so the available-tracker list is NEVER
     * empty (the user always sees the offline-default providers; §6.AB anti-bluff
     * — fetch failure must show the fallback, not a blank list).
     *
     * The client-construction seam ([RemoteTrackerDescriptor] →
     * [TrackerClient]) is set by the consuming module (`:core:tracker:client`)
     * via [setApiClientFactory], because the concrete `ApiBackedTrackerClient`
     * lives in that module — `:core:tracker:registry` must not depend on it
     * (the dependency edge runs the other way).
     */
    fun populateFrom(descriptors: List<RemoteTrackerDescriptor>)

    /**
     * Installs the factory that turns a [RemoteTrackerDescriptor] into a live
     * [TrackerClient] (an `ApiBackedTrackerClient` in production). MUST be called
     * once at DI wiring time before [populateFrom] is used with a non-empty list.
     */
    fun setApiClientFactory(factory: (RemoteTrackerDescriptor) -> TrackerClient)
}
