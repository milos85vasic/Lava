package lava.search.result

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lava.designsystem.color.ProviderColors
import lava.designsystem.component.AppBarState
import lava.designsystem.component.BackButton
import lava.designsystem.component.BodyLarge
import lava.designsystem.component.ExpandableAppBar
import lava.designsystem.component.Icon
import lava.designsystem.component.IconButton
import lava.designsystem.component.InvertedSurface
import lava.designsystem.component.LazyList
import lava.designsystem.component.LocalSnackbarHostState
import lava.designsystem.component.Scaffold
import lava.designsystem.component.ScrollBackFloatingActionButton
import lava.designsystem.drawables.Icon
import lava.designsystem.drawables.LavaIcons
import lava.designsystem.theme.AppTheme
import lava.models.LoadState
import lava.models.search.Filter
import lava.models.search.Period
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.search.result.components.CrossTrackerFallbackModal
import lava.search.result.filter.FilterBar
import lava.ui.component.TopicListItem
import lava.ui.component.appendItems
import lava.ui.component.emptyItem
import lava.ui.component.errorItem
import lava.ui.component.loadingItem
import lava.ui.data.ProviderLabel
import org.orbitmvi.orbit.compose.collectAsState
import org.orbitmvi.orbit.compose.collectSideEffect
import lava.designsystem.R as DsR

@Composable
internal fun SearchResultScreen(
    viewModel: SearchResultViewModel,
    back: () -> Unit,
    openSearchInput: (filter: Filter) -> Unit,
    openSearchResult: (filter: Filter) -> Unit,
    // LVA-052 — providerId threads the source provider so the topic screen can
    // branch the download action (HTTP-file vs `.torrent`); null = active tracker.
    openTopic: (id: String, providerId: String?) -> Unit,
    // SP-3.2 (2026-04-29): hooked up so the Unauthorized empty-state's
    // Login button can navigate to the login screen.
    openLogin: () -> Unit,
) {
    val snackbarHost = LocalSnackbarHostState.current
    val favoriteToggleError = stringResource(lava.ui.R.string.error_title)
    viewModel.collectSideEffect { sideEffect ->
        when (sideEffect) {
            is SearchResultSideEffect.Back -> back()
            is SearchResultSideEffect.OpenLogin -> openLogin()
            is SearchResultSideEffect.OpenSearchInput -> openSearchInput(sideEffect.filter)
            is SearchResultSideEffect.OpenSearchResult -> openSearchResult(sideEffect.filter)
            is SearchResultSideEffect.OpenTopic -> openTopic(sideEffect.id, sideEffect.providerId)
            is SearchResultSideEffect.ShowFavoriteToggleError -> snackbarHost.showSnackbar(favoriteToggleError)
            is SearchResultSideEffect.ShowFallbackDismissedError ->
                snackbarHost.showSnackbar("${sideEffect.failedTracker} is unavailable")
        }
    }
    val state by viewModel.collectAsState()
    SearchResultScreen(state, viewModel::perform)
}

@Composable
private fun SearchResultScreen(
    state: SearchPageState,
    onAction: (SearchResultAction) -> Unit,
) {
    Scaffold(
        topBar = { appBarState ->
            SearchAppBar(
                state = state,
                onAction = onAction,
                appBarState = appBarState,
            )
        },
        content = { padding ->
            SearchResultList(
                modifier = Modifier.padding(padding),
                state = state,
                onAction = onAction,
            )
        },
        floatingActionButton = { ScrollBackFloatingActionButton() },
    )

    // SP-3a Phase 4 (Task 4.18) wiring closure (2026-06-08). When the
    // SDK emits a CrossTrackerFallbackProposed proposal, the ViewModel
    // populates [SearchPageState.crossTrackerFallback]; the screen MUST
    // render the [CrossTrackerFallbackModal] so a real user can accept
    // (re-run on the proposed tracker) or cancel (surface the explicit
    // "<tracker> is unavailable" failure). Pre-fix the modal Composable +
    // FallbackAccept/FallbackDismiss actions existed but the screen never
    // read this slot — the modal was dead-ended (§6.Q/C37 class) and a
    // user could never see it. Tapping a button dispatches through the
    // same `onAction` idiom every other control uses.
    state.crossTrackerFallback?.let { proposal ->
        CrossTrackerFallbackModal(
            failedTracker = state.providerDisplayNames[proposal.failedTrackerId]
                ?: proposal.failedTrackerId,
            proposedTracker = state.providerDisplayNames[proposal.proposedTrackerId]
                ?: proposal.proposedTrackerId,
            onAccept = { onAction(SearchResultAction.FallbackAccept) },
            onDismiss = { onAction(SearchResultAction.FallbackDismiss) },
        )
    }
}

