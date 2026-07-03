package lava.network.api

/**
 * 2026-07-03 reroute — Lava-domain seam for fetching a provider's `.torrent`
 * bytes IN-APP over the app's trusted OkHttp client, PROVIDER-AWARE.
 *
 * This is the `.torrent` twin of [HttpDownloadSource] and the decoupling boundary
 * between `core:domain` (which depends only on `core:network:api`) and the Tracker
 * SDK that actually performs the fetch. The implementation lives in
 * `core:network:impl` and delegates to `LavaTrackerSdk.downloadTorrentFile`,
 * mirroring [HttpDownloadSource]/`HttpDownloadSourceImpl` so `core:domain` never
 * imports `core:tracker:*`.
 *
 * Why provider-aware: the download-stall incident
 * (`.lava-ci-evidence/sixth-law-incidents/2026-07-03-rutracker-kinozal-torrent-download-stall.json`)
 * was that the `.torrent` byte-fetch went to the endpoint's ROOT `/download/:id`,
 * which the goapi routes to RuTracker ONLY. A Kinozal (or any non-rutracker)
 * download therefore fetched from rutracker.org with a foreign id → an HTML error
 * page → the bencode guard rejected it → DownloadState.Error. Routing by
 * [trackerId] sends the fetch to `/v1/{trackerId}/download/:id` (the SAME
 * provider-aware, both-auth-gates path search/topic and the gutenberg HTTP
 * download already use).
 *
 * Capability Honesty (clause 6.E): [downloadTorrentFile] returns null when the
 * provider does NOT declare `TORRENT_DOWNLOAD` (its
 * `getFeature(DownloadableTracker)` resolves null), when the provider is unknown,
 * or when the underlying fetch throws. Null is an honest "this provider has no
 * `.torrent` surface / the fetch failed" signal — never fabricated bytes.
 */
interface TorrentDownloadSource {
    /**
     * Fetches the raw `.torrent` bytes identified by [id] from the provider
     * [trackerId]. A blank [trackerId] falls back to the active tracker inside the
     * SDK, preserving the legacy single-tracker deep-link / favorites path.
     *
     * @return the raw `.torrent` bytes, or null when the provider does not support
     *   `.torrent` download or the fetch fails. §6.H: no credential is logged.
     */
    suspend fun downloadTorrentFile(trackerId: String, id: String): ByteArray?
}
