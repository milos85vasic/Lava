package lava.tracker.rutracker.mapper

import lava.network.dto.auth.AuthResponseDto
import lava.tracker.api.model.LoginResult
import javax.inject.Inject

/**
 * Maps the legacy [AuthResponseDto] (sealed: Success / WrongCredits /
 * CaptchaRequired) to the new tracker-api [LoginResult]. Stub here;
 * populated in Task 2.21.
 */
class AuthMapper @Inject constructor() {
    fun toLoginResult(dto: AuthResponseDto): LoginResult {
        TODO("populated in Task 2.21")
    }
}
