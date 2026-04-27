package lava.data.api.service

import lava.models.Page
import lava.models.forum.Forum
import lava.models.forum.ForumItem

interface ForumService {
    suspend fun getForum(): Forum
    suspend fun getCategoryPage(id: String, page: Int): Page<ForumItem>
}
