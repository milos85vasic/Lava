package lava.navigation

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
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
}

/**
 * Honors [NavHostController]'s documented main-thread contract.
 *
 * `navController.navigate(...)` walks the back stack and calls
 * `LifecycleRegistry.setCurrentState`, which throws
 * `IllegalStateException: Method setCurrentState must be called on the main
 * thread` when invoked off the main thread. On the production happy path this
 * is already satisfied: navigation is driven either from
 * `collectSideEffect` (collected inside the composition, on
 * `AndroidUiDispatcher.Main`) or from Compose event handlers (also Main), so
 * [block] runs inline and this is a no-op. The branch only diverges under the
 * Compose UI test harness, whose `FrameDeferringContinuationInterceptor` can
 * resume the `collectSideEffect` continuation off the device main thread —
 * there the navigation is marshaled onto the main looper instead of crashing.
 * This enforces a contract; it does not mask a production defect.
 */
private inline fun runOnMainThread(crossinline block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block()
    } else {
        Handler(Looper.getMainLooper()).post { block() }
    }
}

/**
 * Result-returning sibling of [runOnMainThread]. `popBackStack()` calls
 * `navHostController.navigateUp()`, which (like `navigate()`) drives
 * `LifecycleRegistry.setCurrentState` and so MUST run on the main thread — but
 * unlike `navigate()` its Boolean "was-handled" result is consumed by the caller
 * (e.g. system-back handling), so a fire-and-forget post will not do. On the main
 * thread it runs inline (the production happy path — no-op). Off the main thread
 * (the Compose UI test harness path documented on [runOnMainThread]) it marshals
 * the block onto the main looper and blocks for the result; no deadlock arises
 * because the main thread never waits on the calling (background) thread.
 */
private inline fun <T> runOnMainThreadResult(crossinline block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) {
        return block()
    }
    val latch = java.util.concurrent.CountDownLatch(1)
    var result: Result<T>? = null
    Handler(Looper.getMainLooper()).post {
        result = runCatching { block() }
        latch.countDown()
    }
    latch.await()
    return result!!.getOrThrow()
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
        runOnMainThread { navHostController.navigate(route = route) }
    }

    @Suppress("RestrictedApi")
    override fun deeplink(uri: Uri) {
        logger.d { "deeplink: uri=$uri" }
        val deepLinkRequest = NavDeepLinkRequest.Builder.fromUri(uri).build()
        val deepLinkMatch = navHostController.graph.matchDeepLink(deepLinkRequest)
        if (deepLinkMatch != null) {
            runOnMainThread { navHostController.navigate(request = deepLinkRequest) }
        }
    }

    override fun popBackStack(): Boolean {
        return runOnMainThreadResult { navHostController.navigateUp() }.also {
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

    override fun popBackStack(): Boolean {
        return runOnMainThreadResult {
            popBackStackOnMain()
        }.also { logger.d { "popBackStack: handled=$it" } }
    }

    private fun popBackStackOnMain(): Boolean {
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
        }
    }

    private fun isGraphRoot() = topLevelRoute == startTopLevelRoute

    private fun navigate(
        route: String,
        addBackStack: Boolean,
        retain: Boolean,
    ) {
        logger.d { "navigate: route=$route; addHistory=$addBackStack; retain=$retain" }
        runOnMainThread {
            navHostController.navigate(route = route) {
                popUpTo(navHostController.graph.id) { saveState = retain }
                launchSingleTop = true
                restoreState = retain
            }
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
