package lava.topic

import androidx.lifecycle.SavedStateHandle
import lava.models.search.Filter
import lava.navigation.NavigationController
import lava.navigation.model.NavigationArgument
import lava.navigation.model.NavigationDeepLink
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.appendOptionalArgs
import lava.navigation.model.appendRequiredArgs
import lava.navigation.model.appendRequiredParams
import lava.navigation.model.buildDeepLink
import lava.navigation.model.buildRoute
import lava.navigation.require
import lava.navigation.ui.NavigationAnimations
import lava.navigation.viewModel

private const val TopicIdKey = "t"
private const val TopicRoute = "topic"

context(NavigationGraphBuilder)
fun addTopic(
    back: () -> Unit,
    openCategory: (id: String) -> Unit,
    openLogin: () -> Unit,
    openSearch: (filter: Filter) -> Unit,
    deepLinkUrls: List<String> = emptyList(),
    animations: NavigationAnimations,
) = addDestination(
    route = buildRoute(
        route = TopicRoute,
        optionalArgsBuilder = { appendRequiredArgs(TopicIdKey) },
    ),
    arguments = listOf(NavigationArgument(TopicIdKey)),
    deepLinks = deepLinkUrls.map { url ->
        NavigationDeepLink(buildDeepLink(url) { appendOptionalArgs(TopicIdKey) })
    },
    animations = animations,
) {
    TopicScreen(
        viewModel = viewModel(),
        back = back,
        openCategory = openCategory,
        openLogin = openLogin,
        openSearch = openSearch,
    )
}

context(NavigationGraphBuilder, NavigationController)
fun openTopic(id: String) {
    navigate(
        buildRoute(
            route = TopicRoute,
            requiredArgsBuilder = { appendRequiredParams(id) },
        ),
    )
}

internal val SavedStateHandle.id: String
    get() = require(TopicIdKey)
