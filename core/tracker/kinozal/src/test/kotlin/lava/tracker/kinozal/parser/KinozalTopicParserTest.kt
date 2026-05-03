package lava.tracker.kinozal.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KinozalTopicParserTest {
    private val loader = LavaFixtureLoader(tracker = "kinozal")
    private val parser = KinozalTopicParser()

    @Test
    fun `parse extracts title magnet and description`() {
        val html = loader.load("topic", "topic-normal-2026-05-02.html")
        val result = parser.parse(html, topicIdHint = "12345")

        assertEquals("12345", result.torrent.torrentId)
        assertTrue(result.torrent.title.contains("Test Movie"))
        assertTrue(result.description?.contains("Description") == true)
        assertTrue(result.torrent.magnetUri?.startsWith("magnet:") == true)
    }
}
