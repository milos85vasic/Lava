package lava.data.impl.service

import lava.auth.api.TokenProvider
import lava.data.api.service.SearchService
import lava.data.converters.toDto
import lava.data.converters.toSearchPage
import lava.models.Page
import lava.models.forum.Category
import lava.models.search.Filter
import lava.models.topic.Torrent
import lava.network.api.NetworkApi
import javax.inject.Inject

class SearchServiceImpl @Inject constructor(
    private val networkApi: NetworkApi,
    private val tokenProvider: TokenProvider,
) : SearchService {

    override suspend fun search(filter: Filter, page: Int): Page<Torrent> {
        return networkApi.getSearchPage(
            token = tokenProvider.getToken(),
            searchQuery = filter.query.orEmpty(),
            sortType = filter.sort.toDto(),
            sortOrder = filter.order.toDto(),
            period = filter.period.toDto(),
            author = filter.author?.name.orEmpty(),
            authorId = filter.author?.id.orEmpty(),
            categories = filter.categories?.map(Category::id)?.joinToString(",").orEmpty(),
            page = page,
        ).toSearchPage()
    }
}
