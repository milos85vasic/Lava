package lava.tracker.rutracker.domain

import lava.network.dto.user.ProfileDto
import lava.tracker.rutracker.api.RuTrackerInnerApi
import org.jsoup.Jsoup

class GetProfileUseCase(private val api: RuTrackerInnerApi) {

    suspend operator fun invoke(id: String): ProfileDto {
        return parseProfile(api.profile(id))
    }

    companion object {
        private fun parseProfile(html: String): ProfileDto {
            val doc = Jsoup.parse(html)
            return ProfileDto(
                id = doc.select("#profile-uname").attr("data-uid"),
                name = doc.select("#profile-uname").toStr(),
                avatarUrl = doc.select("#avatar-img > img").attr("src"),
            )
        }
    }
}
