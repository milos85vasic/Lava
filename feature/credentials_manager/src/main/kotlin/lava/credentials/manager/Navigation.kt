package lava.credentials.manager

import lava.navigation.NavigationController
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.buildRoute
import lava.navigation.ui.NavigationAnimations

private const val CredentialsManagerRoute = "credentials_manager"

context(NavigationGraphBuilder)
fun addCredentialsManager(
    back: () -> Unit,
    animations: NavigationAnimations,
) = addDestination(
    route = buildRoute(CredentialsManagerRoute),
    arguments = emptyList(),
    animations = animations,
) {
    CredentialsManagerScreen(onBack = back)
}

context(NavigationGraphBuilder, NavigationController)
fun openCredentialsManager() {
    navigate(buildRoute(CredentialsManagerRoute))
}
