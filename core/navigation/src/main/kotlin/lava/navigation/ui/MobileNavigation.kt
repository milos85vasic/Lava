package lava.navigation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import lava.designsystem.component.Scaffold
import lava.navigation.LocalDeepLinks
import lava.navigation.NavigationController
import lava.navigation.NestedNavigationController
import lava.navigation.bottomBarVisibleAsState
import lava.navigation.canPopBackAsState
import lava.navigation.currentTopLevelRouteAsState
import lava.navigation.model.NavigationBarItem
import lava.navigation.model.NavigationGraphBuilder

@Composable
fun MobileNavigation(
    navigationController: NavigationController,
    navigationGraphBuilder: NavigationGraphBuilder.() -> Unit,
) = Scaffold { padding ->
    NavigationHost(
        modifier = Modifier.padding(padding),
        navigationController = navigationController,
        navigationGraphBuilder = navigationGraphBuilder,
    )
    val deepLinks = LocalDeepLinks.current
    LaunchedEffect(deepLinks.initialDeepLink) {
        deepLinks.initialDeepLink
            ?.let(navigationController::deeplink)
    }
    LaunchedEffect(deepLinks.deepLink) {
        deepLinks.deepLink
            ?.let(navigationController::deeplink)
    }
}

/**
 * LVA-008 Candidate #7 — single-NavHost multiple-back-stack bottom navigation.
 *
 * Renders exactly ONE [NavigationHost] (the Activity-hosted NavHost) inside a
 * [Scaffold]. The bottom-nav graphs are TOP-LEVEL destinations in that same host
 * (registered by [navigationGraphBuilder] alongside the detail destinations like
 * login / topic / category), so there is NO nested NavHost and NO parent
 * NavBackStackEntry acting as an inner host LifecycleOwner.
 *
 * Tab switching uses the official multiple-back-stack pattern
 * (popUpTo(findStartDestination){saveState} + launchSingleTop + restoreState)
 * implemented in [NestedNavigationController]; per-tab back-stack save/restore is
 * preserved exactly as in the previous nested-host design.
 *
 * The bottom bar is shown only while the current top-level destination is one of
 * the bottom-nav graph routes ([NavigationBarItem.route]); navigating to a detail
 * destination (login, topic, …) hides it.
 *
 * Why this fixes LVA-008: the stranded INITIALIZED `search_input` entry now lives
 * in the Activity-hosted controller, whose host LifecycleOwner is the Activity.
 * At Activity destroy the entries are driven through the regular backward
 * lifecycle pass (≥ CREATED before DESTROYED) instead of the parent entry's
 * abrupt collapse straight to DESTROYED that previously stranded the INITIALIZED
 * child and crashed `LifecycleRegistry.checkLifecycleStateTransition`.
 */
@Composable
fun MobileNavigationWithBottomBar(
    navigationController: NestedNavigationController,
    navigationBarItems: List<NavigationBarItem>,
    navigationGraphBuilder: NavigationGraphBuilder.() -> Unit,
) {
    val bottomNavRoutes = remember(navigationBarItems) {
        navigationBarItems.map(NavigationBarItem::route).toSet()
    }
    val bottomBarVisible by navigationController.bottomBarVisibleAsState(bottomNavRoutes)
    Scaffold(
        content = { padding ->
            NavigationHost(
                modifier = Modifier.padding(padding),
                navigationController = navigationController,
                navigationGraphBuilder = navigationGraphBuilder,
            )
            val deepLinks = LocalDeepLinks.current
            LaunchedEffect(deepLinks.initialDeepLink) {
                deepLinks.initialDeepLink
                    ?.let(navigationController::deeplink)
            }
            LaunchedEffect(deepLinks.deepLink) {
                deepLinks.deepLink
                    ?.let(navigationController::deeplink)
            }
        },
        bottomBar = {
            if (bottomBarVisible) {
                val currentGraphRoute by navigationController.currentTopLevelRouteAsState()
                BottomNavigation(
                    items = navigationBarItems,
                    selected = currentGraphRoute,
                    onClick = navigationController::navigateTopLevel,
                )
            }
        },
    )
}

@Composable
fun NestedMobileNavigation(
    navigationController: NestedNavigationController,
    navigationBarItems: List<NavigationBarItem>,
    navigationGraphBuilder: NavigationGraphBuilder.() -> Unit,
) {
    val backHandlerEnabled by navigationController.canPopBackAsState()
    BackHandler(
        enabled = backHandlerEnabled,
        onBack = navigationController::popBackStack,
    )
    Scaffold(
        content = { padding ->
            NavigationHost(
                modifier = Modifier.padding(padding),
                navigationController = navigationController,
                navigationGraphBuilder = navigationGraphBuilder,
            )
        },
        bottomBar = {
            val currentGraphRoute by navigationController.currentTopLevelRouteAsState()
            BottomNavigation(
                items = navigationBarItems,
                selected = currentGraphRoute,
                onClick = navigationController::navigateTopLevel,
            )
        },
    )
}
