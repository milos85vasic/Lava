package lava.tracker.rutracker.impl

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import lava.network.dto.auth.AuthResponseDto
import lava.tracker.rutracker.domain.AddCommentUseCase
import lava.tracker.rutracker.domain.AddFavoriteUseCase
import lava.tracker.rutracker.domain.CheckAuthorisedUseCase
import lava.tracker.rutracker.domain.GetCategoryPageUseCase
import lava.tracker.rutracker.domain.GetCommentsPageUseCase
import lava.tracker.rutracker.domain.GetFavoritesUseCase
import lava.tracker.rutracker.domain.GetForumUseCase
import lava.tracker.rutracker.domain.GetSearchPageUseCase
import lava.tracker.rutracker.domain.GetTopicPageUseCase
import lava.tracker.rutracker.domain.GetTopicUseCase
import lava.tracker.rutracker.domain.GetTorrentFileUseCase
import lava.tracker.rutracker.domain.GetTorrentUseCase
import lava.tracker.rutracker.domain.LoginUseCase
import lava.tracker.rutracker.domain.RemoveFavoriteUseCase
import lava.tracker.rutracker.model.Unknown
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §6.O closure validation for Crashlytics issue
 * `a29412cf6566d0a71b06df416610be57` — `lava.tracker.rutracker.model.Unknown`
 * thrown from `LoginUseCase` when rutracker returns an HTML response that
 * doesn't match success / login-form / captcha shape. Pre-fix: the throw
 * escaped to the main looper as FATAL on Galaxy S23 Ultra (1.2.8 release,
 * 1 event 2026-05-07).
 *
 * Post-fix: `RuTrackerNetworkApi.login` traps the Unknown (and any other
 * non-cancellation throwable from `LoginUseCase`) and returns
 * `AuthResponseDto.WrongCredits(captcha = null)` as a safe user-visible
 * fallback. The user can retry without app crash; upstream callers
 * (RuTrackerAuth + the ViewModel) record the throwable as a non-fatal.
 *
 * Falsifiability rehearsal:
 *   1. Remove the try/catch in `RuTrackerNetworkApi.login`.
 *   2. Re-run this test → expect Unknown to escape (test fails with
 *      Unknown thrown during login()).
 *   3. Restore the try/catch → test passes.
 */
class RuTrackerNetworkApiLoginUnknownRegressionTest {

    @Test
    fun `login wraps LoginUseCase Unknown throw and returns WrongCredits fallback`() = runTest {
        val loginUseCase = mockk<LoginUseCase>()
        coEvery { loginUseCase.invoke(any(), any(), any(), any(), any()) } throws Unknown

        val api = RuTrackerNetworkApi(
            addCommentUseCase = mockk<AddCommentUseCase>(relaxed = true),
            addFavoriteUseCase = mockk<AddFavoriteUseCase>(relaxed = true),
            checkAuthorisedUseCase = mockk<CheckAuthorisedUseCase>(relaxed = true),
            getCategoryPageUseCase = mockk<GetCategoryPageUseCase>(relaxed = true),
            getCommentsPageUseCase = mockk<GetCommentsPageUseCase>(relaxed = true),
            getFavoritesUseCase = mockk<GetFavoritesUseCase>(relaxed = true),
            getForumUseCase = mockk<GetForumUseCase>(relaxed = true),
            getSearchPageUseCase = mockk<GetSearchPageUseCase>(relaxed = true),
            getTopicUseCase = mockk<GetTopicUseCase>(relaxed = true),
            getTopicPageUseCase = mockk<GetTopicPageUseCase>(relaxed = true),
            getTorrentFileUseCase = mockk<GetTorrentFileUseCase>(relaxed = true),
            getTorrentUseCase = mockk<GetTorrentUseCase>(relaxed = true),
            loginUseCase = loginUseCase,
            removeFavoriteUseCase = mockk<RemoveFavoriteUseCase>(relaxed = true),
        )

        // Pre-fix this would throw Unknown; post-fix it returns
        // AuthResponseDto.WrongCredits(null) as a safe fallback. The
        // assertion fails clearly if the wrap is removed.
        val result = api.login(
            username = "test",
            password = "test",
            captchaSid = null,
            captchaCode = null,
            captchaValue = null,
        )
        assertTrue(
            "Expected WrongCredits fallback when LoginUseCase throws Unknown — got $result. " +
                "Forensic anchor: Crashlytics a29412cf6566d0a71b06df416610be57.",
            result is AuthResponseDto.WrongCredits,
        )
    }
}
