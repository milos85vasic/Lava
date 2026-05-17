package lava.tracker.rutracker.mapper

import lava.network.dto.auth.AuthResponseDto
import lava.network.dto.auth.CaptchaDto
import lava.network.dto.auth.UserDto
import lava.network.dto.forum.CategoryDto
import lava.network.dto.forum.CategoryPageDto
import lava.network.dto.forum.ForumDto
import lava.network.dto.search.SearchPageDto
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.PostDto
import lava.network.dto.topic.Text
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TopicPageCommentsDto
import lava.network.dto.topic.TopicPageDto
import lava.network.dto.topic.TorrentDataDto
import lava.network.dto.topic.TorrentDescriptionDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import lava.network.dto.user.FavoritesDto
import lava.tracker.api.model.AuthState
import lava.tracker.api.model.BrowseResult
import lava.tracker.api.model.CaptchaChallenge
import lava.tracker.api.model.Comment
import lava.tracker.api.model.CommentsPage
import lava.tracker.api.model.ForumCategory
import lava.tracker.api.model.ForumTree
import lava.tracker.api.model.LoginResult
import lava.tracker.api.model.SearchResult
import lava.tracker.api.model.TopicDetail
import lava.tracker.api.model.TopicPage
import lava.tracker.api.model.TorrentItem
import javax.inject.Inject

/**
 * Inverse of the forward mappers in this package
 * (SearchPageMapper / CategoryPageMapper / ForumDtoMapper / TopicMapper /
 * CommentsMapper / FavoritesMapper / AuthMapper).
 *
 * Used by [SwitchingNetworkApi] (Section G) to translate new SDK results
 * back into legacy DTOs that feature ViewModels still expect. This is a
 * temporary bridge: the SDK is the source of truth from Section G onward,
 * but `feature/` modules still consume the `:core:network:api` DTO
 * surface. When Spec 2 migrates `feature/` to consume [LavaTrackerSdk]
 * directly, this
 * aggregator and the reverse mappers it composes are deleted.
 *
 * Round-trip equivalence guarantee:
 *   `forward(reverse(forward(dto))) == forward(dto)`
 * which is the strongest equivalence achievable given the legitimate
 * information loss documented per-mapper (formatted size strings, rich
 * post AST, UserDto.id/avatarUrl, etc.). The strict bidirectional
 * `reverse(forward(dto)) == dto` is NOT guaranteed because forward
 * mapping deliberately drops some DTO data (root-only metadata, post
 * tree structure, `UserDto` profile fields).
 *
 * Note on [LegacySearchParams]: Section E does NOT reverse the
 * `SearchRequest -> LegacySearchParams` direction. Both
 * `SortField.RELEVANCE -> SearchSortTypeDto.Date` and
 * `TimePeriod.LAST_YEAR -> SearchPeriodDto.AllTime` are forward-only
 * collapses (multiple model values map to the same DTO value), which
 * means there is no canonical inverse. Reversing search-page results
 * (`SearchResult -> SearchPageDto`) is well-defined and is what this
 * aggregator implements.
 */
class RuTrackerDtoMappers @Inject constructor() {

    fun searchResultToDto(result: SearchResult): SearchPageDto = SearchPageDto(
        page = result.currentPage,
        pages = result.totalPages,
        torrents = result.items.map { it.toTorrentDto() },
    )

    fun browseResultToDto(result: BrowseResult): CategoryPageDto {
        val topics: List<ForumTopicDto> = result.items.map { it.toForumTopicDto() }
        val category = result.category?.toCategoryDto() ?: CategoryDto(id = null, name = "")
        return CategoryPageDto(
            category = category,
            page = result.currentPage,
            pages = result.totalPages,
            sections = null,
            children = category.children,
            topics = topics,
        )
    }

    fun forumTreeToDto(tree: ForumTree): ForumDto =
        ForumDto(children = tree.rootCategories.map { it.toCategoryDto() })

