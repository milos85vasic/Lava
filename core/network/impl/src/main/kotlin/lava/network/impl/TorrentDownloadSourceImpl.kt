package lava.network.impl

import lava.network.api.TorrentDownloadSource
import lava.tracker.client.LavaTrackerSdk
import javax.inject.Inject

/**
 * 2026-07-03 reroute — production [TorrentDownloadSource], the `core:network:impl`
 * adapter that bridges `core:domain` to the Tracker SDK's provider-aware
 * `.torrent`-download surface.
 *
 * Mirrors [HttpDownloadSourceImpl]: a thin Lava-domain adapter over
 * [LavaTrackerSdk]. It delegates to [LavaTrackerSdk.downloadTorrentFile], which
 * resolves the provider's [lava.tracker.api.feature.DownloadableTracker]
 * (Capability Honesty, clause 6.E — null when the provider doesn't declare
 * `TORRENT_DOWNLOAD`) and performs the real, provider-aware
 * `/v1/{trackerId}/download/{id}` fetch carrying BOTH auth gates. This replaces
 * the prior byte-fetch that always hit the endpoint's rutracker-only root
 * `/download/{id}`, which failed for every non-rutracker provider.
 *
 * §6.H: no credential is logged here.
 */
internal class TorrentDownloadSourceImpl @Inject constructor(
    private val sdk: LavaTrackerSdk,
) : TorrentDownloadSource {

    override suspend fun downloadTorrentFile(trackerId: String, id: String): ByteArray? =
        sdk.downloadTorrentFile(trackerId, id)
}
