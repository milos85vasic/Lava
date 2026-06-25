package lava.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import lava.logger.api.LoggerFactory
import lava.ui.platform.LocalLoggerFactory

interface NavigationController {
    val navHostController: NavHostController
    fun navigate(route: String)
    fun deeplink(uri: Uri)
    fun popBackStack(): Boolean
}

interface NestedNavigationController : NavigationController {
    fun navigateTopLevel(route: String)
    val currentTopLevelRouteFlow: Flow<String>
    val canPopBackFlow: Flow<Boolean>

    /**
     * LVA-008 Candidate #7 (single-NavHost multi-back-stack collapse).
     *
     * Emits whether the bottom navigation bar should be visible for the current
     * destination, i.e. whether the current top-level route is one of the
     * supplied [bottomNavRoutes]. When the user navigates to a non-bottom-nav
     * top-level destination (login, topic, category, the top-level
     * search_input/search_result detail screens) the bottom bar hides.
     *
     * This exists because in the collapsed single-host architecture the bottom
     * navigation graphs live as TOP-LEVEL destinations in the SAME
     * Activity-hosted [NavHostController] as the detail destinations — there is
     * no longer a nested NavHost, so bottom-bar visibility is derived from the
     * single controller's current destination rather than from a separate inner
     * controller's existence.
     */
    fun bottomBarVisibleFlow(bottomNavRoutes: Set<String>): Flow<Boolean>
}

private open class NavigationControllerImpl(
    override val navHostController: NavHostController,
    loggerFactory: LoggerFactory,
) : NavigationController {

    private val logger = loggerFactory.get("NavigationController")

    protected val currentRoute: String?
        get() = navHostController.currentDestination?.route

    protected val currentGraph: String?
        get() = navHostController.currentDestination?.parent?.route

    protected val currentGraphStartRoute: String?
        get() = navHostController.currentDestination?.parent?.startDestinationRoute

    override fun navigate(route: String) {
        logger.d { "navigate: route=$route" }
        navHostController.navigate(route = route)
    }

    @Suppress("RestrictedApi")
    override fun deeplink(uri: Uri) {
        logger.d { "deeplink: uri=$uri" }
        val deepLinkRequest = NavDeepLinkRequest.Builder.fromUri(uri).build()
        val deepLinkMatch = navHostController.graph.matchDeepLink(deepLinkRequest)
        if (deepLinkMatch != null) {
            navHostController.navigate(request = deepLinkRequest)
        }
    }

    override fun popBackStack(): Boolean {
        return navHostController.navigateUp().also {
            logger.d { "popBackStack: handled=$it" }
        }
    }
}

