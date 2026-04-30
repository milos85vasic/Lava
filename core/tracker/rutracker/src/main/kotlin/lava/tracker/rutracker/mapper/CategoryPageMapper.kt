package lava.tracker.rutracker.mapper

import lava.network.dto.forum.CategoryPageDto
import lava.tracker.api.model.BrowseResult
import javax.inject.Inject

/**
 * Maps the legacy [CategoryPageDto] (rutracker category browse scrape) to
 * the new tracker-api [BrowseResult]. Stub here; populated in Task 2.16.
 */
class CategoryPageMapper @Inject constructor() {
    fun toBrowseResult(dto: CategoryPageDto, currentPage: Int): BrowseResult {
        TODO("populated in Task 2.16")
    }
}
