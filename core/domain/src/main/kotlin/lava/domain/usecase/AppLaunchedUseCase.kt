package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.RatingRepository
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

interface AppLaunchedUseCase : suspend () -> Unit

internal class AppLaunchedUseCaseImpl @Inject constructor(
    private val ratingRepository: RatingRepository,
    private val dispatchers: Dispatchers,
) : AppLaunchedUseCase {
    override suspend fun invoke() = withContext(dispatchers.default) {
        ratingRepository.setLaunchCount((ratingRepository.getLaunchCount() - 1).coerceAtLeast(0))
    }
}
