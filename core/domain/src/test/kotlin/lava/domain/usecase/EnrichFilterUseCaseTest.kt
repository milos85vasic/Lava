package lava.domain.usecase

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.data.api.repository.ForumRepository
import lava.data.api.service.ForumService
import lava.models.Page
import lava.models.forum.Category
import lava.models.forum.Forum
import lava.models.forum.ForumCategory
import lava.models.forum.ForumItem
import lava.models.search.Filter
import lava.models.search.Order
import lava.models.search.Period
import lava.models.search.Sort
import lava.testing.TestDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack unit test for the SEARCH-domain [EnrichFilterUseCaseImpl].
 *
 * SUT is the production `EnrichFilterUseCaseImpl`, wired to the REAL collaborating
 * use-cases it depends on — `GetCategoryUseCase`, `EnsureForumLoadUseCase`,
 * `RefreshForumUseCase` — exactly as the Hilt graph assembles them. Only the
 * outermost boundaries are faked: a behavioral [ForumRepository] (stores the forum
 * and resolves categories from what it has stored, like the real Room-backed repo)
 * and a [ForumService] (the network source of the forum tree).
 *
 * `EnrichFilterUseCase` is what a search filter's categories pass through before
 * the search is persisted/executed: each picked category id is re-resolved to a
 * fresh `Category` (so a stale display name picked from an old list is refreshed),
 * while every other filter field is preserved verbatim. The interesting branch is
 * in `GetCategoryUseCase`: when the stored forum does NOT contain a requested
 * category, it triggers a forum refresh and re-resolves — that's the path a user
 * hits when they search a category the local forum cache hasn't seen yet.
 *
 * FALSIFIABILITY REHEARSALS (each performed, observed RED, reverted — see report):
 *  - EnrichFilterUseCaseImpl: changed `filter.categories?.map { getCategoryUseCase(it.id) }`
 *    to `filter.categories` (pass-through, no re-resolution) →
 *    `enriches each filter category to the fresh forum category` FAILED (stale name).
 *  - GetCategoryUseCase: deleted the `if (category == null) refreshForumUseCase()` block →
 *    `resolves a category missing from cache by refreshing the forum` FAILED with
 *    IllegalArgumentException (requireNotNull on the still-missing category).
 */
class EnrichFilterUseCaseTest {

    /**
     * Behavioral fake of `ForumRepositoryImpl`. Stores a flat map of categories
     * derived from the forum tree; `getCategory` returns null for unknown ids
     * (matching the real repo's nullable lookup that drives the refresh branch).
     */
    private class FakeForumRepository(initial: Forum? = null) : ForumRepository {
        private var stored: Forum? = initial
        private var categories: Map<String, Category> = flatten(initial)

        override suspend fun isNotEmpty(): Boolean = categories.isNotEmpty()
        override suspend fun isForumFresh(maxAgeInDays: Int): Boolean = stored != null
        override suspend fun storeForum(forum: Forum) {
            stored = forum
            categories = flatten(forum)
        }
        override suspend fun getForum(): Forum = requireNotNull(stored)
        override suspend fun getCategory(id: String): Category? = categories[id]

        private companion object {
            fun flatten(forum: Forum?): Map<String, Category> {
                if (forum == null) return emptyMap()
                val out = LinkedHashMap<String, Category>()
                fun walk(nodes: List<ForumCategory>) {
                    nodes.forEach {
                        out[it.id] = Category(id = it.id, name = it.name)
                        walk(it.children)
                    }
                }
                walk(forum.children)
                return out
            }
        }
    }

    private class FakeForumService(private val forum: Forum) : ForumService {
        var getForumCalls: Int = 0
            private set
        override suspend fun getForum(): Forum {
            getForumCalls++
            return forum
        }
        override suspend fun getCategoryPage(id: String, page: Int): Page<ForumItem> =
            throw UnsupportedOperationException("not used in this test")
    }

