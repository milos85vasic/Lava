package lava.tracker.kinozal.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KinozalSearchParserTest {
    private val loader = LavaFixtureLoader(tracker = "kinozal")
    private val parser = KinozalSearchParser()

    @Test
    fun `parse extracts rows and pagination`() {
        val html = loader.load("search", "search-normal-2026-05-02.html")
        val result = parser.parse(html, pageHint = 0)

        assertEquals(1, result.items.size)
        val item = result.items.first()
        assertEquals("12345", item.torrentId)
        assertTrue(item.title.contains("Test Movie"))
        assertEquals(0, result.currentPage)
    }
}
