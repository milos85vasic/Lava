package lava.search.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import lava.common.newCancelableScope
import lava.common.relaunch
import lava.domain.usecase.AddSuggestUseCase
import lava.domain.usecase.ObserveSuggestsUseCase
import lava.logger.api.LoggerFactory
import lava.models.search.Suggest
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
internal class SearchInputViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val observeSuggestsUseCase: ObserveSuggestsUseCase,
    private val saveSuggestUseCase: AddSuggestUseCase,
    loggerFactory: LoggerFactory,
) : ViewModel(), ContainerHost<SearchInputState, SearchInputSideEffect> {
    private val logger = loggerFactory.get("SearchInputViewModel")
    private val filter = savedStateHandle.filter
    private val observeSuggestsScope = viewModelScope.newCancelableScope()

    override val container: Container<SearchInputState, SearchInputSideEffect> = container(
        initialState = SearchInputState(),
        onCreate = { onInputChanged(filter.query.toTextFieldValue()) },
    )

    fun perform(action: SearchInputAction) {
        logger.d { "Perform $action" }
        when (action) {
            is SearchInputAction.BackClick -> onBackClick()
            is SearchInputAction.ClearInputClick -> onInputChanged(TextFieldValue())
            is SearchInputAction.InputChanged -> onInputChanged(action.value.removeNewLines())
            is SearchInputAction.SubmitClick -> onSubmit()
            is SearchInputAction.SuggestClick -> onSubmit(action.suggest.value)
            is SearchInputAction.SuggestEditClick -> onSuggestSelected(action.suggest)
        }
    }

    private fun onBackClick() = intent {
        postSideEffect(SearchInputSideEffect.Back)
    }

    private fun onInputChanged(value: TextFieldValue) = intent {
        reduce { state.copy(searchInput = value) }
        observeSuggestsScope.relaunch {
            observeSuggestsUseCase(value.text).collectLatest { suggests ->
                reduce { state.copy(suggests = suggests) }
            }
        }
    }

    private fun onSubmit() = intent {
        val query = state.searchInput.text.trim()
        saveSuggestUseCase(query)
        postSideEffect(SearchInputSideEffect.HideKeyboard)
        postSideEffect(SearchInputSideEffect.OpenSearch(filter.copy(query = query)))
    }

    private fun onSubmit(value: String) = intent {
        val query = value.trim()
        saveSuggestUseCase(query)
        postSideEffect(SearchInputSideEffect.HideKeyboard)
        postSideEffect(SearchInputSideEffect.OpenSearch(filter.copy(query = query)))
    }

    private fun onSuggestSelected(suggest: Suggest) = intent {
        reduce { state.copy(searchInput = suggest.value.toTextFieldValue()) }
    }

    private fun TextFieldValue.removeNewLines(): TextFieldValue = copy(text.replace("\n", " "))

    private fun String?.toTextFieldValue(): TextFieldValue =
        this.orEmpty().let { TextFieldValue(it, TextRange(it.length)) }
}
