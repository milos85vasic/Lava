package lava.search.input

import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import lava.common.analytics.AnalyticsTracker
import lava.credentials.ProviderConfig
import lava.credentials.ProviderConfigRepository
import lava.data.api.repository.SuggestsRepository
import lava.database.dao.ProviderConfigDao
import lava.database.entity.ProviderConfigEntity
import lava.domain.usecase.AddSuggestUseCase
import lava.domain.usecase.ObserveSuggestsUseCase
import lava.models.search.Suggest
import lava.testing.TestDispatchers
import lava.testing.logger.TestLoggerFactory
import lava.testing.rule.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Real-stack Orbit ViewModel test for [SearchInputViewModel].
 *
 * The SUT is the REAL [SearchInputViewModel] wired to the REAL
 * [ObserveSuggestsUseCase], [AddSuggestUseCase] and the REAL concrete
 * [ProviderConfigRepository]. Only the outermost persistence boundaries —
 * the [SuggestsRepository] and the Room-backed [ProviderConfigDao] — are
 * replaced with behaviorally-equivalent in-memory fakes (the DAO fake
 * REPLACE-on-conflict upsert mirrors `OnConflictStrategy.REPLACE`). No
 * UseCase and no repository is mocked (§6.J, Second Law).
 *
 * These tests cover the two anti-bluff-critical behaviours flagged in the
 * production KDoc:
 *  - Bug 3: the chip bar must default-select ONLY onboarded
 *    (searchEnabled && isEnabled) providers, never all four.
 *  - Bug 2: Submit must resolve provider ids from the persisted config
 *    (null == "all providers"; a strict subset == the explicit list).
 *
 * ## Test classification
 * VM-CONTRACT — primary assertions on the rendered [SearchInputState]
 * (chip selection) and the [SearchInputSideEffect.OpenSearch] payload's
 * resolved `providerIds`, the surface the screen + navigation read. The
 * rendered-UI Challenge is owed per `feature/CLAUDE.md`.
 *
 * ## Bluff-Audit
 * See commit body for the per-class mutation + observed-failure record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchInputViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Behaviorally-equivalent in-memory [SuggestsRepository]. */
    private class FakeSuggestsRepository : SuggestsRepository {
        val suggests = MutableStateFlow<List<String>>(emptyList())
        override fun observeSuggests(): Flow<List<String>> = suggests
        override suspend fun addSuggest(suggest: String) {
            suggests.update { if (suggest in it) it else it + suggest }
        }
        override suspend fun clear() {
            suggests.value = emptyList()
        }
    }

    /**
     * Behaviorally-equivalent in-memory [ProviderConfigDao] honouring the
     * production `@Insert(onConflict = REPLACE)` semantics keyed on
     * `providerId`.
     */
    private class FakeProviderConfigDao : ProviderConfigDao {
        val rows = MutableStateFlow<List<ProviderConfigEntity>>(emptyList())
        override suspend fun load(providerId: String): ProviderConfigEntity? =
            rows.value.firstOrNull { it.providerId == providerId }
        override fun observeAll(): Flow<List<ProviderConfigEntity>> = rows
        override fun observe(providerId: String): Flow<ProviderConfigEntity?> =
            rows.map { list -> list.firstOrNull { it.providerId == providerId } }
        override suspend fun upsert(entity: ProviderConfigEntity) {
            rows.update { list -> list.filterNot { it.providerId == entity.providerId } + entity }
        }
        override suspend fun delete(providerId: String) {
            rows.update { list -> list.filterNot { it.providerId == providerId } }
        }
    }

    private class NoopAnalytics : AnalyticsTracker {
        override fun event(name: String, params: Map<String, String>) {}
        override fun setUserId(userId: String?) {}
        override fun setProperty(key: String, value: String?) {}
        override fun recordNonFatal(throwable: Throwable, context: Map<String, String>) {}
        override fun recordWarning(message: String, context: Map<String, String>) {}
        override fun log(message: String) {}
    }

    /**
     * Behaviorally-equivalent in-memory [ProviderDisplayNameResolver] — the
     * outermost provider-catalogue boundary the production resolver wraps
     * (`LavaTrackerSdk.listAvailableTrackers()`). Returns the configured
     * display name for a known provider id, or `null` (so the VM falls back
     * to the raw id, matching the production resolver's fallback contract).
     */
    private class FakeDisplayNames(private val names: Map<String, String> = emptyMap()) :
        ProviderDisplayNameResolver {
        override fun displayNames(): Map<String, String> = names
    }

    private lateinit var suggestsRepo: FakeSuggestsRepository
    private lateinit var dao: FakeProviderConfigDao
    private lateinit var configRepo: ProviderConfigRepository

    private fun createViewModel(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        savedState: SavedStateHandle = SavedStateHandle(),
        displayNames: Map<String, String> = emptyMap(),
    ): SearchInputViewModel {
        suggestsRepo = FakeSuggestsRepository()
        dao = FakeProviderConfigDao()
        configRepo = ProviderConfigRepository(dao)
        val dispatchers = TestDispatchers(UnconfinedTestDispatcher(scheduler))
        return SearchInputViewModel(
            savedStateHandle = savedState,
            observeSuggestsUseCase = ObserveSuggestsUseCase(suggestsRepo),
            saveSuggestUseCase = AddSuggestUseCase(suggestsRepo, dispatchers),
            loggerFactory = TestLoggerFactory(),
            analytics = NoopAnalytics(),
            providerConfigRepository = configRepo,
            displayNameResolver = FakeDisplayNames(displayNames),
        )
    }

    /** Persist an onboarded provider (searchEnabled + isEnabled). */
    private suspend fun onboard(providerId: String, searchEnabled: Boolean = true, isEnabled: Boolean = true) {
        configRepo.save(
            ProviderConfig(
                providerId = providerId,
                searchEnabled = searchEnabled,
                isEnabled = isEnabled,
            ),
        )
    }

    // VM-CONTRACT
    //
    // 2026-06-25 video-cluster root-cause fix. The chip set is now built
    // from ProviderConfigRepository (the source of truth onboarding writes
    // to) — NOT a hardcoded list of 4. With nothing onboarded, the bar is
    // EMPTY (the old code rendered 4 phantom chips the user never onboarded).
    @Test
    fun onCreate_with_no_onboarded_providers_renders_empty_chip_bar() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            val chips = vm.container.stateFlow.value.providerChips
            assertTrue("chip bar must be empty when nothing is onboarded", chips.isEmpty())
        }

    // VM-CONTRACT
    @Test
    fun onCreate_renders_chips_for_only_onboarded_search_enabled_providers() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            onboard("archiveorg")
            onboard("rutor", searchEnabled = false) // onboarded but search disabled -> not shown
            onboard("gutenberg", isEnabled = false) // provider disabled -> not shown
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            // Only the searchEnabled+isEnabled provider appears AND is selected.
            val chips = vm.container.stateFlow.value.providerChips
            assertEquals(listOf("archiveorg"), chips.map { it.providerId })
            assertTrue("the onboarded provider must default-select", chips.single().selected)
        }

    // VM-CONTRACT
    //
    // REPRODUCES the video-reported cluster (#1/#2/#3): the user onboarded
    // ONLY a provider that is NOT in the legacy hardcoded list (e.g. YTS).
    // Pre-fix the chip bar showed the 4 hardcoded providers (rutracker/rutor/
    // archiveorg/gutenberg) and selected NONE of them, so search queried the
    // wrong set entirely. Post-fix the bar shows ONLY the onboarded provider.
    @Test
    fun onCreate_with_onboarded_provider_outside_legacy_list_renders_it() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler, displayNames = mapOf("yts" to "YTS"))
            onboard("yts")
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            val chips = vm.container.stateFlow.value.providerChips
            // The onboarded provider IS the chip set — not the 4 hardcoded ones.
            assertEquals(listOf("yts"), chips.map { it.providerId })
            assertEquals("YTS", chips.single().displayName)
            assertTrue("the onboarded provider must default-select", chips.single().selected)
            assertTrue(
                "no phantom hardcoded provider may appear",
                chips.none { it.providerId in setOf("rutracker", "rutor", "archiveorg", "gutenberg") },
            )
        }

    // VM-CONTRACT
    //
    // REPRODUCES video #2: search must query EXACTLY the onboarded provider,
    // never the legacy hardcoded set, never the null-means-all sentinel.
    @Test
    fun SubmitClick_with_provider_outside_legacy_list_queries_only_it() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler, displayNames = mapOf("yts" to "YTS"))
            onboard("yts")
            var captured: SearchInputSideEffect.OpenSearch? = null
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchInputAction.InputChanged(TextFieldValue("inception")))
                vm.perform(SearchInputAction.SubmitClick)
                captured = awaitOpenSearch()
                cancelAndIgnoreRemainingItems()
            }
            assertEquals("inception", captured?.filter?.query)
            // The explicit onboarded set — NOT null (which routes the result
            // screen into the single-tracker rutracker path → "Something went wrong").
            assertEquals(listOf("yts"), captured?.filter?.providerIds)
        }

    // VM-CONTRACT
    //
    // REPRODUCES video #3: the input-selected provider set MUST equal the set
    // the result screen renders chips from. The result screen reads
    // `filter.providerIds` for its chip bar, so the submit payload's
    // `providerIds` MUST carry the exact selected onboarded set (deterministic,
    // sorted) — never a divergent or null value that drops the result chips.
    @Test
    fun SubmitClick_providerIds_match_the_selected_chip_set_deterministically() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(
                testScheduler,
                displayNames = mapOf("gutenberg" to "Gutenberg", "archiveorg" to "Internet Archive"),
            )
            // Onboard in non-sorted insertion order to prove deterministic ordering.
            onboard("gutenberg")
            onboard("archiveorg")
            var captured: SearchInputSideEffect.OpenSearch? = null
            val chipsSelected = mutableListOf<String>()
            vm.test(this) {
                runOnCreate()
                chipsSelected += vm.container.stateFlow.value.providerChips
                    .filter { it.selected }.map { it.providerId }
                vm.perform(SearchInputAction.InputChanged(TextFieldValue("dune")))
                vm.perform(SearchInputAction.SubmitClick)
                captured = awaitOpenSearch()
                cancelAndIgnoreRemainingItems()
            }
            // Input chip set (selected) == submit payload providerIds == sorted.
            assertEquals(listOf("archiveorg", "gutenberg"), chipsSelected)
            assertEquals(listOf("archiveorg", "gutenberg"), captured?.filter?.providerIds)
        }

    // VM-CONTRACT
    //
    // Toggling de-selects an onboarded provider's chip (the user narrows the
    // search to a subset of what they onboarded). The chip stays in the bar
    // (it is onboarded) but flips to unselected.
    @Test
    fun ProviderToggled_flips_chip_selection_in_rendered_state() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(
                testScheduler,
                displayNames = mapOf("rutracker" to "RuTracker", "rutor" to "RuTor"),
            )
            onboard("rutracker")
            onboard("rutor")
            vm.test(this) {
                runOnCreate()
                // The chip-population intent is queued before this toggle on the
                // container's single intent queue (deterministic ordering), so
                // both chips are present + selected by the time the toggle runs.
                vm.perform(SearchInputAction.ProviderToggled("rutracker"))
                cancelAndIgnoreRemainingItems()
            }
            val chips = vm.container.stateFlow.value.providerChips
            // Deterministic id-sorted order (rutor < rutracker) — video issue #3.
            assertEquals(listOf("rutor", "rutracker"), chips.map { it.providerId })
            assertTrue("toggled rutracker chip must become unselected", chips.first { it.providerId == "rutracker" }.selected.not())
            assertTrue("untouched rutor chip stays selected", chips.first { it.providerId == "rutor" }.selected)
        }

    // VM-CONTRACT
    @Test
    fun InputChanged_updates_searchInput_and_filters_suggests() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            suggestsRepo.suggests.value = listOf("ubuntu", "ubuntu server", "debian")
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchInputAction.InputChanged(TextFieldValue("ubuntu")))
                cancelAndIgnoreRemainingItems()
            }
            val state = vm.container.stateFlow.value
            assertEquals("ubuntu", state.searchInput.text)
            // Real ObserveSuggestsUseCase filters out the exact match + non-matches.
            assertEquals(listOf("ubuntu server"), state.suggests.map { it.value })
        }

    // VM-CONTRACT
    @Test
    fun SubmitClick_with_subset_of_providers_emits_OpenSearch_with_that_subset() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            onboard("archiveorg")
            var captured: SearchInputSideEffect.OpenSearch? = null
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchInputAction.InputChanged(TextFieldValue("ubuntu")))
                vm.perform(SearchInputAction.SubmitClick)
                captured = awaitOpenSearch()
                cancelAndIgnoreRemainingItems()
            }
            assertEquals("ubuntu", captured?.filter?.query)
            // Bug 2 fix: a strict subset must be passed explicitly, not null.
            assertEquals(listOf("archiveorg"), captured?.filter?.providerIds)
            // Real AddSuggestUseCase persisted the query.
            assertTrue("query must be saved to suggests", "ubuntu" in suggestsRepo.suggests.value)
        }

    // VM-CONTRACT
    //
    // 2026-06-25 video-cluster fix. PRE-FIX this asserted that onboarding all
    // 4 legacy-hardcoded providers collapsed `providerIds` to `null` — and
    // `null` routed the RESULT screen into `observePagingData()` (the
    // single-tracker rutracker path) which 401'd → "Something went wrong".
    // That was the bug, encoded as an expectation (§6.J bluff). POST-FIX the
    // submit ALWAYS carries the explicit onboarded set; the `null`-means-all
    // sentinel is never produced from the search-input side.
    @Test
    fun SubmitClick_with_many_providers_onboarded_emits_explicit_list_not_null() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(
                testScheduler,
                displayNames = mapOf(
                    "rutracker" to "RuTracker",
                    "rutor" to "RuTor",
                    "archiveorg" to "Internet Archive",
                    "gutenberg" to "Gutenberg",
                ),
            )
            onboard("rutracker")
            onboard("rutor")
            onboard("archiveorg")
            onboard("gutenberg")
            var captured: SearchInputSideEffect.OpenSearch? = null
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchInputAction.InputChanged(TextFieldValue("ubuntu")))
                vm.perform(SearchInputAction.SubmitClick)
                captured = awaitOpenSearch()
                cancelAndIgnoreRemainingItems()
            }
            // Explicit, sorted, never null — so the result screen renders the
            // multi-provider stream + chips, never the rutracker-only fallback.
            assertEquals(
                listOf("archiveorg", "gutenberg", "rutor", "rutracker"),
                captured?.filter?.providerIds,
            )
        }

    // VM-CONTRACT
    //
    // Covers the `SuggestClick -> onSubmit(action.suggest.value)` overload,
    // which had ZERO coverage: every prior submit test drove `SubmitClick`
    // (the `onSubmit()` overload that reads `state.searchInput.text`).
    // Tapping a suggestion row is a DISTINCT production path — it searches the
    // CLICKED suggestion's value, trimmed, NOT whatever the user has typed into
    // the input field. The load-bearing discrimination assertion: the emitted
    // OpenSearch query is "debian iso" (the suggest), even though the input
    // field holds the unrelated "ubuntu". A mutation that routes SuggestClick
    // through the input-text path (or drops the trim) is caught here.
    @Test
    fun SuggestClick_searches_the_clicked_suggest_value_not_the_input_text() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            // The user onboarded one provider, so the resolved subset is explicit.
            onboard("archiveorg")
            var captured: SearchInputSideEffect.OpenSearch? = null
            vm.test(this) {
                runOnCreate()
                // The input field holds something the user typed but did NOT submit.
                vm.perform(SearchInputAction.InputChanged(TextFieldValue("ubuntu")))
                // The user instead taps a suggestion row whose value has padding
                // that the production trim() must strip.
                vm.perform(SearchInputAction.SuggestClick(Suggest("  debian iso  ")))
                captured = awaitOpenSearch()
                cancelAndIgnoreRemainingItems()
            }
            // Primary user-visible assertion: the search opens for the CLICKED
            // suggestion (trimmed), not the unsubmitted input text "ubuntu".
            assertEquals("debian iso", captured?.filter?.query)
            // The clicked suggest's value (trimmed) is persisted to history,
            // and the stale input text "ubuntu" is NOT.
            assertTrue("clicked suggest must be saved to history", "debian iso" in suggestsRepo.suggests.value)
            assertTrue("unsubmitted input text must NOT be saved", "ubuntu" !in suggestsRepo.suggests.value)
            // Bug-2 resolution still applies on this path: a strict subset is
            // passed explicitly, never collapsed to null.
            assertEquals(listOf("archiveorg"), captured?.filter?.providerIds)
        }

    // VM-CONTRACT
    @Test
    fun BackClick_emits_Back_side_effect() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            var captured: SearchInputSideEffect? = null
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchInputAction.BackClick)
                captured = drainSideEffect()
                cancelAndIgnoreRemainingItems()
            }
            assertEquals(SearchInputSideEffect.Back, captured)
        }

    // VM-CONTRACT
    @Test
    fun SuggestEditClick_loads_the_suggest_text_into_the_input() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchInputAction.SuggestEditClick(Suggest("debian iso")))
                cancelAndIgnoreRemainingItems()
            }
            assertEquals("debian iso", vm.container.stateFlow.value.searchInput.text)
        }
}

/**
 * Drains the item stream until the first matching [SearchInputSideEffect]
 * is observed, ignoring interleaved state items (same pattern as
 * [lava.search.result.SearchResultViewModelFallbackTest]).
 */
private suspend fun org.orbitmvi.orbit.test.OrbitTestContext<SearchInputState, SearchInputSideEffect, SearchInputViewModel>.drainSideEffect(): SearchInputSideEffect {
    while (true) {
        val item = awaitItem()
        if (item is org.orbitmvi.orbit.test.Item.SideEffectItem) return item.value
    }
}

/**
 * onSubmit emits HideKeyboard before OpenSearch, so we skip past it.
 */
private suspend fun org.orbitmvi.orbit.test.OrbitTestContext<SearchInputState, SearchInputSideEffect, SearchInputViewModel>.awaitOpenSearch(): SearchInputSideEffect.OpenSearch {
    while (true) {
        val effect = drainSideEffect()
        if (effect is SearchInputSideEffect.OpenSearch) return effect
    }
}
