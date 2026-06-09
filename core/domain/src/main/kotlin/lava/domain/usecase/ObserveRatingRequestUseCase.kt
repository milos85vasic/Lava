package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import lava.data.api.repository.RatingRepository
import lava.domain.model.rating.RatingRequest
import javax.inject.Inject

interface ObserveRatingRequestUseCase : suspend () -> Flow<RatingRequest>

// Public (was internal) so feature-module tests can wire the REAL use case
// instead of a hand-rolled re-implementation that omits the engagement gate
// (LVA-016 — Fifth Law: refactor for testability). Still bound via @Binds in
// DomainModule; production consumers depend on the ObserveRatingRequestUseCase
// interface, not this class.
class ObserveRatingRequestUseCaseImpl @Inject constructor(
    private val observeSearchHistoryUseCase: ObserveSearchHistoryUseCase,
    private val observeVisitedUseCase: ObserveVisitedUseCase,
    private val observeBookmarksUseCase: ObserveBookmarksUseCase,
    private val ratingRepository: RatingRepository,
) : ObserveRatingRequestUseCase {
    override suspend fun invoke(): Flow<RatingRequest> {
        return combine(
            flows = listOf(
                ratingRepository.observeRatingRequestDisabled().map(Boolean::not),
                ratingRepository.observeLaunchCount().map { it <= 0 },
                combine(
                    flows = listOf(
                        observeSearchHistoryUseCase().map { (pinned, other) ->
                            pinned.size > PinnedSearchCounter || other.size > HistoryCounter
                        },
                        observeVisitedUseCase().map { it.size > VisitedCounter },
                        observeBookmarksUseCase().map { it.size > BookmarksCounter },
                    ),
                    transform = { conditions -> conditions.any { it } },
                ),
            ),
            transform = { conditions -> conditions.all { it } },
        ).mapLatest { show ->
            if (show) {
                RatingRequest.Show(ratingRepository.isRatingRequestPostponed())
            } else {
                RatingRequest.Hide
            }
        }
    }

    private companion object {
        const val PinnedSearchCounter = 1
        const val HistoryCounter = 3
        const val VisitedCounter = 5
        const val BookmarksCounter = 2
    }
}
