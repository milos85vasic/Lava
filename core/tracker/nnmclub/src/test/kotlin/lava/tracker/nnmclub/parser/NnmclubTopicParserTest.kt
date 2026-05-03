package lava.tracker.nnmclub.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NnmclubTopicParserTest {

    private val loader = LavaFixtureLoader(tracker = "nnmclub")
    private val parser = NnmclubTopicParser()

    @Test
    fun `parse normal topic page returns expected detail`() {
        val html = loader.load("topic", "topic-normal-2026-05-02.html")
        val result = parser.parse(html, topicIdHint = "1001")

        assertEquals("1001", result.torrent.torrentId)
        assertEquals("Ubuntu 24.04 LTS", result.torrent.title)
        assertNotNull(result.description)
        assertTrue("magnet should be present", !result.torrent.magnetUri.isNullOrEmpty())
        assertTrue("download should be present", !result.torrent.downloadUrl.isNullOrEmpty())
    }
}
