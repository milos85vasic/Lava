package lava.tracker.api.feature

import lava.tracker.api.TrackerFeature
import lava.tracker.api.model.CommentsPage

interface CommentsTracker : TrackerFeature {
    suspend fun getComments(topicId: String, page: Int): CommentsPage

    /** Returns true on successful add. May trigger AuthenticatableTracker.login() upstream. */
    suspend fun addComment(topicId: String, message: String): Boolean
}
