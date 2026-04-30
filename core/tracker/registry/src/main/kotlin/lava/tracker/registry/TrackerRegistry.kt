package lava.tracker.registry

import lava.sdk.registry.PluginRegistry
import lava.tracker.api.TrackerCapability
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor

interface TrackerRegistry : PluginRegistry<TrackerDescriptor, TrackerClient> {
    fun trackersWithCapability(capability: TrackerCapability): List<TrackerDescriptor>
}
