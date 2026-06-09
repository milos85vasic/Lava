package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.dispatchers.api.Dispatchers
import lava.network.api.ProviderCapabilitySource
import lava.network.api.ProviderDownloadKind
import javax.inject.Inject

/**
 * LVA-052 — resolves the download shape of the provider whose topic the user is
 * viewing so the topic download action can branch HTTP-file vs `.torrent`.
 *
 * The chief consumer is [lava.topic.TopicViewModel]: when the user taps the
 * download button it must route an `archiveorg` / `gutenberg` topic (those
 * declare `HTTP_DOWNLOAD` and NOT `TORRENT_DOWNLOAD`) to
 * [DownloadHttpFileUseCase], and a `rutracker` / `rutor` topic to the existing
 * [DownloadTorrentUseCase] `.torrent` path.
 *
 * Delegates to [ProviderCapabilitySource] (real SDK descriptor capabilities,
 * Capability Honesty — clause 6.E) so `core:domain` never imports
 * `core:tracker:*`. A blank [trackerId] falls back to the active tracker inside
 * the source, preserving the legacy behaviour for callers that cannot yet
 * supply an explicit provider id.
 */
class ResolveProviderDownloadKindUseCase @Inject constructor(
    private val providerCapabilitySource: ProviderCapabilitySource,
    private val dispatchers: Dispatchers,
) {
    /**
     * @param trackerId the provider whose topic is being viewed; blank means
     *   "use the active tracker".
     * @return [ProviderDownloadKind.HTTP] for HTTP-file providers,
     *   [ProviderDownloadKind.TORRENT] for `.torrent`/magnet providers,
     *   [ProviderDownloadKind.NONE] when the provider declares no download
     *   capability or is unknown.
     */
    suspend operator fun invoke(trackerId: String): ProviderDownloadKind {
        return withContext(dispatchers.default) {
            providerCapabilitySource.downloadKind(trackerId)
        }
    }
}
