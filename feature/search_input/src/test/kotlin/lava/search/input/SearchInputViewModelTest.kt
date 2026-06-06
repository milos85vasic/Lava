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
import org.junit.Assert.assertNull
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

    private lateinit var suggestsRepo: FakeSuggestsRepository
    private lateinit var dao: FakeProviderConfigDao
    private lateinit var configRepo: ProviderConfigRepository

    private fun createViewModel(
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        savedState: SavedStateHandle = SavedStateHandle(),
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
    @Test
    fun onCreate_with_no_onboarded_providers_selects_no_chips() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            val chips = vm.container.stateFlow.value.providerChips
            assertEquals(4, chips.size)
            assertTrue("no chip may be selected when nothing is onboarded", chips.none { it.selected })
        }

    // VM-CONTRACT
    @Test
    fun onCreate_pre_selects_only_onboarded_search_enabled_providers() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            onboard("archiveorg")
            onboard("rutor", searchEnabled = false) // onboarded but search disabled -> not selected
            onboard("gutenberg", isEnabled = false) // provider disabled -> not selected
            vm.test(this) {
                runOnCreate()
                cancelAndIgnoreRemainingItems()
            }
            val selected = vm.container.stateFlow.value.providerChips.filter { it.selected }.map { it.providerId }
            assertEquals(listOf("archiveorg"), selected)
        }

    // VM-CONTRACT
    @Test
    fun ProviderToggled_flips_chip_selection_in_rendered_state() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
            vm.test(this) {
                runOnCreate()
                vm.perform(SearchInputAction.ProviderToggled("rutracker"))
                cancelAndIgnoreRemainingItems()
            }
            val chips = vm.container.stateFlow.value.providerChips
            assertTrue("rutracker chip must become selected", chips.first { it.providerId == "rutracker" }.selected)
            assertTrue("untouched chips stay unselected", chips.filter { it.providerId != "rutracker" }.none { it.selected })
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
    @Test
    fun SubmitClick_with_all_providers_onboarded_emits_null_providerIds() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = createViewModel(testScheduler)
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
            // null == "search all providers" per resolveProviderIdsForSubmit().
            assertNull("all-onboarded must collapse to null providerIds", captured?.filter?.providerIds)
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
