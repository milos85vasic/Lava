package lava.tracker.gutenberg

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerClientFactory
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the Project Gutenberg plugin.
 *
 * Hilt instantiates this and the [Provider]<[GutenbergClient]> it holds;
 * [create] unwraps the provider so each call returns the singleton
 * [GutenbergClient] wired by Hilt.
 */
class GutenbergClientFactory @Inject constructor(
    private val clientProvider: Provider<GutenbergClient>,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = GutenbergDescriptor

    override fun create(config: PluginConfig): TrackerClient = clientProvider.get()
}
