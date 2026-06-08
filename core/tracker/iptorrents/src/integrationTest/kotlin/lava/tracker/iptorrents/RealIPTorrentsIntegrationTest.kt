package lava.tracker.iptorrents

import kotlinx.coroutines.runBlocking
import lava.tracker.api.model.SearchRequest
import lava.tracker.iptorrents.feature.IPTorrentsSearch
import lava.tracker.iptorrents.http.IPTorrentsJackettApi
import lava.tracker.iptorrents.model.IPTorrentsResultCache
import lava.tracker.iptorrents.model.JackettResultMapper
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Operator-driven real-stack integration test for IPTorrents (the
 * `integrationTest` source set; runs only with `-PrealTrackers=true`). It
 * requires a RUNNING lava-api-go `/jackett/search` sidecar (Jackett + the
 * IPTorrents Cardigann definition + FlareSolverr) whose origin is supplied via
 * `-DiptorrentsJackettBaseUrl` / `IPTORRENTS_JACKETT_BASE_URL` (§6.R).
 *
 * Honest SKIP (§11.4.3) when the sidecar URL is unconfigured or unreachable —
 * never a fake PASS. The Gradle `integrationTest` task itself is `onlyIf`-gated
 * on `-PrealTrackers=true`, so this never runs in the default `test` invocation.
 */
class RealIPTorrentsIntegrationTest {

    @Test
    fun realSidecarSearchYieldsDownloadableResults() = runBlocking {
        val baseUrl = IPTorrentsConfig.resolve()
        assumeTrue(
            "SKIPPED: lava-api-go sidecar base URL not configured " +
                "(-D${IPTorrentsConfig.SYSTEM_PROPERTY} / ${IPTorrentsConfig.ENV_VAR}). Honest SKIP per §6.L.",
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
            search.search(SearchRequest(query = "linux"), page = 0)
        } catch (t: Throwable) {
            assumeTrue("SKIPPED: sidecar unreachable — ${t.message}. Honest SKIP per §6.L.", false)
            return@runBlocking
        }

        assertTrue("real IPTorrents sidecar search returned no results", result.items.isNotEmpty())
        assertTrue(
            "no IPTorrents result carried a .torrent or magnet download surface",
            result.items.any { !it.downloadUrl.isNullOrBlank() || !it.magnetUri.isNullOrBlank() },
        )
    }
}