private class NestedNavigationControllerImpl(
    navHostController: NavHostController,
    loggerFactory: LoggerFactory,
    initialBackState: List<String>,
) : NavigationControllerImpl(navHostController, loggerFactory), NestedNavigationController {

    private val logger = loggerFactory.get("NestedNavigationController")

    private val startTopLevelRoute: String by lazy {
        requireNotNull(navHostController.graph.startDestinationRoute)
    }
    val topLevelBackStack: MutableList<String> = initialBackState.toMutableList()
    private var topLevelRoute: String = ""

    override fun navigateTopLevel(route: String) {
        logger.d { "navigateTopLevel: route=$route" }
        if (route == currentGraph && currentRoute != currentGraphStartRoute) {
            navigate(
                route = route,
                addBackStack = true,
                retain = false,
            )
        } else if (route != currentRoute) {
            navigate(
                route = route,
                addBackStack = true,
                retain = true,
            )
        }
    }

    override val currentTopLevelRouteFlow: Flow<String> by lazy {
        navHostController
            .currentBackStackEntryFlow
            .map { it.destination.run { parent?.route ?: route.orEmpty() } }
            .onEach { logger.d { "currentTopLevelRoute: $it" } }
    }

    override val canPopBackFlow: Flow<Boolean> by lazy {
        currentTopLevelRouteFlow
            .map {
                val canPopBack = topLevelBackStack.isNotEmpty() || it != startTopLevelRoute
                "canPopBack: ($canPopBack) topLevelBackStack=$topLevelBackStack; currentTopLevelRoute=$it;"
                canPopBack
            }
    }

    override fun bottomBarVisibleFlow(bottomNavRoutes: Set<String>): Flow<Boolean> {
        return currentTopLevelRouteFlow
            .map { it in bottomNavRoutes }
            .onEach { logger.d { "bottomBarVisible: $it" } }
    }

    override fun popBackStack(): Boolean {
        return when {
            navHostController.navigateUp() -> true
            topLevelBackStack.isNotEmpty() -> {
                navigate(
                    // LVA-054: removeLast() desugars to java.util.List.removeLast (JDK21
                    // SequencedCollection), absent on Android < API 35 -> NoSuchMethodError.
                    // removeAt(lastIndex) removes and returns the same element, API-safe.
                    route = topLevelBackStack.removeAt(topLevelBackStack.lastIndex),
                    addBackStack = false,
                    retain = true,
                )
                true
            }

            else -> {
                if (isGraphRoot()) {
                    false
                } else {
                    navigate(
                        route = startTopLevelRoute,
                        addBackStack = false,
                        retain = true,
                    )
                    true
                }
            }
        }.also { logger.d { "popBackStack: handled=$it" } }
    }

    private fun isGraphRoot() = topLevelRoute == startTopLevelRoute

    private fun navigate(
        route: String,
        addBackStack: Boolean,
        retain: Boolean,
    ) {
        logger.d { "navigate: route=$route; addHistory=$addBackStack; retain=$retain" }
        // LVA-008 Candidate #7 — the OFFICIAL single-NavHost multiple-back-stack
        // bottom-nav switch: popUpTo(graph.findStartDestination){saveState} +
        // launchSingleTop + restoreState. findStartDestination() is the canonical
        // anchor (matches the Android Navigation bottom-nav guide); it resolves to
        // the start of the (single) Activity-hosted graph rather than the root
        // graph node id, so per-tab back-stacks save/restore correctly.
        navHostController.navigate(route = route) {
            popUpTo(navHostController.graph.findStartDestination().id) { saveState = retain }
            launchSingleTop = true
            restoreState = retain
        }
        if (addBackStack && topLevelRoute.isNotBlank()) {
            topLevelBackStack.remove(topLevelRoute)
            topLevelBackStack.add(topLevelRoute)
        }
        topLevelBackStack.remove(route)
        topLevelRoute = route
    }
}

@Composable
fun rememberNavigationController(): NavigationController {
    val navHostController = rememberNavController()
    val loggerFactory = LocalLoggerFactory.current
    return remember { NavigationControllerImpl(navHostController, loggerFactory) }
}

@Composable
fun rememberNestedNavigationController(): NestedNavigationController {
    val navHostController = rememberNavController()
    val loggerFactory = LocalLoggerFactory.current
    return rememberSaveable(
        inputs = arrayOf(navHostController),
        saver = listSaver(
            save = { it.topLevelBackStack },
            restore = { NestedNavigationControllerImpl(navHostController, loggerFactory, it) },
        ),
        init = { NestedNavigationControllerImpl(navHostController, loggerFactory, emptyList()) },
    )
}

@Composable
internal fun NestedNavigationController.currentTopLevelRouteAsState(): State<String?> {
    return currentTopLevelRouteFlow.collectAsState(null)
}

@Composable
internal fun NestedNavigationController.canPopBackAsState(): State<Boolean> {
    return canPopBackFlow.collectAsState(false)
}

@Composable
internal fun NestedNavigationController.bottomBarVisibleAsState(
    bottomNavRoutes: Set<String>,
): State<Boolean> {
    // Initial value true: in the collapsed single host the start destination is
    // always a bottom-nav graph, so the bar is visible on first composition.
    return bottomBarVisibleFlow(bottomNavRoutes).collectAsState(true)
}