    private fun build(
        repository: ForumRepository,
        service: ForumService,
    ): EnrichFilterUseCase {
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher())
        val refresh = RefreshForumUseCase(repository, service, dispatchers)
        val ensureLoad = EnsureForumLoadUseCase(refresh, repository, dispatchers)
        val getCategory = GetCategoryUseCase(ensureLoad, refresh, repository, dispatchers)
        return EnrichFilterUseCaseImpl(getCategory, dispatchers)
    }

    @Test
    fun `enriches each filter category to the fresh forum category`() = runTest {
        // The forum cache holds the up-to-date names.
        val forum = Forum(
            children = listOf(
                ForumCategory(id = "10", name = "Movies"),
                ForumCategory(id = "20", name = "Music"),
            ),
        )
        val useCase = build(FakeForumRepository(forum), FakeForumService(forum))

        // The user's filter carries STALE display names picked from an old list.
        val stale = Filter(
            query = "matrix",
            categories = listOf(
                Category(id = "10", name = "OLD-Movies"),
                Category(id = "20", name = "OLD-Music"),
            ),
        )

        val enriched = useCase(stale)

        // User-visible: categories are re-resolved to the current forum names.
        assertEquals(
            listOf(
                Category(id = "10", name = "Movies"),
                Category(id = "20", name = "Music"),
            ),
            enriched.categories,
        )
    }

    @Test
    fun `preserves all non-category filter fields verbatim`() = runTest {
        val forum = Forum(children = listOf(ForumCategory(id = "7", name = "Books")))
        val useCase = build(FakeForumRepository(forum), FakeForumService(forum))

        val original = Filter(
            query = "tolkien",
            sort = Sort.SIZE,
            order = Order.ASCENDING,
            period = Period.LAST_MONTH,
            categories = listOf(Category(id = "7", name = "stale")),
            providerIds = listOf("rutracker", "rutor"),
        )

        val enriched = useCase(original)

        // Only categories change; every other field a user set must survive.
        assertEquals(original.query, enriched.query)
        assertEquals(original.sort, enriched.sort)
        assertEquals(original.order, enriched.order)
        assertEquals(original.period, enriched.period)
        assertEquals(original.providerIds, enriched.providerIds)
        assertEquals(listOf(Category(id = "7", name = "Books")), enriched.categories)
    }

    @Test
    fun `leaves a null-categories filter untouched`() = runTest {
        val forum = Forum(children = listOf(ForumCategory(id = "1", name = "X")))
        val useCase = build(FakeForumRepository(forum), FakeForumService(forum))

        val noCategories = Filter(query = "anything", categories = null)

        val enriched = useCase(noCategories)

        // A "no category" search stays a "no category" search — no spurious work.
        assertNull(enriched.categories)
        assertEquals("anything", enriched.query)
    }

    @Test
    fun `resolves a category missing from cache by refreshing the forum`() = runTest {
        // The repo is already NON-EMPTY and FRESH but is MISSING the requested
        // category id 42 (it only knows id 1). This isolates the refresh-on-MISS
        // branch in GetCategoryUseCase from the ensure-forum-load branch:
        // ensureForumLoad() is a no-op here (forum present + fresh), so the ONLY
        // way id 42 resolves is via the `if (category == null) refreshForumUseCase()`
        // re-fetch. The network source carries the full tree including id 42.
        val cachedForum = Forum(children = listOf(ForumCategory(id = "1", name = "Other")))
        val networkForum = Forum(
            children = listOf(
                ForumCategory(id = "1", name = "Other"),
                ForumCategory(id = "42", name = "Anime"),
            ),
        )
        val service = FakeForumService(networkForum)
        val useCase = build(FakeForumRepository(initial = cachedForum), service)

        val filter = Filter(
            query = "ghibli",
            categories = listOf(Category(id = "42", name = "placeholder")),
        )

        val enriched = useCase(filter)

        // User-visible: the previously-uncached category resolves to its real name,
        // proving the forum was re-fetched from the network to satisfy the lookup.
        assertEquals(listOf(Category(id = "42", name = "Anime")), enriched.categories)
        assertTrue("forum should have been re-fetched on cache miss", service.getForumCalls >= 1)
    }
}
