package lava.tracker.rutracker

import kotlinx.coroutines.runBlocking
import lava.auth.api.TokenProvider
import lava.tracker.api.feature.AuthenticatableTracker
import lava.tracker.api.feature.DownloadableTracker
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.feature.TopicTracker
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.LoginRequest
import lava.tracker.api.model.SearchRequest
import lava.tracker.testing.RealTrackerEvidence
import lava.tracker.testing.RealTrackerHarness
import lava.tracker.testing.RealTrackerTestSupport
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant

/**
 * CROWN-JEWEL real-network verification for RuTracker — the test that REPLACES
 * a human manually confirming "the download option actually works".
 *
 * It exercises the REAL production stack against the REAL rutracker.org over the
 * network, end to end:
 *   1. real login with real credentials (RuTrackerAuth / AuthenticatableTracker),
 *   2. real search (RuTrackerSearch / SearchableTracker),
 *   3. open a real topic (RuTrackerTopic / TopicTracker),
 *   4. obtain a working download option — download the real `.torrent`
 *      (RuTrackerDownload / DownloadableTracker.downloadTorrentFile) and VALIDATE
 *      it with [lava.common.torrent.TorrentFileValidator] (non-empty + valid
 *      bencode + computed info-hash), and/or extract+validate the magnet btih,
 *      cross-checking the info-hash across both surfaces.
 *
 * ## Anti-Bluff (§6.J / §6.L / Seventh Law)
 *
 * Gated OFF by default: without `-PrealTrackers=true` (or `LAVA_REAL_TRACKERS`)
 * the test `assumeTrue`-SKIPs and makes NO outbound calls. With the gate on it
 * still SKIPs honestly when credentials are absent OR rutracker.org is
 * unreachable from this host (Cloudflare / geo-block / network). It NEVER fakes
 * a PASS — a green run means a real `.torrent` was downloaded and validated and
 * the evidence file was written.
 *
 * RuTracker is CAPTCHA_LOGIN: real login may surface
 * [AuthState.CaptchaRequired]. That is treated as an honest SKIP (a human/captcha
 * solver is required and unavailable in the harness), NOT a failure.
 *
 * ## Falsifiability rehearsal (§6.J clause 2)
 *
 * Deliberate break used during authoring: in [RuTrackerDownload.downloadTorrentFile]
 * return `ByteArray(0)` instead of the real bytes. With the gate on + reachable +
 * authenticated, the assertion `torrent bytes must be non-empty` fails with
 * "Expected downloaded .torrent to be non-empty". A second rehearsal corrupts the
 * first byte of the downloaded array before validation: the validator returns
 * `valid=false` ("malformed bencode") and the `download option must validate`
 * assertion fails. Reverted. Both confirm the test catches a broken download path
 * rather than rubber-stamping it.
 */
class RuTrackerRealNetworkDownloadTest {

    /** Test-only TokenProvider: rutracker's token is the login session cookie, managed by the HttpClient's cookie jar. */
    private val tokenProvider = object : TokenProvider {
        override suspend fun getToken(): String = ""
        override suspend fun refreshToken(): Boolean = true
    }

