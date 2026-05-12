package lava.tracker.rutracker.domain

import lava.network.dto.user.ProfileDto
import lava.tracker.rutracker.api.RuTrackerInnerApi
import org.jsoup.Jsoup

class GetCurrentProfileUseCase(
    private val api: RuTrackerInnerApi,
    private val getProfileUseCase: GetProfileUseCase,
) {
    suspend operator fun invoke(token: String): ProfileDto {
        return getProfileUseCase(parseUserId(api.mainPage(token)))
    }

    companion object {
        private val LOGGED_IN_SELECTORS = listOf(
            "#logged-in-username",
            "a.logged-in-as-uname",
            ".menu-userctrl a[href*='profile.php?u=']",
            "a[href*='profile.php?u=']",
        )

        private fun parseUserId(html: String): String {
            val doc = Jsoup.parse(html)
            for (sel in LOGGED_IN_SELECTORS) {
                val els = doc.select(sel)
                val u = els.queryParamOrNull("u")
                if (u != null) return u
            }
            error("rutracker logged-in user-id not found — page may be guest, or selectors stale")
        }
    }
}
