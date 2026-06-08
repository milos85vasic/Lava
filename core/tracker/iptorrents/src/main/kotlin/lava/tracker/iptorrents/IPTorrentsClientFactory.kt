package lava.tracker.iptorrents

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerClientFactory
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the IPTorrents plugin (same Section-J pattern as
 * RuTor/Kinozal). Hilt instantiates this and the [Provider]<[IPTorrentsClient]>
 * it holds; [create] unwraps the provider so the default call returns the
 * singleton [IPTorrentsClient].
 *
 * No clone-URL override path is implemented for IPTorrents: the provider's
 * reachable surface is the lava-api-go sidecar (resolved via [IPTorrentsConfig]),
 * not a set of swappable tracker mirrors, so the SP-4 clone mechanism does not
 * apply here. The factory ignores [PluginConfig] and returns the singleton.
 */
class IPTorrentsClientFactory @Inject constructor(
    private val clientProvider: Provider<IPTorrentsClient>,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = IPTorrentsDescriptor

    override fun create(config: PluginConfig): TrackerClient = clientProvider.get()
}
