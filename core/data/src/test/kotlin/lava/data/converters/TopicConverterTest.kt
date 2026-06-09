package lava.data.converters

import lava.database.entity.FavoriteTopicEntity
import lava.database.entity.VisitedTopicEntity
import lava.models.forum.Category
import lava.models.topic.Author
import lava.models.topic.BaseTopic
import lava.models.topic.Torrent
import lava.models.topic.TorrentStatus
import lava.network.dto.forum.CategoryDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral unit test for the package-internal topic converters in
 * `lava.data.converters` (Topic.kt).
 *
 * Covers the two type-discriminating branches a real user feels:
 *  - ForumTopicDto.toTopic(): a TorrentDto must become a Torrent (so the topic row
 *    shows seeds/size/magnet), while a plain TopicDto/CommentsPageDto becomes a
 *    BaseTopic (no torrent metadata).
 *  - FavoriteTopicEntity.toTopic(): a row with no torrent metadata reconstructs as
 *    BaseTopic; a row with any torrent field reconstructs as Torrent.
 *  - TorrentStatusDto.toStatus(): every wire status maps to the domain enum a user
 *    sees as the moderation badge.
 *
 * FALSIFIABILITY REHEARSAL: changed `TorrentStatusDto.Closed -> TorrentStatus.CLOSED`
 * to `-> TorrentStatus.APPROVED` in Topic.kt. The "status dto maps to domain status"
 * test FAILED with:
 *   java.lang.AssertionError: expected:<CLOSED> but was:<APPROVED>
 * Reverted; test passes.
 *
 * LVA-057 REGRESSION (added 2026-06-09): the FavoriteTopicEntity/VisitedTopicEntity
 * Torrent-vs-BaseTopic discriminator originally checked only
 * `tags/status/size/seeds/leeches == null` and IGNORED `date` and `magnetLink`. A
 * magnet-only torrent (e.g. a favorited search result with only a magnet link) was
 * persisted with its magnetLink, then read back as a BaseTopic — silently DROPPING
 * the magnet so the user could no longer download it. Fix: include `date` and
 * `magnetLink` in the discriminator for both entities.
 *
 * FALSIFIABILITY REHEARSAL (LVA-057): reverted the FavoriteTopicEntity discriminator
 * to omit `magnetLink`/`date`. The two favorite round-trip tests FAILED with:
 *   java.lang.AssertionError: a torrent with a magnet link must survive the entity round-trip as a Torrent
 *   java.lang.AssertionError: a torrent with a date must survive the entity round-trip as a Torrent
 * (the visited test stayed green, proving each assertion targets its own production
 * path). Reverted the mutation; all tests pass.
 *
 * LVA-067 (added 2026-06-09): the favorite/visited write converters thread the
 * SOURCE provider id onto the persisted row so an archiveorg/gutenberg
 * favorite/visited topic can later resolve HTTP_DOWNLOAD instead of falling back
 * to the active tracker. providerId is an explicit converter parameter (the
 * Topic/TopicPage domain models carry no provenance), defaulting null for
 * back-compat.
 *
 * FALSIFIABILITY REHEARSAL (LVA-067): replaced `providerId = providerId` with
 * `providerId = null` in `Topic.toFavoriteEntity()`'s Torrent branch. The
 * `toFavoriteEntity persists the source providerId` test FAILED with:
 *   java.lang.AssertionError: expected:<archiveorg> but was:<null>
 * (the null-default and other tests stayed green, proving the assertion targets
 * its own production path). Reverted; all tests pass.
 */
class TopicConverterTest {

    @Test
    fun `torrent dto becomes Torrent with all metadata`() {
        val dto = TorrentDto(
            id = "t1",
            title = "Big Buck Bunny",
            author = AuthorDto(id = "a1", name = "Alice"),
            category = CategoryDto(id = "c1", name = "Movies"),
            tags = "HD",
            status = TorrentStatusDto.Approved,
            date = 1234L,
            size = "700 MB",
            seeds = 42,
            leeches = 3,
            magnetLink = "magnet:?xt=foo",
        )

        val topic = dto.toTopic()

        assertTrue("torrent dto must map to Torrent", topic is Torrent)
        topic as Torrent
        assertEquals("t1", topic.id)
        assertEquals("Big Buck Bunny", topic.title)
        assertEquals(Author(id = "a1", name = "Alice"), topic.author)
        assertEquals(Category(id = "c1", name = "Movies"), topic.category)
        assertEquals("HD", topic.tags)
        assertEquals(TorrentStatus.APPROVED, topic.status)
        assertEquals(1234L, topic.date)
        assertEquals("700 MB", topic.size)
        assertEquals(42, topic.seeds)
        assertEquals(3, topic.leeches)
        assertEquals("magnet:?xt=foo", topic.magnetLink)
    }