@Composable
private fun SearchAppBar(
    state: SearchPageState,
    onAction: (SearchResultAction) -> Unit,
    appBarState: AppBarState,
) = ExpandableAppBar(
    navigationIcon = { BackButton { onAction(SearchResultAction.BackClick) } },
    title = {
        SearchTextItem(
            modifier = Modifier.fillMaxWidth(),
            filter = state.filter,
            onClick = { onAction(SearchResultAction.SearchClick) },
        )
    },
    actions = {
        FilterButton(
            expanded = state.appBarExpanded,
            icon = state.filter.icon,
            onClick = { onAction(SearchResultAction.ExpandAppBarClick) },
        )
    },
    expanded = state.appBarExpanded,
    expandableContent = {
        FilterBar(
            filter = state.filter,
            categories = state.categories,
            onSelectSort = { onAction(SearchResultAction.SetSort(it)) },
            onSelectOrder = { onAction(SearchResultAction.SetOrder(it)) },
            onSelectPeriod = { onAction(SearchResultAction.SetPeriod(it)) },
            onSelectAuthor = { onAction(SearchResultAction.SetAuthor(it)) },
            onSelectCategories = { onAction(SearchResultAction.SetCategories(it)) },
        )
    },
    appBarState = appBarState,
)

@Composable
private fun FilterButton(
    expanded: Boolean,
    icon: Icon,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "FilterButton_Rotation",
    )
    IconButton(onClick = onClick) {
        Icon(
            modifier = Modifier.rotate(rotation),
            icon = if (expanded) {
                LavaIcons.Expand
            } else {
                icon
            },
            contentDescription = stringResource(R.string.search_screen_content_description_filter),
        )
    }
}

