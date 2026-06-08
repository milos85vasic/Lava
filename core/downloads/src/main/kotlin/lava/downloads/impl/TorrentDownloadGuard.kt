package lava.downloads.impl

import lava.common.analytics.AnalyticsTracker
import lava.common.torrent.TorrentFileValidator

/**
 * Post-download integrity gate for `.torrent` files.
 *
 * §6.J / §6.E finding (2026-06-09): the project shipped a real bencode/info-hash
 * validator (`lava.common.torrent.TorrentFileValidator`) that had ZERO production
 * callers — it was exercised only by unit tests while [DownloadServiceImpl] handed
 * the payload straight to Android `DownloadManager` with no validation. A corrupt
 * or HTML-error `.torrent` (e.g. a Cloudflare interstitial saved under a
 * `.torrent` filename, or a truncated body) therefore reached the user as a
 * "successful" download that no BitTorrent client can open. Tests were green; the
 * validation feature was not wired for the user.
 *
 * This guard closes that gap. After `DownloadManager` reports the download
 * complete, [DownloadServiceImpl] reads the downloaded bytes and asks
 * [verify]/[verifyValid] whether they are a genuinely-usable `.torrent`:
 *  - VALID  → the download is reported as success (the file URI flows back).
 *  - INVALID → the download is NOT reported as success; the corrupt file is
 *    discarded, a §6.AC non-fatal warning is recorded, and the user-visible
 *    outcome is a failed download (the caller surfaces an error) rather than a
 *    silently-corrupt file.
 *
 * The validation decision lives here (a plain JVM class) rather than inline in
 * [DownloadServiceImpl] so it is exercisable end-to-end in a JVM unit test
 * against the REAL [TorrentFileValidator] — the only Android boundary the test
 * fakes is the on-disk file bytes.
 */
internal class TorrentDownloadGuard(
    private val analytics: AnalyticsTracker,
    private val validator: TorrentFileValidator = TorrentFileValidator(),
) {

    /**
     * Validates the downloaded `.torrent` [bytes].
     *
     * @return `true` when the bytes are a genuinely-valid `.torrent` (safe to
     *   surface to the user); `false` when they are corrupt/HTML-error/truncated.
     *   On `false` a §6.AC [AnalyticsTracker.recordWarning] is recorded with the
     *   rejection reason + the download [id]/[title] context so the operator sees
     *   the failure remotely.
     */
    fun verifyValid(id: String, title: String, bytes: ByteArray): Boolean {
        val result = validator.validate(bytes)
        if (!result.valid) {
            analytics.recordWarning(
                "downloaded .torrent rejected as invalid: ${result.reason}",
                mapOf(
                    AnalyticsTracker.Params.MODULE to "downloads",
                    AnalyticsTracker.Params.OPERATION to "validate_downloaded_torrent",
                    AnalyticsTracker.Params.ERROR_CLASS to "InvalidTorrentPayload",
                    AnalyticsTracker.Params.ERROR_MESSAGE to (result.reason ?: "unknown"),
                    AnalyticsTracker.Params.TOPIC_ID to id,
                ),
            )
            analytics.event(
                AnalyticsTracker.Events.DOWNLOAD_TORRENT_FAILURE,
                mapOf(
                    AnalyticsTracker.Params.TOPIC_ID to id,
                    AnalyticsTracker.Params.ERROR to (result.reason ?: "invalid_torrent"),
                ),
            )
        }
        return result.valid
    }
}
