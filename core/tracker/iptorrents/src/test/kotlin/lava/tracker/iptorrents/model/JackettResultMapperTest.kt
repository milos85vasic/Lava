package lava.tracker.iptorrents.model

import lava.tracker.iptorrents.IPTorrentsDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [JackettResultMapper] — the lava-api-go `/jackett/search`
 * JSON → SDK [lava.tracker.api.model.SearchResult] mapper.
 *
 * The delegation + magnet-cache tests exercise the mapper only THROUGH the full
 * HTTP path against one populated fixture. None asserts, in isolation, on:
 *   (a) the 1-based-route → 0-based-SDK pagination (`currentPage = page - 1`);
 *   (b) an empty `results` → empty item list that still preserves pagination;
 *   (c) the blank-string → null normalisation of infoHash / magnetLink /
 *       downloadUrl / category (the `takeIf { it.isNotBlank() }` branches).
 *
 * SUT = the real [JackettResultMapper]; input is the exact JSON wire format
 * lava-api-go's `GET /jackett/search` emits. Nothing mocked.
 *
 * FALSIFIABILITY REHEARSAL (§6.J clause 2):
 *   A — `currentPage = dto.page - 1` → `dto.page`: pagination test FAILS.
 *   B — drop `?.takeIf { it.isNotBlank() }` on magnetUri: blank-null test FAILS.
 *   C — `dto.results.map {…}` → `emptyList()`: populated-results test FAILS.
 */
class JackettResultMapperTest {

    private val mapper = JackettResultMapper()
    private val trackerId = IPTorrentsDescriptor.trackerId

    @Test
    fun `empty results map to an empty item list while preserving pagination`() {
        val body = """
            {"provider":"jackett","page":1,"totalPages":1,"results":[]}
        """.trimIndent()
        val result = mapper.map(trackerId, body)
        assertTrue("empty results must yield no items", result.items.isEmpty())
        assertEquals("totalPages threaded through", 1, result.totalPages)
        assertEquals("page 1 (1-based) becomes 0 (0-based)", 0, result.currentPage)
    }

    @Test
    fun `pagination converts the route's 1-based page to the SDK 0-based page`() {
        val body = """
            {
              "provider":"jackett","page":2,"totalPages":5,
              "results":[
                {"id":"https://iptorrents.com/details.php?id=42","title":"Fedora 41 Workstation",
                 "sizeBytes":2147483648,"seeders":9,"leechers":1,"category":"iptorrents",
                 "magnetLink":"magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                 "infoHash":"0123456789abcdef0123456789abcdef01234567"}
              ]
            }
        """.trimIndent()
        val result = mapper.map(trackerId, body)
        assertEquals("page 2 (1-based) becomes 1 (0-based)", 1, result.currentPage)
        assertEquals("totalPages passed through unchanged", 5, result.totalPages)
        assertEquals(1, result.items.size)
        val item = result.items.single()
        assertEquals(trackerId, item.trackerId)
        assertEquals("https://iptorrents.com/details.php?id=42", item.torrentId)
        assertEquals("Fedora 41 Workstation", item.title)
        assertEquals(java.lang.Long.valueOf(2_147_483_648L), item.sizeBytes)
        assertEquals(9, item.seeders)
        assertEquals(1, item.leechers)
        assertEquals("0123456789abcdef0123456789abcdef01234567", item.infoHash)
        assertTrue(
            "magnet must be the btih magnet; was ${item.magnetUri}",
            item.magnetUri!!.startsWith("magnet:?xt=urn:btih:0123456789abcdef"),
        )
    }

    @Test
    fun `blank string download surfaces normalise to null (no broken affordances)`() {
        val body = """
            {
              "provider":"jackett","page":1,"totalPages":1,
              "results":[
                {"id":"https://iptorrents.com/details.php?id=1","title":"Blank surfaces row",
                 "sizeBytes":1073741824,"seeders":4,"category":"","downloadUrl":"","magnetLink":"","infoHash":""},
                {"id":"https://iptorrents.com/details.php?id=2","title":"Populated row",
                 "sizeBytes":658505728,"seeders":12,"category":"iptorrents",
                 "downloadUrl":"https://localhost:8443/jackett/dl/iptorrents/?file=x.torrent",
                 "magnetLink":"magnet:?xt=urn:btih:1f129d8c4b8e2b3a4c5d6e7f8091a2b3c4d5e6f7",
                 "infoHash":"1f129d8c4b8e2b3a4c5d6e7f8091a2b3c4d5e6f7"}
              ]
            }
        """.trimIndent()
        val result = mapper.map(trackerId, body)
        assertEquals(2, result.items.size)
        val blank = result.items.first { it.torrentId.endsWith("id=1") }
        assertNull("blank magnetLink must normalise to null", blank.magnetUri)
        assertNull("blank downloadUrl must normalise to null", blank.downloadUrl)
        assertNull("blank infoHash must normalise to null", blank.infoHash)
        assertNull("blank category must normalise to null", blank.category)
        assertEquals("Blank surfaces row", blank.title)
        assertEquals(java.lang.Long.valueOf(1_073_741_824L), blank.sizeBytes)
        val populated = result.items.first { it.torrentId.endsWith("id=2") }
        assertEquals("iptorrents", populated.category)
        assertTrue(
            "populated row keeps its magnet; was ${populated.magnetUri}",
            populated.magnetUri!!.startsWith("magnet:?xt=urn:btih:1f129d8c"),
        )
        assertTrue(
            "populated row keeps its /dl/ link; was ${populated.downloadUrl}",
            populated.downloadUrl!!.contains("/jackett/dl/"),
        )
    }
}
