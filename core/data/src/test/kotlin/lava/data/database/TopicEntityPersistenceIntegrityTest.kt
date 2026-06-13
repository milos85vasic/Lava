package lava.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.database.AppDatabase
import lava.database.entity.FavoriteTopicEntity
import lava.database.entity.VisitedTopicEntity
import lava.models.forum.Category
import lava.models.topic.Author
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Anti-bluff data-integrity test (§6.N bluff-hunt, 2026-06-13 DATA-INTEGRITY cycle)
 * for the persistence foundation of the user's saved-topic lists:
 *
 *  - [lava.database.converters.Converters] — the Room [androidx.room.TypeConverter]s
 *    that serialise the non-null `Author` / `Category` columns of
 *    [FavoriteTopicEntity] + [VisitedTopicEntity] to/from JSON text on disk. These
 *    converters had NO test before this file; a round-trip bug (e.g. `fromAuthor`
 *    dropping `avatarUrl`, `toCategory` reading the wrong key) silently corrupts
 *    every persisted bookmark / favorite / visited row carrying author or category
 *    metadata, and would be invisible to the existing migration test (which only
 *    ever writes NULL into those columns).
 *
 *  - [lava.database.dao.VisitedTopicDao] + [lava.database.dao.FavoriteTopicDao] —
 *    the `ORDER BY timestamp DESC` newest-first list contract the visited-history
 *    and favorites screens render, and the `@Insert(onConflict = REPLACE)` dedup
 *    contract (Third Law: a fake that does not enforce primary-key REPLACE is a
 *    bluff fake). Tested against a REAL in-memory Room DB, not a fake.
 *
 * Everything runs through the REAL [AppDatabase] (real SQLite via Robolectric,
 * real DAOs, real converters). No mocking. Primary assertions are on the persisted
 * + read-back row state — the exact bytes a user's saved-topic screen renders.
 *
 * Constitutional compliance:
 *  - Second/Third Law: real Room DB + real DAOs + real converters; no fakes.
 *  - Sixth Law clause 3: primary assertion on persisted DB row fields.
 *  - §6.T.1 / §6.J: a converter or ordering bug here corrupts the user's lists.
 *
 * Bluff-Audit: TopicEntityPersistenceIntegrityTest (converter round-trip)
 *   Mutation: in Converters.fromAuthor, removed the
 *             `value.avatarUrl?.let { put("avatarUrl", it) }` line so the
 *             avatar URL is dropped on write.
 *   Observed-Failure: `favorite Author and Category survive a real Room round-trip`
 *     FAILED — org.junit.ComparisonFailure: persisted Author.avatarUrl must survive
 *       the Room JSON round-trip expected:<[https://img.example/avatar.png]>
 *       but was:<[null]>
 *   Reverted: yes
 *
 * Bluff-Audit: TopicEntityPersistenceIntegrityTest (visited ordering)
 *   Mutation: in VisitedTopicDao.observerAll, changed `ORDER by timestamp DESC`
 *             to `ORDER by timestamp ASC`.
 *   Observed-Failure: `visited history is ordered newest-first`
 *     FAILED — java.lang.AssertionError: visited list must be newest-first
 *       (timestamp DESC) expected:<[vis-new, vis-mid, vis-old]>
 *       but was:<[vis-old, vis-mid, vis-new]>
 *   Reverted: yes
 *
 * Bluff-Audit: TopicEntityPersistenceIntegrityTest (REPLACE dedup)
 *   Mutation: in FavoriteTopicDao, changed the single-entity insert's
 *             `onConflict = OnConflictStrategy.REPLACE` to
 *             `OnConflictStrategy.IGNORE`.
 *   Observed-Failure: `re-saving a favorite with the same id replaces the old row`
 *     FAILED — org.junit.ComparisonFailure: a re-saved favorite (same id) must
 *       REPLACE, not duplicate or keep the stale row expected:<[Updated title]>
 *       but was:<[Original title]>
 *   Reverted: yes
 */
@RunWith(RobolectricTestRunner::class)
class TopicEntityPersistenceIntegrityTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `favorite Author and Category survive a real Room round-trip`() = runTest {
        // A real saved favorite carrying full author + category metadata — the
        // exact shape a rutracker/archiveorg topic produces. This is what the
        // Converters TypeConverters must serialise to JSON and parse back byte
        // for byte, on disk.
        val saved = FavoriteTopicEntity(
            id = "fav-meta",
            timestamp = 100L,
            title = "Topic with metadata",
            author = Author(
                id = "user-42",
                name = "Original Uploader",
                avatarUrl = "https://img.example/avatar.png",
            ),
            category = Category(id = "cat-7", name = "Movies / 1080p"),
            providerId = "rutracker",
        )
        db.favoriteTopicDao().insert(saved)

        val readBack = db.favoriteTopicDao().get("fav-meta")
        requireNotNull(readBack) { "favorite must read back after insert" }

        // Author every field must survive the JSON round-trip.
        assertEquals(
            "persisted Author.id must survive the Room JSON round-trip",
            "user-42",
            readBack.author?.id,
        )
        assertEquals(
            "persisted Author.name must survive the Room JSON round-trip",
            "Original Uploader",
            readBack.author?.name,
        )
        assertEquals(
            "persisted Author.avatarUrl must survive the Room JSON round-trip",
            "https://img.example/avatar.png",
            readBack.author?.avatarUrl,
        )

        // Category every field must survive the JSON round-trip.
        assertEquals(
            "persisted Category.id must survive the Room JSON round-trip",
            "cat-7",
            readBack.category?.id,
        )
        assertEquals(
            "persisted Category.name must survive the Room JSON round-trip",
            "Movies / 1080p",
            readBack.category?.name,
        )
    }

    @Test
    fun `favorite with a null-id Author preserves the missing optional field`() = runTest {
        // Author.id is nullable; the converter must NOT fabricate an id on read.
        val saved = FavoriteTopicEntity(
            id = "fav-anon",
            timestamp = 50L,
            title = "Anon uploader topic",
            author = Author(id = null, name = "Anonymous", avatarUrl = null),
            category = null,
        )
        db.favoriteTopicDao().insert(saved)

        val readBack = db.favoriteTopicDao().get("fav-anon")
        requireNotNull(readBack)
        assertNull("a null Author.id must read back as null", readBack.author?.id)
        assertEquals("Anonymous", readBack.author?.name)
        assertNull("a null Author.avatarUrl must read back as null", readBack.author?.avatarUrl)
        assertNull("a null category must read back as null", readBack.category)
    }

    @Test
    fun `visited history is ordered newest-first`() = runTest {
        // Insert out of timestamp order; the DAO's ORDER BY timestamp DESC is the
        // user-visible list order on the Visited screen.
        db.visitedTopicDao().insert(visited("vis-old", timestamp = 10L))
        db.visitedTopicDao().insert(visited("vis-new", timestamp = 30L))
        db.visitedTopicDao().insert(visited("vis-mid", timestamp = 20L))

        val orderedIds = db.visitedTopicDao().observerAll().first().map { it.id }

        assertEquals(
            "visited list must be newest-first (timestamp DESC)",
            listOf("vis-new", "vis-mid", "vis-old"),
            orderedIds,
        )
    }

    @Test
    fun `favorites list is ordered newest-first`() = runTest {
        db.favoriteTopicDao().insert(favorite("f-old", timestamp = 1L))
        db.favoriteTopicDao().insert(favorite("f-new", timestamp = 3L))
        db.favoriteTopicDao().insert(favorite("f-mid", timestamp = 2L))

        val orderedIds = db.favoriteTopicDao().getAll().map { it.id }

        assertEquals(
            "favorites list must be newest-first (timestamp DESC)",
            listOf("f-new", "f-mid", "f-old"),
            orderedIds,
        )
    }

    @Test
    fun `re-saving a favorite with the same id replaces the old row`() = runTest {
        db.favoriteTopicDao().insert(
            favorite("dup", timestamp = 1L).copy(title = "Original title"),
        )
        db.favoriteTopicDao().insert(
            favorite("dup", timestamp = 2L).copy(title = "Updated title"),
        )

        val all = db.favoriteTopicDao().getAll().filter { it.id == "dup" }
        assertEquals(
            "REPLACE-on-conflict must keep exactly one row per primary key",
            1,
            all.size,
        )
        assertEquals(
            "a re-saved favorite (same id) must REPLACE, not duplicate or keep the stale row",
            "Updated title",
            all.single().title,
        )
    }

    @Test
    fun `re-saving a visited topic with the same id replaces the old row`() = runTest {
        db.visitedTopicDao().insert(visited("dup-v", timestamp = 1L).copy(title = "First view"))
        db.visitedTopicDao().insert(visited("dup-v", timestamp = 2L).copy(title = "Latest view"))

        val rows = db.visitedTopicDao().observerAll().first().filter { it.id == "dup-v" }
        assertEquals(
            "REPLACE-on-conflict must keep exactly one visited row per primary key",
            1,
            rows.size,
        )
        assertEquals(
            "a re-visited topic (same id) must REPLACE the stale row",
            "Latest view",
            rows.single().title,
        )
    }

    private fun favorite(id: String, timestamp: Long) = FavoriteTopicEntity(
        id = id,
        timestamp = timestamp,
        title = "Favorite $id",
        author = null,
        category = null,
    )

    private fun visited(id: String, timestamp: Long) = VisitedTopicEntity(
        id = id,
        timestamp = timestamp,
        title = "Visited $id",
        author = null,
        category = null,
    )
}