    @Test
    fun `plain topic dto becomes BaseTopic without torrent metadata`() {
        val dto = TopicDto(id = "p1", title = "Discussion thread")

        val topic = dto.toTopic()

        assertTrue("plain topic must map to BaseTopic", topic is BaseTopic)
        assertEquals("p1", topic.id)
        assertEquals("Discussion thread", topic.title)
    }

    @Test
    fun `comments page dto becomes BaseTopic`() {
        val dto = CommentsPageDto(
            id = "cp1",
            title = "Thread with comments",
            page = 1,
            pages = 2,
            posts = emptyList(),
        )

        val topic = dto.toTopic()

        assertTrue(topic is BaseTopic)
        assertEquals("cp1", topic.id)
    }

    @Test
    fun `favorite entity without torrent fields reconstructs as BaseTopic`() {
        val entity = FavoriteTopicEntity(
            id = "f1",
            timestamp = 0L,
            title = "Bare favorite",
            author = null,
            category = null,
        )

        val topic = entity.toTopic()

        assertTrue("entity without metadata must be BaseTopic", topic is BaseTopic)
        assertEquals("f1", topic.id)
        assertEquals("Bare favorite", topic.title)
    }

    @Test
    fun `favorite entity with any torrent field reconstructs as Torrent`() {
        val entity = FavoriteTopicEntity(
            id = "f2",
            timestamp = 0L,
            title = "Seeded favorite",
            author = null,
            category = null,
            seeds = 7,
        )

        val topic = entity.toTopic()

        assertTrue("entity with metadata must be Torrent", topic is Torrent)
        assertEquals(7, (topic as Torrent).seeds)
    }

    @Test
    fun `toFavoriteEntity preserves torrent metadata for Torrent`() {
        val torrent = Torrent(
            id = "tt",
            title = "torrent",
            tags = "x",
            status = TorrentStatus.CHECKING,
            size = "1 GB",
            seeds = 5,
            leeches = 1,
            magnetLink = "magnet:?xt=bar",
        )

        val entity = torrent.toFavoriteEntity()

        assertEquals("tt", entity.id)
        assertEquals("torrent", entity.title)
        assertEquals("x", entity.tags)
        assertEquals(TorrentStatus.CHECKING, entity.status)
        assertEquals("1 GB", entity.size)
        assertEquals(5, entity.seeds)
        assertEquals(1, entity.leeches)
        assertEquals("magnet:?xt=bar", entity.magnetLink)
    }

    @Test
    fun `toFavoriteEntity for BaseTopic leaves torrent fields null`() {
        val base = BaseTopic(id = "bt", title = "base")

        val entity = base.toFavoriteEntity()

        assertEquals("bt", entity.id)
        assertEquals(null, entity.tags)
        assertEquals(null, entity.status)
        assertEquals(null, entity.seeds)
    }

    @Test
    fun `status dto maps to domain status`() {
        assertEquals(TorrentStatus.DUPLICATE, TorrentStatusDto.Duplicate.toStatus())
        assertEquals(TorrentStatus.NOT_APPROVED, TorrentStatusDto.NotApproved.toStatus())
        assertEquals(TorrentStatus.CHECKING, TorrentStatusDto.Checking.toStatus())
        assertEquals(TorrentStatus.APPROVED, TorrentStatusDto.Approved.toStatus())
        assertEquals(TorrentStatus.NEEDS_EDIT, TorrentStatusDto.NeedEdit.toStatus())
        assertEquals(TorrentStatus.CLOSED, TorrentStatusDto.Closed.toStatus())
        assertEquals(TorrentStatus.NO_DESCRIPTION, TorrentStatusDto.NoDescription.toStatus())
        assertEquals(TorrentStatus.CONSUMED, TorrentStatusDto.Consumed.toStatus())
    }

