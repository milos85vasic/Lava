package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.RatingRepository
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

interface PostponeRatingRequestUseCase : suspend () -> Unit

internal class PostponeRatingRequestUseCaseImpl @Inject constructor(
    private val ratingRepository: RatingRepository,
    private val dispatchers: Dispatchers,
) : PostponeRatingRequestUseCase {
    override suspend fun invoke() = withContext(dispatchers.default) {
        ratingRepository.setLaunchCount(PostponedLaunchCount)
        ratingRepository.postponeRatingRequest()
    }

    companion object {
        const val PostponedLaunchCount = 10
    }
}
