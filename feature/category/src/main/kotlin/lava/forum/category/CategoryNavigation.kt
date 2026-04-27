package lava.forum.category

import androidx.lifecycle.SavedStateHandle
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

private const val CategoryIdKey = "f"
private const val CategoryRoute = "category"

context(NavigationGraphBuilder)
fun addCategory(
    back: () -> Unit,
    openCategory: (id: String) -> Unit,
    openLogin: () -> Unit,
    openSearchInput: (query: String) -> Unit,
    openTopic: (id: String) -> Unit,
    deepLinkUrls: List<String> = emptyList(),
    animations: NavigationAnimations,
) = addDestination(
    route = buildRoute(
        route = CategoryRoute,
        requiredArgsBuilder = { appendRequiredArgs(CategoryIdKey) },
    ),
    arguments = listOf(NavigationArgument(CategoryIdKey)),
    deepLinks = deepLinkUrls.map { url ->
        NavigationDeepLink(buildDeepLink(url) { appendOptionalArgs(CategoryIdKey) })
    },
    content = {
        CategoryScreen(
            back = back,
            openCategory = openCategory,
            openLogin = openLogin,
            openSearchInput = openSearchInput,
            openTopic = openTopic,
        )
    },
    animations = animations,
)

context(NavigationGraphBuilder, NavigationController)
fun openCategory(id: String) {
    navigate(
        buildRoute(
            route = CategoryRoute,
            requiredArgsBuilder = { appendRequiredParams(id) },
        ),
    )
}

internal val SavedStateHandle.categoryId: String
    get() = require(CategoryIdKey)
