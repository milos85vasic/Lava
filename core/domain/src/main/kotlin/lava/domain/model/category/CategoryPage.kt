package lava.domain.model.category

import lava.models.forum.Category
import lava.models.forum.ForumSection
import lava.models.topic.Topic
import lava.models.topic.TopicModel

data class CategoryPage(
    val categories: List<Category> = emptyList(),
    val sections: List<ForumSection> = emptyList(),
    val topics: List<TopicModel<out Topic>> = emptyList(),
)

fun CategoryPage.isEmpty() = categories.isEmpty() && topics.isEmpty()
