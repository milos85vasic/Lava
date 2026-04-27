package lava.network.api

import lava.network.dto.FileDto
import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.forum.ForumDto
import lava.network.dto.search.SearchPageDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.TopicPageDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.user.FavoritesDto

interface NetworkApi {
    suspend fun checkAuthorized(token: String): Boolean
    suspend fun login(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ): AuthResponseDto
    suspend fun getFavorites(token: String): FavoritesDto
    suspend fun addFavorite(token: String, id: String): Boolean
    suspend fun removeFavorite(token: String, id: String): Boolean
    suspend fun getForum(): ForumDto
    suspend fun getCategory(id: String, page: Int?): CategoryPageDto
    suspend fun getSearchPage(
        token: String,
        searchQuery: String?,
        categories: String?,
        author: String?,
        authorId: String?,
        sortType: SearchSortTypeDto?,
        sortOrder: SearchSortOrderDto?,
        period: SearchPeriodDto?,
        page: Int?,
    ): SearchPageDto
    suspend fun getTopic(token: String, id: String, page: Int?): ForumTopicDto
    suspend fun getTopicPage(token: String, id: String, page: Int?): TopicPageDto
    suspend fun getCommentsPage(token: String, id: String, page: Int?): CommentsPageDto
    suspend fun addComment(token: String, topicId: String, message: String): Boolean
    suspend fun getTorrent(token: String, id: String): TorrentDto
    suspend fun download(token: String, id: String): FileDto
}
