package lava.tracker.archiveorg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchiveOrgDownloadTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloadTorrentFile fetches binary from download endpoint`() = runBlocking {
        val bytes = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgDownload(ArchiveOrgHttpClient(), baseUrl)

        val result = feature.downloadTorrentFile("item-1/file.bin")

        val recordedPath = server.takeRequest().path
        assertEquals("/download/item-1/file.bin", recordedPath)
        assertTrue(result.contentEquals(bytes))
    }

    @Test
    fun `getMagnetLink returns null`() {
        val feature = ArchiveOrgDownload(ArchiveOrgHttpClient(), "http://localhost")
        assertNull(feature.getMagnetLink("any-id"))
    }

    // LVA-070 regression — a topic opened from an archive.org search result
    // carries the BARE identifier (no "/filename"). The download button MUST
    // resolve a real file and complete, NOT throw → DownloadState.Error (the
    // on-device §6.L keystone finding 2026-06-30: tapping "Torrent" on an
    // archiveorg topic surfaced an error and never a downloaded file).
    // Falsifiable by reverting downloadHttpFile to the strict
    // `require(parts.size == 2 ...)` — the bare-identifier tests then throw and
    // never reach their download assertions.

    @Test
    fun `bare identifier resolves the item torrent and downloads it`() = runBlocking {
        val torrentBytes = byteArrayOf(0x64, 0x38, 0x3a, 0x61) // bencode-ish
        // 1st request: /metadata/{id} → files incl. the auto-generated torrent.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"files":[
                  {"name":"item-1_meta.xml","size":"681"},
                  {"name":"item-1_archive.torrent","size":"2045"},
                  {"name":"BigMedia.mp4","size":"8852069"}
                ]}
                """.trimIndent(),
            ),
        )
        // 2nd request: /download/{id}/{torrent}.
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(torrentBytes)))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgDownload(ArchiveOrgHttpClient(), baseUrl)

        val result = feature.downloadHttpFile("item-1")

        assertEquals("/metadata/item-1", server.takeRequest().path)
        assertEquals("/download/item-1/item-1_archive.torrent", server.takeRequest().path)
        assertEquals("item-1_archive.torrent", result.fileName)
        assertTrue(result.bytes.contentEquals(torrentBytes))
    }

    @Test
    fun `bare identifier falls back to the smallest file when no torrent present`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"name":"big.iso","size":"999999"},{"name":"small.txt","size":"12"}]}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgDownload(ArchiveOrgHttpClient(), baseUrl)

        val result = feature.downloadHttpFile("item-2")

        server.takeRequest() // metadata
        assertEquals("/download/item-2/small.txt", server.takeRequest().path)
        assertEquals("small.txt", result.fileName)
    }

    @Test
    fun `explicit identifier-slash-filename downloads that exact file without metadata lookup`() = runBlocking {
        val bytes = byteArrayOf(0x01, 0x02)
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes)))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgDownload(ArchiveOrgHttpClient(), baseUrl)

        val result = feature.downloadHttpFile("item-3/exact.bin")

        assertEquals("/download/item-3/exact.bin", server.takeRequest().path)
        assertEquals("exact.bin", result.fileName)
        assertTrue(result.bytes.contentEquals(bytes))
    }

    @Test
    fun `blank id throws`() = runBlocking {
        val feature = ArchiveOrgDownload(ArchiveOrgHttpClient(), "http://localhost")
        try {
            feature.downloadHttpFile("")
            throw AssertionError("Expected IllegalArgumentException for blank id")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("identifier"))
        }
    }
}
