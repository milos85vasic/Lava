package lava.tracker.registry

import lava.sdk.api.PluginConfig
import lava.sdk.registry.PluginFactory
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor

interface TrackerClientFactory : PluginFactory<TrackerDescriptor, TrackerClient> {
    override val descriptor: TrackerDescriptor
    override fun create(config: PluginConfig): TrackerClient
}
