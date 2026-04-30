package lava.tracker.rutracker.domain

object VerifyTokenUseCase {
    operator fun invoke(token: String): Boolean {
        return token.isNotEmpty()
    }
}
