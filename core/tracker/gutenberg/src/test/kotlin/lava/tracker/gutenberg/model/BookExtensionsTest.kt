package lava.tracker.gutenberg.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Anti-bluff unit tests for the pure mapping/selection logic in [BookExtensions].
 *
 * These exercise the REAL production functions ([Book.toTorrentItem],
 * [pickBestDownloadUrl], [bestFormatLabel], [estimateTotalPages]) and assert on
 * the exact mapped/selected values a downstream consumer (search/browse UI,
 * download flow) would receive. No mocking — the SUT is invoked directly with
 * real [Book] instances.
 */
class BookExtensionsTest {

    // --- pickBestDownloadUrl -------------------------------------------------

    @Test
    fun `pickBestDownloadUrl prefers epub over text and html`() {
        val formats = mapOf(
            "text/html" to "https://g.org/book.html",
            "text/plain" to "https://g.org/book.txt",
            "application/epub+zip" to "https://g.org/book.epub",
        )
        assertEquals("https://g.org/book.epub", pickBestDownloadUrl(formats))
    }

    @Test
    fun `pickBestDownloadUrl prefers plain text over html when no epub`() {
        val formats = mapOf(
            "text/html" to "https://g.org/book.html",
            "text/plain" to "https://g.org/book.txt",
        )
        assertEquals("https://g.org/book.txt", pickBestDownloadUrl(formats))
    }

    @Test
    fun `pickBestDownloadUrl prefers html when only html among preferred present`() {
        val formats = mapOf(
            "application/x-mobipocket-ebook" to "https://g.org/book.mobi",
            "text/html" to "https://g.org/book.html",
        )
        assertEquals("https://g.org/book.html", pickBestDownloadUrl(formats))
    }

    @Test
    fun `pickBestDownloadUrl falls back to first value when no preferred mime present`() {
        val formats = mapOf(
            "application/x-mobipocket-ebook" to "https://g.org/book.mobi",
        )
        assertEquals("https://g.org/book.mobi", pickBestDownloadUrl(formats))
    }

    @Test
    fun `pickBestDownloadUrl returns null for empty formats`() {
        assertNull(pickBestDownloadUrl(emptyMap()))
    }

    // --- bestFormatLabel -----------------------------------------------------

    @Test
    fun `bestFormatLabel reports EPUB when epub present`() {
        assertEquals("EPUB", bestFormatLabel(mapOf("application/epub+zip" to "u", "text/plain" to "u")))
    }

    @Test
    fun `bestFormatLabel reports Text when only plain text among known present`() {
        assertEquals("Text", bestFormatLabel(mapOf("text/plain" to "u", "text/html" to "u")))
    }

    @Test
    fun `bestFormatLabel reports HTML when only html among known present`() {
        assertEquals("HTML", bestFormatLabel(mapOf("text/html" to "u")))
    }

    @Test
    fun `bestFormatLabel reports Unknown when no known format present`() {
        assertEquals("Unknown", bestFormatLabel(mapOf("application/x-mobipocket-ebook" to "u")))
    }

    @Test
    fun `bestFormatLabel reports Unknown for empty formats`() {
        assertEquals("Unknown", bestFormatLabel(emptyMap()))
    }

    // --- estimateTotalPages (32 items per page) ------------------------------

    @Test
    fun `estimateTotalPages returns 1 for zero or negative counts`() {
        assertEquals(1, estimateTotalPages(0))
        assertEquals(1, estimateTotalPages(-5))
    }

    @Test
    fun `estimateTotalPages returns 1 for a partial first page`() {
        assertEquals(1, estimateTotalPages(1))
        assertEquals(1, estimateTotalPages(31))
    }

    @Test
    fun `estimateTotalPages returns 1 for an exactly full first page`() {
        assertEquals(1, estimateTotalPages(32))
    }

    @Test
    fun `estimateTotalPages rounds up partial pages`() {
        assertEquals(2, estimateTotalPages(33))
        assertEquals(2, estimateTotalPages(64))
        assertEquals(3, estimateTotalPages(65))
    }

    @Test
    fun `estimateTotalPages computes large counts exactly`() {
        // 1000 / 32 = 31.25 -> 32 pages
        assertEquals(32, estimateTotalPages(1000))
        // 3200 / 32 = 100 exactly
        assertEquals(100, estimateTotalPages(3200))
    }

    // --- Book.toTorrentItem --------------------------------------------------

    @Test
    fun `toTorrentItem maps every field a consumer reads`() {
        val book = Book(
            id = 1342,
            title = "Pride and Prejudice",
            authors = listOf(Author("Austen, Jane"), Author("Editor, Someone")),
            formats = mapOf(
                "application/epub+zip" to "https://g.org/1342.epub",
                "text/html" to "https://g.org/1342.html",
            ),
            download_count = 47000,
            subjects = listOf("Romance", "England -- Fiction"),
        )

        val item = book.toTorrentItem()

        assertEquals("gutenberg", item.trackerId)
        assertEquals("1342", item.torrentId)
        assertEquals("Pride and Prejudice", item.title)
        assertEquals("Romance", item.category)
        assertEquals("https://g.org/1342.epub", item.downloadUrl)
        assertEquals("Austen, Jane", item.metadata["creator"])
        assertEquals("EPUB", item.metadata["format"])
        assertEquals("47000", item.metadata["downloads"])
    }

    @Test
    fun `toTorrentItem yields empty creator and null category when absent`() {
        val book = Book(
            id = 7,
            title = "Anonymous Work",
            authors = emptyList(),
            formats = mapOf("text/plain" to "https://g.org/7.txt"),
            download_count = 0,
            subjects = emptyList(),
        )

        val item = book.toTorrentItem()

        assertEquals("", item.metadata["creator"])
        assertNull(item.category)
        assertEquals("https://g.org/7.txt", item.downloadUrl)
        assertEquals("Text", item.metadata["format"])
        assertEquals("0", item.metadata["downloads"])
    }

    @Test
    fun `toTorrentItem uses first author and first subject only`() {
        val book = Book(
            id = 100,
            title = "Multi",
            authors = listOf(Author("First Author"), Author("Second Author")),
            formats = mapOf("application/x-mobipocket-ebook" to "https://g.org/100.mobi"),
            download_count = 12,
            subjects = listOf("Primary Subject", "Secondary Subject"),
        )

        val item = book.toTorrentItem()

        assertEquals("First Author", item.metadata["creator"])
        assertEquals("Primary Subject", item.category)
        // No preferred mime present -> falls back to first available value.
        assertEquals("https://g.org/100.mobi", item.downloadUrl)
        assertEquals("Unknown", item.metadata["format"])
    }
}
