package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.repository.RatingRepository
import lava.dispatchers.api.Dispatchers
import javax.inject.Inject

interface DisableRatingRequestUseCase : suspend () -> Unit

internal class DisableRatingRequestUseCaseImpl @Inject constructor(
    private val ratingRepository: RatingRepository,
    private val dispatchers: Dispatchers,
) : DisableRatingRequestUseCase {
    override suspend fun invoke() = withContext(dispatchers.default) {
        ratingRepository.disableRatingRequest()
    }
}
