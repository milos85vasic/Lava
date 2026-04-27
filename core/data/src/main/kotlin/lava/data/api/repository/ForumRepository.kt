package lava.data.api.repository

import lava.models.forum.Category
import lava.models.forum.Forum

interface ForumRepository {
    suspend fun isNotEmpty(): Boolean
    suspend fun isForumFresh(maxAgeInDays: Int = 7): Boolean
    suspend fun storeForum(forum: Forum)
    suspend fun getForum(): Forum
    suspend fun getCategory(id: String): Category?
}
