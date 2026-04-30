package lava.tracker.rutracker.domain

object VerifyAuthorisedUseCase {
    operator fun invoke(html: String): Boolean {
        return html.contains("logged-in-username")
    }
}
