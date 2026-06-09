package lava.domain.usecase

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.auth.api.TokenProvider
import lava.downloads.api.DownloadRequest
import lava.downloads.api.DownloadService
import lava.network.api.NetworkApi
import lava.network.data.NetworkApiRepository
import lava.testing.TestDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for [DownloadTorrentUseCase] (the .torrent download slice).
 *
 * The SUT is a REAL [DownloadTorrentUseCase]; only the outermost boundaries are
 * faked — [NetworkApiRepository] (download URI + auth header), [TokenProvider]
 * (session token), and [DownloadService] (the OS download enqueue). None of the
 * shared :core:testing fakes implement these interfaces, so local in-memory
 * fakes are defined here.
 *
 * Primary assertions are on user-visible state:
 *  - the value the use case returns IS the download result the service produced
 *    (the path/id the user later sees), or honest null when the enqueue fails;
 *  - the request the service receives carries the correct download URI + auth
 *    header derived from the resolved token (so the right file is fetched with
 *    a valid session).
 *
 * FALSIFIABILITY REHEARSAL block at the bottom of this file.
 */
class DownloadTorrentUseCaseTest {

    private class FakeNetworkApiRepository(
        private val downloadUriById: (String) -> String,
        private val authHeaderFor: (String) -> Pair<String, String>,
    ) : NetworkApiRepository {
        override suspend fun getApi(): NetworkApi =
            throw UnsupportedOperationException("not used by DownloadTorrentUseCase")
        override suspend fun getCaptchaUrl(url: String): String =
            throw UnsupportedOperationException("not used by DownloadTorrentUseCase")
        override suspend fun getDownloadUri(id: String): String = downloadUriById(id)
        override suspend fun getAuthHeader(token: String): Pair<String, String> =
            authHeaderFor(token)
    }

    private class FakeTokenProvider(private val token: String) : TokenProvider {
        override suspend fun getToken(): String = token
        override suspend fun refreshToken(): Boolean = true
    }

    /** In-memory DownloadService — records the request and returns a canned result. */
    private class RecordingDownloadService(private val result: String?) : DownloadService {
        var lastRequest: DownloadRequest? = null
        override suspend fun downloadTorrentFile(downloadRequest: DownloadRequest): String? {
            lastRequest = downloadRequest
            return result
        }
    }

    private fun build(
        network: NetworkApiRepository,
        download: DownloadService,
        tokenProvider: TokenProvider,
    ): DownloadTorrentUseCase {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        return DownloadTorrentUseCase(network, download, tokenProvider, dispatchers)
    }

    @Test
    fun `successful enqueue returns the download result the service produced`() = runTest {
        val network = FakeNetworkApiRepository(
            downloadUriById = { id -> "https://tracker.test/dl/$id.torrent" },
            authHeaderFor = { token -> "Cookie" to "session=$token" },
        )
        val service = RecordingDownloadService(result = "/storage/downloads/55.torrent")
        val useCase = build(network, service, FakeTokenProvider("tok-xyz"))

        val result = useCase(id = "55", title = "Ubuntu ISO")

        assertEquals("/storage/downloads/55.torrent", result)
    }

    @Test
    fun `request carries the resolved download uri and token-derived auth header`() = runTest {
        val network = FakeNetworkApiRepository(
            downloadUriById = { id -> "https://tracker.test/dl/$id.torrent" },
            authHeaderFor = { token -> "Cookie" to "session=$token" },
        )
        val service = RecordingDownloadService(result = "/ok")
        val useCase = build(network, service, FakeTokenProvider("tok-xyz"))

        useCase(id = "55", title = "Ubuntu ISO")

        val sent = service.lastRequest!!
        assertEquals("55", sent.id)
        assertEquals("Ubuntu ISO", sent.title)
        assertEquals("https://tracker.test/dl/55.torrent", sent.uri)
        assertTrue(sent.headers.contains("Cookie" to "session=tok-xyz"))
    }

    @Test
    fun `failed enqueue surfaces honest null to the caller`() = runTest {
        val network = FakeNetworkApiRepository(
            downloadUriById = { "https://tracker.test/dl/x.torrent" },
            authHeaderFor = { "Cookie" to "session=anything" },
        )
        val service = RecordingDownloadService(result = null)
        val useCase = build(network, service, FakeTokenProvider("tok"))

        val result = useCase(id = "1", title = "title")

        assertNull(result)
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation A — DownloadTorrentUseCase: return a hardcoded "" instead of the
 *   downloadService result.
 *   Observed failure: `successful enqueue returns the download result...` FAILED
 *   — `expected:</storage/downloads/55.torrent> but was:<>`.
 *
 * Mutation B — DownloadTorrentUseCase: build DownloadRequest.uri from `id`
 *   directly instead of networkApiRepository.getDownloadUri(id).
 *   Observed failure: `request carries the resolved download uri...` FAILED
 *   — `expected:<https://tracker.test/dl/55.torrent> but was:<55>`.
 *
 * Both mutations reverted; suite re-run green. See agent report for verbatim output.
 */