    /**
     * Inverse of [TopicMapper.toTopicDetail]. Per clause D adaptation, the
     * default branch is [TorrentDto] (the most common). The metadata key
     * `"rutracker.kind"` overrides:
     *   - `"topic"`    -> [TopicDto] (thin)
     *   - `"comments"` -> [CommentsPageDto] (uncommon, no posts available)
     *   - default      -> [TorrentDto] with description from [TopicDetail.description]
     *
     * Description text becomes a single-element [TorrentDescriptionDto] wrapping
     * a [Text] node (per clause B: rich AST is not round-trippable, so a
     * single Text leaf is the canonical lossless inverse for the "no rich
     * formatting" subset of post content).
     */
    fun topicDetailToDto(d: TopicDetail): ForumTopicDto =
        when (d.torrent.metadata["rutracker.kind"]) {
            "topic" -> d.torrent.toTopicDto()
            "comments" -> CommentsPageDto(
                id = d.torrent.torrentId,
                title = d.torrent.title,
                author = d.torrent.metadata["rutracker.authorId"]
                    ?.let { AuthorDto(id = it, name = it) },
                category = d.torrent.toCategoryDtoOrNull(),
                page = 1,
                pages = 1,
                posts = emptyList(),
            )
            else -> {
                val base = d.torrent.toTorrentDto()
                val description = d.description?.takeIf { it.isNotEmpty() }
                    ?.let { TorrentDescriptionDto(children = listOf(Text(it))) }
                base.copy(description = description)
            }
        }

    /**
     * Inverse of [TopicMapper.toTopicPage]. Synthesizes an empty
     * [TopicPageCommentsDto] because [TopicPage] does not carry comments
     * inline (per Section D, posts live on [CommentsPage]). Tests that need
     * a fuller round-trip should compose [topicPageToDto] with
     * [commentsPageToDto] via the same origin DTO.
     *
     * Uses metadata keys (Section D forward contract):
     *  - "rutracker.size_text", "rutracker.date_text", "rutracker.tags",
     *    "rutracker.status", "rutracker.posterUrl",
     *    "rutracker.categoryId", "rutracker.categoryName",
     *    "rutracker.authorId"
     */
    fun topicPageToDto(p: TopicPage): TopicPageDto {
        val item = p.topic.torrent
        val torrentData = TorrentDataDto(
            tags = item.metadata["rutracker.tags"],
            posterUrl = item.metadata["rutracker.posterUrl"],
            status = item.metadata["rutracker.status"]?.let { name ->
                runCatching { TorrentStatusDto.valueOf(name) }.getOrNull()
            },
            // Forward mapper preserves the original string in metadata; prefer
            // it over an Instant.toString() reformat to keep the user-visible
            // date display identical across round-trip.
            date = item.metadata["rutracker.date_text"]
                ?: item.publishDate?.toString(),
            size = item.metadata["rutracker.size_text"],
            seeds = item.seeders,
            leeches = item.leechers,
            magnetLink = item.magnetUri,
        )
        return TopicPageDto(
            id = item.torrentId,
            title = item.title,
            author = item.metadata["rutracker.authorId"]
                ?.let { AuthorDto(id = it, name = it) },
            category = item.toCategoryDtoOrNull(),
            torrentData = torrentData,
            commentsPage = TopicPageCommentsDto(
                page = p.currentPage,
                pages = p.totalPages,
                posts = emptyList(),
            ),
        )
    }

    /**
     * Inverse of [CommentsMapper.toCommentsPage].
     *
     * Each [Comment] becomes a [PostDto] with:
     *  - `id` = "" (placeholder; Comment carries no post id — clause A
     *    documents that this is unrecoverable on the reverse path)
     *  - `author` = AuthorDto(id="", name=comment.author) (clause A: id
     *    is unrecoverable, name is the round-trippable signal)
     *  - `date` = ISO-8601 string from comment.timestamp, or "" if null
     *  - `children` = single [Text] element wrapping the body (clause B:
     *    rich AST is forward-only-lossy, this is the canonical inverse)
     *
     * Round-trip from CommentsPageDto to CommentsPage and back is lossy by
     * construction (rich post AST flattens to text), but
     * `forward(reverse(forward(dto))) == forward(dto)` IS achievable because
     * the second forward call also flattens to the same text.
     */
    fun commentsPageToDto(p: CommentsPage): CommentsPageDto =
        CommentsPageDto(
            id = "",
            title = "",
            page = p.currentPage,
            pages = p.totalPages,
            posts = p.items.map { it.toPostDto() },
        )

