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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for [RuTrackerDtoMappers].
 *
 * Anti-Bluff Pact contract: each test exercises the SAME forward mapper
 * the production SwitchingNetworkApi will use, then the reverse mapper
 * we are introducing here, then the forward mapper a second time. The
 * primary assertion is on data equality of the forward result the
 * second time around vs the first time around — i.e.
 *
 *     forward(reverse(forward(dto))) == forward(dto)
 *
 * which is the strongest invariant achievable given the documented
 * forward-only information loss (formatted size strings, post AST,
 * UserDto.id/avatarUrl). A test that asserts only `assertNotNull(round)`
 * would be a bluff under Sixth Law clause 3 — the assertion below is on
 * the entire reconstructed model object, so a missing field surfaces as
 * a clear field-by-field mismatch.
 *
 * Falsifiability rehearsal: deleting `metadata["rutracker.size_text"]`
 * recovery from `toTorrentDto` would make the round-trip TorrentItem
 * lose its size_text metadata key, failing the assertEquals on the
 * second-forward TorrentItem with a clear `expected:<...> but was:<...>`.
 */
class RuTrackerDtoMappersTest {

    private val mappers = RuTrackerDtoMappers()
    private val forward = SearchPageMapper()
    private val browseForward = CategoryPageMapper()
    private val forumForward = ForumDtoMapper()
    private val topicForward = TopicMapper()
    private val commentsForward = CommentsMapper()
    private val favoritesForward = FavoritesMapper()
    private val authForward = AuthMapper()

