package lava.tracker.registry

import lava.sdk.registry.DefaultPluginRegistry
import lava.sdk.registry.PluginRegistry
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor

/**
 * Lava-domain default registry. Composes the SDK's [DefaultPluginRegistry] via
 * Kotlin interface delegation (the SDK class is final by design — submodule
 * pins are frozen contracts). Adds [trackersWithCapability] which the generic
 * SDK registry can't express because it doesn't know [TrackerCapability].
 */
class DefaultTrackerRegistry(
    private val delegate: PluginRegistry<TrackerDescriptor, TrackerClient> =
        DefaultPluginRegistry<TrackerDescriptor, TrackerClient>(),
) : TrackerRegistry, PluginRegistry<TrackerDescriptor, TrackerClient> by delegate {
    override fun trackersWithCapability(capability: TrackerCapability): List<TrackerDescriptor> =
        list().filter { capability in it.capabilities }
}
