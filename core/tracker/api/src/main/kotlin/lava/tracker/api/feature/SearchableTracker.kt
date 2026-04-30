package lava.tracker.api.feature

import lava.tracker.api.TrackerFeature
import lava.tracker.api.model.SearchRequest
import lava.tracker.api.model.SearchResult

interface SearchableTracker : TrackerFeature {
    suspend fun search(request: SearchRequest, page: Int = 0): SearchResult
}
