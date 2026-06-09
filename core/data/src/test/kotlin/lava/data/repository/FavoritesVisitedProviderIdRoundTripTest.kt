package lava.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.data.impl.repository.FavoritesRepositoryImpl
import lava.data.impl.repository.VisitedRepositoryImpl
import lava.database.AppDatabase
import lava.logger.api.Logger
import lava.logger.api.LoggerFactory
import lava.models.Page
import lava.models.topic.BaseTopic
import lava.models.topic.Post
import lava.models.topic.TopicPage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Anti-bluff real-Room round-trip test for LVA-070 — the WRITE→READ providerId
 * thread through the REAL [FavoritesRepositoryImpl] / [VisitedRepositoryImpl]
 * against a REAL Room/SQLite [AppDatabase] (no mocking, no in-memory fake).
 *
 * The user-visible guarantee this pins: when an archiveorg topic is favorited /
 * visited, its source provider is PERSISTED on the row and READ BACK on the
 * favorites/visited list item ([lava.models.topic.TopicModel.providerId]) so the
 * topic screen can route it to HTTP_DOWNLOAD. Before LVA-070 the column was never
 * populated, so the list item always read providerId = null ⇒ active-tracker
 * fallback even for archiveorg/gutenberg topics.
 *
 * Constitution:
 * - Second/Third Law: real repository impls + real Room DAO; only the
 *   ApplicationContext (Robolectric) is the boundary.
 * - Sixth Law clause 3: primary assertions on persisted/observed state — the
 *   TopicModel.providerId the favorites list renders and the id→providerId map
 *   the visited list overlays.
 *
 * Bluff-Audit: FavoritesVisitedProviderIdRoundTripTest
 *   Mutation: in FavoritesRepositoryImpl.add, drop the providerId argument
 *             (`favoriteTopicDao.insert(topic.toFavoriteEntity())`).
 *   Observed-Failure: `favoriting an archiveorg topic persists and reads back
 *     providerId=archiveorg` FAILED —
 *       expected:<archiveorg> but was:<null>
 *       (the row stored NULL, so the list item lost its provider).
 *   Reverted: yes
 *
 * Bluff-Audit: FavoritesVisitedProviderIdRoundTripTest (visited overlay)
 *   Mutation: in VisitedRepositoryImpl.observeProviderIds, return
 *             `flowOf(emptyMap())` instead of the entity-derived map.
 *   Observed-Failure: `visiting an archiveorg topic persists and reads back
 *     providerId=archiveorg via observeProviderIds` FAILED —
 *       expected:<archiveorg> but was:<null>.
 *   Reverted: yes
 */
@RunWith(RobolectricTestRunner::class)
class FavoritesVisitedProviderIdRoundTripTest {

    private lateinit var db: AppDatabase
    private lateinit var favorites: FavoritesRepositoryImpl
    private lateinit var visited: VisitedRepositoryImpl

    private val noopLoggerFactory = object : LoggerFactory {
        override fun get(tag: String): Logger = object : Logger {
            override fun i(message: () -> String) = Unit
            override fun d(message: () -> String) = Unit
            override fun d(t: Throwable?, message: () -> String) = Unit
            override fun e(message: () -> String) = Unit
            override fun e(t: Throwable?, message: () -> String) = Unit
        }
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        favorites = FavoritesRepositoryImpl(db.favoriteTopicDao(), noopLoggerFactory)
        visited = VisitedRepositoryImpl(db.visitedTopicDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun topicPage(id: String) = TopicPage(
        id = id,
        title = "Archive item $id",
        author = null,
        category = null,
        torrentData = null,
        commentsPage = Page(items = emptyList<Post>(), page = 1, pages = 1),
    )

    @Test
    fun `favoriting an archiveorg topic persists and reads back providerId=archiveorg`() = runTest {
        favorites.add(BaseTopic(id = "arch-7", title = "Archive item"), providerId = "archiveorg")

        // READ back via the live observe query the favorites list renders.
        val model = favorites.observeTopics().first().single { it.topic.id == "arch-7" }
        assertEquals(
            "the favorites list item must carry the persisted source provider",
            "archiveorg",
            model.providerId,
        )
    }

    @Test
    fun `favoriting with no provider reads back null (active-tracker fallback)`() = runTest {
        favorites.add(BaseTopic(id = "rt-1", title = "RuTracker item"))

        val model = favorites.observeTopics().first().single { it.topic.id == "rt-1" }
        assertNull(
            "a favorite stored with no provider must read providerId = null",
            model.providerId,
        )
    }

    @Test
    fun `visiting an archiveorg topic persists and reads back providerId=archiveorg via observeProviderIds`() =
        runTest {
            visited.add(topicPage("arch-9"), providerId = "archiveorg")

            val providerIds = visited.observeProviderIds().first()
            assertEquals(
                "the visited overlay must carry the persisted source provider",
                "archiveorg",
                providerIds["arch-9"],
            )
            // The topic itself still round-trips through the visited list.
            assertEquals(
                listOf("arch-9"),
                visited.observeTopics().first().map { it.id },
            )
        }

    @Test
    fun `visiting with no provider reads back null in the overlay map`() = runTest {
        visited.add(topicPage("rt-2"))

        val providerIds = visited.observeProviderIds().first()
        assertNull(
            "a visited topic stored with no provider must overlay null",
            providerIds["rt-2"],
        )
    }
}
