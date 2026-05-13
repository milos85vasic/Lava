package lava.tracker.nnmclub

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.nnmclub.feature.NnmclubAuth
import lava.tracker.nnmclub.feature.NnmclubBrowse
import lava.tracker.nnmclub.feature.NnmclubComments
import lava.tracker.nnmclub.feature.NnmclubDownload
import lava.tracker.nnmclub.feature.NnmclubSearch
import lava.tracker.nnmclub.feature.NnmclubTopic
import lava.tracker.nnmclub.http.NnmclubHttpClient
import lava.tracker.nnmclub.parser.NnmclubBrowseParser
import lava.tracker.nnmclub.parser.NnmclubLoginParser
import lava.tracker.nnmclub.parser.NnmclubSearchParser
import lava.tracker.nnmclub.parser.NnmclubTopicParser
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.registry.cloneBaseUrlOverride
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the NNM-Club plugin.
 *
 * Hilt instantiates this and the [Provider]<[NnmclubClient]> it holds;
 * [create] unwraps the provider so the default call returns the
 * singleton [NnmclubClient].
 *
 * SP-4 Phase F.2 (2026-05-13): when [PluginConfig.cloneBaseUrlOverride]
 * is non-null, [create] returns a PER-CALL [NnmclubClient] whose
 * feature impls route through the clone's `primaryUrl` instead of
 * `nnmclub.to`. Stateless parsers are reused safely from singleton
 * injections.
 */
class NnmclubClientFactory @Inject constructor(
    private val clientProvider: Provider<NnmclubClient>,
    private val http: NnmclubHttpClient,
    private val searchParser: NnmclubSearchParser,
    private val browseParser: NnmclubBrowseParser,
    private val topicParser: NnmclubTopicParser,
    private val loginParser: NnmclubLoginParser,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = NnmclubDescriptor

    override fun create(config: PluginConfig): TrackerClient {
        val override = config.cloneBaseUrlOverride
        if (override != null) {
            return NnmclubClient(
                http = http,
                search = NnmclubSearch(http, searchParser, override),
                browse = NnmclubBrowse(http, browseParser, override),
                topic = NnmclubTopic(http, topicParser, override),
                comments = NnmclubComments(http, override),
                auth = NnmclubAuth(http, loginParser, override),
                download = NnmclubDownload(http, override),
            )
        }
        return clientProvider.get()
    }
}
