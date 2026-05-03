package lava.tracker.nnmclub

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerClientFactory
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the NNM-Club plugin.
 *
 * Hilt instantiates this and the [Provider]<[NnmclubClient]> it holds; [create]
 * unwraps the provider so each call returns the singleton [NnmclubClient] wired
 * by Hilt.
 */
class NnmclubClientFactory @Inject constructor(
    private val clientProvider: Provider<NnmclubClient>,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = NnmclubDescriptor

    override fun create(config: PluginConfig): TrackerClient = clientProvider.get()
}
