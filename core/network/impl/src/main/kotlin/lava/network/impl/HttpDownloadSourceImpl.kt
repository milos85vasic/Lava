package lava.network.impl

import lava.network.api.HttpArtifact
import lava.network.api.HttpDownloadSource
import lava.tracker.client.LavaTrackerSdk
import javax.inject.Inject

/**
 * LVA-052 — production [HttpDownloadSource], the `core:network:impl` adapter
 * that bridges `core:domain` to the Tracker SDK's HTTP-download surface.
 *
 * Mirrors the [SwitchingNetworkApi] pattern: a thin Lava-domain adapter over
 * [LavaTrackerSdk]. It delegates to [LavaTrackerSdk.downloadHttpFile], which
 * resolves the provider's [lava.tracker.api.feature.HttpDownloadableTracker]
 * (Capability Honesty, clause 6.E — null when the provider doesn't declare
 * `HTTP_DOWNLOAD`) and performs the real HTTP fetch.
 *
 * The SDK's `HttpDownloadResult` is mapped to the api-side [HttpArtifact] so
 * `core:domain` never imports `core:tracker:*`.
 */
internal class HttpDownloadSourceImpl @Inject constructor(
    private val sdk: LavaTrackerSdk,
) : HttpDownloadSource {

    override suspend fun downloadHttpFile(trackerId: String, id: String): HttpArtifact? {
        val result = sdk.downloadHttpFile(trackerId, id) ?: return null
        return HttpArtifact(
            bytes = result.bytes,
            sourceUrl = result.sourceUrl,
            fileName = result.fileName,
        )
    }
}
