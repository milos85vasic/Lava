package lava.domain.usecase

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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
            // LVA-070 — overlay the persisted source provider id onto each enriched
            // model (the shared EnrichTopicsUseCase has no provenance) so a
            // visited-history tap reopens the topic with its provider, routing
            // archiveorg/gutenberg topics to HTTP_DOWNLOAD.
            .combine(visitedRepository.observeProviderIds()) { models, providerIds ->
                models.map { model ->
                    val providerId = providerIds[model.topic.id]
                    if (providerId != null) model.copy(providerId = providerId) else model
                }
            }
            .distinctUntilChanged()
            .catch {
                visitedRepository.clear()
                emit(emptyList())
            }
    }
}
