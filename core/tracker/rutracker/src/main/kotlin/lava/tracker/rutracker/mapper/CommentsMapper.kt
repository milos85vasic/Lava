package lava.tracker.rutracker.mapper

import kotlinx.datetime.Instant
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.PostDto
import lava.tracker.api.model.Comment
import lava.tracker.api.model.CommentsPage
import javax.inject.Inject

/**
 * Maps the legacy [CommentsPageDto] (rutracker comments scrape) to the new
 * tracker-api [CommentsPage].
 *
 * Each [PostDto] becomes a [Comment]:
 *  - author     := post.author.name
 *  - timestamp  := Instant.parse(post.date) or null on parse failure
 *  - body       := flattened plain text from post.children (the rich
 *                  PostElementDto AST). See PostElementFlatten.kt.
 *
 * Information-loss note (Section E concern): the new [Comment] model has
 * no metadata field — the rich AST cannot be preserved. The reverse mapper
 * in Section E will have to encode the flattened body text as the entire
 * post (a single Text node). Embedded quotes, code blocks, spoilers, and
 * links collapse to text annotation; round-tripping from Comment back to
 * PostDto is therefore lossy by construction.
 */
class CommentsMapper @Inject constructor() {
    fun toCommentsPage(dto: CommentsPageDto, currentPage: Int): CommentsPage {
        val items = dto.posts.map { it.toComment() }
        return CommentsPage(
            items = items,
            totalPages = dto.pages,
            currentPage = currentPage,
        )
    }
}

internal fun PostDto.toComment(): Comment {
    val timestamp = runCatching { Instant.parse(date) }.getOrNull()
    val body = children.flattenToText()
    return Comment(
        author = author.name,
        timestamp = timestamp,
        body = body,
    )
}
