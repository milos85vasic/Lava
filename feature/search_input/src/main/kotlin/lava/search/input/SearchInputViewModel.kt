package lava.search.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import lava.common.analytics.AnalyticsTracker
import lava.common.newCancelableScope
import lava.common.relaunch
import lava.credentials.ProviderConfigRepository
import lava.domain.usecase.AddSuggestUseCase
import lava.domain.usecase.ObserveSuggestsUseCase
import lava.logger.api.LoggerFactory
import lava.models.search.Suggest
import lava.search.input.components.ProviderChip
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
    private val analytics: AnalyticsTracker,
    private val providerConfigRepository: ProviderConfigRepository,
) : ViewModel(), ContainerHost<SearchInputState, SearchInputSideEffect> {
    private val logger = loggerFactory.get("SearchInputViewModel")
    private val filter = savedStateHandle.filter
    private val observeSuggestsScope = viewModelScope.newCancelableScope()

    /**
     * The full set of providers Lava knows about. ProviderChip(providerId,
     * displayName, selected) — selected is recomputed in onCreate based on
     * which providers the user actually onboarded; never trust this default
     * for the rendered UI.
     */
    private val availableProviders = listOf(
        ProviderChip("rutracker", "RuTracker", false),
        ProviderChip("rutor", "RuTor", false),
        ProviderChip("archiveorg", "Internet Archive", false),
        ProviderChip("gutenberg", "Gutenberg", false),
    )

    /**
     * Bug 3 (2026-05-17, user report on 1.2.23-1043): the prior version
     * initialized this to `availableProviders.map { it.providerId }.toSet()`
     * — ALL providers selected by default. The user saw the search filter
     * pre-selecting providers they had never onboarded, which then either
     * (a) failed with "not registered" in streamMultiSearch, or
     * (b) silently sent traffic for unconfigured providers.
     *
     * Fix: initialize empty + populate in onCreate from the persisted
     * ProviderConfigRepository (the same source onboarding writes to).
     * Only providers with a config row AND searchEnabled = true are
     * pre-selected. The user can still toggle any chip; this only
     * controls the DEFAULT for the search-input chip bar.
     */
    private var selectedProviders: Set<String> = emptySet()

    override val container: Container<SearchInputState, SearchInputSideEffect> = container(
        initialState = SearchInputState(providerChips = availableProviders),
        onCreate = {
            viewModelScope.launch {
                val configured = providerConfigRepository.observeAll().first()
                val onboardedAndSearchEnabled = configured
                    .filter { it.searchEnabled && it.isEnabled }
                    .map { it.providerId }
                    .toSet()
                selectedProviders = onboardedAndSearchEnabled
                reduce {
                    state.copy(
                        providerChips = availableProviders.map {
                            it.copy(selected = it.providerId in onboardedAndSearchEnabled)
                        },
                    )
                }
            }
            onInputChanged(filter.query.toTextFieldValue())
        },
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
            is SearchInputAction.ProviderToggled -> onProviderToggled(action.providerId)
        }
    }

    private fun onBackClick() = intent {
        postSideEffect(SearchInputSideEffect.Back)
    }

    private fun onInputChanged(value: TextFieldValue) = intent {
        reduce { state.copy(searchInput = value) }
        observeSuggestsScope.relaunch {
            try {
                observeSuggestsUseCase(value.text).collectLatest { suggests ->
                    reduce { state.copy(suggests = suggests) }
                }
            } catch (e: Exception) {
                analytics.recordNonFatal(e, mapOf(AnalyticsTracker.Params.QUERY to value.text))
            }
        }
    }

    /**
     * Bug 2 second-cascade fix (2026-05-17): if the user taps Submit
     * BEFORE the Bug 3 onCreate async coroutine has populated
     * selectedProviders from the repository, selectedProviders is
     * still emptySet() and the prior code produced providerIds = [].
     * The empty list was then dropped by SearchResultNavigation's
     * serializer, routing the user into observePagingData() (the
     * single-tracker rutracker path) which fails with LoadState.Error
     * for users who only onboarded anonymous providers.
     *
     * Fix: if selectedProviders is empty at submit time, BLOCK on
     * loading from ProviderConfigRepository (same source the chip-bar
     * onCreate reads). The user's perceived latency on first tap is
     * the existing repo-read time, which is < 100 ms in practice.
     * Subsequent taps reuse the cached selectedProviders.
     */
    private suspend fun resolveProviderIdsForSubmit(): List<String>? {
        if (selectedProviders.isEmpty()) {
            val configured = providerConfigRepository.observeAll().first()
            val onboarded = configured
                .filter { it.searchEnabled && it.isEnabled }
                .map { it.providerId }
                .toSet()
            selectedProviders = onboarded
        }
        val selected = selectedProviders.toList()
        return if (selected.size == availableProviders.size) null else selected
    }

    private fun onSubmit() = intent {
        val query = state.searchInput.text.trim()
        saveSuggestUseCase(query)
        val providerIds = resolveProviderIdsForSubmit()
        postSideEffect(SearchInputSideEffect.HideKeyboard)
        postSideEffect(SearchInputSideEffect.OpenSearch(filter.copy(query = query, providerIds = providerIds)))
    }

    private fun onSubmit(value: String) = intent {
        val query = value.trim()
        saveSuggestUseCase(query)
        val providerIds = resolveProviderIdsForSubmit()
        postSideEffect(SearchInputSideEffect.HideKeyboard)
        postSideEffect(SearchInputSideEffect.OpenSearch(filter.copy(query = query, providerIds = providerIds)))
    }

    private fun onSuggestSelected(suggest: Suggest) = intent {
        reduce { state.copy(searchInput = suggest.value.toTextFieldValue()) }
    }

    private fun onProviderToggled(providerId: String) = intent {
        val current = selectedProviders.toMutableSet()
        if (current.contains(providerId)) {
            current.remove(providerId)
        } else {
            current.add(providerId)
        }
        selectedProviders = current
        reduce {
            state.copy(
                providerChips = availableProviders.map { it.copy(selected = it.providerId in current) },
            )
        }
    }

    private fun TextFieldValue.removeNewLines(): TextFieldValue = copy(text.replace("\n", " "))

    private fun String?.toTextFieldValue(): TextFieldValue =
        this.orEmpty().let { TextFieldValue(it, TextRange(it.length)) }
}
