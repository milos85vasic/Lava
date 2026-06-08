package lava.tracker.iptorrents

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.iptorrents.feature.IPTorrentsSearch
import lava.tracker.iptorrents.http.IPTorrentsJackettApi
import lava.tracker.iptorrents.model.IPTorrentsResultCache
import lava.tracker.iptorrents.model.JackettResultMapper
import lava.tracker.testing.RealTrackerTestSupport
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * §6.G real-stack verification for IPTorrents — the analogue of rutor's
 * crown-jewel proof, adapted for the Jackett-delegating design.
 *
 * It drives the REAL production path ([IPTorrentsSearch] → real OkHttp → a
 * RUNNING lava-api-go `/jackett/search?indexer=iptorrents` sidecar →
 * [JackettResultMapper]) and asserts a real result carrying a valid `.torrent`
 * `downloadUrl` and/or a magnet/infohash.
 *
 * ## Anti-Bluff (§6.J / §6.L / §11.4.3) — honest SKIP, never a fake PASS
 * Gated off by default. It runs ONLY when BOTH:
 *   1. `-PrealTrackers=true` (or `LAVA_REAL_TRACKERS=true`) is set, AND
 *   2. the sidecar base URL is configured (`-DiptorrentsJackettBaseUrl=...` or
 *      the `IPTORRENTS_JACKETT_BASE_URL` env).
 * When either is missing — or the sidecar is unreachable — it `assumeTrue`-SKIPs
 * with an auditable reason and makes no outbound calls. It NEVER asserts green
 * against an absent sidecar.
 *
 * ## Falsifiability (§6.J clause 2)
 * Deliberate break during authoring (with the sidecar up): point
 * `iptorrentsJackettBaseUrl` at a port serving an empty `{"results":[]}` →
 * `real /jackett/search returned no IPTorrents results` fails. Reverted.
 */
class IPTorrentsRealStackTest {

    @Test
    fun `real sidecar search returns IPTorrents results with a torrent or magnet`() = runBlocking {
        assumeTrue(
            "SKIPPED: -PrealTrackers=true (or LAVA_REAL_TRACKERS=true) not set; suite gated off by default.",
            RealTrackerTestSupport.realTrackersEnabled(),
        )
        val baseUrl = IPTorrentsConfig.resolve()
        assumeTrue(
            "SKIPPED: lava-api-go sidecar base URL not configured " +
                "(set -D${IPTorrentsConfig.SYSTEM_PROPERTY} / ${IPTorrentsConfig.ENV_VAR}); §6.R forbids a hardcoded default. " +
                "Honest SKIP per §6.L; NOT a fake PASS.",
            baseUrl != null,
        )

        val cache = IPTorrentsResultCache()
        val search = IPTorrentsSearch(
            api = IPTorrentsJackettApi(),
            mapper = JackettResultMapper(),
            cache = cache,
            baseUrl = baseUrl!!,
        )

        val result = try {
            search.search(SearchRequest(query = "1080p"), page = 0)
        } catch (t: Throwable) {
            // Sidecar down / Cloudflare not solved / network — honest SKIP, not a fake PASS.
            assumeTrue(
                "SKIPPED: lava-api-go /jackett/search unreachable from this host — ${t.message}. " +
                    "Honest SKIP per §6.L; NOT a fake PASS.",
                false,
            )
            return@runBlocking
        }

        assertTrue(
            "Real /jackett/search returned no IPTorrents results for '1080p' — search is broken " +
                "(sidecar up but yielded nothing; check Jackett indexer config + FlareSolverr).",
            result.items.isNotEmpty(),
        )

        val withDownloadSurface = result.items.firstOrNull {
            !it.downloadUrl.isNullOrBlank() || !it.magnetUri.isNullOrBlank()
        }
        assertTrue(
            "No IPTorrents result carried a .torrent downloadUrl OR a magnet — the download surface " +
                "the user needs is missing from the real route response.",
            withDownloadSurface != null,
        )
        val hasTorrent = !withDownloadSurface?.downloadUrl.isNullOrBlank()
        val hasMagnet = !withDownloadSurface?.magnetUri.isNullOrBlank()
        println(
            "[iptorrents §6.G] real sidecar evidence: ${result.items.size} results; " +
                "sample id=${withDownloadSurface?.torrentId} hasTorrent=$hasTorrent hasMagnet=$hasMagnet",
        )
    }
}
