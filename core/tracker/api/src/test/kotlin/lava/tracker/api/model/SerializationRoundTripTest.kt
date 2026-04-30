package lava.tracker.api.model

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializationRoundTripTest {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
    }

    @Test
    fun `TorrentItem round-trips with all fields`() {
        val original = TorrentItem(
            trackerId = "rutracker",
            torrentId = "12345",
            title = "Ubuntu 24.04",
            sizeBytes = 4_500_000_000L,
            seeders = 100,
            leechers = 5,
            infoHash = "0123456789abcdef0123456789abcdef01234567",
            magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            downloadUrl = "https://example/dl/12345",
            detailUrl = "https://example/topic/12345",
            category = "Linux",
            publishDate = Instant.fromEpochSeconds(1714430400),
            metadata = mapOf("rutracker.post.element_type" to "header"),
        )
        val encoded = json.encodeToString(TorrentItem.serializer(), original)
        val decoded = json.decodeFromString(TorrentItem.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `SearchResult round-trips with empty items`() {
        val original = SearchResult(items = emptyList(), totalPages = 0, currentPage = 0)
        val encoded = json.encodeToString(SearchResult.serializer(), original)
        val decoded = json.decodeFromString(SearchResult.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `AuthState sealed class round-trips for each variant`() {
        val variants = listOf(
            AuthState.Authenticated,
            AuthState.Unauthenticated,
            AuthState.CaptchaRequired(CaptchaChallenge("sid1", "code1", "https://x/cap.png")),
        )
        for (v in variants) {
            val encoded = json.encodeToString(AuthState.serializer(), v)
            val decoded = json.decodeFromString(AuthState.serializer(), encoded)
            assertEquals(v, decoded)
        }
    }
}
