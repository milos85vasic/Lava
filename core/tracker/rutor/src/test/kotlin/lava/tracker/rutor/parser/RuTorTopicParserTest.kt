package lava.tracker.rutor.parser

import lava.tracker.testing.LavaFixtureLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives [RuTorTopicParser] against the three `topic-*-2026-04-30.html` fixtures.
 *
 * Sixth Law clause 2 falsifiability: assertions are on user-visible state
 * (title text, sizeBytes, magnet hash, description text). A parser that
 * returns the wrong title (e.g. "rutor.info :: Ubuntu...") would fail the
 * exact-match assertion on the GamePack page.
 */
class RuTorTopicParserTest {

    private val loader = LavaFixtureLoader(tracker = "rutor")
    private val parser = RuTorTopicParser()

    @Test
    fun `topic-normal extracts title size hash and description`() {
        val html = loader.load("topic", "topic-normal-2026-04-30.html")
        val detail = parser.parse(html, topicIdHint = "1052665")

        // Sixth Law clause 2 anchor: exact title text the user sees.
        assertEquals(
            "Ubuntu GamePack 24.04 [amd64] [сентябрь] (2025) PC",
            detail.torrent.title,
        )
        assertEquals("1052665", detail.torrent.torrentId)
        // Parenthetical Bytes count: "(4567465984 Bytes)" → exact match.
        assertEquals(4_567_465_984L, detail.torrent.sizeBytes)
        // 40-char hex infoHash from magnet.
        assertEquals(
            "fb3e518132e636b798c4ae4b346b60578665e09e",
            detail.torrent.infoHash,
        )
        assertNotNull("magnet URI must be populated", detail.torrent.magnetUri)
        assertTrue(
            "magnet URI must start with magnet: — got '${detail.torrent.magnetUri}'",
            detail.torrent.magnetUri!!.startsWith("magnet:"),
        )
        // Download URL should be promoted from protocol-relative to https.
        assertEquals("https://d.rutor.info/download/1052665", detail.torrent.downloadUrl)
        // Description is non-empty.
        val description = detail.description.orEmpty()
        assertTrue(
            "description must be non-empty — got length=${description.length}",
            description.isNotEmpty(),
        )
        // Adaptation-C: file list is empty on the topic page (AJAX-loaded).
        assertEquals(0, detail.files.size)
        // Category is the category anchor text inside the "Категория" row.
        assertEquals("Софт", detail.torrent.category)
    }

    @Test
    fun `topic-with-files reports an empty file list per AJAX-loaded contract`() {
        val html = loader.load("topic", "topic-with-files-2026-04-30.html")
        val detail = parser.parse(html, topicIdHint = "1050403")

        // Even though the page declares "Файлы (1)", the topic HTML never carries the
        // resolved list — it's loaded later via /descriptions/1050403.files. The parser
        // honestly returns an empty list (Section I will hit the AJAX endpoint).
        assertEquals(0, detail.files.size)
        // Other fields are still extracted correctly.
        assertEquals(
            "Ubuntu ServerPack 24.04 [amd64] [август] (2025) PC",
            detail.torrent.title,
        )
        assertEquals(4_415_342_592L, detail.torrent.sizeBytes)
        assertNotNull("magnet must be populated even when files list is empty", detail.torrent.magnetUri)
        assertTrue(
            "description must surface the release prose",
            (detail.description ?: "").length > 100,
        )
    }

    @Test
    fun `topic-with-long-description surfaces a long description without click-to-expand text`() {
        val html = loader.load("topic", "topic-with-long-description-2026-04-30.html")
        val detail = parser.parse(html, topicIdHint = "1049192")

        val description = detail.description.orEmpty()
        assertTrue(
            "long-description fixture should produce a description > 200 chars; got ${description.length}",
            description.length > 200,
        )
        // Click-to-expand <textarea class="hidearea"> bodies must NOT appear in the visible
        // description — they are gated behind a click. The clickable <div class="hidehead">
        // label IS visible (e.g. "Преимущества при установке"), but the textarea content
        // beneath it (e.g. "Скорость и простота предустановки") is not.
        assertTrue(
            "description must not include click-to-expand textarea body — found leaked text",
            !description.contains("Скорость и простота предустановки"),
        )
        // The visible description prose IS surfaced.
        assertTrue(
            "description must contain the user-visible prose 'Дистрибутив предназначен для'",
            description.contains("Дистрибутив предназначен для"),
        )
        // Title is correct.
        assertEquals(
            "Ubuntu*Pack 24.04 LXqt / Lubuntu [amd64] [июль] (2025) PC",
            detail.torrent.title,
        )
        // Exact bytes from "(4224020480 Bytes)".
        assertEquals(4_224_020_480L, detail.torrent.sizeBytes)
    }
}
