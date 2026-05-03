package lava.tracker.nnmclub.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Test

class NnmclubBrowseParserTest {

    private val loader = LavaFixtureLoader(tracker = "nnmclub")
    private val parser = NnmclubBrowseParser()

    @Test
    fun `parse normal browse page returns expected items`() {
        val html = loader.load("browse", "browse-normal-2026-05-02.html")
        val result = parser.parse(html, pageHint = 0)

        assertEquals("expected 1 item", 1, result.items.size)

        val first = result.items[0]
        assertEquals("2001", first.torrentId)
        assertEquals("Movie A", first.title)
        assertEquals(45, first.seeders)
    }
}
