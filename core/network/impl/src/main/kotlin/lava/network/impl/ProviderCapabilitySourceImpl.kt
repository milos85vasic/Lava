package lava.network.impl

import lava.network.api.ProviderCapabilitySource
import lava.network.api.ProviderDownloadKind
import lava.tracker.api.TrackerCapability
import lava.tracker.client.LavaTrackerSdk
import javax.inject.Inject

/**
 * LVA-052 — production [ProviderCapabilitySource], the `core:network:impl`
 * adapter that bridges `core:domain` to the Tracker SDK's descriptor capability
 * set so the topic download action can branch HTTP-file vs `.torrent`.
 *
 * Reads [LavaTrackerSdk.listAvailableTrackers] (which includes cloned providers)
 * and maps the matching descriptor's [TrackerCapability] set to the domain-safe
 * [ProviderDownloadKind] (Capability Honesty, clause 6.E — the descriptor
 * declares only capabilities whose `getFeature` actually resolves).
 *
 * `core:domain` never imports `core:tracker:*`; only this adapter does.
 */
internal class ProviderCapabilitySourceImpl @Inject constructor(
    private val sdk: LavaTrackerSdk,
) : ProviderCapabilitySource {

    @Suppress("DEPRECATION") // legacy single-active-tracker fallback for callers without a provider id.
    override suspend fun downloadKind(trackerId: String): ProviderDownloadKind {
        val resolvedId = trackerId.ifBlank { sdk.activeTrackerId() }
        val descriptor = sdk.listAvailableTrackers().firstOrNull { it.trackerId == resolvedId }
            ?: return ProviderDownloadKind.NONE
        val capabilities = descriptor.capabilities
        return when {
            // HTTP-file providers (archiveorg, gutenberg) declare HTTP_DOWNLOAD
            // and NOT TORRENT_DOWNLOAD — route them to the HTTP-file path.
            TrackerCapability.HTTP_DOWNLOAD in capabilities &&
                TrackerCapability.TORRENT_DOWNLOAD !in capabilities -> ProviderDownloadKind.HTTP
            TrackerCapability.TORRENT_DOWNLOAD in capabilities -> ProviderDownloadKind.TORRENT
            else -> ProviderDownloadKind.NONE
        }
    }
}
