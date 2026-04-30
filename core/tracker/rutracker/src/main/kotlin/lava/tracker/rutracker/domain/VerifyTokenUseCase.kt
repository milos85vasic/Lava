package lava.tracker.rutracker.domain

internal object VerifyTokenUseCase {
    operator fun invoke(token: String): Boolean {
        return token.isNotEmpty()
    }
}
