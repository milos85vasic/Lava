package lava.tracker.rutracker.mapper

import lava.network.dto.forum.ForumDto
import lava.tracker.api.model.ForumTree
import javax.inject.Inject

/**
 * Maps the legacy [ForumDto] (rutracker forum tree scrape) to the new
 * tracker-api [ForumTree]. Stub here; populated in Task 2.15.
 */
class ForumDtoMapper @Inject constructor() {
    fun toForumTree(dto: ForumDto): ForumTree {
        TODO("populated in Task 2.15")
    }
}
