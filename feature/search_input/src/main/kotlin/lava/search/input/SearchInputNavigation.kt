package lava.search.input

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.search.Order
import lava.models.search.Period
import lava.models.search.Sort
import lava.models.topic.Author
import lava.navigation.NavigationController
import lava.navigation.model.NavigationArgument
import lava.navigation.model.NavigationGraphBuilder
import lava.navigation.model.appendOptionalArgs
import lava.navigation.model.appendOptionalParams
import lava.navigation.model.buildRoute
import lava.navigation.ui.NavigationAnimations
import lava.navigation.viewModel

private const val QueryKey = "query"
private const val CategoriesKey = "categories"
private const val AuthorNameKey = "author_name"
private const val AuthorIdKey = "author_id"
private const val SortKey = "sort"
private const val OrderKey = "order"
private const val PeriodKey = "period"
private const val SearchInputRoute = "search_input"

context(NavigationGraphBuilder)
fun addSearchInput(
    back: () -> Unit,
    openSearchResult: (Filter) -> Unit,
    animations: NavigationAnimations,
) {
    addDestination(
        route = buildRoute(
            route = SearchInputRoute,
            optionalArgsBuilder = {
                appendOptionalArgs(
                    QueryKey,
                    CategoriesKey,
                    AuthorNameKey,
                    AuthorIdKey,
                    SortKey,
                    OrderKey,
                    PeriodKey,
                )
            },
        ),
        arguments = listOf(
            NavigationArgument(QueryKey, true),
            NavigationArgument(CategoriesKey, true),
            NavigationArgument(AuthorNameKey, true),
            NavigationArgument(AuthorIdKey, true),
            NavigationArgument(SortKey, true),
            NavigationArgument(OrderKey, true),
            NavigationArgument(PeriodKey, true),
        ),
        animations = animations,
    ) {
        SearchInputScreen(
            viewModel = viewModel(),
            back = back,
            openSearchResult = openSearchResult,
        )
    }
}

context(NavigationGraphBuilder, NavigationController)
fun openSearchInput(filter: Filter = Filter()) {
    navigate(
        buildRoute(
            route = SearchInputRoute,
            optionalArgsBuilder = {
                appendOptionalParams(
                    QueryKey to filter.query?.takeIf(String::isNotBlank)?.urlEncoded(),
                    CategoriesKey to filter.categories.queryParam(),
                    AuthorIdKey to filter.author?.id?.urlEncoded(),
                    AuthorNameKey to filter.author?.name?.urlEncoded(),
                    SortKey to filter.sort.queryParam,
                    OrderKey to filter.order.queryParam,
                    PeriodKey to filter.period.queryParam,
                )
            },
        ),
        // LVA-008 Candidate #8 — see KDoc below.
        launchSingleTop = true,
    )
}

context(NavigationGraphBuilder, NavigationController)
fun openSearchInput(categoryId: String) {
    navigate(
        buildRoute(
            route = SearchInputRoute,
            optionalArgsBuilder = {
                appendOptionalParams(
                    CategoriesKey to categoryId.urlEncoded(),
                )
            },
        ),
        // LVA-008 Candidate #8 — see KDoc on [openSearchInput] below.
        launchSingleTop = true,
    )
}

