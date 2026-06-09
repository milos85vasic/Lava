package lava.testing.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.models.topic.BaseTopic
import lava.models.topic.Topic
import lava.models.topic.Torrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral-equivalence guard for [TestFavoritesRepository] (Anti-Bluff Pact
 * Third Law, LVA-012). Pins the fake to the REAL `FavoritesRepositoryImpl`
 * semantics (Room `FavoriteTopicDao`, insert = UPSERT-by-id, `timestamp DESC`):
 *
 *  - observe/contains reflect adds live;
 *  - add UPSERTS (no duplicate id);
 *  - getTorrents filters to Torrent;
 *  - removeById deletes the matching row and keeps the rest;
 *  - markVisited / updateTorrent drive the hasUpdate flag and observeUpdatedIds;
 *  - add(List) updates existing rows and recomputes hasUpdate from a changed magnet.
 *
 * Bluff-Audit (Sixth Law 6.A / Seventh Law clause 1):
 *   Mutation: in TestFavoritesRepository.removeById(id), invert the predicate to
 *             `filter { it.topic.id == id }` (keep the matched, drop the rest).
 *   Observed-Failure: `removeById_deletes_matching_and_keeps_rest` fails —
 *     "removeById(beta) keeps alpha+gamma expected:<[alpha, gamma]> but was:<[beta]>".
 *   Reverted: yes.
 */
class TestFavoritesRepositoryEquivalenceTest {

    private fun base(id: String, title: String = "T$id"): Topic = BaseTopic(id = id, title = title)

    private fun torrent(id: String, magnet: String? = null, seeds: Int? = null): Torrent =
        Torrent(id = id, title = "T$id", magnetLink = magnet, seeds = seeds)

    @Test
    fun add_upserts_and_observers_and_contains_are_live() = runTest {
        val repo = TestFavoritesRepository()

        assertFalse(repo.contains("a"))
        repo.add(base("a"))
        repo.add(base("b"))

        // Live emission + newest-first.
        assertEquals(listOf("b", "a"), repo.observeIds().first())
        assertEquals(listOf("b", "a"), repo.getIds())
        assertTrue(repo.contains("a"))
        assertEquals(true, repo.observeTopics().first().first().isFavorite)

        // Re-add existing id → UPSERT (one row), moved to newest.
        repo.add(base("a", title = "A v2"))
        val ids = repo.observeIds().first()
        assertEquals("add of existing id MUST upsert (no duplicate)", listOf("a", "b"), ids)
        assertEquals("A v2", repo.observeTopics().first().first().topic.title)
    }

    @Test
    fun getTorrents_filters_to_torrent_only() = runTest {
        val repo = TestFavoritesRepository()
        repo.add(base("plain"))
        repo.add(torrent("t1", seeds = 3))

        val torrents = repo.getTorrents()
        assertEquals(listOf("t1"), torrents.map(Torrent::id))
        assertEquals(3, torrents.first().seeds)
    }

    @Test
    fun removeById_deletes_matching_and_keeps_rest() = runTest {
        val repo = TestFavoritesRepository()
        repo.add(base("alpha"))
        repo.add(base("beta"))
        repo.add(base("gamma"))

        repo.removeById("beta")

        assertEquals(
            "removeById(beta) keeps alpha+gamma",
            listOf("alpha", "gamma"),
            repo.getIds().sorted(),
        )
        assertFalse(repo.contains("beta"))
    }

    @Test
    fun markVisited_clears_hasUpdate_and_observeUpdatedIds_is_live() = runTest {
        val repo = TestFavoritesRepository()
        repo.updateTorrent(torrent("t1", magnet = "m1"), hasUpdate = true)

        assertEquals("t1 reported as updated", listOf("t1"), repo.observeUpdatedIds().first())

        repo.markVisited("t1")
        assertEquals("markVisited clears hasUpdate", emptyList<String>(), repo.observeUpdatedIds().first())
        // The row itself survives markVisited (only the flag is cleared).
        assertTrue(repo.contains("t1"))
    }

    @Test
    fun addList_updates_existing_and_sets_hasUpdate_on_magnet_change() = runTest {
        val repo = TestFavoritesRepository()
        repo.add(torrent("t1", magnet = "old-magnet"))

        // Re-add via add(List) with a changed magnet → updated in place, hasUpdate true.
        repo.add(listOf(torrent("t1", magnet = "new-magnet"), torrent("t2", magnet = "m2")))

        assertEquals("t1 updated in place + t2 inserted (no dup)", setOf("t1", "t2"), repo.getIds().toSet())
        assertEquals(
            "changed magnet link flips hasUpdate for t1",
            listOf("t1"),
            repo.observeUpdatedIds().first(),
        )
        assertEquals(
            "t1 magnet merged to the new value",
            "new-magnet",
            repo.getTorrents().first { it.id == "t1" }.magnetLink,
        )
    }

    @Test
    fun clear_empties_the_store() = runTest {
        val repo = TestFavoritesRepository()
        repo.add(base("a"))
        repo.clear()
        assertEquals(emptyList<String>(), repo.getIds())
    }
}
