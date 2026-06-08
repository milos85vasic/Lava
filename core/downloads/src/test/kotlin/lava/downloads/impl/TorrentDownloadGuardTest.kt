package lava.downloads.impl

import lava.common.analytics.AnalyticsTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for the post-download `.torrent` integrity gate.
 *
 * Closes the §6.J / §6.E finding (2026-06-09) that `TorrentFileValidator` was
 * DEAD CODE — referenced only by tests while the real download flow handed the
 * payload to Android `DownloadManager` unvalidated, so a corrupt / HTML-error
 * `.torrent` reached the user as a "successful" download. This test wires the
 * REAL [lava.common.torrent.TorrentFileValidator] through the production
 * [TorrentDownloadGuard] (the exact decision [DownloadServiceImpl] makes after a
 * download completes) and asserts the USER-VISIBLE outcome:
 *  - a VALID `.torrent` is accepted (download flows through to the user);
 *  - an INVALID payload (Cloudflare-style HTML error page; truncated bencode) is
 *    REJECTED and the §6.AC non-fatal warning is surfaced — NOT a silent corrupt
 *    download.
 *
 * Only the outermost telemetry boundary ([AnalyticsTracker]) is faked, with a
 * recording fake; the validator and the guard decision are the real production
 * classes (Second Law: no mocking of internal business logic).
 *
 * Bluff-Audit: TorrentDownloadGuardTest
 *   Mutation: in [TorrentDownloadGuard.verifyValid], replace
 *     `return result.valid` with `return true` (i.e. accept every payload —
 *     simulating the original DEAD-CODE state where validation never gates the
 *     download).
 *   Observed-Failure: `invalid HTML-error payload is rejected and surfaces a
 *     warning expected:<false> but was:<true>` at the
 *     `htmlErrorPagePayloadIsRejectedAndSurfacesWarning` assertion (and the
 *     truncated-bencode case fails the same way).
 *   Reverted: yes
 */
class TorrentDownloadGuardTest {

    /** Recording fake for the only faked boundary — telemetry. */
    private class RecordingAnalytics : AnalyticsTracker {
        val warnings = mutableListOf<String>()
        val failureEvents = mutableListOf<String>()

        override fun event(name: String, params: Map<String, String>) {
            if (name == AnalyticsTracker.Events.DOWNLOAD_TORRENT_FAILURE) {
                failureEvents.add(params[AnalyticsTracker.Params.ERROR].orEmpty())
            }
        }

        override fun setUserId(userId: String?) = Unit
        override fun setProperty(key: String, value: String?) = Unit
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) = Unit
        override fun recordWarning(message: String, context: Map<String, String>) {
            warnings.add(message)
        }

        override fun log(message: String) = Unit
    }

    private val analytics = RecordingAnalytics()
    private val guard = TorrentDownloadGuard(analytics)

    /**
     * Builds a minimal but genuinely-valid single-file `.torrent`:
     * top-level dict with an `info` dict carrying a non-empty `name`, a
     * `piece length` > 0 and a `pieces` byte string of exactly 20 bytes
     * (one piece's SHA-1). This is the same shape a real BitTorrent client
     * accepts and that [lava.common.torrent.TorrentFileValidator] requires.
     */
    private fun validTorrentBytes(): ByteArray {
        val pieces = "A".repeat(20) // 20 bytes = one piece SHA-1
        // d4:infod6:lengthi1234e4:name8:demo.txt12:piece lengthi16384e6:pieces20:<20 bytes>ee
        val info = "d6:lengthi1234e4:name8:demo.txt12:piece lengthi16384e6:pieces20:$pieces" + "e"
        return ("d4:info$info" + "e").toByteArray(Charsets.UTF_8)
    }

    @Test
    fun validTorrentPayloadIsAccepted() {
        val accepted = guard.verifyValid(id = "42", title = "demo", bytes = validTorrentBytes())

        assertTrue("a genuinely-valid .torrent must be accepted for the user", accepted)
        assertEquals("no warning should be recorded for a valid payload", 0, analytics.warnings.size)
        assertEquals(0, analytics.failureEvents.size)
    }

    @Test
    fun htmlErrorPagePayloadIsRejectedAndSurfacesWarning() {
        // A Cloudflare interstitial / server error page saved under a .torrent name.
        val htmlError = (
            "<!DOCTYPE html><html><head><title>Just a moment...</title></head>" +
                "<body>Checking your browser before accessing the tracker.</body></html>"
            ).toByteArray(Charsets.UTF_8)

        val accepted = guard.verifyValid(id = "42", title = "demo", bytes = htmlError)

        assertFalse(
            "an HTML error page is NOT a usable .torrent and must be rejected, not " +
                "silently handed to the user as a successful download",
            accepted,
        )
        assertEquals(
            "the §6.AC non-fatal warning must surface so the operator/user sees the bad download",
            1,
            analytics.warnings.size,
        )
        assertTrue(
            "warning must name the rejection",
            analytics.warnings.single().contains("rejected as invalid"),
        )
        assertEquals(
            "the download-failure event must fire",
            1,
            analytics.failureEvents.size,
        )
    }

    @Test
    fun truncatedBencodePayloadIsRejectedAndSurfacesWarning() {
        // Valid-looking start but the body is cut off (truncated download).
        val truncated = "d4:infod6:lengthi1234e4:name8:demo.tx".toByteArray(Charsets.UTF_8)

        val accepted = guard.verifyValid(id = "7", title = "demo", bytes = truncated)

        assertFalse("a truncated .torrent must be rejected, not surfaced as success", accepted)
        assertEquals(1, analytics.warnings.size)
        assertEquals(1, analytics.failureEvents.size)
    }

    @Test
    fun emptyPayloadIsRejected() {
        val accepted = guard.verifyValid(id = "9", title = "demo", bytes = ByteArray(0))

        assertFalse("an empty download is not a valid .torrent", accepted)
        assertEquals(1, analytics.warnings.size)
    }
}
