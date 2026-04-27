package lava.data.impl.repository

import lava.common.SingleItemMutableSharedFlow
import lava.data.api.repository.RatingRepository
import lava.securestorage.PreferencesStorage
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject

internal class RatingRepositoryImpl @Inject constructor(
    private val preferencesStorage: PreferencesStorage,
) : RatingRepository {
    private val mutableRatingDisabledFlow = SingleItemMutableSharedFlow<Boolean>()
    private val mutableLaunchCountFlow = SingleItemMutableSharedFlow<Int>()

    override suspend fun getLaunchCount() = preferencesStorage.getRatingLaunchCount()

    override fun observeLaunchCount() = mutableLaunchCountFlow
        .asSharedFlow()
        .onStart { emit(getLaunchCount()) }

    override suspend fun setLaunchCount(value: Int) {
        mutableLaunchCountFlow.emit(value)
        preferencesStorage.setRatingLaunchCount(value)
    }

    override fun observeRatingRequestDisabled() = mutableRatingDisabledFlow
        .asSharedFlow()
        .onStart { emit(preferencesStorage.getRatingDisabled()) }

    override suspend fun disableRatingRequest() {
        mutableRatingDisabledFlow.emit(true)
        preferencesStorage.setRatingDisabled(true)
    }

    override suspend fun isRatingRequestPostponed(): Boolean {
        return preferencesStorage.getRatingPostponed()
    }

    override suspend fun postponeRatingRequest() {
        preferencesStorage.setRatingPostponed(true)
    }
}
