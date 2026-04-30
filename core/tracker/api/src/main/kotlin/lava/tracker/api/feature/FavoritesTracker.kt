package lava.tracker.api.feature

import lava.tracker.api.TrackerFeature
import lava.tracker.api.model.TorrentItem

interface FavoritesTracker : TrackerFeature {
    suspend fun list(): List<TorrentItem>

    suspend fun add(id: String): Boolean

    suspend fun remove(id: String): Boolean
}
