package lava.domain.usecase

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.downloads.api.DownloadRequest
import lava.downloads.api.DownloadService
import lava.downloads.api.HttpFileDownloadRequest
import lava.network.api.TorrentDownloadSource
import lava.testing.TestDispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for [DownloadTorrentUseCase] (the `.torrent` download slice).
 *
 * 2026-07-03 reroute + provider-aware fix (incident
 * `.lava-ci-evidence/sixth-law-incidents/2026-07-03-rutracker-kinozal-torrent-download-stall.json`):
 * the `.torrent` download used to hand a URI to the OS
 * [android.app.DownloadManager] (which fetches over the SYSTEM trust store and
 * REJECTED the goapi's self-signed cert). The reroute then fetched the bytes
 * in-app but through the WRONG route — the endpoint's rutracker-only root
 * `/download/:id` — so a Kinozal (or any non-rutracker) download fetched from
 * rutracker.org and failed. The fix routes the fetch through
 * [TorrentDownloadSource] (→ `LavaTrackerSdk.downloadTorrentFile(trackerId, id)`
 * → `/v1/{trackerId}/download/:id`), PROVIDER-AWARE with both auth gates — the
 * same trusted path [DownloadHttpFileUseCase] already uses.
 *
 * The SUT is a REAL [DownloadTorrentUseCase]; only the outermost boundaries are
 * faked — [TorrentDownloadSource] (the in-app provider-aware byte fetch) and
 * [DownloadService] (the disk persist). None of the shared :core:testing fakes
 * implement these, so local in-memory fakes are defined here.
 *
 * Primary assertions are on user-visible state:
 *  - the value the use case returns IS the saved-file result the service produced
 *    (the path/id the user later opens), or honest null when the fetch or persist
 *    fails;
 *  - the request the service receives carries the ACTUAL fetched `.torrent` bytes
 *    for the resolved trackerId + id (so the real file the user asked for is saved);
 *  - when the provider has no `.torrent` surface / the fetch fails, NOTHING is
 *    written to disk (§6.E capability honesty — never a fabricated file).
 *
 * FALSIFIABILITY REHEARSAL block at the bottom of this file.
 */
class DownloadTorrentUseCaseTest {

    /** In-memory provider-aware `.torrent` byte source (the SDK seam). */
    private class FakeTorrentDownloadSource(
        private val bytesFor: (trackerId: String, id: String) -> ByteArray?,
    ) : TorrentDownloadSource {
        var seenTrackerId: String? = null
        var seenId: String? = null
        override suspend fun downloadTorrentFile(trackerId: String, id: String): ByteArray? {
            seenTrackerId = trackerId
            seenId = id
            return bytesFor(trackerId, id)
        }
    }

    /** In-memory DownloadService — records the request and returns a canned result. */
    private class RecordingDownloadService(private val result: String?) : DownloadService {
        var lastRequest: DownloadRequest? = null
        var torrentPersistCount: Int = 0
        override suspend fun downloadTorrentFile(downloadRequest: DownloadRequest): String? {
            lastRequest = downloadRequest
            torrentPersistCount++
            return result
        }

        override suspend fun downloadHttpFile(downloadRequest: HttpFileDownloadRequest): String? =
            throw UnsupportedOperationException("not used by DownloadTorrentUseCase")
    }

    private fun build(
        source: TorrentDownloadSource,
        download: DownloadService,
    ): DownloadTorrentUseCase {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        return DownloadTorrentUseCase(source, download, dispatchers)
    }

    @Test
    fun `successful persist returns the saved-file result the service produced`() = runTest {
        val source = FakeTorrentDownloadSource(
            bytesFor = { _, id -> "d4:info$id:torrent-bytese".toByteArray() },
        )
        val service = RecordingDownloadService(result = "/storage/downloads/55.torrent")
        val useCase = build(source, service)

        val result = useCase(trackerId = "kinozal", id = "55", title = "Ubuntu ISO")

        assertEquals("/storage/downloads/55.torrent", result)
    }

    @Test
    fun `request carries the actual fetched torrent bytes for the resolved provider and id`() = runTest {
        val fetched = "d8:announce20:the-real-torrent-bodye".toByteArray()
        val source = FakeTorrentDownloadSource(bytesFor = { _, _ -> fetched })
        val service = RecordingDownloadService(result = "/ok")
        val useCase = build(source, service)

        useCase(trackerId = "kinozal", id = "55", title = "Ubuntu ISO")

        // The in-app fetch was PROVIDER-AWARE — it used the source provider id (so
        // a Kinozal download reaches Kinozal, not the rutracker-only root route)
        // and the requested id …
        assertEquals("kinozal", source.seenTrackerId)
        assertEquals("55", source.seenId)
        // … and the EXACT fetched bytes were handed to the service to persist
        // (not a URI for DownloadManager to re-fetch over the untrusted system store).
        val sent = service.lastRequest!!
        assertEquals("55", sent.id)
        assertEquals("Ubuntu ISO", sent.title)
        assertArrayEquals(fetched, sent.bytes)
    }

    @Test
    fun `failed persist surfaces honest null to the caller`() = runTest {
        val source = FakeTorrentDownloadSource(bytesFor = { _, _ -> "d4:infoe".toByteArray() })
        val service = RecordingDownloadService(result = null)
        val useCase = build(source, service)

        val result = useCase(trackerId = "rutor", id = "1", title = "title")

        assertNull(result)
    }

    @Test
    fun `null fetch surfaces honest null and never writes a fabricated file`() = runTest {
        // Provider has no `.torrent` surface OR the provider-aware fetch failed →
        // the source returns null. The user must get an honest failure, NOT a
        // 0-byte / fabricated file on disk (§6.E capability honesty).
        val source = FakeTorrentDownloadSource(bytesFor = { _, _ -> null })
        val service = RecordingDownloadService(result = "/should/not/be/reached")
        val useCase = build(source, service)

        val result = useCase(trackerId = "unknown-provider", id = "9", title = "nope")

        assertNull("a null fetch MUST surface null to the caller", result)
        assertEquals(
            "the disk writer MUST NOT be invoked when the fetch produced no bytes",
            0,
            service.torrentPersistCount,
        )
        assertTrue("the source WAS queried for the requested provider", source.seenTrackerId == "unknown-provider")
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation (RUN 2026-07-03) — DownloadTorrentUseCase: replace the provider-aware
 *   `torrentDownloadSource.downloadTorrentFile(trackerId, id)` argument with a
 *   hardcoded `""` (i.e. ignore the source trackerId, mimicking the old
 *   rutracker-only root-route fetch).
 *   Observed failure: `request carries the actual fetched torrent bytes for the
 *   resolved provider and id` FAILED — `expected:<kinozal> but was:<>` at the
 *   `assertEquals("kinozal", source.seenTrackerId)` line (proving the test catches
 *   a NON-provider-aware fetch, which is exactly the shipped bug). Reverted; suite
 *   re-run BUILD SUCCESSFUL.
 *
 * (Mutation B — build DownloadRequest.bytes = ByteArray(0) instead of the fetched
 *  bytes — fails `request carries the actual fetched torrent bytes…` on
 *  `array lengths differed, expected.length=37 actual.length=0`.)
 * (Mutation C — persist even when the source returns null (drop the `?: return`
 *  null-guard) — fails `null fetch surfaces honest null…` at
 *  `the disk writer MUST NOT be invoked… expected:<0> but was:<1>`.)
 */
