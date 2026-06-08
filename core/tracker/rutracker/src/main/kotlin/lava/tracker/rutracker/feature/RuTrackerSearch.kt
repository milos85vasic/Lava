package lava.tracker.rutracker.feature

import lava.auth.api.TokenProvider
import lava.tracker.api.feature.SearchableTracker
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult
import lava.tracker.rutracker.domain.GetSearchPageUseCase
import lava.tracker.rutracker.magnet.RuTrackerMagnetCache
import lava.tracker.rutracker.mapper.SearchPageMapper
import lava.tracker.rutracker.mapper.toLegacySearchParams
import javax.inject.Inject

/**
 * RuTracker implementation of [SearchableTracker]. Delegates to the legacy
 * [GetSearchPageUseCase], decomposing the new [SearchRequest] into the
 * positional params it expects via [toLegacySearchParams].
 *
 * §6.E: each result row that carries a genuinely-parsed magnet is recorded into
 * the shared [RuTrackerMagnetCache] so a subsequent synchronous
 * [lava.tracker.api.feature.DownloadableTracker.getMagnetLink] resolves without
 * re-fetching (mirrors RuTorSearch).
 */
class RuTrackerSearch @Inject constructor(
    private val getSearchPage: GetSearchPageUseCase,
    private val mapper: SearchPageMapper,
    private val tokenProvider: TokenProvider,
    private val magnetCache: RuTrackerMagnetCache,
) : SearchableTracker {

    override suspend fun search(request: SearchRequest, page: Int): SearchResult {
        val token = tokenProvider.getToken()
        val legacy = request.toLegacySearchParams()
        val dto = getSearchPage(
            token = token,
            searchQuery = request.query,
            categories = legacy.categories,
            author = request.author,
            authorId = null,
            sortType = legacy.sortType,
            sortOrder = legacy.sortOrder,
            period = legacy.period,
            page = page,
        )
        val result = mapper.toSearchResult(dto, currentPage = page)
        result.items.forEach { magnetCache.put(it.torrentId, it.magnetUri) }
        return result
    }
}
