package lava.network.dto.topic

import kotlinx.serialization.MissingFieldException
import lava.network.serialization.JsonFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Real-stack serialization regression tests for [TopicPageDto].
 *
 * Crashlytics NON_FATAL 8cde0ac208b3... (1.3.9) — Internet Archive WPO-* crawl topics
 * omit the `commentsPage` field from the `/topic2/<id>` response because archived web
 * pages have no forum-comment section.  Before the fix, kotlinx.serialization threw
 * [MissingFieldException] at runtime; the topic page silently failed to render.
 *
 * Anti-Bluff posture (§6.J / §6.T.1 reproduce-before-fix):
 *   - SUT is the production [JsonFactory.create] serializer applied to the production
 *     [TopicPageDto] DTO graph — nothing is mocked.
 *   - Primary assertion is on user-visible state: the decoded [TopicPageDto] and its
 *     [TopicPageCommentsDto] default values that the Compose topic screen renders.
 *   - Falsifiability: reverting the `commentsPage` default in [TopicPageDto] causes
 *     `internetArchiveCrawlTopicWithoutCommentsPage_parsesWithDefaultCommentsPage` to
 *     throw [MissingFieldException], failing with "Field 'commentsPage' is required...".
 *
 * Bluff-Audit recorded in the commit body per Seventh Law clause 1.
 */
class TopicPageDtoSerializationTest {

    private val json = JsonFactory.create()

    /**
     * REGRESSION — Crashlytics 8cde0ac208b3 (1.3.9 NON_FATAL).
     *
     * Internet Archive WPO-* items are archived web crawls, not forum topics, so the
     * lava-api-go `/topic2/<id>` handler returns a JSON shape that has no `commentsPage`
     * key.  The DTO MUST tolerate this by supplying an empty-page default rather than
     * throwing [MissingFieldException].
     *
     * Before fix: throws MissingFieldException: Field 'commentsPage' is required for
     *   type with serial name 'lava.network.dto.topic.TopicPageDto'
     * After fix: decodes successfully; commentsPage defaults to page=1, pages=1, posts=[].
     */
    @Test
    fun `internet archive crawl topic without commentsPage parses with default commentsPage`() {
        // Realistic Internet Archive WPO-* JSON shape — commentsPage is absent.
        val json = json.decodeFromString<TopicPageDto>(
            """
            {
              "id": "WPO-0001234",
              "title": "Archived web page title",
              "author": null,
              "category": null,
              "torrentData": null
            }
            """.trimIndent(),
        )

        // User-visible state: the topic page must load (no exception) and display a
        // sensible empty-comments fallback — page 1 of 1 with no posts.
        assertEquals("WPO-0001234", json.id)
        assertEquals("Archived web page title", json.title)
        assertNull(json.author)
        assertNull(json.category)
        assertNull(json.torrentData)
        assertEquals(1, json.commentsPage.page)
        assertEquals(1, json.commentsPage.pages)
        assertEquals(emptyList<PostDto>(), json.commentsPage.posts)
    }

    /**
     * Sanity: a full rutracker-shaped TopicPageDto (with commentsPage present) still
     * deserializes correctly after the fix.  This guards against the fix accidentally
     * overriding a present commentsPage field with the default.
     */
    @Test
    fun `rutracker topic with commentsPage present deserializes the explicit commentsPage`() {
        val decoded = json.decodeFromString<TopicPageDto>(
            """
            {
              "id": "12345",
              "title": "Some rutracker topic",
              "author": null,
              "category": null,
              "torrentData": null,
              "commentsPage": {
                "page": 3,
                "pages": 10,
                "posts": []
              }
            }
            """.trimIndent(),
        )

        assertEquals("12345", decoded.id)
        // The EXPLICIT commentsPage values must win — no default override.
        assertEquals(3, decoded.commentsPage.page)
        assertEquals(10, decoded.commentsPage.pages)
        assertEquals(emptyList<PostDto>(), decoded.commentsPage.posts)
    }
}
