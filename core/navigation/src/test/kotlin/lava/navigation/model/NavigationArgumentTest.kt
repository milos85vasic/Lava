package lava.navigation.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real-stack tests for the route + deep-link string builders in
 * [NavigationArgument]. These functions produce the route TEMPLATES
 * (`category/{f}?q={q}`) the NavHost registers and the concrete NAVIGATION
 * PATHS (`category/123?q=hi`) the app pushes when the user taps through to a
 * screen. A drift here means the user taps a link and lands on the wrong (or
 * no) destination — a directly user-visible defect.
 *
 * Anti-Bluff posture (§6.J): the SUT is the production builder functions; no
 * mocking. Primary assertions are on the built string — the exact value the
 * navigation framework matches against. `buildRoute` carries a
 * `context(NavigationGraphBuilder)` receiver, so the tests supply a real
 * lightweight [NavigationGraphBuilder] instance (only the `graph` property is
 * read by `buildRoute`).
 *
 * Bluff-Audit recorded in the commit body.
 */
class NavigationArgumentTest {

    // Minimal real NavigationGraphBuilder: buildRoute only reads `graph` to
    // decide whether to prefix the route with a parent graph segment.
    private class TestGraphBuilder(override val graph: String?) : NavigationGraphBuilder {
        override fun addDestination(
            route: String,
            isStartRoute: Boolean,
            arguments: List<NavigationArgument>,
            deepLinks: List<NavigationDeepLink>,
            animations: lava.navigation.ui.NavigationAnimations,
            options: NavigationOptions,
            content: @androidx.compose.runtime.Composable () -> Unit,
        ) = error("not used")

        override fun addGraph(
            route: String,
            isStartRoute: Boolean,
            animations: lava.navigation.ui.NavigationAnimations,
            nestedDestinations: NavigationGraphBuilder.() -> Unit,
        ) = error("not used")
    }

    @Test
    fun `appendRequiredArgs emits brace-wrapped path segments in order`() {
        val built = buildString { appendRequiredArgs("a", "b") }
        assertEquals("/{a}/{b}", built)
    }

    @Test
    fun `appendOptionalArgs emits a query-template joined by ampersands`() {
        val built = buildString { appendOptionalArgs("q", "page") }
        assertEquals("?q={q}&page={page}", built)
    }

    @Test
    fun `appendOptionalArgs with a single arg emits a leading question mark and no ampersand`() {
        val built = buildString { appendOptionalArgs("q") }
        assertEquals("?q={q}", built)
    }

    @Test
    fun `appendOptionalArgs with no args emits nothing`() {
        val built = buildString { appendOptionalArgs() }
        assertEquals("", built)
    }

    @Test
    fun `appendRequiredParams emits slash-prefixed concrete values in order`() {
        val built = buildString { appendRequiredParams("123", "abc") }
        assertEquals("/123/abc", built)
    }

    @Test
    fun `appendOptionalParams skips null values and starts with a question mark`() {
        val built = buildString { appendOptionalParams("q" to "hi", "page" to null, "sort" to "asc") }
        // The null "page" is dropped; the first emitted param uses '?', the rest '&'.
        assertEquals("?q=hi&sort=asc", built)
    }

    @Test
    fun `appendOptionalParams with all null values emits nothing`() {
        val built = buildString { appendOptionalParams("q" to null, "page" to null) }
        assertEquals("", built)
    }

    @Test
    fun `appendOptionalParams uses question mark only for the first non-null even when earlier are null`() {
        // The first param is null, so the SECOND (first non-null) must still
        // open the query with '?', not '&'. This is the boundary the
        // `current == initial` length check protects.
        val built = buildString { appendOptionalParams("a" to null, "b" to "x") }
        assertEquals("?b=x", built)
    }

    @Test
    fun `buildDeepLink concatenates the url with the optional query template`() {
        val built = buildDeepLink("https://rutracker.org/forum") { appendOptionalArgs("f") }
        assertEquals("https://rutracker.org/forum?f={f}", built)
    }

    @Test
    fun `buildRoute without a parent graph yields route plus required arg template`() {
        with(TestGraphBuilder(graph = null)) {
            val route = buildRoute(
                route = "category",
                requiredArgsBuilder = { appendRequiredArgs("f") },
            )
            assertEquals("category/{f}", route)
        }
    }

    @Test
    fun `buildRoute prefixes the parent graph segment when present`() {
        with(TestGraphBuilder(graph = "home")) {
            val route = buildRoute(
                route = "category",
                requiredArgsBuilder = { appendRequiredArgs("f") },
            )
            // The parent graph "home" is prepended with a slash separator.
            assertEquals("home/category/{f}", route)
        }
    }

    @Test
    fun `buildRoute composes required and optional templates together`() {
        with(TestGraphBuilder(graph = null)) {
            val route = buildRoute(
                route = "search",
                requiredArgsBuilder = { appendRequiredArgs("id") },
                optionalArgsBuilder = { appendOptionalArgs("q", "page") },
            )
            assertEquals("search/{id}?q={q}&page={page}", route)
        }
    }

    @Test
    fun `buildRoute with concrete params produces a navigable path`() {
        // Mirrors openCategory(id): the same builder, with appendRequiredParams,
        // yields the concrete path the app navigates to.
        with(TestGraphBuilder(graph = null)) {
            val path = buildRoute(
                route = "category",
                requiredArgsBuilder = { appendRequiredParams("789") },
            )
            assertEquals("category/789", path)
        }
    }
}
