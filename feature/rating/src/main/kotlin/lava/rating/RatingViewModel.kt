package lava.rating

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import lava.domain.model.rating.RatingRequest
import lava.domain.usecase.AppLaunchedUseCase
import lava.domain.usecase.DisableRatingRequestUseCase
import lava.domain.usecase.GetRatingStoreUseCase
import lava.domain.usecase.ObserveRatingRequestUseCase
import lava.domain.usecase.PostponeRatingRequestUseCase
import lava.logger.api.LoggerFactory
import kotlinx.coroutines.flow.collectLatest
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
class RatingViewModel @Inject constructor(
    private val appLaunchedUseCase: AppLaunchedUseCase,
    private val disableRatingRequestUseCase: DisableRatingRequestUseCase,
    private val getRatingStoreUseCase: GetRatingStoreUseCase,
    private val observeRatingRequestUseCase: ObserveRatingRequestUseCase,
    private val postponeRatingRequestUseCase: PostponeRatingRequestUseCase,
    loggerFactory: LoggerFactory,
) : ViewModel(), ContainerHost<RatingRequest, RatingSideEffect> {
    private val logger = loggerFactory.get("RatingViewModel")

    override val container: Container<RatingRequest, RatingSideEffect> = container(
        initialState = RatingRequest.Hide,
        onCreate = {
            observeRatingRequest()
            intent { appLaunchedUseCase() }
        },
    )

    fun perform(action: RatingAction) {
        logger.d { "Perform $action" }
        when (action) {
            is RatingAction.AskLaterClick -> onAskLaterClick()
            is RatingAction.DismissClick -> onAskLaterClick()
            is RatingAction.NeverAskAgainClick -> onNeverAskAgainClick()
            is RatingAction.RatingClick -> onRatingClick()
        }
    }

    private fun observeRatingRequest() = intent {
        observeRatingRequestUseCase().collectLatest { reduce { it } }
    }

    private fun onAskLaterClick() = intent {
        postponeRatingRequestUseCase()
    }

    private fun onNeverAskAgainClick() = intent {
        disableRatingRequestUseCase()
    }

    private fun onRatingClick() = intent {
        val store = getRatingStoreUseCase.invoke()
        postSideEffect(RatingSideEffect.OpenLink(store.link))
        disableRatingRequestUseCase()
    }
}
