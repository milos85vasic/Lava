package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import lava.data.api.repository.VisitedRepository
import lava.models.topic.Topic
import lava.models.topic.TopicModel
import javax.inject.Inject

class ObserveVisitedUseCase @Inject constructor(
    private val visitedRepository: VisitedRepository,
    private val enrichTopicsUseCase: EnrichTopicsUseCase,
) {
    operator fun invoke(): Flow<List<TopicModel<out Topic>>> {
        return visitedRepository.observeTopics()
            .flatMapLatest(enrichTopicsUseCase::invoke)
            .distinctUntilChanged()
            .catch {
                visitedRepository.clear()
                emit(emptyList())
            }
    }
}