    /**
     * Inverse of [FavoritesMapper.toTorrentItems]. Each TorrentItem becomes
     * either a [TorrentDto] or a [TopicDto] based on the
     * `metadata["rutracker.kind"]` discriminator (clause D adaptation:
     * default branch is TorrentDto). The forward mapper drops
     * CommentsPageDto entries via mapNotNull, so the reverse path never
     * needs to produce them — fav lists are always Topic-or-Torrent.
     */
    fun favoritesToDto(items: List<TorrentItem>): FavoritesDto =
        FavoritesDto(topics = items.map { it.toForumTopicDto() })

    /**
     * Inverse of [AuthMapper.toLoginResult].
     *
     * Branch table:
     *  - state == Authenticated && sessionToken != null -> Success(UserDto)
     *      with synthesized id="" and avatarUrl="" because UserDto's id
     *      and avatarUrl are forward-dropped (clause A: unrecoverable).
     *  - state == Authenticated && sessionToken == null -> degenerate;
     *      we treat as WrongCredits(captcha=null) rather than fabricating
     *      a fake token (a fake token would mislead downstream auth-bearer
     *      injection).
     *  - state is CaptchaRequired                       -> CaptchaRequired(captcha)
     *  - state == Unauthenticated && captchaChallenge != null
     *                                                   -> WrongCredits(captcha)
     *  - state == Unauthenticated && captchaChallenge == null
     *                                                   -> WrongCredits(null)
     */
    fun loginResultToDto(r: LoginResult): AuthResponseDto {
        val state = r.state
        return when (state) {
            is AuthState.Authenticated -> {
                val token = r.sessionToken
                if (token != null) {
                    AuthResponseDto.Success(
                        user = UserDto(
                            id = "",
                            token = token,
                            avatarUrl = "",
                        ),
                    )
                } else {
                    AuthResponseDto.WrongCredits(captcha = null)
                }
            }
            is AuthState.CaptchaRequired ->
                AuthResponseDto.CaptchaRequired(captcha = state.challenge.toCaptchaDto())
            is AuthState.Unauthenticated ->
                AuthResponseDto.WrongCredits(captcha = r.captchaChallenge?.toCaptchaDto())
            // Bug 1 (2026-05-17, §6.L 57th invocation): reverse path for the
            // new ServiceUnavailable state. Preserves the reason string so
            // downstream consumers (UI ViewModel, lava-api-go bridge if /
            // when it adopts the wire-shape) can render it verbatim.
            is AuthState.ServiceUnavailable ->
                AuthResponseDto.ServiceUnavailable(
                    reason = state.reason,
                    captcha = r.captchaChallenge?.toCaptchaDto(),
                )
        }
    }
}

/**
 * Reverse of [SearchPageMapper.toTorrentItem] / [TopicDto.toTorrentItem].
 *
 * Reads metadata keys "rutracker.categoryId", "rutracker.categoryName",
 * "rutracker.authorId", "rutracker.tags", "rutracker.status",
 * "rutracker.size_text" plus the typed fields the forward mapper carried
 * directly (seeders -> seeds, magnetUri -> magnetLink, publishDate epoch
 * seconds -> date).
 *
 * Field synthesis:
 *  - [AuthorDto.name] is required (non-null) by the legacy DTO but
 *    forward mapping never preserves it. We fall back to the metadata
 *    "rutracker.authorId" if present, else empty string. The presence
 *    of an "rutracker.authorId" key is the signal that an author existed
 *    on the forward DTO; without it we omit the AuthorDto entirely.
 *  - [TorrentDto.description] is not round-trippable from this mapper
 *    (descriptions live on TopicDetail, not TorrentItem). Always null.
 *  - [TorrentStatusDto] is restored by name; an unrecognised string maps
 *    to null (graceful) rather than throwing.
 */
private fun TorrentItem.toTorrentDto(): TorrentDto {
    val item = this
    val categoryId = item.metadata["rutracker.categoryId"]
    val categoryName = item.metadata["rutracker.categoryName"] ?: item.category
    val category: CategoryDto? = if (categoryId != null || categoryName != null) {
        CategoryDto(id = categoryId, name = categoryName.orEmpty())
    } else {
        null
    }
    val authorId = item.metadata["rutracker.authorId"]
    val author: AuthorDto? = authorId?.let { AuthorDto(id = it, name = it) }
    val status = item.metadata["rutracker.status"]?.let { name ->
        runCatching { TorrentStatusDto.valueOf(name) }.getOrNull()
    }
    return TorrentDto(
        id = item.torrentId,
        title = item.title,
        author = author,
        category = category,
        tags = item.metadata["rutracker.tags"],
        status = status,
        date = item.publishDate?.epochSeconds,
        size = item.metadata["rutracker.size_text"],
        seeds = item.seeders,
        leeches = item.leechers,
        magnetLink = item.magnetUri,
        description = null,
    )
}

