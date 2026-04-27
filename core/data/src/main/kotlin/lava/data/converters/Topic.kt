package lava.data.converters

import lava.database.entity.FavoriteTopicEntity
import lava.database.entity.VisitedTopicEntity
import lava.models.Page
import lava.models.topic.Author
import lava.models.topic.BaseTopic
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import lava.models.topic.TopicPage
import lava.models.topic.Torrent
import lava.models.topic.TorrentData
import lava.models.topic.TorrentStatus
import lava.network.dto.topic.AuthorDto
import lava.network.dto.topic.CommentsPageDto
import lava.network.dto.topic.ForumTopicDto
import lava.network.dto.topic.TopicDto
import lava.network.dto.topic.TopicPageDto
import lava.network.dto.topic.TorrentDataDto
import lava.network.dto.topic.TorrentDto
import lava.network.dto.topic.TorrentStatusDto
import lava.network.dto.user.FavoritesDto

internal fun FavoritesDto.toFavorites(): List<Topic> {
    return topics.map(ForumTopicDto::toTopic)
}

internal fun ForumTopicDto.toTopic(): Topic = when (this) {
    is CommentsPageDto -> BaseTopic(id, title, author?.toAuthor(), category?.toCategory())
    is TopicDto -> BaseTopic(id, title, author?.toAuthor(), category?.toCategory())
    is TorrentDto -> toTorrent()
}

internal fun TopicPageDto.toTopicPage(): TopicPage {
    return TopicPage(
        id = id,
        title = title,
        author = author?.toAuthor(),
        category = category?.toCategory(),
        torrentData = torrentData?.toTorrentData(),
        commentsPage = Page(
            page = commentsPage.page,
            pages = commentsPage.pages,
            items = commentsPage.posts.toPosts(),
        ),
    )
}

internal fun TopicPageDto.toCommentsPage(): Page<Post> {
    return Page(
        page = commentsPage.page,
        pages = commentsPage.pages,
        items = commentsPage.posts.toPosts(),
    )
}

internal fun AuthorDto.toAuthor(): Author = Author(id, name, avatarUrl)

internal fun TorrentDto.toTorrent(): Torrent = Torrent(
    id = id,
    title = title,
    tags = tags,
    author = author?.toAuthor(),
    category = category?.toCategory(),
    status = status?.toStatus(),
    date = date,
    size = size,
    seeds = seeds,
    leeches = leeches,
    magnetLink = magnetLink,
)

internal fun TorrentDataDto.toTorrentData() = TorrentData(
    tags = tags,
    posterUrl = posterUrl,
    status = status?.toStatus(),
    date = date,
    size = size,
    seeds = seeds,
    leeches = leeches,
    magnetLink = magnetLink,
)

internal fun TorrentStatusDto.toStatus(): TorrentStatus = when (this) {
    TorrentStatusDto.Duplicate -> TorrentStatus.DUPLICATE
    TorrentStatusDto.NotApproved -> TorrentStatus.NOT_APPROVED
    TorrentStatusDto.Checking -> TorrentStatus.CHECKING
    TorrentStatusDto.Approved -> TorrentStatus.APPROVED
    TorrentStatusDto.NeedEdit -> TorrentStatus.NEEDS_EDIT
    TorrentStatusDto.Closed -> TorrentStatus.CLOSED
    TorrentStatusDto.NoDescription -> TorrentStatus.NO_DESCRIPTION
    TorrentStatusDto.Consumed -> TorrentStatus.CONSUMED
}

internal fun FavoriteTopicEntity.toTopic(): Topic =
    if (tags == null && status == null && size == null && seeds == null && leeches == null) {
        BaseTopic(
            id = id,
            title = title,
            author = author,
            category = category,
        )
    } else {
        Torrent(
            id = id,
            title = title,
            author = author,
            category = category,
            tags = tags,
            status = status,
            date = date,
            size = size,
            seeds = seeds,
            leeches = leeches,
            magnetLink = magnetLink,
        )
    }

internal fun FavoriteTopicEntity.toTopicModel(): TopicModel<out Topic> {
    return TopicModel(
        topic = toTopic(),
        isFavorite = true,
        hasUpdate = hasUpdate,
    )
}

internal fun Topic.toFavoriteEntity(): FavoriteTopicEntity {
    return when (this) {
        is BaseTopic -> FavoriteTopicEntity(
            id = id,
            timestamp = System.currentTimeMillis(),
            title = title,
            author = author,
            category = category,
        )

        is Torrent -> FavoriteTopicEntity(
            id = id,
            timestamp = System.currentTimeMillis(),
            title = title,
            author = author,
            category = category,
            tags = tags,
            status = status,
            date = date,
            size = size,
            seeds = seeds,
            leeches = leeches,
            magnetLink = magnetLink,
        )
    }
}

internal fun VisitedTopicEntity.toTopic(): Topic {
    return if (tags == null && status == null && size == null && seeds == null && leeches == null) {
        BaseTopic(
            id = id,
            title = title,
            author = author,
            category = category,
        )
    } else {
        Torrent(
            id = id,
            title = title,
            author = author,
            category = category,
            tags = tags,
            status = status,
            date = date,
            size = size,
            seeds = seeds,
            leeches = leeches,
            magnetLink = magnetLink,
        )
    }
}

internal fun TopicPage.toVisitedEntity(): VisitedTopicEntity {
    val timestamp = System.currentTimeMillis()
    return VisitedTopicEntity(
        id = id,
        timestamp = timestamp,
        title = title,
        author = author,
        category = category,
        tags = torrentData?.tags,
        status = torrentData?.status,
        size = torrentData?.size,
        seeds = torrentData?.seeds,
        leeches = torrentData?.leeches,
        magnetLink = torrentData?.magnetLink,
    )
}
