package digital.vasic.lava.client.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import digital.vasic.lava.client.R
import lava.designsystem.component.Page
import lava.designsystem.component.PagesScreen
import lava.designsystem.drawables.LavaIcons
import lava.favorites.FavoritesScreen
import lava.forum.ForumScreen
import lava.forum.bookmarks.BookmarksScreen
import lava.forum.category.addCategory
import lava.forum.category.openCategory
import lava.login.addLogin
import lava.login.openLogin
import lava.menu.MenuScreen
import lava.navigation.NavigationController
import lava.navigation.model.NavigationBarItem
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.buildRoute
import lava.navigation.rememberNestedNavigationController
import lava.navigation.ui.MobileNavigation
import lava.navigation.ui.NavigationAnimations
import lava.navigation.ui.NavigationAnimations.Companion.slideInLeft
import lava.navigation.ui.NavigationAnimations.Companion.slideInRight
import lava.navigation.ui.NavigationAnimations.Companion.slideOutLeft
import lava.navigation.ui.NavigationAnimations.Companion.slideOutRight
import lava.navigation.ui.NestedMobileNavigation
import lava.search.addSearchHistory
import lava.search.input.addSearchInput
import lava.search.input.openSearchInput
import lava.search.result.addSearchResult
import lava.search.result.openSearchResult
import lava.topic.addTopic
import lava.topic.openTopic
import lava.visited.VisitedScreen

@Composable
fun MobileNavigation(navigationController: NavigationController) {
    MobileNavigation(navigationController) {
        with(navigationController) {
            addLogin(
                back = ::popBackStack,
                animations = NavigationAnimations.ScaleInOutAnimation,
            )
            addSearchInput(
                back = ::popBackStack,
                openSearchResult = {
                    popBackStack()
                    openSearchResult(it)
                },
                animations = NavigationAnimations.Default,
            )
            addSearchResult(
                back = ::popBackStack,
                openSearchInput = { openSearchInput(it) },
                openSearchResult = { openSearchResult(it) },
                openTopic = { openTopic(it) },
                openLogin = { openLogin() },
                deepLinkUrls = DeepLinks.searchResultUrls,
                animations = NavigationAnimations.Default,
            )
            addCategory(
                back = ::popBackStack,
                openCategory = { openCategory(it) },
                openLogin = { openLogin() },
                openSearchInput = { openSearchInput(it) },
                openTopic = { openTopic(it) },
                deepLinkUrls = DeepLinks.categoryUrls,
                animations = NavigationAnimations.ScaleInOutAnimation,
            )
            addTopic(
                back = ::popBackStack,
                openCategory = { openCategory(it) },
                openLogin = { openLogin() },
                openSearch = { openSearchResult(it) },
                deepLinkUrls = DeepLinks.topicUrls,
                animations = NavigationAnimations.ScaleInOutAnimation,
            )
            addNestedNavigation(
                openSearchInput = { openSearchInput(it) },
                openLogin = { openLogin() },
                openTopic = { openTopic(it) },
            )
        }
    }
}

context(NavigationGraphBuilder)
private fun addNestedNavigation(
    openSearchInput: (id: String) -> Unit,
    openLogin: () -> Unit,
    openTopic: (id: String) -> Unit,
) = addDestination {
    val navigationBarItems = remember { BottomRoute.entries.map(BottomRoute::navigationBarItem) }
    val navigationController = rememberNestedNavigationController()
    with(navigationController) {
        NestedMobileNavigation(
            navigationController = navigationController,
            navigationBarItems = navigationBarItems,
        ) {
            addSearch(
                openLogin = openLogin,
                openTopic = openTopic,
            )
            addForum(
                openSearchInput = openSearchInput,
                openLogin = openLogin,
                openTopic = openTopic,
            )
            addTopics(
                openTopic = openTopic,
            )
            addMenu(
                openLogin = openLogin,
            )
        }
    }
}

context(NavigationGraphBuilder, NavigationController)
private fun addSearch(
    openLogin: () -> Unit,
    openTopic: (id: String) -> Unit,
) = addGraph(
    isStartRoute = true,
    route = BottomRoute.Search.route,
    animations = BottomRoute.Search.animations,
) {
    addSearchHistory(
        openLogin = openLogin,
        openSearchInput = { openSearchInput() },
        openSearchResult = { openSearchResult(it) },
        animations = NavigationAnimations.Default,
    )
    addSearchInput(
        back = ::popBackStack,
        openSearchResult = {
            popBackStack()
            openSearchResult(it)
        },
        animations = NavigationAnimations.FadeInOutAnimations,
    )
    addSearchResult(
        back = ::popBackStack,
        openSearchInput = { openSearchInput(it) },
        openSearchResult = { openSearchResult(it) },
        openTopic = openTopic,
        openLogin = openLogin,
        animations = NavigationAnimations.Default,
    )
}

