package lava.search.input

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import lava.common.analytics.AnalyticsTracker
import lava.common.analytics.rethrowIfCancellation
import lava.common.newCancelableScope
import lava.common.relaunch
import lava.credentials.ProviderConfigRepository
import lava.domain.usecase.AddSuggestUseCase
import lava.domain.usecase.ObserveSuggestsUseCase
import lava.domain.usecase.StartupProvidersGate
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
    private val displayNameResolver: ProviderDisplayNameResolver,
    // LVA-085 x LVA-093 composition follow-up (2026-08-13): the same
    // cold-start readiness gate SearchResultViewModel awaits before reading
    // the tracker registry — see [StartupProvidersGate] KDoc. This screen is
    // reached BEFORE the results screen, so it is at least as exposed to the
    // race: a search fired immediately after cold launch could read
    // `displayNameResolver.displayNames()` (backed by
    // `LavaTrackerSdk.listAvailableTrackers()`) before
    // `RepopulateProvidersOnStartupUseCase` has installed the dynamic
    // providers, silently falling back to raw provider ids in the chip bar.
    private val providersReadyGate: StartupProvidersGate,
) : ViewModel(), ContainerHost<SearchInputState, SearchInputSideEffect> {
    private val logger = loggerFactory.get("SearchInputViewModel")
    private val filter = savedStateHandle.filter
    private val observeSuggestsScope = viewModelScope.newCancelableScope()

    /**
     * The set of provider ids the search will query — i.e. the user's
     * currently-selected chips.
     *
     * 2026-06-25 VIDEO-CLUSTER ROOT-CAUSE FIX (operator video, issues #1/#2/#3).
     * The prior version maintained a HARDCODED `availableProviders` list of 4
     * ids (rutracker/rutor/archiveorg/gutenberg) and intersected the onboarded
     * set with it. A user who onboarded a provider OUTSIDE that list (e.g. YTS)
     * got ZERO selected chips → the submit resolved to an empty list → the
     * SearchResultNavigation serializer dropped the empty list → the result
     * screen read `providerIds == null` → `observePagingData()` (the
     * single-tracker rutracker path) → 401 → "Something went wrong". The chip
     * bar ALSO only ever showed those 4 phantom providers, never the onboarded
     * one. And the legacy `null`-means-ALL sentinel (when the selected size
     * happened to equal 4) silently re-routed search to the wrong set.
     *
     * Fix: the chip bar IS the user's onboarded set (the source of truth
     * `ProviderConfigRepository` — the same table onboarding writes to),
     * filtered by `searchEnabled && isEnabled`, deterministically sorted by
     * provider id. Display names resolve from the live tracker registry
     * (so dynamic / API-vended providers like YTS render correctly). Submit
     * passes the EXPLICIT selected list — never the `null`-means-all sentinel.
     */
    private var selectedProviders: Set<String> = emptySet()

    override val container: Container<SearchInputState, SearchInputSideEffect> = container(
        initialState = SearchInputState(providerChips = emptyList()),
        onCreate = {
            // Populate the chip bar as an Orbit intent (NOT a raw
            // viewModelScope.launch) so it shares the container's single
            // intent queue — guaranteeing the chips are in state before any
            // user ProviderToggled intent is processed (deterministic
            // ordering; video issue #3).
            loadOnboardedChips()
            onInputChanged(filter.query.toTextFieldValue())
        },
    )

    private fun loadOnboardedChips() = intent {
        val onboarded = onboardedSearchableProviders()
        selectedProviders = onboarded.toSet()
        // LVA-085 x LVA-093 composition follow-up: await the cold-start
        // registry-readiness gate BEFORE resolving display names — NOT
        // before `onboardedSearchableProviders()` above, which reads the
        // user's persisted onboarding config (Room), an entirely different
        // data source unrelated to the dynamic tracker-registry race.
        providersReadyGate.awaitReady()
        val names = displayNameResolver.displayNames()
        reduce {
            state.copy(
                providerChips = onboarded.map { id ->
                    ProviderChip(
                        providerId = id,
                        displayName = names[id] ?: id,
                        selected = true,
                    )
                },
            )
        }
    }

    /**
     * The user's onboarded, search-enabled providers — the single source of
     * truth for both the chip bar and the search query set. Deterministically
     * sorted by provider id so the rendered chips + the submit payload are
     * stable run-to-run (video issue #3: no non-deterministic provider set).
     */
    private suspend fun onboardedSearchableProviders(): List<String> =
        providerConfigRepository.observeAll().first()
            .filter { it.searchEnabled && it.isEnabled }
            .map { it.providerId }
            .distinct()
            .sorted()

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
                e.rethrowIfCancellation()
                analytics.recordNonFatal(e, mapOf(AnalyticsTracker.Params.QUERY to value.text))
            }
        }
    }

    /**
     * Resolves the explicit provider-id list for the search the user just
     * submitted. ALWAYS an explicit list — NEVER the legacy `null`-means-all
     * sentinel (removed 2026-06-25): the search-input set is the user's
     * onboarded set, so "all" has no meaning here, and emitting `null` would
     * route the result screen into the single-tracker rutracker fallback
     * (video issue #1/#2). Returns `null` ONLY when the user has no onboarded
     * search-enabled providers at all — in which case there is genuinely
     * nothing to query and the result screen renders its empty/error state.
     *
     * Bug 2 second-cascade guard (2026-05-17) retained: if Submit fires
     * BEFORE the onCreate coroutine populated [selectedProviders], block on a
     * fresh repository read (the same source-of-truth) so the submit never
     * ships an empty set the navigation serializer would silently drop.
     */
    private suspend fun resolveProviderIdsForSubmit(): List<String>? {
        if (selectedProviders.isEmpty()) {
            selectedProviders = onboardedSearchableProviders().toSet()
        }
        return selectedProviders.toList().sorted().takeIf { it.isNotEmpty() }
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
        // Toggle selection on the EXISTING (onboarded) chip set — the chip bar
        // membership is the onboarded set; toggling only flips `selected`.
        reduce {
            state.copy(
                providerChips = state.providerChips.map { it.copy(selected = it.providerId in current) },
            )
        }
    }

    private fun TextFieldValue.removeNewLines(): TextFieldValue = copy(text.replace("\n", " "))

    private fun String?.toTextFieldValue(): TextFieldValue =
        this.orEmpty().let { TextFieldValue(it, TextRange(it.length)) }
}
