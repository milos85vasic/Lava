package lava.tracker.kinozal

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.kinozal.feature.KinozalAuth
import lava.tracker.kinozal.feature.KinozalBrowse
import lava.tracker.kinozal.feature.KinozalDownload
import lava.tracker.kinozal.feature.KinozalSearch
import lava.tracker.kinozal.feature.KinozalTopic
import lava.tracker.kinozal.http.KinozalHttpClient
import lava.tracker.kinozal.parser.KinozalSearchParser
import lava.tracker.kinozal.parser.KinozalTopicParser
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.registry.cloneBaseUrlOverride
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the Kinozal plugin.
 *
 * Hilt instantiates this and the [Provider]<[KinozalClient]> it holds; [create]
 * unwraps the provider so the default call returns the singleton
 * [KinozalClient].
 *
 * SP-4 Phase F.2 (2026-05-13): when [PluginConfig.cloneBaseUrlOverride]
 * is non-null (set by `LavaTrackerSdk.clientFor` for a cloned provider),
 * [create] returns a PER-CALL [KinozalClient] whose feature impls route
 * through the clone's `primaryUrl` instead of `kinozal.tv`. Parsers
 * (`KinozalSearchParser`, `KinozalTopicParser`) are stateless, so the
 * shared singletons are reused safely.
 */
class KinozalClientFactory @Inject constructor(
    private val clientProvider: Provider<KinozalClient>,
    private val http: KinozalHttpClient,
    private val searchParser: KinozalSearchParser,
    private val topicParser: KinozalTopicParser,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = KinozalDescriptor

    override fun create(config: PluginConfig): TrackerClient {
        val override = config.cloneBaseUrlOverride
        if (override != null) {
            return KinozalClient(
                http = http,
                search = KinozalSearch(http, searchParser, override),
                browse = KinozalBrowse(http, searchParser, override),
                topic = KinozalTopic(http, topicParser, override),
                auth = KinozalAuth(http, override),
                download = KinozalDownload(http, override),
            )
        }
        return clientProvider.get()
    }
}
