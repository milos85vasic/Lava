package lava.tracker.rutracker.domain

import lava.network.dto.FileDto
import lava.tracker.rutracker.api.RuTrackerInnerApi

class GetTorrentFileUseCase(
    private val api: RuTrackerInnerApi,
    private val withTokenVerificationUseCase: WithTokenVerificationUseCase,
) {
    suspend operator fun invoke(token: String, id: String): FileDto {
        return withTokenVerificationUseCase(token) { validToken ->
            api.download(validToken, id)
        }
    }
}
