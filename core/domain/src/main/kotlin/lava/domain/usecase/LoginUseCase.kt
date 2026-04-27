package lava.domain.usecase

import lava.auth.api.AuthService
import lava.dispatchers.api.Dispatchers
import lava.models.auth.AuthResult
import lava.models.auth.Captcha
import lava.network.data.NetworkApiRepository
import lava.work.api.BackgroundService
import kotlinx.coroutines.withContext
import javax.inject.Inject

class LoginUseCase @Inject constructor(
    private val authService: AuthService,
    private val backgroundService: BackgroundService,
    private val networkApiRepository: NetworkApiRepository,
    private val dispatchers: Dispatchers,
) {
    suspend operator fun invoke(
        username: String,
        password: String,
        captchaSid: String?,
        captchaCode: String?,
        captchaValue: String?,
    ): AuthResult {
        return withContext(dispatchers.default) {
            runCatching {
                authService.login(
                    username,
                    password,
                    captchaSid,
                    captchaCode,
                    captchaValue,
                )
            }
                .mapCatching { result ->
                    when (result) {
                        is AuthResult.CaptchaRequired -> result.copy(
                            captcha = result.captcha.enrich(),
                        )
                        is AuthResult.Error -> result
                        is AuthResult.Success -> result
                        is AuthResult.WrongCredits -> result.copy(
                            captcha = result.captcha?.enrich(),
                        )
                    }
                }
                .onSuccess { result ->
                    if (result == AuthResult.Success) {
                        backgroundService.loadFavorites()
                    }
                }
                .getOrElse(AuthResult::Error)
        }
    }

    private suspend fun Captcha.enrich(): Captcha {
        return copy(url = networkApiRepository.getCaptchaUrl(url))
    }
}
