package lava.tracker.rutor

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.SearchRequest
import lava.tracker.rutor.feature.RuTorAuth
import lava.tracker.rutor.feature.RuTorDownload
import lava.tracker.rutor.feature.RuTorSearch
import lava.tracker.rutor.feature.RuTorTopic
import lava.tracker.rutor.http.RuTorHttpClient
import lava.tracker.rutor.magnet.RuTorMagnetCache
import lava.tracker.rutor.parser.RuTorLoginParser
import lava.tracker.rutor.parser.RuTorSearchParser
import lava.tracker.rutor.parser.RuTorTopicParser
import lava.tracker.testing.RealTrackerEvidence
import lava.tracker.testing.RealTrackerHarness
import lava.tracker.testing.RealTrackerTestSupport
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/**
 * CROWN-JEWEL real-network verification for RuTor — replaces a human manually
 * confirming the download option works against the REAL rutor.info over the
 * network: real login → real search → real topic → real `.torrent` download +
 * [lava.common.torrent.TorrentFileValidator] + magnet btih validation +
 * info-hash cross-check.
 *
 * RuTor permits anonymous browse/search (`supportsAnonymous = true`); login is
 * still attempted with real credentials when present and a login failure is a
 * non-fatal note (the read flow still proceeds), because the user-visible
 * "download a torrent" outcome does not require auth on rutor. A magnet that
 * fails to validate, or a `.torrent` that fails to validate, IS a hard failure.
 *
 * ## Anti-Bluff (§6.J / §6.L)
 * Gated off by default (`-PrealTrackers=true`). Honest SKIP when the gate is off
 * OR rutor.info is unreachable (Cloudflare / ipv6-only mirrors / network). Never
 * a fake PASS.
 *
 * ## Falsifiability rehearsal (§6.J clause 2)
 * Deliberate break during authoring: [RuTorDownload.downloadTorrentFile] returns
 * `ByteArray(0)` → `Expected downloaded .torrent to be non-empty` fails. Second
 * rehearsal: flip a byte in the downloaded array before validation → validator
 * `valid=false` ("malformed bencode") → `download option must validate` fails.
 * Reverted.
 */
class RuTorRealNetworkDownloadTest {

    @Test
    fun realSearchTopicDownloadAndValidate() = runBlocking {
        assumeTrue(
            "SKIPPED: -PrealTrackers=true (or LAVA_REAL_TRACKERS=true) not set; suite is gated off by default.",
            RealTrackerTestSupport.realTrackersEnabled(),
        )

        val baseUrl = RuTorDescriptor.baseUrls.first { it.isPrimary }.url
        val harness = RealTrackerHarness("rutor", baseUrl)

        val reach = harness.probe()
        assumeTrue(
            "SKIPPED: rutor.info unreachable from this host — ${reach.detail}. Honest SKIP per §6.L; NOT a fake PASS.",
            reach.reachable,
        )

        val http = RuTorHttpClient()
        val magnetCache = RuTorMagnetCache()
        val search = RuTorSearch(http, RuTorSearchParser(), magnetCache, baseUrl)
        val topic = RuTorTopic(http, RuTorTopicParser(), magnetCache, baseUrl)
        val auth = RuTorAuth(http, RuTorLoginParser(), baseUrl)
        val download = RuTorDownload(http, magnetCache)

        // Login is optional for rutor (anonymous browse/search). Attempt it when
        // creds exist so the crown-jewel exercises the AuthenticatableTracker path,
        // but a failure is a note, not a SKIP/fail — read flow continues.
        val creds = RealTrackerTestSupport.credentials("RUTOR")
        var authenticated = false
        var authNote = "anonymous (no RUTOR_* credentials provided)"
        if (creds != null) {
            authNote = when (val state = auth.login(LoginRequest(creds.username, creds.password)).state) {
                is AuthState.Authenticated -> {
                    authenticated = true
                    "authenticated"
                }
                is AuthState.ServiceUnavailable -> "login ServiceUnavailable: ${state.reason} (continued anonymously)"
                is AuthState.CaptchaRequired -> "login captcha required (continued anonymously)"
                is AuthState.Unauthenticated -> "login rejected (continued anonymously per rutor's anonymous policy)"
            }
        }

        // "matrix" — a globally-known film present on all four trackers; reliable
        // result yield on the movie trackers as well as rutracker/rutor.
        val query = "matrix"
        val results = search.search(SearchRequest(query = query), page = 0)
        assertTrue(
            "Real rutor.info search for '$query' returned no results — search is broken.",
            results.items.isNotEmpty(),
        )

        val pick = results.items.first { it.torrentId.isNotBlank() }
        val detail = topic.getTopic(pick.torrentId)
        val topicItem = detail.torrent

        val torrentBytes = download.downloadTorrentFile(pick.torrentId)
        assertTrue(
            "Expected downloaded .torrent to be non-empty for rutor topic ${pick.torrentId}.",
            torrentBytes.isNotEmpty(),
        )
        val torrentVerdict = harness.validateTorrent(torrentBytes)
        assertTrue(
            "Downloaded .torrent for rutor topic ${pick.torrentId} failed validation: ${torrentVerdict.reason}.",
            torrentVerdict.valid,
        )

        val magnet = topicItem.magnetUri ?: download.getMagnetLink(pick.torrentId)
        val magnetVerdict = magnet?.let { harness.validateMagnet(it) }
        if (magnetVerdict != null) {
            assertTrue(
                "Magnet surfaced for rutor topic ${pick.torrentId} but is not a valid btih: ${magnetVerdict.reason}.",
                magnetVerdict.valid,
            )
        }

        val crossCheck: Boolean? =
            if (torrentVerdict.infoHashHex != null && magnetVerdict?.infoHashHex != null) {
                val match = torrentVerdict.infoHashHex.equals(magnetVerdict.infoHashHex, ignoreCase = true)
                assertTrue(
                    "info-hash MISMATCH: torrent=${torrentVerdict.infoHashHex} magnet=${magnetVerdict.infoHashHex} " +
                        "for rutor topic ${pick.torrentId}.",
                    match,
                )
                match
            } else {
                null
            }

        val evidenceFile = harness.writeEvidence(
            RealTrackerEvidence(
                provider = "rutor",
                baseUrl = baseUrl,
                timestampUtc = Instant.now().toString(),
                username = creds?.username ?: "<anonymous>",
                authenticated = authenticated,
                searchQuery = query,
                searchResultCount = results.items.size,
                topicId = pick.torrentId,
                torrentByteLength = torrentBytes.size,
                torrentSha256 = RealTrackerTestSupport.sha256Hex(torrentBytes),
                torrentValid = torrentVerdict.valid,
                torrentInfoHash = torrentVerdict.infoHashHex,
                torrentValidatorReason = torrentVerdict.reason,
                magnetUri = magnet,
                magnetValid = magnetVerdict?.valid,
                magnetInfoHash = magnetVerdict?.infoHashHex,
                magnetValidatorReason = magnetVerdict?.reason,
                infoHashCrossCheckMatch = crossCheck,
                notes = "Real rutor.info crown-jewel run; auth: $authNote.",
            ),
        )
        println("[rutor crown-jewel] real evidence written: ${evidenceFile.absolutePath}")
    }
}
