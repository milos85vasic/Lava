package lava.search.input

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.navigation.NavHostController
import lava.models.search.Filter
import lava.models.topic.Author
import lava.navigation.NavigationController
import lava.navigation.model.NavigationArgument
import lava.navigation.model.NavigationDeepLink
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.NavigationOptions
import lava.navigation.ui.NavigationAnimations
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real-stack round-trip test for [openSearchInput] (the route BUILD side) and
 * [SavedStateHandle.filter] (the route DECODE side) in [SearchInputNavigation].
 *
 * This exercises the exact production code path a user triggers when they tap a
 * topic author or run a filtered search: the [Filter] is serialised into a
 * navigation route by the production [openSearchInput] extension, and the route
 * query string is parsed back into a [Filter] by the production
 * [SavedStateHandle.filter] getter.
 *
 * Runs under Robolectric so the REAL [android.net.Uri.encode] (production encode
 * side, LVA-049) and the REAL [android.net.Uri.decode] (the exact decode
 * Navigation-Compose applies when the host navigates a route) both execute — no
 * stand-in encoder/decoder, so a value that survives this round-trip survives on
 * a real device.
 *
 * The SUT is the REAL [openSearchInput] + REAL [SavedStateHandle.filter]. The only
 * fakes are the outermost boundaries: a [NavigationGraphBuilder] (graph == null,
 * so the route has no graph prefix — matching the actual SearchInput graph) and a
 * route-capturing [NavigationController].
 *
 * Covers two real navigation bugs:
 *  - LVA-048: the author NAME was written under the AUTHOR-ID key (duplicate
 *    `AuthorIdKey to` pair) instead of the AUTHOR-NAME key, so the name was lost
 *    and the id was clobbered.
 *  - LVA-049: route values were appended raw (no URL-encoding), so a query
 *    containing reserved chars (`&`, `?`) corrupted the route — the part after
 *    the first `&`/`?` was parsed as a different query parameter (or dropped).
 *
 * FALSIFIABILITY REHEARSAL (§6.T.1 / Sixth Law clause 2):
 *  - LVA-048 mutation: revert `AuthorNameKey to filter.author?.name` back to
 *    `AuthorIdKey to filter.author?.name`.
 *    Observed failure: `authorRoundtrip preserves id and name` →
 *    `expected:<Ironman & Co> but was:<...>` (name comes back empty / wrong because
 *    AuthorNameKey is never written).
 *  - LVA-049 mutation: revert `Uri.encode(...)` back to the raw value in
 *    `appendOptionalParams(QueryKey to ...)`.
 *    Observed failure: `queryRoundtrip preserves reserved characters` →
 *    `expected:<a & b ? c> but was:<a >` (everything after the first ` & ` is lost
 *    because the raw `&` is parsed as a query-param separator).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SearchInputNavigationRoundtripTest {

    // CHALLENGE
    @Test
    fun `authorRoundtrip preserves id and name`() {
        val filter = Filter(
            author = Author(id = "42", name = "Ironman & Co"),
        )

        val decoded = decodeRoute(captureRoute { openSearchInput(filter) })

        assertEquals("42", decoded.author?.id)
        assertEquals("Ironman & Co", decoded.author?.name)
    }

    // CHALLENGE
    @Test
    fun `queryRoundtrip preserves reserved characters`() {
        val filter = Filter(query = "a & b ? c")

        val decoded = decodeRoute(captureRoute { openSearchInput(filter) })

        assertEquals("a & b ? c", decoded.query)
    }

    // CHALLENGE — full filter with both an author (id + name) and a reserved-char query.
    @Test
    fun `fullRoundtrip preserves author and reserved-char query together`() {
        val filter = Filter(
            query = "a & b ? c",
            author = Author(id = "7", name = "Stark & Sons"),
        )

        val decoded = decodeRoute(captureRoute { openSearchInput(filter) })

        assertEquals("a & b ? c", decoded.query)
        assertEquals("7", decoded.author?.id)
        assertEquals("Stark & Sons", decoded.author?.name)
    }

    /**
     * Invokes the production [openSearchInput] extension within the two context
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
        return requireNotNull(captured) { "openSearchInput did not call navigate()" }
    }

    /**
     * Parses a `route?key=value&key2=value2` string back into a [Filter] via the
     * REAL [SavedStateHandle.filter] getter. Query-parameter values are
     * percent-decoded exactly as Navigation-Compose / [android.net.Uri] decode them
     * when the host navigates the route.
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
