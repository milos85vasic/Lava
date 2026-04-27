package lava.domain.usecase

import lava.auth.api.AuthService
import javax.inject.Inject

class IsAuthorizedUseCase @Inject constructor(
    private val authService: AuthService,
) {
    suspend operator fun invoke(): Boolean = authService.isAuthorized()
}
