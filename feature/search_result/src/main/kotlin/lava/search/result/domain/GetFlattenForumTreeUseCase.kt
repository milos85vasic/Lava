package lava.search.result.domain

import kotlinx.coroutines.withContext
import lava.dispatchers.api.Dispatchers
import lava.domain.usecase.GetForumUseCase
import lava.models.forum.ForumCategory
import lava.search.result.domain.models.ForumTreeItem
import lava.search.result.domain.models.SelectState
import javax.inject.Inject

internal class GetFlattenForumTreeUseCase @Inject constructor(
    private val getForumUseCase: GetForumUseCase,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(
        expanded: Set<String>,
        selected: Set<String>,
    ): List<ForumTreeItem> {
        return withContext(dispatchers.default) {
            val forumTree = getForumUseCase()
            mutableListOf<ForumTreeItem>().apply {
                forumTree.children.map { root ->
                    val rootItem = ForumTreeItem.Root(
                        id = root.id,
                        name = root.name,
                        expandable = root.children.isNotEmpty(),
                        expanded = expanded.contains(root.id),
                    )
                    add(rootItem)
                    if (expanded.contains(root.id)) {
                        root.children.forEach { forum ->
                            val forumId = forum.id
                            val children = forum.children
                            val childrenIds = forum.children.map(ForumCategory::id)
                            val groupItem = ForumTreeItem.Group(
                                id = forumId,
                                name = forum.name,
                                expandable = children.isNotEmpty(),
                                expanded = expanded.contains(forumId),
                                selectState = when {
                                    selected.contains(forumId) -> SelectState.Selected
                                    childrenIds.any(selected::contains) -> SelectState.PartSelected
                                    else -> SelectState.Unselected
                                },
                            )
                            add(groupItem)
                            if (expanded.contains(forumId)) {
                                children.forEach { category ->
                                    add(
                                        ForumTreeItem.Category(
                                            id = category.id,
                                            name = category.name,
                                            selectState = if (selected.contains(category.id)) {
                                                SelectState.Selected
                                            } else {
                                                SelectState.Unselected
                                            },
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
