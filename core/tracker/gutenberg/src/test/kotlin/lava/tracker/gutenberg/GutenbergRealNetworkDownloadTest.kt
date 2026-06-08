package lava.tracker.gutenberg

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.gutenberg.feature.GutenbergDownload
import lava.tracker.gutenberg.feature.GutenbergSearch
import lava.tracker.gutenberg.http.GutenbergHttpClient
import lava.tracker.testing.RealTrackerEvidence
import lava.tracker.testing.RealTrackerHarness
import lava.tracker.testing.RealTrackerTestSupport
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/**
 * CROWN-JEWEL real-network verification for Project Gutenberg (gutenberg, via the
 * Gutendex JSON API) — the automated replacement for a human manually confirming
 * the download option works against the REAL gutendex.com over the network.
 *
 * gutenberg is anonymous (`AuthType.NONE`), so there is no login step. It drives
 * the REAL production path:
 *   real search → pick a real book → real best-format download
 *   ([GutenbergDownload.downloadTorrentFile] → EPUB / plain-text bytes).
 *
 * ## Honest scope note — `.torrent`/magnet validators do NOT apply here
 * Project Gutenberg distributes EBOOK files (EPUB / plain text / HTML), not
 * BitTorrent metainfo and not magnet links — [GutenbergDownload.getMagnetLink]
 * returns null by design, and the downloaded bytes are an EPUB (a zip) or UTF-8
 * text, never a bencoded `.torrent`. Running [lava.common.torrent.TorrentFileValidator]
 * or [lava.common.torrent.MagnetLinkValidator] against an EPUB would itself be a
 * §6.J BLUFF (asserting a passing torrent/magnet verdict on a payload that is
 * neither). The honest, user-visible OUTCOME this crown-jewel asserts is therefore
 * that the REAL download yields a genuinely non-empty ebook whose bytes carry the
 * recognizable signature of the format the user would open (EPUB zip magic `PK`,
 * or readable text) — i.e. a usable book, the thing the download button promises.
 *
 * ## Anti-Bluff (§6.J / §6.L / §11.4.3) — honest SKIP, never a fake PASS
 * Gated off by default. Runs ONLY when `-PrealTrackers=true` (or
 * `LAVA_REAL_TRACKERS=true`) is set. When the gate is off, OR gutendex.com /
 * the gutenberg.org content host is unreachable from this host, the test
 * `assumeTrue`-SKIPs with an auditable reason and asserts nothing. gutendex.com's
 * anonymous CDN is normally reachable, so a reachable run that yields no book, or
 * a book whose download is empty / not a recognizable ebook, IS a hard failure.
 *
 * ## Falsifiability rehearsal (§6.J clause 2)
 * Rehearsal A (empty payload): make [GutenbergDownload.downloadTorrentFile] return
 * `ByteArray(0)` → `Expected downloaded ebook to be non-empty` fails.
 * Rehearsal B (garbage payload): replace the downloaded bytes with
 * `ByteArray(8) { 0 }` before the signature check → `not a recognizable ebook`
 * fails (no EPUB zip magic, not valid text). Both reverted.
 */
class GutenbergRealNetworkDownloadTest {

