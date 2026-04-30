package lava.tracker.api.feature

import lava.tracker.api.TrackerFeature
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.ForumTree

interface BrowsableTracker : TrackerFeature {
    suspend fun browse(category: String?, page: Int): BrowseResult

    /** Returns null when this tracker has no forum tree (e.g. RuTor). */
    suspend fun getForumTree(): ForumTree?
}
