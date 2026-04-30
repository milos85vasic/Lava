package lava.tracker.rutracker.api

import lava.network.dto.FileDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto

interface RuTrackerInnerApi {
    suspend fun mainPage(token: String): String
    suspend fun login(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ): Pair<String?, String>
    suspend fun search(
        token: String,
        searchQuery: String?,
        categories: String?,
        author: String?,
        authorId: String?,
        sortType: SearchSortTypeDto?,
        sortOrder: SearchSortOrderDto?,
        period: SearchPeriodDto?,
        page: Int?,
    ): String
    suspend fun forum(): String
    suspend fun category(id: String, page: Int?): String
    suspend fun topic(token: String, id: String, page: Int? = null): String
    suspend fun download(token: String, id: String): FileDto
    suspend fun profile(userId: String): String
    suspend fun postMessage(token: String, topicId: String, formToken: String, message: String): String
    suspend fun favorites(token: String, page: Int?): String
    suspend fun addFavorite(token: String, id: String, formToken: String): String
    suspend fun removeFavorite(token: String, id: String, formToken: String): String
    suspend fun futureDownloads(token: String, page: Int?): String
    suspend fun addFutureDownload(token: String, id: String, formToken: String): String
    suspend fun removeFutureDownload(token: String, id: String, formToken: String): String
}
