package lava.domain.usecase

import kotlinx.coroutines.test.runTest
import lava.data.api.service.TopicService
import lava.models.Page
import lava.models.topic.Post
import lava.models.topic.Topic
import lava.models.topic.TopicPage
import lava.testing.testDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Real-stack test for [AddCommentUseCase] wired to the REAL use-case
 * implementation (NOT a mock). Only the outermost boundary is faked: an
 * in-memory [TopicService] standing in for the network/HTTP layer, which either
 * records the posted comment (success) or throws like a real network failure.
 *
 * The user-visible feature this covers: tapping "send" on a topic comment. The
 * use case branches on whether the post actually reached the server —
 * `runCatching { topicService.addComment(...) }.isSuccess` returns `true` so the
 * UI can show the comment as sent, or `false` (swallowing the throwable) so the
 * UI can surface a failure instead of crashing. Both branches are exercised
 * here, and on the success branch we additionally assert the topic id + message
 * the use case forwarded to the boundary are exactly what the caller supplied.
 *
 * Bluff-Audit (§6.J clause 2 falsifiability), see FALSIFIABILITY REHEARSAL note
 * at the bottom of this file.
 */
class AddCommentUseCaseTest {

    /**
     * In-memory TopicService fake at the network boundary. On success it records
     * the exact (topicId, message) the use case forwarded so the test can assert
     * the payload is correct; when [failWith] is set, [addComment] throws to
     * model a real network/server failure.
     */
    private class FakeTopicService(
        private val failWith: Throwable? = null,
        private val result: Boolean = true,
    ) : TopicService {
        val postedComments = mutableListOf<Pair<String, String>>()

        override suspend fun addComment(topicId: String, message: String): Boolean {
            failWith?.let { throw it }
            postedComments.add(topicId to message)
            return result
        }

        override suspend fun getTopic(id: String): Topic =
            throw UnsupportedOperationException("not used by AddCommentUseCase")

        override suspend fun getTopicPage(id: String, page: Int?, providerId: String?): TopicPage =
            throw UnsupportedOperationException("not used by AddCommentUseCase")

        override suspend fun getCommentsPage(id: String, page: Int): Page<Post> =
            throw UnsupportedOperationException("not used by AddCommentUseCase")
    }

    @Test
    fun `successful post returns true and forwards the exact topic id and message`() = runTest {
        val service = FakeTopicService()
        val useCase = AddCommentUseCase(service, testDispatchers())

        val posted = useCase(topicId = "topic-42", message = "Great release, thanks!")

        // Primary user-visible state: the comment is reported as sent.
        assertTrue("Comment that reached the server must report success", posted)
        // And the payload the user typed reached the boundary unchanged.
        assertEquals(listOf("topic-42" to "Great release, thanks!"), service.postedComments)
    }

    @Test
    fun `network failure is swallowed and reported as not sent`() = runTest {
        val service = FakeTopicService(failWith = IOException("connection reset"))
        val useCase = AddCommentUseCase(service, testDispatchers())

        val posted = useCase(topicId = "topic-7", message = "won't reach the server")

        // Primary user-visible state: a failed post reports false (no crash),
        // so the UI can show an error rather than a green checkmark.
        assertFalse("A thrown network error must be reported as not-sent", posted)
        assertTrue("Nothing should be recorded as posted on failure", service.postedComments.isEmpty())
    }

    @Test
    fun `a non-throwing boundary call is treated as sent regardless of its boolean`() = runTest {
        // Documents the PRODUCTION contract: the use case is
        // `runCatching { addComment(...) }.isSuccess`, so the success signal is
        // "the call did not throw" — the boundary's own Boolean return value is
        // discarded. A boundary that returns false WITHOUT throwing is therefore
        // still reported as sent. This pins that real behavior so a future
        // refactor that starts honoring the boundary Boolean is caught.
        val service = FakeTopicService(result = false)
        val useCase = AddCommentUseCase(service, testDispatchers())

        val posted = useCase(topicId = "topic-9", message = "boundary returned false")

        assertTrue("A non-throwing boundary call is reported as sent", posted)
        // The call DID reach the boundary with the user's payload.
        assertEquals(listOf("topic-9" to "boundary returned false"), service.postedComments)
    }
}

/*
 * FALSIFIABILITY REHEARSAL (§6.J clause 2 / Seventh Law clause 5)
 *
 * Mutation — AddCommentUseCase.invoke: replace the body with `true` (i.e. drop
 *   the `runCatching { topicService.addComment(...) }.isSuccess` call entirely,
 *   always returning true and never touching the service).
 *   Observed failure: `network failure is swallowed and reported as not sent`
 *   FAILED — `A thrown network error must be reported as not-sent` expected
 *   <false> but was <true>; and `successful post ...` FAILED on the payload
 *   assertion (expected [(topic-42, Great release, thanks!)] but the service was
 *   never called, so postedComments was []).
 *   Reverted: yes — suite re-run green.
 */