/*
 * LVA-008 Candidate #8 (UNCONFIRMED, device-gated by Challenge C06 + C11).
 *
 * The two `openSearchInput` overloads above navigate into `search_input` with
 * `launchSingleTop = true` so a second, never-composed (`INITIALIZED`)
 * `search_input` `NavBackStackEntry` is never minted on the back stack. The
 * §11.4.150 deep-research pass identifies a duplicate `INITIALIZED`
 * `search_input` entry as the one that strands below `CREATED` at `MainActivity`
 * destroy and crashes `LifecycleRegistry`'s `CREATED -> DESTROYED` guard (the
 * LVA-008 teardown ISE).
 *
 * The research predicted the duplicate was minted by a `launchSingleTop` +
 * `popUpTo` single-top combo on the `search_input -> search_result` transition;
 * on inspection that transition is the app-layer `popBackStack();
 * openSearchResult(it)` lambda (no single-top combo present), so this candidate
 * instead prevents the duplicate at the navigation INTO `search_input`, using
 * `launchSingleTop` — the navigation-compose idiom for "do not stack a second
 * instance of the same destination". The already-device-FALSIFIED
 * atomic-`popUpTo`-replace (incident hypothesis_2) acted on the forward
 * (`search_result`) transition; this acts on the `search_input` entry
 * navigation and is distinct from all 6 prior falsified avenues. UNCONFIRMED
 * until C06 + C11 pass on the thinker containerized-KVM gate.
 */

internal val SavedStateHandle.filter: Filter
    get() = Filter(
        query = get(QueryKey),
        categories = categories,
        author = author,
        sort = Sort.fromQueryParam(get(SortKey)),
        order = Order.fromQueryParam(get(OrderKey)),
        period = Period.fromQueryParam(get(PeriodKey)),
    )

private val SavedStateHandle.categories: List<Category>?
    get() = get<String>(CategoriesKey)
        ?.split(",")
        ?.map { Category(it, "") }

private val SavedStateHandle.author: Author?
    get() = if (contains(AuthorIdKey) || contains(AuthorNameKey)) {
        Author(get(AuthorIdKey), get<String>(AuthorNameKey).orEmpty())
    } else {
        null
    }

private fun List<Category>?.queryParam(): String? {
    return this?.takeIf(List<Category>::isNotEmpty)
        ?.map { it.id.urlEncoded() }
        ?.joinToString(",")
}

/**
 * Percent-encodes a route parameter value (LVA-049). Without this, a value
 * containing route-reserved characters (`&`, `?`, `=`, `#`, spaces) corrupts the
 * navigation route: everything after a raw `&`/`?` is parsed as a separate query
 * parameter (or dropped). The matching decode happens in Navigation-Compose when
 * the host navigates the route (`Uri.decode`), so the [SavedStateHandle.filter]
 * getter receives the original value back verbatim.
 */
private fun String.urlEncoded(): String = Uri.encode(this)

private val Sort.queryParam
    get() = when (this) {
        Sort.DATE -> "1"
        Sort.TITLE -> "2"
        Sort.DOWNLOADED -> "4"
        Sort.SEEDS -> "10"
        Sort.LEECHES -> "11"
        Sort.SIZE -> "7"
    }

private fun Sort.Companion.fromQueryParam(param: String?) = when (param) {
    "1" -> Sort.DATE
    "2" -> Sort.TITLE
    "4" -> Sort.DOWNLOADED
    "10" -> Sort.SEEDS
    "11" -> Sort.LEECHES
    "7" -> Sort.SIZE
    else -> Sort.DATE
}

private val Order.queryParam
    get() = when (this) {
        Order.ASCENDING -> "1"
        Order.DESCENDING -> "2"
    }

private fun Order.Companion.fromQueryParam(param: String?) = when (param) {
    "1" -> Order.ASCENDING
    "2" -> Order.DESCENDING
    else -> Order.ASCENDING
}

private val Period.queryParam
    get() = when (this) {
        Period.ALL_TIME -> "-1"
        Period.TODAY -> "1"
        Period.LAST_THREE_DAYS -> "3"
        Period.LAST_WEEK -> "7"
        Period.LAST_TWO_WEEKS -> "14"
        Period.LAST_MONTH -> "32"
    }

private fun Period.Companion.fromQueryParam(param: String?) = when (param) {
    "-1" -> Period.ALL_TIME
    "1" -> Period.TODAY
    "3" -> Period.LAST_THREE_DAYS
    "7" -> Period.LAST_WEEK
    "14" -> Period.LAST_TWO_WEEKS
    "32" -> Period.LAST_MONTH
    else -> Period.ALL_TIME
}