    @Test
    fun realSearchDownloadAndValidateEbook() = runBlocking {
        assumeTrue(
            "SKIPPED: -PrealTrackers=true (or LAVA_REAL_TRACKERS=true) not set; suite gated off by default.",
            RealTrackerTestSupport.realTrackersEnabled(),
        )

        val baseUrl = GutenbergDescriptor.baseUrls.first { it.isPrimary }.url
        val harness = RealTrackerHarness("gutenberg", baseUrl)

        val reach = harness.probe()
        assumeTrue(
            "SKIPPED: gutendex.com unreachable from this host — ${reach.detail}. Honest SKIP per §6.L; NOT a fake PASS.",
            reach.reachable,
        )

        val http = GutenbergHttpClient()
        val search = GutenbergSearch(http, baseUrl)
        val download = GutenbergDownload(http, baseUrl)

        // "shakespeare" — guaranteed large public-domain catalogue on Gutenberg
        // with EPUB + plain-text formats on essentially every result.
        val query = "shakespeare"
        val results = try {
            search.search(SearchRequest(query = query), page = 0)
        } catch (t: Throwable) {
            assumeTrue(
                "SKIPPED: gutendex.com search unreachable from this host — " +
                    "${t.javaClass.simpleName}: ${t.message}. Honest SKIP per §6.L; NOT a fake PASS.",
                false,
            )
            return@runBlocking
        }
        assertTrue(
            "Real gutendex.com search for '$query' returned no results — search is broken.",
            results.items.isNotEmpty(),
        )

        // Pick the first book with a usable id and download its best format. The
        // content files are hosted on gutenberg.org's CDN; if THAT host is
        // unreachable (distinct from gutendex.com), treat it as an honest SKIP.
        val pick = results.items.first { it.torrentId.isNotBlank() }
        val ebookBytes = try {
            download.downloadTorrentFile(pick.torrentId)
        } catch (t: Throwable) {
            assumeTrue(
                "SKIPPED: gutenberg.org content host unreachable for book ${pick.torrentId} — " +
                    "${t.javaClass.simpleName}: ${t.message}. Honest SKIP per §6.L; NOT a fake PASS.",
                false,
            )
            return@runBlocking
        }

        assertTrue(
            "Expected downloaded ebook to be non-empty for gutenberg book ${pick.torrentId}.",
            ebookBytes.isNotEmpty(),
        )

        // User-visible OUTCOME assertion: the bytes are a genuinely usable ebook,
        // not an error page or truncated stub. EPUB = zip (PK magic);
        // plain text / HTML = a substantial run of decodable UTF-8 text.
        val isEpubZip = ebookBytes.size >= 4 &&
            ebookBytes[0] == 0x50.toByte() && ebookBytes[1] == 0x4B.toByte() &&
            ebookBytes[2] == 0x03.toByte() && ebookBytes[3] == 0x04.toByte()
        val asText = ebookBytes.take(2048).toByteArray().toString(Charsets.UTF_8)
        val looksLikeText = ebookBytes.size > 1024 &&
            asText.count { it.isLetterOrDigit() || it.isWhitespace() } >= asText.length / 2
        val recognizableEbook = isEpubZip || looksLikeText
        assertTrue(
            "Downloaded payload for gutenberg book ${pick.torrentId} is not a recognizable " +
                "ebook (no EPUB zip magic, not substantial readable text; ${ebookBytes.size} bytes) — " +
                "the download surface the user needs returned something unusable.",
            recognizableEbook,
        )

        val format = if (isEpubZip) "EPUB(zip)" else "text/html"
        val evidenceFile = harness.writeEvidence(
            RealTrackerEvidence(
                provider = "gutenberg",
                baseUrl = baseUrl,
                timestampUtc = Instant.now().toString(),
                username = "<anonymous>",
                authenticated = false,
                searchQuery = query,
                searchResultCount = results.items.size,
                topicId = pick.torrentId,
                // The Gutenberg download is an EBOOK, not a `.torrent`: byte length
                // + sha256 are real download evidence; the torrent/magnet validator
                // fields stay null because those validators do not apply (see KDoc).
                torrentByteLength = ebookBytes.size,
                torrentSha256 = RealTrackerTestSupport.sha256Hex(ebookBytes),
                torrentValid = null,
                torrentInfoHash = null,
                torrentValidatorReason = "N/A: Gutenberg serves $format ebooks, not .torrent files.",
                magnetUri = null, // Gutenberg has no magnet links (getMagnetLink == null).
                magnetValid = null,
                magnetInfoHash = null,
                magnetValidatorReason = "N/A: Gutenberg has no magnet links.",
                infoHashCrossCheckMatch = null,
                notes = "Real gutendex.com/gutenberg.org crown-jewel run (anonymous); downloaded a " +
                    "real $format ebook (${ebookBytes.size} bytes) and verified its format signature. " +
                    "Torrent/magnet validators intentionally N/A for an ebook provider.",
            ),
        )
        println(
            "[gutenberg crown-jewel] real evidence written: ${evidenceFile.absolutePath} " +
                "(book=${pick.torrentId} format=$format bytes=${ebookBytes.size})",
        )
    }
}