    @Test
    fun `category dto with null id throws when converted`() {
        val dto = TorrentDto(
            id = "n1",
            title = "no category id",
            category = CategoryDto(id = null, name = "Orphan"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            dto.toTopic()
        }
    }

    @Test
    fun `favorite Torrent with only magnetLink round-trips as Torrent preserving the magnet`() {
        // LVA-057: a magnet-only torrent (no tags/status/size/seeds/leeches, but a
        // magnetLink the user needs to download it) MUST reconstruct as a Torrent.
        // The discriminator that decides Torrent-vs-BaseTopic on read-back must
        // account for magnetLink, otherwise the download link is silently dropped.
        val torrent = Torrent(
            id = "magnet1",
            title = "Magnet-only release",
            magnetLink = "magnet:?xt=urn:btih:DEADBEEF",
        )

        val roundTripped = torrent.toFavoriteEntity().toTopic()

        assertTrue(
            "a torrent with a magnet link must survive the entity round-trip as a Torrent",
            roundTripped is Torrent,
        )
        assertEquals(
            "magnet:?xt=urn:btih:DEADBEEF",
            (roundTripped as Torrent).magnetLink,
        )
    }

    @Test
    fun `favorite Torrent with only date round-trips as Torrent preserving the date`() {
        // LVA-057: same discriminator bug, date branch — a torrent whose only
        // distinguishing field is its publish date must not collapse to BaseTopic.
        val torrent = Torrent(
            id = "dated1",
            title = "Dated release",
            date = 1700000000000L,
        )

        val roundTripped = torrent.toFavoriteEntity().toTopic()

        assertTrue(
            "a torrent with a date must survive the entity round-trip as a Torrent",
            roundTripped is Torrent,
        )
        assertEquals(1700000000000L, (roundTripped as Torrent).date)
    }

    @Test
    fun `visited Torrent with only magnetLink round-trips as Torrent preserving the magnet`() {
        // LVA-057: VisitedTopicEntity.toTopic() has the identical discriminator bug.
        // A visited magnet-only torrent must keep its magnet so the user can
        // re-download it from history.
        val torrent = Torrent(
            id = "vismagnet1",
            title = "Visited magnet release",
            magnetLink = "magnet:?xt=urn:btih:CAFEBABE",
        )

        // Mirror the production write path: a visited torrent is persisted via a
        // TopicPage's TorrentData. Build the entity from the Torrent's magnet by
        // converting through TorrentData-equivalent fields the entity stores.
        val entity = VisitedTopicEntity(
            id = torrent.id,
            timestamp = 0L,
            title = torrent.title,
            author = null,
            category = null,
            magnetLink = torrent.magnetLink,
        )

        val roundTripped = entity.toTopic()

        assertTrue(
            "a visited torrent with a magnet link must reconstruct as a Torrent",
            roundTripped is Torrent,
        )
        assertEquals(
            "magnet:?xt=urn:btih:CAFEBABE",
            (roundTripped as Torrent).magnetLink,
        )
    }

    @Test
    fun `toFavoriteEntity persists the source providerId`() {
        // LVA-067: a favorite written WITH a source provider id (e.g. archiveorg)
        // must persist that id on the row so the topic-screen download branch can
        // later resolve HTTP_DOWNLOAD instead of falling back to the active tracker.
        val torrent = Torrent(id = "p1", title = "Archive item")

        val entity = torrent.toFavoriteEntity(providerId = "archiveorg")

        assertEquals("archiveorg", entity.providerId)
    }

    @Test
    fun `toFavoriteEntity defaults providerId to null for back-compat`() {
        // LVA-067: omitting the source provider (favorites toggled without a known
        // provider — the current write path) writes NULL ⇒ active-tracker fallback,
        // identical to pre-LVA-067 behaviour.
        val entity = Torrent(id = "p2", title = "No provider").toFavoriteEntity()

        assertEquals(null, entity.providerId)
    }

    @Test
    fun `favorite providerId survives the entity copy round-trip`() {
        // LVA-067: the persisted providerId must read back unchanged so a favorite
        // archiveorg/gutenberg topic can be reopened with its source provider. (The
        // real Room insert+read round-trip is covered by FavoriteVisitedProviderId
        // MigrationTest; this asserts the entity field itself carries the value.)
        val entity = FavoriteTopicEntity(
            id = "rt-p",
            timestamp = 0L,
            title = "round trip",
            author = null,
            category = null,
            providerId = "gutenberg",
        )

        assertEquals("gutenberg", entity.copy().providerId)
    }

    @Test
    fun `toVisitedEntity persists the source providerId`() {
        // LVA-067: a visited topic written WITH a source provider id must persist it
        // so a history tap can reopen the topic with its provider for HTTP_DOWNLOAD.
        val page = lava.models.topic.TopicPage(
            id = "v1",
            title = "Visited archive item",
            author = null,
            category = null,
            torrentData = null,
            commentsPage = lava.models.Page(page = 1, pages = 1, items = emptyList()),
        )

        val entity = page.toVisitedEntity(providerId = "archiveorg")

        assertEquals("archiveorg", entity.providerId)
    }

    @Test
    fun `toVisitedEntity defaults providerId to null for back-compat`() {
        val page = lava.models.topic.TopicPage(
            id = "v2",
            title = "No provider",
            author = null,
            category = null,
            torrentData = null,
            commentsPage = lava.models.Page(page = 1, pages = 1, items = emptyList()),
        )

        assertEquals(null, page.toVisitedEntity().providerId)
    }

    @Test
    fun `favorite entity round-trips through topic model as favorite`() {
        val entity = FavoriteTopicEntity(
            id = "rt",
            timestamp = 0L,
            title = "round trip",
            author = null,
            category = null,
            hasUpdate = true,
        )

        val model = entity.toTopicModel()

        assertTrue(model.isFavorite)
        assertTrue(model.hasUpdate)
        assertEquals("rt", model.topic.id)
    }
}
