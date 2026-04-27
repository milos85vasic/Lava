package lava.navigation.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
    navigationGraphBuilder: NavigationGraphBuilder.() -> Unit,
) {
    val (startRoute, destinations) = buildNavigationGraph(destinationsBuilder = navigationGraphBuilder)
    NavHost(
        modifier = modifier,
        navController = navigationController.navHostController,
        startDestination = startRoute,
        builder = { destinations.forEach(::add) },
    )
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
