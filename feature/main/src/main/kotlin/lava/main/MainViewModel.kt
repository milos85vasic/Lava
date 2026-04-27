package lava.main

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.map
import lava.domain.usecase.ObserveSettingsUseCase
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    observeSettingsUseCase: ObserveSettingsUseCase,
) : ViewModel() {
    val theme = observeSettingsUseCase().map { it.theme }
}
