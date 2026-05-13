package lava.tracker.archiveorg

import lava.sdk.api.PluginConfig
import lava.tracker.api.TrackerClient
import lava.tracker.api.TrackerDescriptor
import lava.tracker.archiveorg.feature.ArchiveOrgBrowse
import lava.tracker.archiveorg.feature.ArchiveOrgDownload
import lava.tracker.archiveorg.feature.ArchiveOrgSearch
import lava.tracker.archiveorg.feature.ArchiveOrgTopic
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import lava.tracker.registry.TrackerClientFactory
import lava.tracker.registry.cloneBaseUrlOverride
import javax.inject.Inject
import javax.inject.Provider

/**
 * [TrackerClientFactory] for the Internet Archive plugin.
 *
 * Hilt instantiates this and the [Provider]<[ArchiveOrgClient]> it holds;
 * [create] unwraps the provider so the default call returns the
 * singleton [ArchiveOrgClient] wired by Hilt.
 *
 * SP-4 Phase F.2 (2026-05-13): when [PluginConfig.cloneBaseUrlOverride]
 * is non-null (set by `LavaTrackerSdk.clientFor` for a cloned provider),
 * [create] returns a PER-CALL [ArchiveOrgClient] whose feature impls
 * route through the clone's `primaryUrl` instead of `archive.org`.
 */
class ArchiveOrgClientFactory @Inject constructor(
    private val clientProvider: Provider<ArchiveOrgClient>,
    private val http: ArchiveOrgHttpClient,
) : TrackerClientFactory {
    override val descriptor: TrackerDescriptor = ArchiveOrgDescriptor

    override fun create(config: PluginConfig): TrackerClient {
        val override = config.cloneBaseUrlOverride
        if (override != null) {
            return ArchiveOrgClient(
                http = http,
                search = ArchiveOrgSearch(http, override),
                browse = ArchiveOrgBrowse(http, override),
                topic = ArchiveOrgTopic(http, override),
                download = ArchiveOrgDownload(http, override),
            )
        }
        return clientProvider.get()
    }
}
