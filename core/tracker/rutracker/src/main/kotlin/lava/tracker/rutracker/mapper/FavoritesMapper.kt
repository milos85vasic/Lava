package lava.tracker.rutracker.mapper

import lava.network.dto.user.FavoritesDto
import lava.tracker.api.model.TorrentItem
import javax.inject.Inject

/**
 * Maps the legacy [FavoritesDto] (rutracker bookmarks scrape) to the new
 * tracker-api list of [TorrentItem]. Stub here; populated in Task 2.19.
 */
class FavoritesMapper @Inject constructor() {
    fun toTorrentItems(dto: FavoritesDto): List<TorrentItem> {
        TODO("populated in Task 2.19")
    }
}
