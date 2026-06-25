package digital.vasic.lava.client.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import digital.vasic.lava.client.BuildConfig
import digital.vasic.lava.client.R
import lava.applink.PackageManagerSiblingAppLauncher
import lava.credentials.manager.addCredentialsManager
import lava.credentials.manager.openCredentialsManager
import lava.designsystem.component.Page
import lava.designsystem.component.PagesScreen
import lava.designsystem.drawables.LavaIcons
import lava.favorites.FavoritesScreen
import lava.feature.credentials.addCredentials
import lava.feature.credentials.openCredentials
import lava.forum.ForumScreen
import lava.forum.bookmarks.BookmarksScreen
import lava.forum.category.addCategory
import lava.forum.category.openCategory
import lava.login.addLogin
import lava.login.openLogin
import lava.menu.MenuScreen
import lava.navigation.NavigationController
import lava.navigation.NestedNavigationController
import lava.navigation.model.NavigationBarItem
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.buildRoute
import lava.navigation.ui.MobileNavigationWithBottomBar
import lava.navigation.ui.NavigationAnimations
import lava.navigation.ui.NavigationAnimations.Companion.slideInLeft
import lava.navigation.ui.NavigationAnimations.Companion.slideInRight
import lava.navigation.ui.NavigationAnimations.Companion.slideOutLeft
import lava.navigation.ui.NavigationAnimations.Companion.slideOutRight
import lava.provider.config.addProviderConfig
import lava.provider.config.openProviderConfig
import lava.search.addSearchHistory
import lava.search.input.addSearchInput
import lava.search.input.openSearchInput
import lava.search.result.addSearchResult
import lava.search.result.openSearchResult
import lava.topic.addTopic
import lava.topic.openTopic
import lava.visited.VisitedScreen

/**
 * LVA-008 Candidate #7 — single-NavHost multiple-back-stack collapse.
 *
 * Previously this composed a nested NavHost (the four bottom-nav graphs) INSIDE
 * an outer-host `addNestedNavigation` destination, which made a parent
 * NavBackStackEntry the inner host's LifecycleOwner and stranded an INITIALIZED
 * `search_input` entry at Activity destroy (LVA-008 teardown crash).
 *
 * Now there is exactly ONE Activity-hosted NavHost. The four bottom-nav graphs
 * (search/forum/topics/menu) are TOP-LEVEL destinations in that same host,
 * alongside the detail destinations (login, credentials, provider config,
 * category, topic, and the top-level search_input/search_result detail screens
 * reached from forum/category). The bottom bar is rendered by
 * [MobileNavigationWithBottomBar] and shown only on the bottom-nav graph routes;
 * tab switching uses the official multi-back-stack pattern (preserved in
 * [NestedNavigationController]).
 *
 * All destinations that existed before remain reachable (§11.4.122 — no screen
 * dropped): the four bottom-nav graphs PLUS every modal/detail destination.
 */
@Composable
fun MobileNavigation(navigationController: NestedNavigationController) {
    val navigationBarItems = remember { BottomRoute.entries.map(BottomRoute::navigationBarItem) }
    MobileNavigationWithBottomBar(
        navigationController = navigationController,
        navigationBarItems = navigationBarItems,
    ) {
        with(navigationController) {
            // --- Bottom-nav graphs: TOP-LEVEL in the single host (search = start) ---
            addSearch(
                openLogin = { openLogin() },
                openTopic = { id, providerId -> openTopic(id, providerId) },
            )
            addForum(
                openSearchInput = { openSearchInput(it) },
                openLogin = { openLogin() },
                // forum/category topics are single-tracker → active-tracker default.
                openTopic = { id -> openTopic(id, null) },
            )
            addTopics(
                // LVA-070 — favorites/visited persist the source provider (Room
                // providerId column), so a favorited/visited archiveorg/gutenberg
                // topic reopens with `?p=<providerId>` and routes to HTTP_DOWNLOAD.
                // Null ⇒ active-tracker fallback (legacy rows).
                openTopic = { id, providerId -> openTopic(id, providerId) },
            )
            addMenu(
                openLogin = { openLogin() },
                openCredentials = { openCredentialsManager() },
                openProviderConfig = { openProviderConfig(it) },
            )

            // --- Detail / modal destinations: TOP-LEVEL siblings (bottom bar hidden) ---
            addLogin(
                back = ::popBackStack,
                animations = NavigationAnimations.ScaleInOutAnimation,
            )
            addCredentials(
                back = ::popBackStack,
                animations = NavigationAnimations.ScaleInOutAnimation,
            )
            addCredentialsManager(
                back = ::popBackStack,
                animations = NavigationAnimations.ScaleInOutAnimation,
            )
            addProviderConfig(
                back = ::popBackStack,
                openCredentialsManager = { openCredentialsManager() },
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
                // LVA-052 — thread the source providerId from multi-search
                // results so the topic download action branches HTTP-file vs
                // `.torrent`; null falls back to the active tracker.
                openTopic = { id, providerId -> openTopic(id, providerId) },
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
        }
    }
}

context(NavigationGraphBuilder, NavigationController)
private fun addSearch(
    openLogin: () -> Unit,
    // LVA-052 — providerId threads the multi-search result's source provider
    // for the topic download branch.
    openTopic: (id: String, providerId: String?) -> Unit,
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
    // LVA-070 — favorites/visited now persist the source provider, so their
    // open-topic callback carries it through to route HTTP_DOWNLOAD providers.
    openTopic: (id: String, providerId: String?) -> Unit,
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
    openCredentials: () -> Unit = {},
    openProviderConfig: (String) -> Unit = {},
) = addDestination(
    route = BottomRoute.Menu.route,
    animations = BottomRoute.Menu.animations,
    content = {
        // Sub-project 2 (on-device API): build the SiblingAppLauncher here in
        // the app layer where Context + BuildConfig are available. The download
        // URL comes from BuildConfig (§6.R, sourced from .env); when
        // unconfigured it falls back to the documented placeholder. No market://
        // anywhere — both apps are distributed via Firebase App Distribution.
        val context = LocalContext.current
        val apiAppLauncher = remember(context) {
            PackageManagerSiblingAppLauncher.from(
                context = context,
                candidatePackageIds = listOf(
                    BuildConfig.API_RELEASE_PACKAGE,
                    BuildConfig.API_TARGET_PACKAGE,
                ).distinct(),
                downloadUrl = BuildConfig.LAVA_API_APP_DOWNLOAD_URL,
                fallbackDownloadUrl = "https://lava.app/download/api-app",
            )
        }
        MenuScreen(
            openLogin = openLogin,
            openCredentials = openCredentials,
            openProviderConfig = openProviderConfig,
            apiAppLauncher = apiAppLauncher,
        )
    },
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
