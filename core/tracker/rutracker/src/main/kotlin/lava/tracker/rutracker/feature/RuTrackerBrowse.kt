package lava.tracker.rutracker.feature

import lava.tracker.api.feature.BrowsableTracker
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.ForumTree
import lava.tracker.rutracker.domain.GetCategoryPageUseCase
import lava.tracker.rutracker.domain.GetForumUseCase
import lava.tracker.rutracker.mapper.CategoryPageMapper
import lava.tracker.rutracker.mapper.ForumDtoMapper
import javax.inject.Inject

/**
 * RuTracker implementation of [BrowsableTracker]. Delegates to the legacy
 * [GetCategoryPageUseCase] for category pages and [GetForumUseCase] for the
 * full forum tree. Neither UseCase requires an auth token.
 */
class RuTrackerBrowse @Inject constructor(
    private val getCategoryPage: GetCategoryPageUseCase,
    private val getForum: GetForumUseCase,
    private val categoryMapper: CategoryPageMapper,
    private val forumMapper: ForumDtoMapper,
) : BrowsableTracker {

    override suspend fun browse(category: String?, page: Int): BrowseResult {
        // The legacy UseCase requires a non-null category id (forum id). Browsing
        // without an id is not a rutracker concept — callers should walk the
        // forum tree first. require() throws IllegalArgumentException, which is
        // the correct contract-violation idiom (vs. error()'s IllegalStateException).
        require(category != null) { "RuTracker browse requires a forum category id" }
        val dto = getCategoryPage(category, page)
        return categoryMapper.toBrowseResult(dto, currentPage = page)
    }

    override suspend fun getForumTree(): ForumTree? {
        // RuTracker always serves a forum tree, so this never returns null in
        // practice — but the override matches BrowsableTracker's nullable return
        // so trackers without a forum tree (e.g. RuTor) can satisfy the contract.
        val dto = getForum()
        return forumMapper.toForumTree(dto)
    }
}
