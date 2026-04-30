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
        // forum tree first. Mappers in Task 2.16 will define the failure shape.
        val id = category ?: error("RuTracker browse requires a forum category id")
        val dto = getCategoryPage(id, page)
        return categoryMapper.toBrowseResult(dto)
    }

    override suspend fun getForumTree(): ForumTree {
        val dto = getForum()
        return forumMapper.toForumTree(dto)
    }
}
