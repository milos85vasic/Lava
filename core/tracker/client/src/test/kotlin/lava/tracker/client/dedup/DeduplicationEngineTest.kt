package lava.tracker.client.dedup

import lava.tracker.api.model.TorrentItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeduplicationEngineTest {

    @Test
    fun `matches returns true for identical infoHash`() {
        val a = makeItem(infoHash = "ABC123", title = "Movie A", sizeBytes = 1_000_000)
        val b = makeItem(infoHash = "abc123", title = "Movie B", sizeBytes = 2_000_000)
        assertTrue(DeduplicationEngine.matches(a, b))
    }

    @Test
    fun `matches returns false for different infoHash`() {
        val a = makeItem(infoHash = "ABC123", title = "Movie A")
        val b = makeItem(infoHash = "DEF456", title = "Movie A")
        assertFalse(DeduplicationEngine.matches(a, b))
    }

    @Test
    fun `matches returns true for same title and size within tolerance`() {
        val a = makeItem(title = "Ubuntu 24.04", sizeBytes = 1_000_000)
        val b = makeItem(title = "Ubuntu 24.04", sizeBytes = 1_005_000)
        assertTrue(DeduplicationEngine.matches(a, b))
    }

    @Test
    fun `matches returns false for same title but size outside tolerance`() {
        val a = makeItem(title = "Ubuntu 24.04", sizeBytes = 1_000_000)
        val b = makeItem(title = "Ubuntu 24.04", sizeBytes = 2_000_000)
        assertFalse(DeduplicationEngine.matches(a, b))
    }

    @Test
    fun `matches returns true for normalized title match`() {
        val a = makeItem(title = "Ubuntu-24.04 LTS", sizeBytes = 1_000_000)
        val b = makeItem(title = "ubuntu 24.04 lts", sizeBytes = 1_000_000)
        assertTrue(DeduplicationEngine.matches(a, b))
    }

    @Test
    fun `matches falls back to title-only when both sizes null`() {
        val a = makeItem(title = "Ubuntu 24.04")
        val b = makeItem(title = "Ubuntu 24.04")
        assertTrue(DeduplicationEngine.matches(a, b))
    }

    @Test
    fun `deduplicate merges identical items across providers`() {
        val rutrackerItem = makeItem(
            trackerId = "rutracker",
            torrentId = "rt1",
            infoHash = "HASH1",
            title = "Movie",
            sizeBytes = 1_000_000,
            seeders = 10,
        )
        val nnmclubItem = makeItem(
            trackerId = "nnmclub",
            torrentId = "nm1",
            infoHash = "HASH1",
            title = "Movie",
            sizeBytes = 1_000_000,
            seeders = 20,
        )

        val result = DeduplicationEngine.deduplicate(
            mapOf(
                "rutracker" to listOf(rutrackerItem),
                "nnmclub" to listOf(nnmclubItem),
            ),
            mapOf("rutracker" to "RuTracker", "nnmclub" to "NNM-Club"),
        )

        assertEquals(1, result.size)
        val unified = result.first()
        assertEquals(2, unified.occurrences.size)
        assertEquals("rutracker", unified.primaryProvider)
    }

    @Test
    fun `deduplicate preserves distinct items`() {
        val a = makeItem(trackerId = "rutracker", torrentId = "rt1", infoHash = "HASH1", title = "Movie A")
        val b = makeItem(trackerId = "rutracker", torrentId = "rt2", infoHash = "HASH2", title = "Movie B")

        val result = DeduplicationEngine.deduplicate(
            mapOf("rutracker" to listOf(a, b)),
            mapOf("rutracker" to "RuTracker"),
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `deduplicate preserves order of first occurrence`() {
        val a = makeItem(trackerId = "rutracker", torrentId = "rt1", title = "First")
        val b = makeItem(trackerId = "nnmclub", torrentId = "nm1", title = "Second")

        val result = DeduplicationEngine.deduplicate(
            mapOf(
                "rutracker" to listOf(a),
                "nnmclub" to listOf(b),
            ),
            mapOf("rutracker" to "RuTracker", "nnmclub" to "NNM-Club"),
        )

        assertEquals("First", result[0].torrent.title)
        assertEquals("Second", result[1].torrent.title)
    }

    private fun makeItem(
        trackerId: String = "rutracker",
        torrentId: String = "t1",
        infoHash: String? = null,
        title: String = "Title",
        sizeBytes: Long? = null,
        seeders: Int? = null,
    ): TorrentItem = TorrentItem(
        trackerId = trackerId,
        torrentId = torrentId,
        title = title,
        sizeBytes = sizeBytes,
        seeders = seeders,
        infoHash = infoHash,
    )
}
