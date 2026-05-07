package lava.network.sse

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SseClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: SseClient

    @Before
    fun setUp() {
        server = MockWebServer()
        client = SseClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses SSE events correctly`() = runBlocking {
        val sseBody = """
            event: provider_start
            data: {"provider_id":"test1","display_name":"Test One"}

            event: results
            data: {"provider_id":"test1","items":[{"id":"1","title":"Item One"}],"page":1,"total_pages":1}

            event: provider_done
            data: {"provider_id":"test1","result_count":1}

            event: stream_end
            data: {"providers_searched":1,"total_results":1}

        """.trimIndent() + "\n"

        server.enqueue(
            MockResponse()
                .setBody(sseBody)
                .setHeader("Content-Type", "text/event-stream"),
        )

        val events = client.connect(server.url("/v1/search?q=test").toString()).toList()

        assertEquals(4, events.size)
        assertTrue(events[0] is SseEvent.Event && (events[0] as SseEvent.Event).type == "provider_start")
        assertTrue(events[1] is SseEvent.Event && (events[1] as SseEvent.Event).type == "results")
        assertTrue(events[2] is SseEvent.Event && (events[2] as SseEvent.Event).type == "provider_done")
        assertTrue(events[3] is SseEvent.StreamEnd)
    }

    @Test
    fun `emits error on HTTP failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))

        val events = client.connect(server.url("/v1/search?q=test").toString()).toList()

        assertEquals(1, events.size)
        assertTrue(events[0] is SseEvent.Error)
        val error = events[0] as SseEvent.Error
        assertTrue("Expected 500 in error message, got: ${error.message}", error.message.contains("500"))
    }

    @Test
    fun `emits error on connection failure`() = runBlocking {
        server.shutdown()

        val events = client.connect("http://localhost:1/v1/search?q=test").toList()

        assertTrue(events.isNotEmpty())
        assertTrue(events.first() is SseEvent.Error)
    }
}
