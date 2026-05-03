package lava.tracker.kinozal

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerClientFactory
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the Kinozal plugin.
 *
 * Hilt instantiates this and the [Provider]<[KinozalClient]> it holds; [create]
 * unwraps the provider so each call returns the singleton [KinozalClient].
 */
class KinozalClientFactory @Inject constructor(
    private val clientProvider: Provider<KinozalClient>,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = KinozalDescriptor

    override fun create(config: PluginConfig): TrackerClient = clientProvider.get()
}
