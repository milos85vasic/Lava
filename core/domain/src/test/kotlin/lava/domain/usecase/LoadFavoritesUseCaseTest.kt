package lava.domain.usecase

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import lava.data.api.service.FavoritesService
import lava.models.topic.BaseTopic
import lava.models.topic.Topic
import lava.testing.repository.TestFavoritesRepository
import lava.testing.testDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack test for [LoadFavoritesUseCase] — the server↔local favorites
 * RECONCILIATION the user sees when their favorites list refreshes from the
 * remote tracker account.
 *
 * ## Why this is a real gap
 *
 * [LoadFavoritesUseCase] had ZERO tests. It carries user-visible branching
 * logic that, if broken, silently corrupts the user's saved favorites:
 *
 *  1. The `if (remoteFavoriteTopics.isNotEmpty())` guard — when the remote
 *     account returns NO favorites (offline / fetch failed / genuinely empty),
 *     the use case MUST leave the local favorites untouched. Dropping this
 *     guard would wipe the user's entire favorites list every time the server
 *     returns empty.
 *  2. The `getIds().subtract(remote ids)` set-difference — local favorites the
 *     user removed on another device (no longer on the server) MUST be deleted
 *     locally, server favorites MUST be added, and favorites present on BOTH
 *     MUST be retained. This is the dedup/merge the user perceives as "my
 *     favorites are in sync".
 *
 * ## Anti-Bluff Pact compliance
 *
 *  - SUT is the REAL [LoadFavoritesUseCase] (no mocking of the SUT).
 *  - Collaborators are REAL: [TestFavoritesRepository] (the behaviorally-
 *    equivalent fake of `FavoritesRepositoryImpl`, with its own equivalence
 *    test) and real [lava.testing.TestDispatchers]. Only the true boundary —
 *    [FavoritesService] (the network call) — is faked.
 *  - Primary assertions are on the PERSISTED repository state (the rows the
 *    user's favorites screen renders), never on call counts.
 *
 * ## Falsifiability (Sixth Law clause 2 / Seventh Law clause 1)
 *  - Mutation A: drop the `isNotEmpty()` guard so an empty remote list runs
 *    the reconcile branch → `emptyRemote_keepsLocalFavorites` FAILS (local
 *    favorites get wiped).
 *  - Mutation B: replace `subtract(...)` with `emptyList()` so nothing is
 *    deleted → `reconcile_addsRemoteOnly_deletesLocalOnly_keepsShared` FAILS
 *    (the local-only stale favorite is not removed).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoadFavoritesUseCaseTest {

    /** True-boundary fake of the remote favorites endpoint. */
    private class FakeFavoritesService(
        private val remote: List<Topic>,
    ) : FavoritesService {
        override suspend fun getFavorites(): List<Topic> = remote
        override suspend fun add(id: String): Boolean = true
        override suspend fun remove(id: String): Boolean = true
    }

    private fun topic(id: String): Topic = BaseTopic(id = id, title = "Title $id")

    @Test
    fun reconcile_addsRemoteOnly_deletesLocalOnly_keepsShared() = runTest {
        val repository = TestFavoritesRepository()
        // Local state: "local-only" (user removed on another device, not on
        // server) + "shared" (on both). Add singly so each persists distinctly.
        repository.add(topic("local-only"))
        repository.add(topic("shared"))

        // Remote returns "shared" (kept) + "remote-only" (added locally).
        val service = FakeFavoritesService(listOf(topic("shared"), topic("remote-only")))
        val useCase = LoadFavoritesUseCase(
            favoritesService = service,
            favoritesRepository = repository,
            dispatchers = testDispatchers(),
        )

        useCase()

        val ids = repository.getIds().toSet()
        assertEquals(
            "after reconcile the local favorites must equal the server set",
            setOf("shared", "remote-only"),
            ids,
        )
        assertTrue("server favorite present on both must be retained", repository.contains("shared"))
        assertTrue("server-only favorite must be added locally", repository.contains("remote-only"))
        assertFalse(
            "local-only favorite absent from server must be deleted",
            repository.contains("local-only"),
        )
    }

    @Test
    fun emptyRemote_keepsLocalFavorites() = runTest {
        val repository = TestFavoritesRepository()
        repository.add(topic("keep-1"))
        repository.add(topic("keep-2"))

        // Remote returns NOTHING — the isNotEmpty() guard must prevent any wipe.
        val service = FakeFavoritesService(emptyList())
        val useCase = LoadFavoritesUseCase(
            favoritesService = service,
            favoritesRepository = repository,
            dispatchers = testDispatchers(),
        )

        useCase()

        assertEquals(
            "an empty remote favorites list must NOT delete local favorites",
            setOf("keep-1", "keep-2"),
            repository.getIds().toSet(),
        )
    }
}
