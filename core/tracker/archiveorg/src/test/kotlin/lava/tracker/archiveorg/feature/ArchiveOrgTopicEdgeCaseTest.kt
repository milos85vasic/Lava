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

/**
 * Edge-case tests for [ArchiveOrgTopic] metadata JSON parsing.
 *
 * Drives the REAL feature + REAL [ArchiveOrgHttpClient] over MockWebServer.
 * Covers the /metadata/{id} shapes: missing files array, files with missing or
 * non-numeric size, missing optional metadata fields, and unknown extra fields.
 * Only the network socket is faked.
 */
class ArchiveOrgTopicEdgeCaseTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun feature(): ArchiveOrgTopic =
        ArchiveOrgTopic(ArchiveOrgHttpClient(), server.url("/").toString().trimEnd('/'))

    @Test
    fun `metadata with only a title and absent files yields empty file list and null fields`() = runBlocking {
        val json = """{"metadata":{"title":"Only Title"}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().getTopic("only-title")

        assertEquals("Only Title", result.torrent.title)
        assertNull(result.description)
        assertTrue(result.files.isEmpty())
        assertTrue(result.torrent.metadata.isEmpty())
    }

    @Test
    fun `file with non-numeric size maps to null size bytes without throwing`() = runBlocking {
        val json = """
            {"metadata":{"title":"Mixed Files"},
             "files":[
               {"name":"good.mp4","size":"1024"},
               {"name":"bad.mp4","size":"not-a-number"},
               {"name":"missing.jpg"}
             ]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val files = feature().getTopic("mixed").files

        assertEquals(3, files.size)
        assertEquals(1024L, files[0].sizeBytes)
        assertNull(files[1].sizeBytes)
        assertNull(files[2].sizeBytes)
    }

    @Test
    fun `unknown extra fields in metadata and files are ignored`() = runBlocking {
        val json = """
            {"metadata":{"title":"Has Extras","creator":"Bob","collection":["movies"],
                         "addeddate":"2020-01-01","uploader":"someone"},
             "files":[{"name":"f.mp4","size":"42","crc32":"abc","md5":"def","format":"MPEG4"}]}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().getTopic("extras")

        assertEquals("Has Extras", result.torrent.title)
        assertEquals("Bob", result.torrent.metadata["creator"])
        assertEquals(1, result.files.size)
        assertEquals(42L, result.files[0].sizeBytes)
    }

    // LVA-070 regression — archive.org returns title/creator/date/description as
    // a JSON ARRAY for multi-author / multi-volume / multi-date items. Before
    // [FlexStringSerializer], a single array-valued field threw
    // JsonDecodingException for the WHOLE /metadata response, so getTopic()
    // threw, LavaTrackerSdk.getTopicPage returned null, and the topic-detail
    // screen rendered its "Something went wrong, please try again" error state.
    // These tests pin the user-visible outcome: the page decodes and the
    // multi-value fields are flattened (joined with ", "). They are falsifiable
    // by reverting the @Serializable(with = FlexStringSerializer::class)
    // annotations on MetadataDto — getTopic then throws and the asserts never run.

    @Test
    fun `array-valued metadata fields are flattened instead of failing the whole topic decode`() = runBlocking {
        val json = """
            {"metadata":{
                "title":["The Pickwick Papers","Volume 1"],
                "creator":["Charles Dickens","John Forster"],
                "date":["1836","1837"],
                "description":["First serialised instalment.","Public domain scan."],
                "mediatype":"texts"
            }}
        """.trimIndent()
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().getTopic("pickwick")

        assertEquals("The Pickwick Papers, Volume 1", result.torrent.title)
        assertEquals("Charles Dickens, John Forster", result.torrent.metadata["creator"])
        assertEquals("1836, 1837", result.torrent.metadata["date"])
        assertEquals("First serialised instalment., Public domain scan.", result.description)
        assertEquals("texts", result.torrent.metadata["mediatype"])
    }

    @Test
    fun `single-element array metadata flattens to the bare value`() = runBlocking {
        val json = """{"metadata":{"title":["Solo Title"],"creator":["Only Author"]}}"""
        server.enqueue(MockResponse().setBody(json).setResponseCode(200))

        val result = feature().getTopic("solo")

        assertEquals("Solo Title", result.torrent.title)
        assertEquals("Only Author", result.torrent.metadata["creator"])
    }
}
