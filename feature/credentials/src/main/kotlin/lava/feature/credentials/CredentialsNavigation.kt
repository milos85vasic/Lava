package lava.feature.credentials

import lava.navigation.NavigationController
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.buildRoute
import lava.navigation.ui.NavigationAnimations

private const val CredentialsRoute = "credentials"

/**
 * Add the Credentials Management destination to the navigation graph.
 *
 * Added in Multi-Provider Extension (Task 6.12).
 */
context(NavigationGraphBuilder)
fun addCredentials(
    back: () -> Unit,
    animations: NavigationAnimations,
) = addDestination(
    route = buildRoute(CredentialsRoute),
    arguments = emptyList(),
    animations = animations,
) {
    CredentialsScreen(onBack = back)
}

context(NavigationGraphBuilder, NavigationController)
fun openCredentials() {
    navigate(buildRoute(CredentialsRoute))
}
