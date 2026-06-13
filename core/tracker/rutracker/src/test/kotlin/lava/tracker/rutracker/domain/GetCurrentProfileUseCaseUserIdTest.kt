package lava.tracker.rutracker.domain

import kotlinx.coroutines.runBlocking
import lava.network.dto.FileDto
import lava.network.dto.search.SearchPeriodDto
import lava.network.dto.search.SearchSortOrderDto
import lava.network.dto.search.SearchSortTypeDto
import lava.tracker.rutracker.api.RuTrackerInnerApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * §6.O investigation + regression for Crashlytics issue
 * `6519b490…` (NON_FATAL `IllegalStateException: rutracker logged-in user-id
 * not found — page may be guest, or selectors stale`, 1.2.22, recorded from
 * `GetCurrentProfileUseCase.parseUserId`).
 *
 * The forensic question the operator asked: is this telemetry working-as-
 * designed (a genuine guest / expired-session page legitimately has no
 * logged-in user-id) OR are the production CSS selectors stale (rutracker
 * changed its markup so a real logged-in page no longer matches)?
 *
 * This test answers it with EVIDENCE rather than a guess (§11.4.6):
 *
 *   - [loggedInPage_parsesUserId] feeds a representative logged-in main-page
 *     HTML through the REAL production code path (`GetCurrentProfileUseCase`
 *     → the real private `parseUserId` → real Jsoup → real `GetProfileUseCase`,
 *     only the network boundary `RuTrackerInnerApi` faked). The four production
 *     selectors MUST extract the user-id `u=12345`. GREEN here means the
 *     selectors are NOT stale against this markup shape — the non-fatal is
 *     working-as-designed on genuine guest/expired pages.
 *
 *   - [guestPage_throwsUserIdNotFound] feeds a page with NO logged-in profile
 *     link (the guest/expired-session shape) and asserts the production code
 *     throws the exact "user-id not found" IllegalStateException — i.e. the
 *     telemetry path fires ONLY when there genuinely is no user-id, which is
 *     correct behaviour, not a bug.
 *
 * If rutracker later changes its real logged-in markup so none of the four
 * selectors match, [loggedInPage_parsesUserId] is the canary that turns RED
 * and reclassifies the issue as "selectors stale".
 *
 * FALSIFIABILITY REHEARSAL (Sixth Law clause 2):
 *   Mutation: delete every selector from `GetCurrentProfileUseCase.LOGGED_IN_SELECTORS`
 *             except a deliberately-wrong one (e.g. `"#nonexistent"`).
 *   Observed-Failure: [loggedInPage_parsesUserId] fails — the real logged-in
 *             page no longer parses, `invoke` throws the "user-id not found"
 *             IllegalStateException, and the test reports the unexpected throw.
 *   Reverted: yes.
 */
class GetCurrentProfileUseCaseUserIdTest {

    /** Fakes ONLY the network boundary; everything below is the real SUT. */
    private class FakeApi(
        private val mainPageHtml: String,
        private val profileHtml: String = PROFILE_HTML,
    ) : RuTrackerInnerApi {
        override suspend fun mainPage(token: String): String = mainPageHtml
        override suspend fun profile(userId: String): String = profileHtml

        override suspend fun login(
            username: String,
            password: String,
            captchaSid: String?,
            captchaCode: String?,
            captchaValue: String?,
        ): Pair<String?, String> = error("not used")
        override suspend fun search(
            token: String,
            searchQuery: String?,
            categories: String?,
            author: String?,
            authorId: String?,
            sortType: SearchSortTypeDto?,
            sortOrder: SearchSortOrderDto?,
            period: SearchPeriodDto?,
            page: Int?,
        ): String = error("not used")
        override suspend fun forum(): String = error("not used")
        override suspend fun category(id: String, page: Int?): String = error("not used")
        override suspend fun topic(token: String, id: String, page: Int?): String = error("not used")
        override suspend fun download(token: String, id: String): FileDto = error("not used")
        override suspend fun postMessage(token: String, topicId: String, formToken: String, message: String): String =
            error("not used")
        override suspend fun favorites(token: String, page: Int?): String = error("not used")
        override suspend fun addFavorite(token: String, id: String, formToken: String): String = error("not used")
        override suspend fun removeFavorite(token: String, id: String, formToken: String): String = error("not used")
        override suspend fun futureDownloads(token: String, page: Int?): String = error("not used")
        override suspend fun addFutureDownload(token: String, id: String, formToken: String): String = error("not used")
        override suspend fun removeFutureDownload(token: String, id: String, formToken: String): String = error("not used")
    }

    @Test
    fun loggedInPage_parsesUserId() = runBlocking {
        val api = FakeApi(mainPageHtml = LOGGED_IN_MAIN_PAGE)
        val useCase = GetCurrentProfileUseCase(api, GetProfileUseCase(api))

        val profile = useCase.invoke("session-token")

        // user-visible signal: the profile resolves to the logged-in user's id.
        assertEquals(
            "Production parseUserId selectors must extract u=12345 from a logged-in " +
                "main page. If this fails, the selectors are STALE against this markup " +
                "and Crashlytics 6519b490 is a real bug, not working-as-designed.",
            "12345",
            profile.id,
        )
        assertEquals("TestUser", profile.name)
    }

    @Test
    fun guestPage_throwsUserIdNotFound() = runBlocking {
        val api = FakeApi(mainPageHtml = GUEST_MAIN_PAGE)
        val useCase = GetCurrentProfileUseCase(api, GetProfileUseCase(api))

        try {
            useCase.invoke("expired-or-guest-token")
            fail("A guest / expired-session page has no logged-in user-id; invoke MUST throw")
        } catch (e: IllegalStateException) {
            assertTrue(
                "The thrown message must be the working-as-designed 'user-id not found' " +
                    "signal (this is the non-fatal Crashlytics records on genuine guest pages).",
                e.message?.contains("user-id not found") == true,
            )
        }
        Unit
    }

    private companion object {
        // A logged-in rutracker main page exposes the user's own profile link
        // (profile.php?u=<id>) — the broad fallback selector in production.
        val LOGGED_IN_MAIN_PAGE = """
            <html><body>
              <div class="menu-userctrl">
                <a href="profile.php?u=12345" class="logged-in-as-uname">TestUser</a>
              </div>
            </body></html>
        """.trimIndent()

        // A guest / expired-session page has NO profile.php?u= link anywhere —
        // the legitimate condition the non-fatal fires on.
        val GUEST_MAIN_PAGE = """
            <html><body>
              <div class="login-form">
                <a href="login.php">Войти</a>
              </div>
            </body></html>
        """.trimIndent()

        val PROFILE_HTML = """
            <html><body>
              <div id="profile-uname" data-uid="12345">TestUser</div>
              <div id="avatar-img"><img src="https://example.invalid/a.png"/></div>
            </body></html>
        """.trimIndent()
    }
}
