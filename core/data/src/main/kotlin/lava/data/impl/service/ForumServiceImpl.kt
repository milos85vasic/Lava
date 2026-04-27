package lava.data.impl.service

import lava.data.api.service.ForumService
import lava.data.converters.toCategoryPage
import lava.data.converters.toForum
import lava.models.Page
import lava.models.forum.Forum
import lava.models.forum.ForumItem
import lava.network.api.NetworkApi
import javax.inject.Inject

class ForumServiceImpl @Inject constructor(
    private val networkApi: NetworkApi,
) : ForumService {
    override suspend fun getForum(): Forum {
        return networkApi.getForum().toForum()
    }

    override suspend fun getCategoryPage(id: String, page: Int): Page<ForumItem> {
        return networkApi.getCategory(id, page).toCategoryPage()
    }
}
