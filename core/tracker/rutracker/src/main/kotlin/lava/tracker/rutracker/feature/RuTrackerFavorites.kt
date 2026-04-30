package lava.tracker.rutracker.feature

import lava.auth.api.TokenProvider
import lava.tracker.api.feature.FavoritesTracker
import lava.tracker.api.model.TorrentItem
import lava.tracker.rutracker.domain.AddFavoriteUseCase
import lava.tracker.rutracker.domain.GetFavoritesUseCase
import lava.tracker.rutracker.domain.RemoveFavoriteUseCase
import lava.tracker.rutracker.mapper.FavoritesMapper
import javax.inject.Inject

/**
 * RuTracker implementation of [FavoritesTracker]. All three operations
 * (list / add / remove) require a valid auth token.
 */
class RuTrackerFavorites @Inject constructor(
    private val getFavorites: GetFavoritesUseCase,
    private val addFavorite: AddFavoriteUseCase,
    private val removeFavorite: RemoveFavoriteUseCase,
    private val mapper: FavoritesMapper,
    private val tokenProvider: TokenProvider,
) : FavoritesTracker {

    override suspend fun list(): List<TorrentItem> {
        val token = tokenProvider.getToken()
        val dto = getFavorites(token)
        return mapper.toTorrentItems(dto)
    }

    override suspend fun add(id: String): Boolean {
        val token = tokenProvider.getToken()
        return addFavorite(token, id)
    }

    override suspend fun remove(id: String): Boolean {
        val token = tokenProvider.getToken()
        return removeFavorite(token, id)
    }
}
