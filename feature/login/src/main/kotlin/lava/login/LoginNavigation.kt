package lava.login

import lava.navigation.NavigationController
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.buildRoute
import lava.navigation.ui.NavigationAnimations
import lava.navigation.viewModel

private const val LoginRoute = "login"

context(NavigationGraphBuilder)
fun addLogin(
    back: () -> Unit,
    animations: NavigationAnimations,
) = addDestination(
    route = buildRoute(LoginRoute),
    arguments = emptyList(),
    animations = animations,
) {
    LoginScreen(
        viewModel = viewModel(),
        back = back,
    )
}

context(NavigationGraphBuilder, NavigationController)
fun openLogin() {
    navigate(buildRoute(LoginRoute))
}
