package lava.data.converters

import lava.database.entity.FavoriteTopicEntity
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
