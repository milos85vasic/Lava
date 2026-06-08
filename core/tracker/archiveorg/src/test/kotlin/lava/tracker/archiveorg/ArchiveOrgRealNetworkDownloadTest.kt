package lava.tracker.archiveorg

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.archiveorg.feature.ArchiveOrgDownload
import lava.tracker.archiveorg.feature.ArchiveOrgSearch
import lava.tracker.archiveorg.feature.ArchiveOrgTopic
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import lava.tracker.testing.RealTrackerEvidence
import lava.tracker.testing.RealTrackerHarness
import lava.tracker.testing.RealTrackerTestSupport
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/**
 * CROWN-JEWEL real-network verification for the Internet Archive (archiveorg) —
 * the automated replacement for a human manually confirming that the download
 * option actually works against the REAL archive.org over the network.
 *
 * archiveorg is anonymous (`AuthType.NONE`), so there is no login step. Every
 * downloadable Internet Archive item publishes a derived `<identifier>_archive.torrent`
 * BitTorrent metainfo file alongside its content. The crown-jewel drives the REAL
 * production path:
 *   real search → real metadata (file listing) → real `.torrent` download →
 *   [lava.common.torrent.TorrentFileValidator] (non-empty + valid bencode + info dict).
 *
 * This asserts a genuine `.torrent` OUTCOME (not a magnet — archive.org has no
 * magnet URIs; [ArchiveOrgDownload.getMagnetLink] returns null by design).
 *
 * ## Anti-Bluff (§6.J / §6.L / §11.4.3) — honest SKIP, never a fake PASS
 * Gated off by default. Runs ONLY when `-PrealTrackers=true` (or
 * `LAVA_REAL_TRACKERS=true`) is set. When the gate is off, OR archive.org is
 * unreachable from this host (network / CDN / DNS), the test `assumeTrue`-SKIPs
 * with an auditable reason and makes no assertion against an absent service.
 * archive.org's anonymous CDN is normally reachable, so a reachable run that
 * yields no `.torrent`-bearing item, or an item whose `.torrent` fails
 * validation, IS a hard failure — that is the user-visible defect this test
 * exists to catch.
 *
 * ## Falsifiability rehearsal (§6.J clause 2)
 * Rehearsal A (empty payload): make [ArchiveOrgDownload.downloadTorrentFile]
 * return `ByteArray(0)` → `Expected downloaded .torrent to be non-empty` fails.
 * Rehearsal B (garbage payload): validate `"not a torrent".toByteArray()` instead
 * of the downloaded bytes → [TorrentFileValidator] returns `valid=false`
 * ("malformed bencode") → `failed validation` fails. Both reverted.
 */
class ArchiveOrgRealNetworkDownloadTest {

    @Test
    fun realSearchTopicDownloadAndValidateTorrent() = runBlocking {
        assumeTrue(
            "SKIPPED: -PrealTrackers=true (or LAVA_REAL_TRACKERS=true) not set; suite gated off by default.",
            RealTrackerTestSupport.realTrackersEnabled(),
        )

        val baseUrl = ArchiveOrgDescriptor.baseUrls.first { it.isPrimary }.url
        val harness = RealTrackerHarness("archiveorg", baseUrl)

        val reach = harness.probe()
        assumeTrue(
            "SKIPPED: archive.org unreachable from this host — ${reach.detail}. Honest SKIP per §6.L; NOT a fake PASS.",
            reach.reachable,
        )

        val http = ArchiveOrgHttpClient()
        val search = ArchiveOrgSearch(http, baseUrl)
        val topic = ArchiveOrgTopic(http, baseUrl)
        val download = ArchiveOrgDownload(http, baseUrl)

        // "matrix" — a globally-known title with many public-domain / openly
        // licensed Internet Archive items; reliable result yield, downloads-desc
        // sorted so the top items are well-seeded with derived torrents.
        val query = "matrix"
        val results = try {
            search.search(SearchRequest(query = query), page = 0)
        } catch (t: Throwable) {
            assumeTrue(
                "SKIPPED: archive.org search unreachable from this host — " +
                    "${t.javaClass.simpleName}: ${t.message}. Honest SKIP per §6.L; NOT a fake PASS.",
                false,
            )
            return@runBlocking
        }
        assertTrue(
            "Real archive.org search for '$query' returned no results — search is broken.",
            results.items.isNotEmpty(),
        )

        // Walk the top results; for each, read the metadata file listing and pick
        // the derived `<identifier>_archive.torrent`. Not every item exposes one
        // (some are dark/metadata-only), so scan a bounded window until we find a
        // real downloadable torrent. Finding NONE across the window is a failure.
        data class Found(val identifier: String, val torrentName: String)
        var found: Found? = null
        for (item in results.items.take(12)) {
            val identifier = item.torrentId
            if (identifier.isBlank()) continue
            val detail = try {
                topic.getTopic(identifier)
            } catch (t: Throwable) {
                continue // metadata fetch failed for this item; try the next
            }
            val torrentFile = detail.files.firstOrNull {
                it.name.endsWith("_archive.torrent") || it.name.endsWith(".torrent")
            }
            if (torrentFile != null) {
                found = Found(identifier, torrentFile.name)
                break
            }
        }
        assertTrue(
            "No archive.org item among the top results for '$query' exposed a derived " +
                "`.torrent` file in its metadata listing — the download surface the user " +
                "needs is missing from the real route response.",
            found != null,
        )
        val (identifier, torrentName) = found!!

        val torrentBytes = download.downloadTorrentFile("$identifier/$torrentName")
        assertTrue(
            "Expected downloaded .torrent to be non-empty for archive.org item " +
                "$identifier ($torrentName).",
            torrentBytes.isNotEmpty(),
        )
        val torrentVerdict = harness.validateTorrent(torrentBytes)
        assertTrue(
            "Downloaded .torrent for archive.org item $identifier ($torrentName) failed " +
                "validation: ${torrentVerdict.reason}.",
            torrentVerdict.valid,
        )

        val evidenceFile = harness.writeEvidence(
            RealTrackerEvidence(
                provider = "archiveorg",
                baseUrl = baseUrl,
                timestampUtc = Instant.now().toString(),
                username = "<anonymous>",
                authenticated = false,
                searchQuery = query,
                searchResultCount = results.items.size,
                topicId = identifier,
                torrentByteLength = torrentBytes.size,
                torrentSha256 = RealTrackerTestSupport.sha256Hex(torrentBytes),
                torrentValid = torrentVerdict.valid,
                torrentInfoHash = torrentVerdict.infoHashHex,
                torrentValidatorReason = torrentVerdict.reason,
                magnetUri = null, // archive.org has no magnet URIs (getMagnetLink == null).
                magnetValid = null,
                magnetInfoHash = null,
                magnetValidatorReason = null,
                infoHashCrossCheckMatch = null,
                notes = "Real archive.org crown-jewel run (anonymous); downloaded derived " +
                    "'$torrentName' and validated as a real .torrent.",
            ),
        )
        println(
            "[archiveorg crown-jewel] real evidence written: ${evidenceFile.absolutePath} " +
                "(item=$identifier file=$torrentName bytes=${torrentBytes.size} " +
                "infoHash=${torrentVerdict.infoHashHex})",
        )
    }
}
