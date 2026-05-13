package lava.tracker.rutor

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.registry.cloneBaseUrlOverride
import lava.tracker.rutor.feature.RuTorAuth
import lava.tracker.rutor.feature.RuTorBrowse
import lava.tracker.rutor.feature.RuTorComments
import lava.tracker.rutor.feature.RuTorDownload
import lava.tracker.rutor.feature.RuTorSearch
import lava.tracker.rutor.feature.RuTorTopic
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.parser.RuTorBrowseParser
import lava.tracker.rutor.parser.RuTorCommentsParser
import lava.tracker.rutor.parser.RuTorLoginParser
import lava.tracker.rutor.parser.RuTorSearchParser
import lava.tracker.rutor.parser.RuTorTopicParser
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the RuTor plugin (SP-3a Task 3.40, Section J).
 *
 * Hilt instantiates this and the [Provider]<[RuTorClient]> it holds; [create]
 * unwraps the provider so the default call returns the singleton [RuTorClient].
 *
 * SP-4 Phase F.2 (2026-05-13): when [PluginConfig.cloneBaseUrlOverride]
 * is non-null (set by `LavaTrackerSdk.clientFor` for a cloned provider),
 * [create] returns a PER-CALL [RuTorClient] whose feature impls route
 * through the clone's `primaryUrl` instead of `rutor.info`. Note that
 * the Download impl uses a `d.rutor.info` download host by default; the
 * F.2 override applies its single URL uniformly across all features —
 * a per-feature-host clone is a Phase F.3+ refinement.
 */
class RuTorClientFactory @Inject constructor(
    private val clientProvider: Provider<RuTorClient>,
    private val http: RuTorHttpClient,
    private val searchParser: RuTorSearchParser,
    private val browseParser: RuTorBrowseParser,
    private val topicParser: RuTorTopicParser,
    private val commentsParser: RuTorCommentsParser,
    private val loginParser: RuTorLoginParser,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = RuTorDescriptor

    override fun create(config: PluginConfig): TrackerClient {
        val override = config.cloneBaseUrlOverride
        if (override != null) {
            return RuTorClient(
                http = http,
                search = RuTorSearch(http, searchParser, override),
                browse = RuTorBrowse(http, browseParser, override),
                topic = RuTorTopic(http, topicParser, override),
                comments = RuTorComments(http, commentsParser, override),
                auth = RuTorAuth(http, loginParser, override),
                download = RuTorDownload(http, override),
            )
        }
        return clientProvider.get()
    }
}
