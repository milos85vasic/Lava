package lava.domain.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.models.LoadState
import lava.models.Page
import lava.testing.logger.TestLoggerFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real-stack unit test for [PagingDataLoader] — the pagination state machine
 * that backs search / category / topic infinite scroll (consumed by
 * [lava.domain.usecase.ObserveSearchPagingDataUseCase],
 * [lava.domain.usecase.ObserveCategoryPagingDataUseCase] and
 * [lava.domain.usecase.ObserveTopicPagingDataUseCase]). codegraph confirmed
 * this class had NO covering tests; this closes that gap.
 *
 * The SUT is the REAL [PagingDataLoader]. The only fakes are the outermost
 * boundaries the production use-cases also fake/inject: a `fetchData` lambda
 * standing in for the [lava.data.api.service.SearchService] network call and an
 * identity `transform` standing in for [lava.domain.usecase.EnrichTopicsUseCase]
 * (which the production search loader wraps; an identity transform is the
 * behaviorally-minimal honest stand-in — it preserves order + count). The
 * action `Flow` is the same `MutableSharedFlow<PagingAction>` shape every
 * production use-case wires.
 *
 * Primary assertions (Sixth Law clause 3) are on the user-visible
 * [PagingData.data] (the rendered result list) + the
 * [PagingData.loadStates] / [PagingData.pagination] the screen footer reads to
 * decide whether to show the "loading more" spinner and whether to keep
 * requesting more pages on scroll.
 *
 * ## Bluff-Audit
 * See commit body for the per-test mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PagingDataLoaderTest {

    private val logger = TestLoggerFactory().get("PagingDataLoaderTest")

    /** Identity transform: the production search loader's EnrichTopicsUseCase
     * preserves order + count, so an identity flow is the honest minimal fake. */
    private fun identityTransform(items: List<String>) = flowOf(items)

    private fun page(items: List<String>, page: Int, pages: Int) =
        Page(items = items, page = page, pages = pages)

    // CHALLENGE
    @Test
    fun `refresh then append accumulates pages in order and stops at the last page`() =
        runTest(UnconfinedTestDispatcher()) {
            val actions = MutableSharedFlow<PagingAction>(extraBufferCapacity = 16)
            val requestedPages = mutableListOf<Int>()
            // 3 total pages; each page returns one item. Pages are 1-indexed,
            // `pages` is the total count (= last page number) per the rutracker
            // "Page X of Y" parser contract.
            val loader = PagingDataLoader(
                fetchData = { p ->
                    requestedPages += p
                    when (p) {
                        1 -> page(listOf("a1"), page = 1, pages = 3)
                        2 -> page(listOf("a2"), page = 2, pages = 3)
                        3 -> page(listOf("a3"), page = 3, pages = 3)
                        // Page 4 does not exist. Returning a real (empty) page
                        // here — rather than throwing — means an off-by-one
                        // terminal guard (`<=` instead of `<`) is NOT masked by
                        // a swallowed error; it surfaces as a real extra request
                        // the `requestedPages` assertion below catches.
                        else -> page(emptyList(), page = p, pages = 3)
                    }
                },
                transform = ::identityTransform,
                actions = actions,
                scope = backgroundScope,
                logger = logger,
            )

            // onStart { refresh() } is the production wiring; here we drive it
            // explicitly through the same action Flow the screen uses.
            actions.emit(PagingAction.Refresh(1))
            assertEquals(listOf("a1"), loader.flow.first { it.data == listOf("a1") }.data)

            actions.emit(PagingAction.Append)
            assertEquals(listOf("a1", "a2"), loader.flow.first { it.data?.size == 2 }.data)

            actions.emit(PagingAction.Append)
            val afterPage3 = loader.flow.first { it.data?.size == 3 }
            // User-visible: every page's item appears, in page order.
            assertEquals(listOf("a1", "a2", "a3"), afterPage3.data)
            // All three pages loaded.
            assertEquals(1..3, afterPage3.pagination.loadedPages)

            // A further append at the last page MUST be a no-op — it MUST NOT
            // request page 4 and MUST NOT change the rendered list. This is the
            // infinite-scroll terminal guard.
            actions.emit(PagingAction.Append)
            val finalState = loader.flow.first()
            assertEquals(
                "appending past the last page must not change the rendered list",
                listOf("a1", "a2", "a3"),
                finalState.data,
            )
            assertEquals(1..3, finalState.pagination.loadedPages)
            // The load-bearing terminal-guard assertion: page 4 was NEVER
            // requested. An off-by-one in the `lastPage < totalPages` guard
            // (e.g. `<=`) would issue a wasted network request for the
            // non-existent page 4 on every scroll-to-bottom — invisible in the
            // rendered list but a real bug (extra traffic + a phantom append
            // load state). The rendered list alone can't discriminate this;
            // the request log can.
            assertEquals(
                "the terminal append must never request a page past the last",
                listOf(1, 2, 3),
                requestedPages,
            )
        }

    // CHALLENGE
    @Test
    fun `refresh failure surfaces a refresh LoadState_Error then retry recovers`() =
        runTest(UnconfinedTestDispatcher()) {
            val actions = MutableSharedFlow<PagingAction>(extraBufferCapacity = 16)
            var failNextFetch = true
            val loader = PagingDataLoader(
                fetchData = { p ->
                    if (failNextFetch) {
                        failNextFetch = false
                        error("simulated network failure on page $p")
                    }
                    page(listOf("ok"), page = 1, pages = 1)
                },
                transform = ::identityTransform,
                actions = actions,
                scope = backgroundScope,
                logger = logger,
            )

            actions.emit(PagingAction.Refresh(1))
            // User-visible: the screen renders the full-screen error state from
            // refresh = LoadState.Error (not a silent empty list).
            val errored = loader.flow.first { it.loadStates.refresh is LoadState.Error }
            assertTrue(errored.loadStates.refresh is LoadState.Error)
            assertEquals(null, errored.data)

            // Retry re-runs the refresh; this time the fetch succeeds.
            actions.emit(PagingAction.Retry)
            val recovered = loader.flow.first { it.data == listOf("ok") }
            assertEquals(listOf("ok"), recovered.data)
            assertEquals(LoadState.NotLoading, recovered.loadStates.refresh)
        }

    // CHALLENGE
    //
    // Covers the PREPEND path — the user deep-links to a mid-list page
    // (e.g. a bookmarked "Page 3 of 5" forum scroll position) and scrolls
    // UP, which the screen translates into PagingAction.Prepend. This whole
    // production branch (`prepend()` + `MutableStateFlow<Pagination>.prepend`
    // + `MutableStateFlow<List>.prepend` which PREPENDS rather than appends)
    // had ZERO coverage before this test; the existing tests only exercised
    // refresh + append. A bug in the prepend order (e.g. appending instead
    // of prepending the earlier page) renders the earlier page BELOW the
    // current one — visibly wrong scroll content.
    //
    // Falsifiability rehearsal (Sixth Law clause 2): see commit Bluff-Audit.
    @Test
    fun `refresh at a mid-list page then prepend loads earlier pages above and stops at page 1`() =
        runTest(UnconfinedTestDispatcher()) {
            val actions = MutableSharedFlow<PagingAction>(extraBufferCapacity = 16)
            val requestedPages = mutableListOf<Int>()
            val loader = PagingDataLoader(
                fetchData = { p ->
                    requestedPages += p
                    when (p) {
                        // 1-indexed pages; total of 3 pages.
                        1 -> page(listOf("p1"), page = 1, pages = 3)
                        2 -> page(listOf("p2"), page = 2, pages = 3)
                        3 -> page(listOf("p3"), page = 3, pages = 3)
                        // Page 0 does not exist. Returning a real (empty) page
                        // here — rather than throwing — means an off-by-one
                        // terminal guard (`>= 1` instead of `> 1`) is NOT masked
                        // by a swallowed error; it surfaces as a real request for
                        // page 0 the `requestedPages` assertion below catches.
                        else -> page(emptyList(), page = p, pages = 3)
                    }
                },
                transform = ::identityTransform,
                actions = actions,
                scope = backgroundScope,
                logger = logger,
            )

            // Deep-link: the user opened the list already scrolled to page 3.
            actions.emit(PagingAction.Refresh(3))
            val afterRefresh = loader.flow.first { it.data == listOf("p3") }
            assertEquals(listOf("p3"), afterRefresh.data)
            assertEquals(3..3, afterRefresh.pagination.loadedPages)

            // Scroll up — page 2 must be loaded and rendered ABOVE page 3.
            actions.emit(PagingAction.Prepend)
            val afterPage2 = loader.flow.first { it.data?.size == 2 }
            assertEquals(
                "prepended page must appear ABOVE the current page, not below",
                listOf("p2", "p3"),
                afterPage2.data,
            )
            assertEquals(2..3, afterPage2.pagination.loadedPages)

            // Scroll up again — page 1 prepended above page 2.
            actions.emit(PagingAction.Prepend)
            val afterPage1 = loader.flow.first { it.data?.size == 3 }
            assertEquals(listOf("p1", "p2", "p3"), afterPage1.data)
            assertEquals(1..3, afterPage1.pagination.loadedPages)

            // A further prepend at the FIRST page MUST be a no-op — it MUST NOT
            // request page 0 and MUST NOT change the rendered list. This is the
            // top-of-list terminal guard, symmetric to the append bottom guard.
            actions.emit(PagingAction.Prepend)
            val finalState = loader.flow.first()
            assertEquals(
                "prepending past page 1 must not change the rendered list",
                listOf("p1", "p2", "p3"),
                finalState.data,
            )
            assertEquals(1..3, finalState.pagination.loadedPages)
            assertEquals(
                "the terminal prepend must never request a page before page 1",
                listOf(3, 2, 1),
                requestedPages,
            )
        }

    // CHALLENGE
    //
    // Covers the APPEND-ERROR → RETRY recovery path. The user scrolls to the
    // bottom, the next-page fetch fails (transient network), the screen shows
    // an inline "load more" error chip with a Retry; the user taps it and the
    // page loads. Before this test, only the REFRESH-error→retry branch was
    // covered — `retry()`'s `isAppendError()` dispatch arm was untested, and
    // crucially the retry MUST re-issue the SAME page (lastPage + 1), not
    // skip it or re-request the already-loaded page.
    //
    // Falsifiability rehearsal (Sixth Law clause 2): see commit Bluff-Audit.
    @Test
    fun `append failure surfaces an append LoadState_Error then retry loads the same next page`() =
        runTest(UnconfinedTestDispatcher()) {
            val actions = MutableSharedFlow<PagingAction>(extraBufferCapacity = 16)
            val requestedPages = mutableListOf<Int>()
            var failPage2Once = true
            val loader = PagingDataLoader(
                fetchData = { p ->
                    requestedPages += p
                    if (p == 2 && failPage2Once) {
                        failPage2Once = false
                        error("simulated network failure on page $p")
                    }
                    when (p) {
                        1 -> page(listOf("a1"), page = 1, pages = 2)
                        2 -> page(listOf("a2"), page = 2, pages = 2)
                        else -> page(emptyList(), page = p, pages = 2)
                    }
                },
                transform = ::identityTransform,
                actions = actions,
                scope = backgroundScope,
                logger = logger,
            )

            actions.emit(PagingAction.Refresh(1))
            assertEquals(listOf("a1"), loader.flow.first { it.data == listOf("a1") }.data)

            // Scroll to bottom — page 2 fetch fails.
            actions.emit(PagingAction.Append)
            val errored = loader.flow.first { it.loadStates.append is LoadState.Error }
            // User-visible: an inline append-error (NOT a full-screen refresh
            // error, and the already-loaded page 1 stays rendered).
            assertTrue(errored.loadStates.append is LoadState.Error)
            assertEquals(LoadState.NotLoading, errored.loadStates.refresh)
            assertEquals(
                "the already-loaded first page must remain rendered during an append error",
                listOf("a1"),
                errored.data,
            )

            // Tap Retry — the SAME next page (2) is re-requested and appended.
            actions.emit(PagingAction.Retry)
            val recovered = loader.flow.first { it.data?.size == 2 }
            assertEquals(listOf("a1", "a2"), recovered.data)
            assertEquals(LoadState.NotLoading, recovered.loadStates.append)
            assertEquals(1..2, recovered.pagination.loadedPages)
            // Retry MUST re-request page 2 (the failed page), never skip to 3
            // and never re-fetch the already-loaded page 1.
            assertEquals(
                "append-retry must re-request exactly the failed next page",
                listOf(1, 2, 2),
                requestedPages,
            )
        }
}
