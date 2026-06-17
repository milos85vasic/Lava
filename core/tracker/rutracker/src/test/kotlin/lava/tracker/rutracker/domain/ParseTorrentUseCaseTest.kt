package lava.tracker.rutracker.domain

import lava.network.dto.topic.TorrentStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Anti-bluff unit tests for [ParseTorrentUseCase] — the rutracker.org topic-page
 * HTML -> [lava.network.dto.topic.TorrentDto] parser. Every field asserted here is
 * user-visible on the Topic screen: the title, the `[...]` tags chip, seeds/leeches
 * counts, the human-readable size, the category, the uploader, and the moderation
 * status badge.
 *
 * SUT is the REAL `ParseTorrentUseCase` object (it is a stateless `object`, so there
 * is nothing to mock — the production Jsoup parse path runs verbatim). The only inputs
 * are inline HTML string fixtures shaped like the real rutracker topic markup the
 * parser's CSS selectors target; no network, no mocks.
 *
 * Falsifiability rehearsal (Sixth Law clause 2 / §6.J): documented per-assertion in
 * the test bodies. The load-bearing branch is the logged-in vs. anonymous SIZE split
 * in `ParseTorrentUseCase.invoke` (lines reading `#tor-size-humn` when the page
 * contains `logged-in-username`, otherwise `.attach_link > ul > li:nth-child(2)`).
 * Breaking that branch — e.g. always reading `#tor-size-humn` — makes the anonymous
 * case return the empty string instead of the size a not-logged-in user sees, which
 * `anonymous user reads size from attach_link` asserts against. Rehearsal evidence
 * captured in the agent task report.
 */
class ParseTorrentUseCaseTest {

    @Test
    fun `parses user-visible torrent fields from a logged-in topic page`() {
        val dto = ParseTorrentUseCase(LOGGED_IN_HTML)

        // id comes from the `t` query param on the #topic-title link.
        assertEquals("123456", dto.id)
        // getTitle strips ALL bracketed tag groups + trims; getTags collects them.
        assertEquals("Some Great Movie 2024 1080p", dto.title)
        assertEquals("[Movies] [BluRay] ", dto.tags)
        // Category from the last breadcrumb anchor (id from `f` query param, name from text).
        assertEquals("2200", dto.category?.id)
        assertEquals("HD Video", dto.category?.name)
        // Author from .nick text + the poster button `u` query param.
        assertEquals("uploader_bob", dto.author?.name)
        assertEquals("777", dto.author?.id)
        // seeds / leeches integer extraction from .seed > b / .leech > b.
        assertEquals(345, dto.seeds)
        assertEquals(6, dto.leeches)
        // Logged-in branch reads the humanized size node.
        assertEquals("8.42 GB", dto.size)
        // Magnet link the "open in torrent client" button copies.
        assertEquals("magnet:?xt=urn:btih:DEADBEEF", dto.magnetLink)
        // Status badge: .tor-approved class -> Approved.
        assertEquals(TorrentStatusDto.Approved, dto.status)
    }

    @Test
    fun `anonymous user reads size from attach_link not tor-size-humn`() {
        // No `logged-in-username` token anywhere in the page -> the anonymous size branch.
        val dto = ParseTorrentUseCase(ANONYMOUS_HTML)

        // The user-visible size for an anonymous visitor comes from the attach_link
        // list, NOT #tor-size-humn (which is absent on the not-logged-in page).
        assertEquals("4.7 GB", dto.size)
        assertEquals("999", dto.id)
        assertEquals(10, dto.seeds)
        assertEquals(2, dto.leeches)
    }

    @Test
    fun `missing seeds leeches and status degrade to null instead of crashing`() {
        val dto = ParseTorrentUseCase(MINIMAL_HTML)

        // No .seed/.leech nodes -> toIntOrNull yields null (count hidden on screen).
        assertNull(dto.seeds)
        assertNull(dto.leeches)
        // No #tor-status-resp element -> no status badge.
        assertNull(dto.status)
        // Title still parses with no bracketed tags; getTags yields empty string.
        assertEquals("Plain Title No Tags", dto.title)
        assertEquals("", dto.tags)
    }

    @Test
    fun `unrecognized status class yields null status`() {
        val dto = ParseTorrentUseCase(UNKNOWN_STATUS_HTML)

        // #tor-status-resp present but with a class ParseTorrentStatusUseCase does not
        // recognize -> null (no badge), not a wrong/incorrect badge.
        assertNull(dto.status)
    }

    private companion object {
        // Logged-in topic page: contains the `logged-in-username` token AND #tor-size-humn.
        val LOGGED_IN_HTML = """
            <html><body class="logged-in-username">
              <a id="topic-title" href="viewtopic.php?t=123456">[Movies] Some Great Movie 2024 1080p [BluRay]</a>
              <div class="nav w100 pad_2">
                <a href="index.php">Home</a>
                <a href="viewforum.php?f=2200">HD Video</a>
              </div>
              <span class="nick">uploader_bob</span>
              <span class="poster_btn"><a class="txtb" href="profile.php?mode=viewprofile&u=777">profile</a></span>
              <table class="forumline dl_list"><tbody><tr>
                <td class="seed"><b>345</b></td>
                <td class="leech"><b>6</b></td>
              </tr></tbody></table>
              <div id="tor-status-resp"><span class="tor-approved">approved</span></div>
              <span id="tor-size-humn">8.42 GB</span>
              <a class="magnet-link" href="magnet:?xt=urn:btih:DEADBEEF">magnet</a>
            </body></html>
        """.trimIndent()

        // Anonymous topic page: NO `logged-in-username`, NO #tor-size-humn; size in attach_link.
        val ANONYMOUS_HTML = """
            <html><body>
              <a id="topic-title" href="viewtopic.php?t=999">Anonymous Release 720p</a>
              <div class="nav w100 pad_2">
                <a href="viewforum.php?f=10">Video</a>
              </div>
              <table class="forumline dl_list"><tbody><tr>
                <td class="seed"><b>10</b></td>
                <td class="leech"><b>2</b></td>
              </tr></tbody></table>
              <div class="attach_link">
                <ul>
                  <li>filename.torrent</li>
                  <li>4.7 GB</li>
                </ul>
              </div>
              <a class="magnet-link" href="magnet:?xt=urn:btih:CAFE">magnet</a>
            </body></html>
        """.trimIndent()

        // Minimal page: no seeds/leeches/status nodes, plain title.
        val MINIMAL_HTML = """
            <html><body>
              <a id="topic-title" href="viewtopic.php?t=1">Plain Title No Tags</a>
              <div class="nav w100 pad_2"><a href="viewforum.php?f=1">Cat</a></div>
              <table class="forumline dl_list"><tbody><tr></tr></tbody></table>
              <a class="magnet-link" href="magnet:?xt=urn:btih:0">magnet</a>
            </body></html>
        """.trimIndent()

        // Status element present but with an unrecognized class.
        val UNKNOWN_STATUS_HTML = """
            <html><body>
              <a id="topic-title" href="viewtopic.php?t=2">Title</a>
              <div class="nav w100 pad_2"><a href="viewforum.php?f=2">Cat</a></div>
              <table class="forumline dl_list"><tbody><tr></tr></tbody></table>
              <div id="tor-status-resp"><span class="tor-some-future-state">???</span></div>
              <a class="magnet-link" href="magnet:?xt=urn:btih:1">magnet</a>
            </body></html>
        """.trimIndent()
    }
}
