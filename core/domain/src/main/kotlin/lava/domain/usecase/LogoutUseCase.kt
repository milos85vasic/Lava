package lava.domain.usecase

import lava.auth.api.AuthService
import lava.data.api.repository.BookmarksRepository
import lava.data.api.repository.FavoritesRepository
import lava.data.api.repository.SearchHistoryRepository
import lava.data.api.repository.SuggestsRepository
import lava.data.api.repository.VisitedRepository
import lava.dispatchers.api.Dispatchers
import lava.work.api.BackgroundService
import kotlinx.coroutines.withContext
import javax.inject.Inject

interface LogoutUseCase : suspend () -> Unit

internal class LogoutUseCaseImpl @Inject constructor(
    private val authService: AuthService,
    private val backgroundService: BackgroundService,
    private val bookmarksRepository: BookmarksRepository,
    private val favoritesRepository: FavoritesRepository,
    private val searchHistoryRepository: SearchHistoryRepository,
    private val suggestsRepository: SuggestsRepository,
    private val visitedRepository: VisitedRepository,
    private val dispatchers: Dispatchers,
) : LogoutUseCase {
    override suspend operator fun invoke() {
        withContext(dispatchers.default) {
            backgroundService.stopBackgroundWorks()
            authService.logout()
            bookmarksRepository.clear()
            favoritesRepository.clear()
            searchHistoryRepository.clear()
            suggestsRepository.clear()
            visitedRepository.clear()
        }
    }
}
