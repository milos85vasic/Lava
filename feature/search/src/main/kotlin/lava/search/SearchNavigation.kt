package lava.search

import lava.models.search.Filter
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.buildRoute
import lava.navigation.ui.NavigationAnimations
import lava.navigation.viewModel

private const val SearchHistoryRoute = "search_history"

context(NavigationGraphBuilder)
fun addSearchHistory(
    openLogin: () -> Unit,
    openSearchInput: () -> Unit,
    openSearchResult: (Filter) -> Unit,
    animations: NavigationAnimations,
) = addDestination(
    route = buildRoute(SearchHistoryRoute),
    isStartRoute = true,
    content = {
        SearchScreen(
            viewModel = viewModel(),
            openLogin = openLogin,
            openSearchInput = openSearchInput,
            openSearch = openSearchResult,
        )
    },
    animations = animations,
)
