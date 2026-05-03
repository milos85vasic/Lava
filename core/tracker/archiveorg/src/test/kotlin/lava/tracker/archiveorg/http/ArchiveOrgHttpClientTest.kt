package lava.tracker.archiveorg.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArchiveOrgHttpClientTest {

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
    fun `get returns the response body produced by the server`() = runBlocking {
        server.enqueue(MockResponse().setBody("hello-archive").setResponseCode(200))
        val client = ArchiveOrgHttpClient()

        val response = client.get(server.url("/").toString())
        val body = response.body?.string()
        response.close()

        assertEquals("hello-archive", body)
    }

    @Test
    fun `download returns the binary body produced by the server`() = runBlocking {
        val bytes = byteArrayOf(0x01, 0x02, 0x03)
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)).setResponseCode(200))
        val client = ArchiveOrgHttpClient()

        val result = client.download(server.url("/file.bin").toString())

        assertTrue(result.contentEquals(bytes))
    }

    @Test
    fun `download throws on non-2xx response`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
        val client = ArchiveOrgHttpClient()

        try {
            client.download(server.url("/missing").toString())
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("failed"))
        }
    }

    @Test
    fun `json instance ignores unknown keys`() {
        val client = ArchiveOrgHttpClient()
        val json = client.json

        val parsed = json.decodeFromString(
            TestDto.serializer(),
            """{"known":"value","unknown":"ignored"}""",
        )
        assertEquals("value", parsed.known)
    }

    @Serializable
    private data class TestDto(val known: String)
}