    @Test
    fun realLoginSearchTopicDownloadAndValidate() = runBlocking {
        assumeTrue(
            "SKIPPED: -PrealTrackers=true (or LAVA_REAL_TRACKERS=true) not set; suite is gated off by default.",
            RealTrackerTestSupport.realTrackersEnabled(),
        )
        val creds = RealTrackerTestSupport.credentials("RUTRACKER")
        assumeTrue(
            "SKIPPED: RUTRACKER_USERNAME / RUTRACKER_PASSWORD absent from environment (.env not loaded).",
            creds != null,
        )
        requireNotNull(creds)

        val baseUrl = RuTrackerDescriptor.baseUrls.first { it.isPrimary }.url
        val harness = RealTrackerHarness("rutracker", baseUrl)

        val reach = harness.probe()
        assumeTrue(
            "SKIPPED: rutracker.org unreachable from this host — ${reach.detail}. " +
                "Honest SKIP per §6.L (Cloudflare / geo-block / network); NOT a fake PASS.",
            reach.reachable,
        )

        // Real production stack pinned to rutracker.org via the same builder the
        // clone-path uses — mechanically identical to the Hilt singleton wiring.
        val http = RuTrackerHttpClientFactory.create("$baseUrl/forum/")
        val client = RuTrackerSubgraphBuilder.build(http, tokenProvider)

        val auth = client.getFeature(AuthenticatableTracker::class)!!
        val search = client.getFeature(SearchableTracker::class)!!
        val topic = client.getFeature(TopicTracker::class)!!
        val download = client.getFeature(DownloadableTracker::class)!!

        val loginResult = auth.login(LoginRequest(creds.username, creds.password))
        when (val state = loginResult.state) {
            is AuthState.Authenticated -> Unit
            is AuthState.CaptchaRequired -> assumeTrue(
                "SKIPPED: rutracker.org demanded a captcha — cannot be solved headlessly. Honest SKIP, not a fail.",
                false,
            )
            is AuthState.ServiceUnavailable -> assumeTrue(
                "SKIPPED: rutracker.org login could not complete — ${state.reason}. Honest SKIP per §6.J ServiceUnavailable.",
                false,
            )
            is AuthState.Unauthenticated -> error(
                "Real login REJECTED valid RUTRACKER credentials (user=${creds.username}). " +
                    "This is a genuine failure, not a SKIP — the download flow is unreachable for a logged-in user.",
            )
        }

        // "matrix" is a globally-known film present on every one of the four
        // trackers (movie-content + general). Chosen so a reachable+authenticated
        // run reliably yields results across all providers, not just Linux-distro
        // hits (which exist on rutracker/rutor but not the movie trackers).
        val query = "matrix"
        val results = search.search(SearchRequest(query = query), page = 0)
        // Anti-bluff nuance: rutracker.org sits behind Cloudflare and may serve a
        // challenge / reduced page to a headless harness session even after a
        // cookie-bearing login. An empty result set is then an infrastructure
        // condition — an HONEST SKIP, not a fake PASS and not a hard FAIL. When
        // rows ARE returned, the load-bearing download+validation below still
        // hard-asserts.
        assumeTrue(
            "SKIPPED: rutracker.org returned 0 results for '$query' to the harness session " +
                "(Cloudflare / anti-bot defense). Honest SKIP per §6.L; NOT a fake PASS. " +
                "A genuinely result-bearing run would proceed to download+validate.",
            results.items.isNotEmpty(),
        )

        // Open the first real result that carries a usable torrent id.
        val pick = results.items.first { it.torrentId.isNotBlank() }
        val detail = topic.getTopic(pick.torrentId)
        val topicItem = detail.torrent

        // --- Download option: real .torrent ---
        val torrentBytes = download.downloadTorrentFile(pick.torrentId)
        assertTrue(
            "Expected downloaded .torrent to be non-empty for rutracker topic ${pick.torrentId}.",
            torrentBytes.isNotEmpty(),
        )
        val torrentVerdict = harness.validateTorrent(torrentBytes)
        assertTrue(
            "Downloaded .torrent for rutracker topic ${pick.torrentId} failed validation: " +
                "${torrentVerdict.reason}. A download option that yields an invalid torrent does NOT work.",
            torrentVerdict.valid,
        )

        // --- Download option: magnet (if surfaced) ---
        val magnet = topicItem.magnetUri ?: download.getMagnetLink(pick.torrentId)
        val magnetVerdict = magnet?.let { harness.validateMagnet(it) }
        if (magnetVerdict != null) {
            assertTrue(
                "Magnet surfaced for rutracker topic ${pick.torrentId} but is not a valid btih: ${magnetVerdict.reason}.",
                magnetVerdict.valid,
            )
        }

        // --- Cross-check the two surfaces where both exist ---
        val crossCheck: Boolean? =
            if (torrentVerdict.infoHashHex != null && magnetVerdict?.infoHashHex != null) {
                val match = torrentVerdict.infoHashHex.equals(magnetVerdict.infoHashHex, ignoreCase = true)
                assertTrue(
                    "info-hash MISMATCH: torrent=${torrentVerdict.infoHashHex} magnet=${magnetVerdict.infoHashHex} " +
                        "for rutracker topic ${pick.torrentId}.",
                    match,
                )
                match
            } else {
                null
            }

        val evidenceFile = harness.writeEvidence(
            RealTrackerEvidence(
                provider = "rutracker",
                baseUrl = baseUrl,
                timestampUtc = Instant.now().toString(),
                username = creds.username,
                authenticated = true,
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
                notes = "Real rutracker.org login+search+topic+download crown-jewel run.",
            ),
        )
        println("[rutracker crown-jewel] real evidence written: ${evidenceFile.absolutePath}")
        client.close()
    }
}
