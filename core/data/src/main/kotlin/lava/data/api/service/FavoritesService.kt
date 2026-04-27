package lava.data.api.service

import lava.models.topic.Topic

interface FavoritesService {
    suspend fun getFavorites(): List<Topic>
    suspend fun add(id: String): Boolean
    suspend fun remove(id: String): Boolean
}
