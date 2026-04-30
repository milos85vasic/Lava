package lava.tracker.rutracker.mapper

import lava.network.dto.forum.CategoryDto
import lava.network.dto.forum.ForumDto
import lava.tracker.api.model.ForumCategory
import lava.tracker.api.model.ForumTree
import javax.inject.Inject

/**
 * Maps the legacy [ForumDto] (rutracker forum tree scrape) to the new
 * tracker-api [ForumTree].
 *
 * The legacy CategoryDto is a recursive tree (`children: List<CategoryDto>?`)
 * whose root has no id. We strip the root and emit each top-level child as
 * a "root category" of the new ForumTree. Parent IDs are computed during
 * recursion and stamped into [ForumCategory.parentId].
 *
 * Information-loss notes (relevant to Section E):
 *  - CategoryDto.id is nullable in the legacy model (root has none, and
 *    occasionally rutracker's HTML omits it for grouping headers). The new
 *    ForumCategory.id is non-null; we synthesize an empty string when the
 *    legacy id is null. The reverse mapper in Section E should treat empty
 *    ids as a "preserve as null" signal.
 */
class ForumDtoMapper @Inject constructor() {
    fun toForumTree(dto: ForumDto): ForumTree {
        val rootChildren = dto.children.map { it.toForumCategory(parentId = null) }
        return ForumTree(rootCategories = rootChildren)
    }
}

internal fun CategoryDto.toForumCategory(parentId: String?): ForumCategory {
    val ownId = id.orEmpty()
    val mappedChildren = (children ?: emptyList()).map { it.toForumCategory(parentId = ownId) }
    return ForumCategory(
        id = ownId,
        name = name,
        parentId = parentId,
        children = mappedChildren,
    )
}
