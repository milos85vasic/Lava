package lava.provider.config

import androidx.lifecycle.SavedStateHandle
import lava.navigation.NavigationController
import lava.navigation.model.NavigationArgument
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.appendRequiredArgs
import lava.navigation.model.appendRequiredParams
import lava.navigation.model.buildRoute
import lava.navigation.require
import lava.navigation.ui.NavigationAnimations

private const val ProviderConfigRoute = "provider_config"
private const val ProviderIdKey = ProviderConfigViewModel.PROVIDER_ID_KEY

context(NavigationGraphBuilder)
fun addProviderConfig(
    back: () -> Unit,
    openCredentialsManager: () -> Unit,
    animations: NavigationAnimations,
) = addDestination(
    route = buildRoute(
        route = ProviderConfigRoute,
        requiredArgsBuilder = { appendRequiredArgs(ProviderIdKey) },
    ),
    arguments = listOf(NavigationArgument(ProviderIdKey)),
    animations = animations,
) {
    ProviderConfigScreen(
        onBack = back,
        onOpenCredentialsManager = openCredentialsManager,
    )
}

context(NavigationGraphBuilder, NavigationController)
fun openProviderConfig(providerId: String) {
    navigate(
        buildRoute(
            route = ProviderConfigRoute,
            requiredArgsBuilder = { appendRequiredParams(providerId) },
        ),
    )
}

internal val SavedStateHandle.providerId: String
    get() = require(ProviderIdKey)