context(NavigationGraphBuilder, NavigationController)
private fun addForum(
    openSearchInput: (categoryId: String) -> Unit,
    openLogin: () -> Unit,
    openTopic: (id: String) -> Unit,
) = addGraph(
    route = BottomRoute.Forum.route,
    animations = BottomRoute.Forum.animations,
) {
    addCategory(
        back = ::popBackStack,
        openCategory = { openCategory(it) },
        openLogin = openLogin,
        openSearchInput = openSearchInput,
        openTopic = openTopic,
        animations = BottomRoute.Forum.animations,
    )
    addDestination(
        route = buildRoute("forums"),
        isStartRoute = true,
    ) {
        PagesScreen(
            pages = listOf(
                Page(
                    labelResId = R.string.tab_title_forum,
                    icon = LavaIcons.Forum,
                    content = { ForumScreen { openCategory(it) } },
                ),
                Page(
                    labelResId = R.string.tab_title_bookmarks,
                    icon = LavaIcons.Bookmarks,
                    content = { BookmarksScreen { openCategory(it) } },
                ),
            ),
        )
    }
}

context(NavigationGraphBuilder)
private fun addTopics(
    openTopic: (id: String) -> Unit,
) = addDestination(
    route = BottomRoute.Topics.route,
    animations = BottomRoute.Topics.animations,
) {
    PagesScreen(
        pages = listOf(
            Page(
                labelResId = R.string.tab_title_favorites,
                icon = LavaIcons.Favorite,
                content = { FavoritesScreen(openTopic = openTopic) },
            ),
            Page(
                labelResId = R.string.tab_title_recents,
                icon = LavaIcons.History,
                content = { VisitedScreen(openTopic = openTopic) },
            ),
        ),
    )
}

context(NavigationGraphBuilder, NavigationController)
private fun addMenu(
    openLogin: () -> Unit,
) = addDestination(
    route = BottomRoute.Menu.route,
    animations = BottomRoute.Menu.animations,
    content = { MenuScreen(openLogin = openLogin) },
)

private enum class BottomRoute(val navigationBarItem: NavigationBarItem) {
    Search(
        navigationBarItem = NavigationBarItem(
            route = "search",
            labelResId = R.string.label_search,
            icon = LavaIcons.Search,
        ),
    ),
    Forum(
        navigationBarItem = NavigationBarItem(
            route = "forum",
            labelResId = R.string.label_forum,
            icon = LavaIcons.Forum,
        ),
    ),
    Topics(
        navigationBarItem = NavigationBarItem(
            route = "topics",
            labelResId = R.string.label_topics,
            icon = LavaIcons.Topics,
        ),
    ),
    Menu(
        navigationBarItem = NavigationBarItem(
            route = "menu",
            labelResId = R.string.label_menu,
            icon = LavaIcons.Menu,
        ),
    ),
    ;

    val route = navigationBarItem.route

    val animations: NavigationAnimations = NavigationAnimations(
        enterTransition = {
            val route = BottomRoute.valueOf(from.graph ?: from.route)
            when {
                route == null -> fadeIn()
                route.ordinal > ordinal -> slideInRight()
                route.ordinal < ordinal -> slideInLeft()
                else -> fadeIn()
            }
        },
        exitTransition = {
            val route = BottomRoute.valueOf(to.graph ?: to.route)
            when {
                route == null -> fadeOut()
                route.ordinal > ordinal -> slideOutRight()
                route.ordinal < ordinal -> slideOutLeft()
                else -> fadeOut()
            }
        },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutLeft() },
    )

    private companion object {
        fun valueOf(value: String?): BottomRoute? {
            return entries.firstOrNull { it.route == value }
        }
    }
}

private object DeepLinks {
    private const val BASE_URL = "rutracker.org/forum/"
    val topicUrls = listOf("${BASE_URL}viewtopic.php")
    val categoryUrls = listOf("${BASE_URL}viewforum.php")
    val searchResultUrls = listOf("${BASE_URL}tracker.php")
}
