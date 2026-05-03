package lava.tracker.archiveorg.feature

import kotlinx.coroutines.runBlocking
import lava.tracker.archiveorg.http.ArchiveOrgHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ArchiveOrgTopicTest {

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
    fun `getTopic parses metadata and files`() = runBlocking {
        val json = """
            {
                "metadata": {
                    "title": "Test Topic",
                    "creator": "Bob",
                    "description": "A test description.",
                    "date": "2023-01-15",
                    "mediatype": "movies"
                },
                "files": [
                    {"name": "file1.mp4", "size": "12582912", "format": "MPEG4"},
                    {"name": "file2.jpg", "format": "JPEG"}
                ]
            }
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgTopic(ArchiveOrgHttpClient(), baseUrl)

        val result = feature.getTopic("test-id")

        val recordedPath = server.takeRequest().path
        assertEquals("/metadata/test-id", recordedPath)
        assertEquals("Test Topic", result.torrent.title)
        assertEquals("Bob", result.torrent.metadata["creator"])
        assertEquals("A test description.", result.description)
        assertEquals(2, result.files.size)
        assertEquals("file1.mp4", result.files[0].name)
        assertEquals(12582912L, result.files[0].sizeBytes)
        assertEquals("file2.jpg", result.files[1].name)
        assertEquals(null, result.files[1].sizeBytes)
    }

    @Test
    fun `getTopicPage returns single page`() = runBlocking {
        val json = """
            {"metadata":{"title":"Minimal"},"files":[]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))
        val baseUrl = server.url("/").toString().trimEnd('/')
        val feature = ArchiveOrgTopic(ArchiveOrgHttpClient(), baseUrl)

        val page = feature.getTopicPage("min", page = 3)

        assertEquals("Minimal", page.topic.torrent.title)
        assertEquals(1, page.totalPages)
        assertEquals(0, page.currentPage)
    }
}
