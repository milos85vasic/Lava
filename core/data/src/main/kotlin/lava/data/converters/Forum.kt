package lava.data.converters

import lava.database.entity.BookmarkEntity
import lava.database.entity.ForumCategoryEntity
import lava.models.Page
import lava.models.forum.Category
import lava.models.forum.Forum
import lava.models.forum.ForumCategory
import lava.models.forum.ForumItem
import lava.models.forum.ForumSection
import lava.network.dto.forum.CategoryDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.forum.ForumDto
import lava.network.dto.forum.SectionDto
import lava.network.dto.topic.ForumTopicDto

internal fun ForumDto.toForum() = Forum(
    children = children.mapIndexed { index, rootCategory ->
        ForumCategory(
            id = rootCategory.id ?: "c-$index",
            name = rootCategory.name,
            children = rootCategory.children.orEmpty().map(CategoryDto::toForumCategory),
        )
    },
)

private fun CategoryDto.toForumCategory(): ForumCategory = ForumCategory(
    id = requireNotNull(id),
    name = name,
    children = children.orEmpty().map(CategoryDto::toForumCategory),
)

internal fun CategoryPageDto.toCategoryPage() = Page(
    page = page,
    pages = pages,
    items = children.orEmpty().map(CategoryDto::toForumItem) +
        topics.orEmpty().map(ForumTopicDto::toForumItem) +
        sections.orEmpty().map(SectionDto::toForumItem),
)

internal fun CategoryDto.toCategory(): Category = Category(requireNotNull(id), name)

private fun CategoryDto.toForumItem(): ForumItem = ForumItem.Category(toCategory())
private fun SectionDto.toForumItem(): ForumItem = ForumItem.Section(ForumSection(name, topics))
private fun ForumTopicDto.toForumItem(): ForumItem = ForumItem.Topic(toTopic())

internal fun Category.toBookmarkEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    timestamp = System.currentTimeMillis(),
    category = this,
)

internal fun ForumCategoryEntity.toCategory(): Category = Category(id, name)
