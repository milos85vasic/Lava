package lava.testing.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.Page
import lava.models.topic.Topic
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import lava.models.topic.TorrentData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral-equivalence guard for [TestVisitedRepository] (Anti-Bluff Pact
 * Third Law, LVA-012). Pins the fake to the REAL `VisitedRepositoryImpl`
 * semantics (Room `VisitedTopicDao`, insert = UPSERT-by-id, `timestamp DESC`):
 *
 *  - observe emits live on add;
 *  - add UPSERTS by id (no duplicate row) and moves the topic to newest;
 *  - torrentData drives the Torrent-vs-BaseTopic branch (mirrors
 *    `VisitedTopicEntity.toTopic`);
 *  - clear empties.
 *
 * Bluff-Audit (Sixth Law 6.A / Seventh Law clause 1):
 *   Mutation: in TestVisitedRepository.add, drop the dedup filter so the body is
 *             `topicsFlow.value = listOf(converted) + topicsFlow.value`.
 *   Observed-Failure: `observe_emits_live_and_add_upserts_by_id` fails —
 *     "add of an existing id MUST upsert (one row) expected:<1> but was:<2>".
 *   Reverted: yes.
 */
class TestVisitedRepositoryEquivalenceTest {

    private fun page(id: String, title: String, torrentData: TorrentData? = null): TopicPage =
        TopicPage(
            id = id,
            title = title,
            author = null,
            category = null,
            torrentData = torrentData,
            commentsPage = Page(items = emptyList(), page = 1, pages = 1),
        )

    @Test
    fun observe_emits_live_and_add_upserts_by_id() = runTest {
        val repo = TestVisitedRepository()

        assertEquals("fresh repository observes empty", emptyList<Topic>(), repo.observeTopics().first())

        repo.add(page("1", "Alpha"))
        repo.add(page("2", "Beta"))

        // Live emission + newest-first ordering (Beta added last → first).
        assertEquals(listOf("2", "1"), repo.observeTopics().first().map(Topic::id))
        assertEquals(listOf("2", "1"), repo.observeIds().first())

        // Re-add an existing id → UPSERT (one row), moved to newest, title refreshed.
        repo.add(page("1", "Alpha v2"))
        val topics = repo.observeTopics().first()
        assertEquals("add of an existing id MUST upsert (one row)", 2, topics.size)
        assertEquals("re-added id moves to newest", listOf("1", "2"), topics.map(Topic::id))
        assertEquals("re-added title is refreshed", "Alpha v2", topics.first().title)
    }

    @Test
    fun add_with_torrent_data_yields_torrent_else_base_topic() = runTest {
        val repo = TestVisitedRepository()

        repo.add(page("plain", "Plain"))
        repo.add(
            page(
                "torrent",
                "With seeds",
                TorrentData(
                    tags = null,
                    posterUrl = null,
                    status = null,
                    date = null,
                    size = "1 GB",
                    seeds = 5,
                    leeches = 1,
                    magnetLink = "magnet:?xt=1",
                ),
            ),
        )

        val byId = repo.observeTopics().first().associateBy(Topic::id)
        assertTrue("topic with no torrent fields is a BaseTopic", byId.getValue("plain") !is Torrent)
        val torrent = byId.getValue("torrent")
        assertTrue("topic with torrent fields is a Torrent", torrent is Torrent)
        assertEquals(5, (torrent as Torrent).seeds)
        assertEquals("1 GB", torrent.size)
    }

    @Test
    fun clear_empties_the_store() = runTest {
        val repo = TestVisitedRepository()
        repo.add(page("1", "Alpha"))
        repo.clear()
        assertEquals(emptyList<String>(), repo.observeIds().first())
    }
}
