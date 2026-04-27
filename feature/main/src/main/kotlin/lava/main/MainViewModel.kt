package lava.main

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import lava.domain.usecase.ObserveSettingsUseCase
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    observeSettingsUseCase: ObserveSettingsUseCase,
) : ViewModel() {
    val theme = observeSettingsUseCase().map { it.theme }
}
