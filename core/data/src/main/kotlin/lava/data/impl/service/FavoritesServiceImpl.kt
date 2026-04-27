package lava.data.impl.service

import lava.auth.api.AuthService
import lava.auth.api.TokenProvider
import lava.data.api.service.FavoritesService
import lava.data.converters.toFavorites
import lava.models.topic.Topic
import lava.network.api.NetworkApi
import javax.inject.Inject

class FavoritesServiceImpl @Inject constructor(
    private val authService: AuthService,
    private val tokenProvider: TokenProvider,
    private val networkApi: NetworkApi,
) : FavoritesService {

    override suspend fun getFavorites(): List<Topic> {
        return if (authService.isAuthorized()) {
            runCatching {
                networkApi.getFavorites(tokenProvider.getToken()).toFavorites()
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }
    }

    override suspend fun add(id: String): Boolean {
        return if (authService.isAuthorized()) {
            networkApi.addFavorite(tokenProvider.getToken(), id)
        } else {
            true
        }
    }

    override suspend fun remove(id: String): Boolean {
        return if (authService.isAuthorized()) {
            networkApi.removeFavorite(tokenProvider.getToken(), id)
        } else {
            true
        }
    }
}
