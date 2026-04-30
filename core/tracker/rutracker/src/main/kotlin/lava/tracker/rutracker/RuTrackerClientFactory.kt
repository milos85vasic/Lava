package lava.tracker.rutracker

import javax.inject.Inject
import javax.inject.Provider
import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerClientFactory

/**
 * [TrackerClientFactory] for the RuTracker plugin. Hilt instantiates this and the
 * [Provider]<[RuTrackerClient]> it holds; [create] simply unwraps the provider so
 * each call returns the singleton [RuTrackerClient] wired by Hilt.
 *
 * The [PluginConfig] argument is ignored — RuTracker has no run-time configurable
 * options today. SP-3a Section H may add per-tracker tunables; this factory will
 * become non-trivial then.
 */
class RuTrackerClientFactory @Inject constructor(
    private val clientProvider: Provider<RuTrackerClient>,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = RuTrackerDescriptor

    override fun create(config: PluginConfig): TrackerClient = clientProvider.get()
}