    @Test
    fun `searchResultToDto round-trips a populated SearchPageDto`() {
        val originalDto = SearchPageDto(
            page = 2,
            pages = 7,
            torrents = listOf(
                TorrentDto(
                    id = "12345",
                    title = "Ubuntu 24.04 LTS",
                    author = AuthorDto(id = "777", name = "uploader"),
                    category = CategoryDto(id = "33", name = "OS Distros"),
                    tags = "[ISO]",
                    status = TorrentStatusDto.Approved,
                    date = 1_700_000_000L,
                    size = "4.7 GB",
                    seeds = 1234,
                    leeches = 56,
                    magnetLink = "magnet:?xt=urn:btih:abcdef",
                ),
            ),
        )

        val firstForward = forward.toSearchResult(originalDto, currentPage = 2)
        val reversedDto = mappers.searchResultToDto(firstForward)
        val secondForward = forward.toSearchResult(reversedDto, currentPage = 2)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto)",
            firstForward,
            secondForward,
        )
    }

    @Test
    fun `searchResultToDto round-trips an empty page boundary`() {
        val originalDto = SearchPageDto(page = 1, pages = 1, torrents = emptyList())

        val firstForward = forward.toSearchResult(originalDto, currentPage = 1)
        val reversedDto = mappers.searchResultToDto(firstForward)
        val secondForward = forward.toSearchResult(reversedDto, currentPage = 1)

        assertEquals(firstForward, secondForward)
        assertEquals(0, reversedDto.torrents.size)
        assertEquals(1, reversedDto.pages)
        assertEquals(1, reversedDto.page)
    }

    @Test
    fun `browseResultToDto round-trips mixed Torrent and Topic rows`() {
        val originalDto = CategoryPageDto(
            category = CategoryDto(id = "33", name = "OS Distros"),
            page = 3,
            pages = 11,
            sections = null,
            children = null,
            topics = listOf(
                TorrentDto(
                    id = "111",
                    title = "TorrentRow",
                    author = AuthorDto(id = "u1id", name = "u1"),
                    category = CategoryDto(id = "33", name = "OS Distros"),
                    status = TorrentStatusDto.Approved,
                    seeds = 10,
                    leeches = 2,
                    size = "1.0 GB",
                ),
                TopicDto(
                    id = "222",
                    title = "TopicRow (no torrent meta)",
                    author = AuthorDto(id = "u2id", name = "u2"),
                    category = CategoryDto(id = "33", name = "OS Distros"),
                ),
            ),
        )

        val firstForward = browseForward.toBrowseResult(originalDto, currentPage = 3)
        val reversedDto = mappers.browseResultToDto(firstForward)
        val secondForward = browseForward.toBrowseResult(reversedDto, currentPage = 3)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto) for browse pages",
            firstForward,
            secondForward,
        )
        assertEquals(2, reversedDto.topics?.size)
        // The TopicDto branch must be preserved across reverse — the forward
        // mapper relies on `metadata["rutracker.kind"] == "topic"` to choose
        // the thin TopicDto path; if the reverse mapper produced a TorrentDto
        // for a Topic row, the second-forward call would fill seeders=0 and
        // the equality check above would fail.
        assertEquals(true, reversedDto.topics?.get(1) is TopicDto)
    }

    @Test
    fun `browseResultToDto round-trips an empty topics list with null id collapse`() {
        // Boundary: legacy CategoryDto.id is nullable. Forward collapses to
        // "" and reverse must restore null. If the reverse step left "",
        // re-forward would yield ForumCategory.id = "" but the forward step
        // also collapses null -> "" so equality holds either way at the
        // ForumCategory level — but the reversed CategoryDto.id should still
        // be null to match the original DTO contract.
        val originalDto = CategoryPageDto(
            category = CategoryDto(id = null, name = "Root"),
            page = 1,
            pages = 1,
            sections = null,
            children = null,
            topics = emptyList(),
        )

        val firstForward = browseForward.toBrowseResult(originalDto, currentPage = 1)
        val reversedDto = mappers.browseResultToDto(firstForward)
        val secondForward = browseForward.toBrowseResult(reversedDto, currentPage = 1)

        assertEquals(firstForward, secondForward)
        // Anti-Bluff Pact (third law): null-id collapse must round-trip
        // exactly per Section D's empty-string-as-null contract.
        assertEquals(null, reversedDto.category.id)
        assertEquals("Root", reversedDto.category.name)
    }

    @Test
    fun `forumTreeToDto round-trips a recursive forum tree`() {
        // Real rutracker forum trees are 2-3 levels deep with categories
        // grouping subforums grouping topics. The reverse mapper must
        // recurse correctly and preserve the parent-child structure.
        val originalDto = ForumDto(
            children = listOf(
                CategoryDto(
                    id = "10",
                    name = "Films",
                    children = listOf(
                        CategoryDto(id = "11", name = "HD Movies"),
                        CategoryDto(id = "12", name = "DVD Movies"),
                    ),
                ),
                CategoryDto(
                    id = "20",
                    name = "Music",
                    children = listOf(
                        CategoryDto(id = "21", name = "Lossless"),
                    ),
                ),
            ),
        )

        val firstForward = forumForward.toForumTree(originalDto)
        val reversedDto = mappers.forumTreeToDto(firstForward)
        val secondForward = forumForward.toForumTree(reversedDto)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto) for forum tree",
            firstForward,
            secondForward,
        )
        // Primary user-visible assertion: tree shape is preserved.
        assertEquals(2, secondForward.rootCategories.size)
        assertEquals("Films", secondForward.rootCategories[0].name)
        assertEquals(2, secondForward.rootCategories[0].children.size)
        assertEquals("HD Movies", secondForward.rootCategories[0].children[0].name)
        // Parent IDs must propagate down the tree (Section D guarantee).
        assertEquals("10", secondForward.rootCategories[0].children[0].parentId)
    }

    @Test
    fun `topicDetailToDto round-trips a Torrent branch with description`() {
        val originalDto: TorrentDto = TorrentDto(
            id = "9000",
            title = "Some.Movie.2024.1080p.BluRay",
            author = AuthorDto(id = "u1", name = "uploader"),
            category = CategoryDto(id = "44", name = "Movies"),
            tags = "[Movies]",
            status = TorrentStatusDto.Approved,
            date = 1_700_000_000L,
            size = "8.0 GB",
            seeds = 555,
            leeches = 12,
            magnetLink = "magnet:?xt=urn:btih:abcd",
            description = TorrentDescriptionDto(
                children = listOf(Text("Plot summary")),
            ),
        )

        val firstForward = topicForward.toTopicDetail(originalDto)
        val reversedDto = mappers.topicDetailToDto(firstForward)
        val secondForward = topicForward.toTopicDetail(reversedDto)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto) for TopicDetail",
            firstForward,
            secondForward,
        )
        // Primary user-visible assertion: magnet survives every leg.
        assertEquals(
            "magnet:?xt=urn:btih:abcd",
            secondForward.torrent.magnetUri,
        )
        // Description text round-trips even though the rich AST is lossy.
        assertNotNull(secondForward.description)
        assertEquals("Plot summary", secondForward.description)
    }

    @Test
    fun `topicPageToDto round-trips a populated topic page`() {
        val originalDto = TopicPageDto(
            id = "12",
            title = "Topic Page",
            author = AuthorDto(id = "u1", name = "u1"),
            category = CategoryDto(id = "33", name = "OS"),
            torrentData = TorrentDataDto(
                tags = "[ISO]",
                status = TorrentStatusDto.Approved,
                date = "2024-04-01T12:00:00Z",
                size = "4.5 GB",
                seeds = 100,
                leeches = 5,
                magnetLink = "magnet:?xt=urn:btih:beef",
            ),
            commentsPage = TopicPageCommentsDto(
                page = 4,
                pages = 9,
                posts = emptyList(),
            ),
        )

        val firstForward = topicForward.toTopicPage(originalDto, currentPage = 4)
        val reversedDto = mappers.topicPageToDto(firstForward)
        val secondForward = topicForward.toTopicPage(reversedDto, currentPage = 4)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto) for TopicPage",
            firstForward,
            secondForward,
        )
        // Primary user-visible assertions: pagination + magnet + size.
        assertEquals(9, secondForward.totalPages)
        assertEquals(4, secondForward.currentPage)
        assertEquals(
            "magnet:?xt=urn:btih:beef",
            secondForward.topic.torrent.magnetUri,
        )
        assertEquals(
            "4.5 GB",
            secondForward.topic.torrent.metadata["rutracker.size_text"],
        )
    }

    @Test
    fun `commentsPageToDto round-trips a CommentsPage with multiple posts`() {
        val originalDto = CommentsPageDto(
            id = "topic-77",
            title = "Comments thread",
            page = 2,
            pages = 5,
            posts = listOf(
                PostDto(
                    id = "p1",
                    author = AuthorDto(id = "uA", name = "alice"),
                    date = "2024-04-01T12:00:00Z",
                    children = listOf(Text("Hello world")),
                ),
                PostDto(
                    id = "p2",
                    author = AuthorDto(id = "uB", name = "bob"),
                    date = "2024-04-02T13:00:00Z",
                    children = listOf(Text("Reply text")),
                ),
            ),
        )

        val firstForward = commentsForward.toCommentsPage(originalDto, currentPage = 2)
        val reversedDto = mappers.commentsPageToDto(firstForward)
        val secondForward = commentsForward.toCommentsPage(reversedDto, currentPage = 2)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto) for CommentsPage",
            firstForward,
            secondForward,
        )
        // Primary user-visible assertion: comment bodies survive the trip.
        assertEquals(2, secondForward.items.size)
        assertEquals("Hello world", secondForward.items[0].body)
        assertEquals("alice", secondForward.items[0].author)
        assertEquals("Reply text", secondForward.items[1].body)
        assertEquals("bob", secondForward.items[1].author)
    }

    @Test
    fun `favoritesToDto round-trips a mixed Torrent and Topic favorites list`() {
        val originalDto = FavoritesDto(
            topics = listOf(
                TorrentDto(
                    id = "fav-1",
                    title = "Favorited Torrent",
                    author = AuthorDto(id = "u1", name = "u1"),
                    category = CategoryDto(id = "33", name = "OS"),
                    status = TorrentStatusDto.Approved,
                    seeds = 10,
                    leeches = 0,
                    size = "2.0 GB",
                ),
                TopicDto(
                    id = "fav-2",
                    title = "Favorited Topic",
                    author = AuthorDto(id = "u2", name = "u2"),
                    category = CategoryDto(id = "1", name = "Discussion"),
                ),
            ),
        )

        val firstForward = favoritesForward.toTorrentItems(originalDto)
        val reversedDto = mappers.favoritesToDto(firstForward)
        val secondForward = favoritesForward.toTorrentItems(reversedDto)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto) for favorites",
            firstForward,
            secondForward,
        )
        assertEquals(2, secondForward.size)
        assertEquals("fav-1", secondForward[0].torrentId)
        assertEquals("fav-2", secondForward[1].torrentId)
        // Topic vs Torrent discriminator must propagate.
        assertEquals("topic", secondForward[1].metadata["rutracker.kind"])
        assertNull(
            "Topic rows do not carry seeders",
            secondForward[1].seeders,
        )
    }

    @Test
    fun `loginResultToDto round-trips Success with session token`() {
        val originalDto = AuthResponseDto.Success(
            user = UserDto(
                id = "user-1",
                token = "token-abc-123",
                avatarUrl = "https://rutracker.org/avatars/1.png",
            ),
        )

        val firstForward = authForward.toLoginResult(originalDto)
        val reversedDto = mappers.loginResultToDto(firstForward)
        val secondForward = authForward.toLoginResult(reversedDto)

        assertEquals(
            "forward(reverse(forward(dto))) must equal forward(dto) for Success",
            firstForward,
            secondForward,
        )
        // Primary user-visible assertion: session token survives the trip.
        assertEquals(AuthState.Authenticated, secondForward.state)
        assertEquals("token-abc-123", secondForward.sessionToken)
    }

    @Test
    fun `loginResultToDto round-trips WrongCredits with captcha`() {
        val originalDto = AuthResponseDto.WrongCredits(
            captcha = CaptchaDto(
                id = "sid-7",
                code = "cap_code_xxxxx",
                url = "https://rutracker.org/captcha/7.png",
            ),
        )

        val firstForward = authForward.toLoginResult(originalDto)
        val reversedDto = mappers.loginResultToDto(firstForward)
        val secondForward = authForward.toLoginResult(reversedDto)

        assertEquals(firstForward, secondForward)
        assertEquals(AuthState.Unauthenticated, secondForward.state)
        assertNotNull(secondForward.captchaChallenge)
        // CaptchaDto field-name mapping must be preserved across round-trip.
        assertEquals("sid-7", secondForward.captchaChallenge!!.sid)
        assertEquals("cap_code_xxxxx", secondForward.captchaChallenge!!.code)
        assertEquals(
            "https://rutracker.org/captcha/7.png",
            secondForward.captchaChallenge!!.imageUrl,
        )
        // The reverse mapper must emit a real WrongCredits DTO with the
        // captcha attached, NOT degrade to captcha=null.
        assertTrue(reversedDto is AuthResponseDto.WrongCredits)
        assertNotNull((reversedDto as AuthResponseDto.WrongCredits).captcha)
    }

    @Test
    fun `loginResultToDto round-trips CaptchaRequired with challenge`() {
        val originalDto = AuthResponseDto.CaptchaRequired(
            captcha = CaptchaDto(
                id = "sid-9",
                code = "cap_code_yyyy",
                url = "https://rutracker.org/captcha/9.png",
            ),
        )

        val firstForward = authForward.toLoginResult(originalDto)
        val reversedDto = mappers.loginResultToDto(firstForward)
        val secondForward = authForward.toLoginResult(reversedDto)

        assertEquals(firstForward, secondForward)
        assertTrue(
            "state should be CaptchaRequired",
            secondForward.state is AuthState.CaptchaRequired,
        )
        // Verify reverse produces CaptchaRequired (not WrongCredits) — this
        // is the discriminator that drives whether the UI shows the captcha
        // dialog vs the wrong-password dialog.
        assertTrue(reversedDto is AuthResponseDto.CaptchaRequired)
    }

    @Test
    fun `loginResultToDto round-trips WrongCredits without captcha`() {
        val originalDto = AuthResponseDto.WrongCredits(captcha = null)

        val firstForward = authForward.toLoginResult(originalDto)
        val reversedDto = mappers.loginResultToDto(firstForward)
        val secondForward = authForward.toLoginResult(reversedDto)

        assertEquals(firstForward, secondForward)
        assertEquals(AuthState.Unauthenticated, secondForward.state)
        assertNull(secondForward.captchaChallenge)
        assertTrue(reversedDto is AuthResponseDto.WrongCredits)
        assertNull((reversedDto as AuthResponseDto.WrongCredits).captcha)
    }
}
