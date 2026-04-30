package lava.tracker.rutor

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerClientFactory
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the RuTor plugin (SP-3a Task 3.40, Section J).
 *
 * Hilt instantiates this and the [Provider]<[RuTorClient]> it holds; [create]
 * unwraps the provider so each call returns the singleton [RuTorClient] wired
 * by Hilt. Mirrors the [lava.tracker.rutracker.RuTrackerClientFactory] pattern.
 *
 * The [PluginConfig] argument is ignored — RuTor has no run-time configurable
 * options today. SP-3a Phase 4 may add per-tracker tunables (e.g. preferred
 * mirror); this factory will become non-trivial then.
 */
class RuTorClientFactory @Inject constructor(
    private val clientProvider: Provider<RuTorClient>,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = RuTorDescriptor

    override fun create(config: PluginConfig): TrackerClient = clientProvider.get()
}
