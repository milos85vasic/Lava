package lava.domain.usecase

import kotlinx.coroutines.withContext
import lava.data.api.service.StoreService
import lava.dispatchers.api.Dispatchers
import lava.models.Store
import javax.inject.Inject

interface GetRatingStoreUseCase : suspend () -> Store

internal class GetRatingStoreUseCaseImpl @Inject constructor(
    private val storeService: StoreService,
    private val dispatchers: Dispatchers,
) : GetRatingStoreUseCase {
    override suspend fun invoke() = withContext(dispatchers.default) {
        storeService.getStore()
    }
}
