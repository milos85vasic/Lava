package lava.tracker.archiveorg

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerClientFactory
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the Internet Archive plugin.
 *
 * Hilt instantiates this and the [Provider]<[ArchiveOrgClient]> it holds;
 * [create] unwraps the provider so each call returns the singleton
 * [ArchiveOrgClient] wired by Hilt.
 *
 * The [PluginConfig] argument is ignored — Internet Archive has no run-time
 * configurable options today.
 */
class ArchiveOrgClientFactory @Inject constructor(
    private val clientProvider: Provider<ArchiveOrgClient>,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = ArchiveOrgDescriptor

    override fun create(config: PluginConfig): TrackerClient = clientProvider.get()
}
