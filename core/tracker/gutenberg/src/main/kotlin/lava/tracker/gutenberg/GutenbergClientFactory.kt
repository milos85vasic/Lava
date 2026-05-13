package lava.tracker.gutenberg

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.gutenberg.feature.GutenbergBrowse
import lava.tracker.gutenberg.feature.GutenbergDownload
import lava.tracker.gutenberg.feature.GutenbergSearch
import lava.tracker.gutenberg.feature.GutenbergTopic
import lava.tracker.gutenberg.http.GutenbergHttpClient
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.registry.cloneBaseUrlOverride
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the Project Gutenberg plugin.
 *
 * Hilt instantiates this and the [Provider]<[GutenbergClient]> it holds;
 * [create] unwraps the provider so the default call returns the
 * singleton [GutenbergClient] wired by Hilt.
 *
 * SP-4 Phase F.2 (2026-05-13): when [PluginConfig.cloneBaseUrlOverride]
 * is non-null (set by `LavaTrackerSdk.clientFor` for a cloned provider),
 * [create] returns a PER-CALL [GutenbergClient] whose feature impls
 * route through the clone's `primaryUrl` instead of `gutendex.com`.
 * The original-tracker path is unchanged.
 */
class GutenbergClientFactory @Inject constructor(
    private val clientProvider: Provider<GutenbergClient>,
    private val http: GutenbergHttpClient,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = GutenbergDescriptor

    override fun create(config: PluginConfig): TrackerClient {
        val override = config.cloneBaseUrlOverride
        if (override != null) {
            return GutenbergClient(
                http = http,
                search = GutenbergSearch(http, override),
                browse = GutenbergBrowse(http, override),
                topic = GutenbergTopic(http, override),
                download = GutenbergDownload(http, override),
            )
        }
        return clientProvider.get()
    }
}
