package lava.feature.tracker.settings

import lava.navigation.NavigationController
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.buildRoute
import lava.navigation.ui.NavigationAnimations

private const val TrackerSettingsRoute = "tracker_settings"

/**
 * Add the Tracker Settings destination to the navigation graph. Mirrors
 * the addLogin / addAccount / addConnection conventions used elsewhere.
 *
 * Added in SP-3a Phase 4 (Task 4.19).
 */
context(NavigationGraphBuilder)
fun addTrackerSettings(
    back: () -> Unit,
    animations: NavigationAnimations,
) = addDestination(
    route = buildRoute(TrackerSettingsRoute),
    arguments = emptyList(),
    animations = animations,
) {
    TrackerSettingsScreen(onBack = back)
}

context(NavigationGraphBuilder, NavigationController)
fun openTrackerSettings() {
    navigate(buildRoute(TrackerSettingsRoute))
}
