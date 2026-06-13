package lava.search.result

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import lava.models.search.Filter
import lava.navigation.NavigationController
import lava.navigation.model.NavigationArgument
import lava.navigation.model.NavigationDeepLink
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.NavigationOptions
import lava.navigation.ui.NavigationAnimations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-stack round-trip test for [openSearchResult] (the route BUILD side) and
 * [SavedStateHandle.filter] (the route DECODE side) in [SearchResultNavigation],
 * focused on the [Filter.providerIds] field.
 *
 * ## Why this test exists (bluff-hunt GAP closure, 2026-06-13)
 *
 * The §6.N search-flow bluff hunt found that NO existing test exercised the
 * `openSearchResult` → route → `SavedStateHandle.filter` round-trip for
 * `providerIds`. The four existing `search_result` ViewModel tests inject a
 * `SavedStateHandle(mapOf("pids" to "rutracker"))` DIRECTLY — they bypass both
 * the production serializer (`openSearchResult`'s
 * `ProviderIdsKey to filter.providerIds...joinToString(",")`, line 120) AND the
 * production deserializer (`SavedStateHandle.filter`'s split/filter, lines
 * 135-138). The hunt proved the gap by deleting the serializer's providerIds
 * pair entirely: the whole `search_result` suite stayed GREEN while the
 * production route dropped providerIds — the exact Bug-2-Layer-3 regression the
 * `ProviderIdsKey` constant was added (2026-05-17) to prevent.
 *
 * Bug-2-Layer-3 (the regression this test guards): when `providerIds` is lost in
 * the search-result route, [SearchResultViewModel.onCreate] reads
 * `filter.providerIds == null` and dispatches to `observePagingData()` — the
 * single-tracker rutracker-direct path — which fails at auth for users who only
 * onboarded anonymous providers, rendering "Something went wrong". Anonymous-only
 * search becomes unreachable. The user-visible determinant of which search path
 * runs is exactly the decoded `providerIds`, so it is the test's primary
 * assertion.
 *
 * The SUT is the REAL [openSearchResult] + REAL [SavedStateHandle.filter]. Runs
 * under Robolectric so the REAL [android.net.Uri.encode]/[android.net.Uri.decode]
 * execute — a value that survives this round-trip survives on a real device. The
 * only fakes are the outermost navigation boundaries (a graph-less
 * [NavigationGraphBuilder] + a route-capturing [NavigationController]), mirroring
 * `feature/search_input`'s `SearchInputNavigationRoundtripTest`.
 *
 * FALSIFIABILITY REHEARSAL (§6.T.1 / Sixth Law clause 2):
 *  - Mutation A (serialize side): in `openSearchResult`, replace
 *    `ProviderIdsKey to filter.providerIds?.takeIf(...)?.joinToString(",")` with
 *    `ProviderIdsKey to null`.
 *    Observed failure: `providerIds roundtrip preserves a multi-provider subset`
 *    → `expected:<[archiveorg, gutenberg]> but was:<null>` (the decoded filter
 *    has providerIds == null because the route never carried them).
 *  - Mutation B (deserialize side): in `SavedStateHandle.filter`, drop the
 *    `.split(",")` so the comma-joined value is taken whole.
 *    Observed failure: same test → `expected:<[archiveorg, gutenberg]> but
 *    was:<[archiveorg,gutenberg]>` (one fused element instead of two).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SearchResultNavigationProviderIdsRoundtripTest {

    // CHALLENGE — the decoded providerIds is the user-visible determinant of the
    // search path (multi-provider streaming vs single-tracker paging).
    @Test
    fun `providerIds roundtrip preserves a multi-provider subset`() {
        val filter = Filter(
            query = "ubuntu",
            providerIds = listOf("archiveorg", "gutenberg"),
        )

        val decoded = decodeRoute(captureRoute { openSearchResult(filter) })

        assertEquals(listOf("archiveorg", "gutenberg"), decoded.providerIds)
        assertEquals("ubuntu", decoded.query)
    }

    // CHALLENGE — a single onboarded provider must survive as a 1-element list,
    // NOT collapse to null (which would route into the single-tracker path).
    @Test
    fun `providerIds roundtrip preserves a single-provider subset`() {
        val filter = Filter(
            query = "debian",
            providerIds = listOf("archiveorg"),
        )

        val decoded = decodeRoute(captureRoute { openSearchResult(filter) })

        assertEquals(listOf("archiveorg"), decoded.providerIds)
    }

    // CHALLENGE — null providerIds ("search all") must round-trip as null so
    // onCreate dispatches to the all-providers / paging path, not an empty list.
    @Test
    fun `null providerIds roundtrip stays null`() {
        val filter = Filter(query = "fedora", providerIds = null)

        val decoded = decodeRoute(captureRoute { openSearchResult(filter) })

        assertNull(decoded.providerIds)
    }

    /**
     * Invokes the production [openSearchResult] extension within the two context
     * receivers it requires and returns the route string it hands to
     * [NavigationController.navigate].
     */
    private fun captureRoute(block: context(NavigationGraphBuilder, NavigationController) () -> Unit): String {
        var captured: String? = null
        val graphBuilder = NoGraphBuilder()
        val controller = RouteCapturingController { captured = it }
        with(graphBuilder) {
            with(controller) {
                block(graphBuilder, controller)
            }
        }
        return requireNotNull(captured) { "openSearchResult did not call navigate()" }
    }

    /**
     * Parses a `route?key=value&key2=value2` string back into a [Filter] via the
     * REAL [SavedStateHandle.filter] getter. Query-parameter values are
     * percent-decoded exactly as Navigation-Compose / [android.net.Uri] decode
     * them when the host navigates the route.
     */
    private fun decodeRoute(route: String): Filter {
        val query = route.substringAfter('?', "")
        val handle = SavedStateHandle()
        if (query.isNotEmpty()) {
            query.split('&').forEach { pair ->
                val name = pair.substringBefore('=')
                val rawValue = pair.substringAfter('=', "")
                handle[name] = Uri.decode(rawValue)
            }
        }
        return handle.filter
    }

    private class RouteCapturingController(
        private val onNavigate: (String) -> Unit,
    ) : NavigationController {
        override val navHostController: NavHostController
            get() = throw UnsupportedOperationException("not needed for route capture")

        override fun navigate(route: String) = onNavigate(route)

        override fun deeplink(uri: Uri) = throw UnsupportedOperationException()

        override fun popBackStack(): Boolean = throw UnsupportedOperationException()
    }

    private class NoGraphBuilder : NavigationGraphBuilder {
        override val graph: String? = null

        override fun addDestination(
            route: String,
            isStartRoute: Boolean,
            arguments: List<NavigationArgument>,
            deepLinks: List<NavigationDeepLink>,
            animations: NavigationAnimations,
            options: NavigationOptions,
            content: @androidx.compose.runtime.Composable () -> Unit,
        ) = Unit

        override fun addGraph(
            route: String,
            isStartRoute: Boolean,
            animations: NavigationAnimations,
            nestedDestinations: NavigationGraphBuilder.() -> Unit,
        ) = Unit
    }
}