@Composable
private fun SearchTextItem(
    filter: Filter,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = InvertedSurface(
    modifier = modifier
        .fillMaxWidth()
        .height(AppTheme.sizes.default),
    onClick = onClick,
    shape = AppTheme.shapes.small,
    tonalElevation = AppTheme.elevations.medium,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.CenterStart,
    ) {
        BodyLarge(
            modifier = Modifier
                .padding(horizontal = AppTheme.spaces.large)
                .alpha(if (filter.query.isNullOrBlank()) 0.7f else 1f),
            text = filter.query?.takeIf(String::isNotBlank)
                ?: stringResource(DsR.string.designsystem_hint_search),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SearchResultList(
    modifier: Modifier,
    state: SearchPageState,
    onAction: (SearchResultAction) -> Unit,
) = LazyList(
    modifier = modifier,
    contentPadding = PaddingValues(
        top = AppTheme.spaces.medium,
        bottom = AppTheme.spaces.extraLargeBottom,
    ),
    onLastItemVisible = { onAction(SearchResultAction.ListBottomReached) },
) {
    // Issue #3 (2026-06-25 QA video): render the chips from the deterministic,
    // request-derived source of truth (`filterProviderChipIds`) — NOT raw
    // `filter.providerIds` — so the results chip set agrees with the search-input
    // chip set and is stable run-to-run regardless of response order or caller
    // ordering. See SearchPageState.filterProviderChipIds.
    val providerIds = state.filterProviderChipIds
    if (providerIds.isNotEmpty()) {
        item {
            ProviderFilterChipBar(
                providerIds = providerIds,
                providerDisplayNames = state.providerDisplayNames,
                selectedProviderId = state.selectedFilterProvider,
                onProviderSelected = { onAction(SearchResultAction.SetFilterProvider(it)) },
            )
        }
    }
    when (state.loadStates.refresh) {
        is LoadState.Error -> errorItem(onRetryClick = { onAction(SearchResultAction.RetryClick) })
        is LoadState.Loading -> loadingItem()
        is LoadState.NotLoading -> when (state.searchContent) {
            is SearchResultContent.Content -> {
                val torrents = state.selectedFilterProvider?.let { pid ->
                    state.searchContent.torrents.filter { it.providerId == pid }
                } ?: state.searchContent.torrents
                items(items = torrents) { model ->
                    TopicListItem(
                        modifier = Modifier.padding(
                            horizontal = AppTheme.spaces.mediumLarge,
                            vertical = AppTheme.spaces.mediumSmall,
                        ),
                        topicModel = model,
                        providerLabel = providerLabelFor(model, state.providerDisplayNames),
                        onClick = { onAction(SearchResultAction.TopicClick(model)) },
                        onFavoriteClick = { onAction(SearchResultAction.FavoriteClick(model)) },
                    )
                }
                appendItems(
                    state = state.loadStates.append,
                    onRetryClick = { onAction(SearchResultAction.RetryClick) },
                )
            }

            is SearchResultContent.Empty -> emptyItem(
                titleRes = R.string.search_screen_result_empty_title,
                subtitleRes = R.string.search_screen_result_empty_subtitle,
                imageRes = lava.ui.R.drawable.ill_empty,
            )

            // Sweep finding #2 closure (2026-05-17, 1.2.29-1049).
            // Pre-fix SSE-error routed to Empty which rendered "Nothing
            // found" — misleading shape failure mode. Now render a
            // dedicated error placeholder with the localized title +
            // subtitle + a Retry button that fires the existing
            // RetryClick action. The dynamic SSE reason is captured via
            // analytics.recordNonFatal in the ViewModel for operator
            // triage (NOT user-visible per §6.H — error.message could
            // leak query content).
            is SearchResultContent.Error -> item {
                lava.designsystem.component.Placeholder(
                    modifier = Modifier.fillParentMaxSize(),
                    titleRes = R.string.search_screen_result_error_title,
                    subtitleRes = R.string.search_screen_result_error_subtitle,
                    imageRes = lava.ui.R.drawable.ill_empty,
                    action = {
                        lava.designsystem.component.Button(
                            onClick = { onAction(SearchResultAction.RetryClick) },
                            text = stringResource(R.string.search_screen_result_error_retry),
                            color = AppTheme.colors.primary,
                        )
                    },
                )
            }

            // SP-3.2 (2026-04-29): "Login required" empty-state with a
            // Login button — replaces the misleading "Nothing found"
            // when the user is not signed in. Tap → SearchResultAction.LoginClick
            // → SearchResultSideEffect.OpenLogin → screen-level openLogin().
            is SearchResultContent.Unauthorized -> item {
                lava.designsystem.component.Placeholder(
                    modifier = Modifier.fillParentMaxSize(),
                    titleRes = R.string.search_screen_result_unauthorized_title,
                    subtitleRes = R.string.search_screen_result_unauthorized_subtitle,
                    imageRes = lava.ui.R.drawable.ill_empty,
                    action = {
                        lava.designsystem.component.Button(
                            onClick = { onAction(SearchResultAction.LoginClick) },
                            text = stringResource(lava.designsystem.R.string.designsystem_action_login),
                            color = AppTheme.colors.primary,
                        )
                    },
                )
            }

            is SearchResultContent.Streaming -> {
                val filteredItems = state.selectedFilterProvider?.let { pid ->
                    state.searchContent.items.filter { it.providerId == pid }
                } ?: state.searchContent.items
                items(items = filteredItems) { model ->
                    TopicListItem(
                        modifier = Modifier.padding(
                            horizontal = AppTheme.spaces.mediumLarge,
                            vertical = AppTheme.spaces.mediumSmall,
                        ),
                        topicModel = model,
                        providerLabel = providerLabelFor(model, state.providerDisplayNames),
                        onClick = { onAction(SearchResultAction.TopicClick(model)) },
                        onFavoriteClick = { onAction(SearchResultAction.FavoriteClick(model)) },
                    )
                }
                // 2026-06-25 video-cluster fix (#5 — no loading indicator).
                // While at least one provider is still SEARCHING, show a loading
                // spinner below whatever incremental results have arrived. Before
                // this, a freshly-started multi-provider search rendered a BLANK
                // screen (no items yet + no spinner) — perceived as a hang. The
                // terminal Empty / Error / Content states are produced by
                // handleStreamEnd() once every provider reaches a terminal state.
                if (filteredItems.isEmpty() &&
                    state.searchContent.activeProviders.any { it.status == StreamStatus.SEARCHING }
                ) {
                    loadingItem()
                }
            }

            is SearchResultContent.Initial -> loadingItem()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderFilterChipBar(
    providerIds: List<String>,
    providerDisplayNames: Map<String, String>,
    selectedProviderId: String?,
    onProviderSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedProviderId == null,
            onClick = { onProviderSelected(null) },
            label = {
                Text(
                    text = "All",
                    style = MaterialTheme.typography.labelMedium,
                )
            },
            colors = FilterChipDefaults.filterChipColors(),
        )
        providerIds.forEach { pid ->
            val color = ProviderColors.forProvider(pid)
            val displayName = providerDisplayNames[pid] ?: pid
            FilterChip(
                selected = selectedProviderId == pid,
                onClick = { onProviderSelected(pid) },
                label = {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = color.copy(alpha = 0.2f),
                    selectedLabelColor = color,
                ),
            )
        }
    }
}

private fun providerLabelFor(
    model: TopicModel<out Topic>,
    displayNames: Map<String, String>,
): ProviderLabel? {
    val pid = model.providerId ?: return null
    val displayName = displayNames[pid] ?: pid
    return ProviderLabel(
        providerId = pid,
        displayName = displayName,
        color = ProviderColors.forProvider(pid),
    )
}

private val Filter.icon: Icon
    get() {
        var counter = 0
        if (period != Period.ALL_TIME) {
            counter++
        }
        if (author != null) {
            counter++
        }
        counter += categories.orEmpty().size
        return when (counter) {
            0 -> LavaIcons.Filters.NoFilters
            1 -> LavaIcons.Filters.Filters1
            2 -> LavaIcons.Filters.Filters2
            3 -> LavaIcons.Filters.Filters3
            4 -> LavaIcons.Filters.Filters4
            5 -> LavaIcons.Filters.Filters5
            6 -> LavaIcons.Filters.Filters6
            7 -> LavaIcons.Filters.Filters7
            8 -> LavaIcons.Filters.Filters8
            9 -> LavaIcons.Filters.Filters9
            else -> LavaIcons.Filters.Filters9Plus
        }
    }
