package lava.tracker.rutracker.mapper

import lava.network.dto.topic.CommentsPageDto
import lava.tracker.api.model.CommentsPage
import javax.inject.Inject

/**
 * Maps the legacy [CommentsPageDto] (rutracker comments scrape) to the new
 * tracker-api [CommentsPage]. Stub here; populated in Task 2.18.
 */
class CommentsMapper @Inject constructor() {
    fun toCommentsPage(dto: CommentsPageDto): CommentsPage {
        TODO("populated in Task 2.18")
    }
}
