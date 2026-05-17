package lava.search.result

import lava.domain.model.LoadStates
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.topic.TopicModel
import lava.models.topic.Torrent

internal data class SearchPageState(
    val filter: Filter,
    val appBarExpanded: Boolean = false,
    val searchContent: SearchResultContent = SearchResultContent.Initial,
    val loadStates: LoadStates = LoadStates.Idle,
    /**
     * SP-3a Phase 4 (Task 4.18). When the SDK emits
     * SearchOutcome.CrossTrackerFallbackProposed, this slot holds the
     * pair (failedTrackerId, proposedTrackerId) so [SearchResultScreen]
     * can render the [CrossTrackerFallbackModal]. Null means no modal.
     */
    val crossTrackerFallback: CrossTrackerFallbackProposal? = null,
    val selectedFilterProvider: String? = null,
    val providerDisplayNames: Map<String, String> = emptyMap(),
)

internal data class CrossTrackerFallbackProposal(
    val failedTrackerId: String,
    val proposedTrackerId: String,
)

internal sealed interface SearchResultContent {
    data object Initial : SearchResultContent
    data object Empty : SearchResultContent

    /**
     * SP-3.2 (2026-04-29). The user is not signed in to the upstream
     * tracker; rutracker (and therefore lava-api-go's `/search`) returns
     * 401 for an unauthenticated client. Rendered with a login button
     * instead of the misleading "Nothing found" empty-state — the user
     * REPORTED this exact confusion ("search does not return any results
     * - we get Nothing found screen!"). Sixth-Law clause 3 primary
     * user-visible state: a clear, actionable login prompt.
     */
    data object Unauthorized : SearchResultContent

    /**
     * Sweep finding #2 closure (2026-05-17, 1.2.29-1049). Top-level
     * search error state with a clear reason + retry affordance. Pre-fix
     * SSE errors silently routed to [Empty] which rendered "Nothing
     * found" — the same misleading shape that motivated [Unauthorized].
     * Now SSE errors set [Error(reason)] and the screen renders an
     * error-colored message + Retry button that routes to
     * [SearchResultAction.RetryClick]. Distinct from Paging3's per-page
     * LoadState.Error (which surfaces in the result-list footer); this
     * is the "no results because the stream itself failed" outcome.
     */
    data class Error(val reason: String) : SearchResultContent

    data class Content(
        val torrents: List<TopicModel<Torrent>>,
        val categories: List<Category>,
    ) : SearchResultContent

    data class Streaming(
        val items: List<TopicModel<Torrent>>,
        val activeProviders: List<ProviderStreamStatus>,
    ) : SearchResultContent
}

data class ProviderStreamStatus(
    val providerId: String,
    val displayName: String,
    val status: StreamStatus,
    val resultCount: Int = 0,
)

enum class StreamStatus { SEARCHING, RECEIVING, DONE, ERROR }

internal val SearchPageState.categories
    get() = when (searchContent) {
        is SearchResultContent.Content -> searchContent.categories
        is SearchResultContent.Empty -> emptyList()
        is SearchResultContent.Error -> emptyList()
        is SearchResultContent.Initial -> emptyList()
        is SearchResultContent.Unauthorized -> emptyList()
        is SearchResultContent.Streaming -> emptyList()
    }
