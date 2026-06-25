package lava.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavDeepLink
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import lava.navigation.NavigationController
import lava.navigation.model.NavigationArgument
import lava.navigation.model.NavigationDeepLink
import lava.navigation.model.NavigationDestination
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.buildNavigationGraph

@Composable
internal fun NavigationHost(
    modifier: Modifier = Modifier,
    navigationController: NavigationController,
    // LVA-008: when this host is NESTED inside another NavHost destination
    // (the bottom-nav graph mounted from a parent NavBackStackEntry), the
    // inner NavController's host LifecycleOwner defaults to that parent
    // NavBackStackEntry. At activity-destroy the parent entry is driven
    // straight to DESTROYED, and the inner controller's host-lifecycle
    // observer then walks its own back stack moving every entry to
    // DESTROYED — including any entry still INITIALIZED (a search_input
    // entry stranded by the popBackStack()+navigate() pattern that never
    // got composed to CREATED). LifecycleRegistry rejects INITIALIZED ->
    // DESTROYED with "State must be at least 'CREATED' to be moved to
    // 'DESTROYED'", killing the process at MainActivity teardown.
    //
    // Setting activityScopedLifecycle=true binds the inner NavHost's host
    // LifecycleOwner to the Activity's view-tree LifecycleOwner instead of
    // the parent NavBackStackEntry. The Activity reaches CREATED during its
    // own normal lifecycle (driving inner entries to at least CREATED before
    // any teardown), and its destroy path runs the inner controller's
    // entries through the regular RESUMED -> ... -> CREATED -> DESTROYED
    // backward pass rather than the parent-entry's abrupt collapse to
    // DESTROYED. ViewModel scoping is UNAFFECTED — only LocalLifecycleOwner
    // is re-pointed; LocalViewModelStoreOwner + LocalSavedStateRegistryOwner
    // remain the parent entry, so nested-graph ViewModels still clear when
    // the nested destination leaves the back stack.
    activityScopedLifecycle: Boolean = false,
    navigationGraphBuilder: NavigationGraphBuilder.() -> Unit,
) {
    val (startRoute, destinations) = buildNavigationGraph(destinationsBuilder = navigationGraphBuilder)
    val host = @Composable {
        NavHost(
            modifier = modifier,
            navController = navigationController.navHostController,
            startDestination = startRoute,
            builder = { destinations.forEach(::add) },
        )
    }
    if (activityScopedLifecycle) {
        val activityLifecycleOwner = rememberActivityScopedLifecycleOwner()
        CompositionLocalProvider(LocalLifecycleOwner provides activityLifecycleOwner) {
            host()
        }
    } else {
        host()
    }
}

/**
 * Resolves the Activity-level [LifecycleOwner] from the Compose view tree.
 *
 * `ComponentActivity.setContent` installs the Activity as the view-tree
 * lifecycle owner; the outer [NavHost] re-points `LocalLifecycleOwner` to a
 * per-destination NavBackStackEntry via a CompositionLocal (NOT the view
 * tree), so [findViewTreeLifecycleOwner] still returns the Activity here.
 * Falls back to the current `LocalLifecycleOwner` if the view tree has no
 * owner (e.g. an isolated Compose preview) so the host always has a valid
 * lifecycle to bind to.
 */
@Composable
private fun rememberActivityScopedLifecycleOwner(): LifecycleOwner {
    val view = LocalView.current
    val fallback = LocalLifecycleOwner.current
    return remember(view, fallback) { view.findViewTreeLifecycleOwner() ?: fallback }
}

internal fun NavGraphBuilder.add(destination: NavigationDestination) {
    when (destination) {
        is NavigationDestination.Graph -> navigation(
            route = destination.route,
            startDestination = destination.startRoute,
            builder = { destination.destinations.forEach(this::add) },
            enterTransition = destination.animations.enterTransition.toEnterTransition(),
            exitTransition = destination.animations.exitTransition.toExitTransition(),
            popEnterTransition = destination.animations.popEnterTransition.toEnterTransition(),
            popExitTransition = destination.animations.popExitTransition.toExitTransition(),
        )

        is NavigationDestination.Destination -> composable(
            route = destination.route,
            content = { destination.content() },
            arguments = destination.arguments.map(NavigationArgument::toArgument),
            deepLinks = destination.deepLinks.map(NavigationDeepLink::toDeepLink),
            enterTransition = destination.animations.enterTransition.toEnterTransition(),
            exitTransition = destination.animations.exitTransition.toExitTransition(),
            popEnterTransition = destination.animations.popEnterTransition.toEnterTransition(),
            popExitTransition = destination.animations.popExitTransition.toExitTransition(),
        )
    }
}

private fun NavigationArgument.toArgument(): NamedNavArgument {
    return navArgument(name) {
        type = NavType.StringType
        if (this@toArgument.nullable) {
            nullable = this@toArgument.nullable
        }
    }
}

private fun NavigationDeepLink.toDeepLink(): NavDeepLink {
    return navDeepLink { uriPattern = uri }
}