/**
 * Reverse of [TopicDto.toTorrentItem]. Used when the
 * forward mapper recorded `metadata["rutracker.kind"] = "topic"` to mark
 * the row as a thin TopicDto rather than a full TorrentDto. TopicDto
 * carries no seed/leech/size/magnet/date fields.
 */
private fun TorrentItem.toTopicDto(): TopicDto {
    val item = this
    val categoryId = item.metadata["rutracker.categoryId"]
    val categoryName = item.metadata["rutracker.categoryName"] ?: item.category
    val category: CategoryDto? = if (categoryId != null || categoryName != null) {
        CategoryDto(id = categoryId, name = categoryName.orEmpty())
    } else {
        null
    }
    val authorId = item.metadata["rutracker.authorId"]
    val author: AuthorDto? = authorId?.let { AuthorDto(id = it, name = it) }
    return TopicDto(
        id = item.torrentId,
        title = item.title,
        author = author,
        category = category,
    )
}

/**
 * Discriminate between `TorrentDto` and `TopicDto` based on the
 * `rutracker.kind` metadata key set by the forward mappers
 * (CategoryPageMapper.toTorrentItemOrNull marks TopicDto rows with
 * `"rutracker.kind" = "topic"`).
 *
 * Note (clause D adaptation): the reverse mapper cannot fully discriminate
 * `CommentsPageDto` here because the forward path drops it on browse pages.
 * Per Section D's `CategoryPageMapper.toTorrentItemOrNull`, CommentsPageDto
 * inputs collapse to null and therefore never reach this reverse path.
 */
private fun TorrentItem.toForumTopicDto(): ForumTopicDto =
    when (metadata["rutracker.kind"]) {
        "topic" -> toTopicDto()
        else -> toTorrentDto()
    }

/**
 * Reverse of [CategoryDto.toForumCategory]. Restores `id = null` when the
 * forward mapper collapsed it to "" (Section D ForumDtoMapper documents
 * this empty-string-as-null contract).
 */
private fun ForumCategory.toCategoryDto(): CategoryDto {
    val mappedChildren = children.takeIf { it.isNotEmpty() }
        ?.map { it.toCategoryDto() }
    return CategoryDto(
        id = id.takeIf { it.isNotEmpty() },
        name = name,
        children = mappedChildren,
    )
}

/**
 * Reconstruct a CategoryDto from a TorrentItem's metadata + category name.
 * Returns null when neither categoryId nor a category name is present.
 */
private fun TorrentItem.toCategoryDtoOrNull(): CategoryDto? {
    val item = this
    val categoryId = item.metadata["rutracker.categoryId"]
    val categoryName = item.metadata["rutracker.categoryName"] ?: item.category
    return if (categoryId != null || categoryName != null) {
        CategoryDto(id = categoryId, name = categoryName.orEmpty())
    } else {
        null
    }
}

/**
 * Reverse of [CaptchaDto.toChallenge].
 *
 * Section D's forward mapper assigns:
 *   `CaptchaChallenge(sid = id, code = code, imageUrl = url)`
 * so the reverse direction is:
 *   `CaptchaDto(id = sid, code = code, url = imageUrl)`.
 */
private fun CaptchaChallenge.toCaptchaDto(): CaptchaDto =
    CaptchaDto(id = sid, code = code, url = imageUrl)

/**
 * Reverse of [PostDto.toComment]. Synthesizes:
 *  - `id` = "" (clause A: post id is not surfaced on Comment)
 *  - `AuthorDto.id` = "" (clause A: not surfaced on Comment)
 *  - `date` = ISO-8601 string of the timestamp, "" if null
 *  - `children` = single Text node wrapping the entire body
 */
private fun Comment.toPostDto(): PostDto = PostDto(
    id = "",
    author = AuthorDto(id = "", name = author),
    date = timestamp?.toString().orEmpty(),
    children = listOf(Text(body)),
)
