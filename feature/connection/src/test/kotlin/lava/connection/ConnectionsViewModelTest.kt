package lava.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import lava.domain.model.endpoint.EndpointState
import lava.domain.usecase.AddEndpointUseCaseImpl
import lava.domain.usecase.DiscoverLocalEndpointsUseCaseImpl
import lava.domain.usecase.ObserveEndpointsStatusUseCase
import lava.domain.usecase.RemoveEndpointUseCaseImpl
import lava.domain.usecase.SetEndpointUseCaseImpl
import lava.models.settings.Endpoint
import lava.testing.repository.TestEndpointsRepository
import lava.testing.repository.TestSettingsRepository
import lava.testing.service.TestLocalNetworkDiscoveryService
import lava.testing.testDispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Tests for [ConnectionsViewModel] — mixed Integration Challenge + ViewModel Contract suite.
 *
 * Per the root `CLAUDE.md` Sixth Law (clauses 1, 3, 4) two distinct categories
 * live in this file and a reader must not conflate them:
 *
 * 1. **Integration Challenge Tests** — primary assertion on persisted/observable
 *    user-visible state (repository membership, settings row, ViewModel `state.discovering`
 *    progress flag). These satisfy Sixth-Law clause 4 for their respective domain
 *    operations because a real user observes the same persisted state through later
 *    screens, or sees the `discovering` flag directly via the loading indicator.
 *    Tagged `// CHALLENGE` below.
 *
 * 2. **ViewModel Contract Tests** — primary assertion on `SideEffect` emission only.
 *    These verify the screen's contract with the ViewModel (which navigation /
 *    snackbar / dialog event the screen will receive). They do NOT prove the
 *    rendered screen reacts correctly; that is a separate layer requiring a
 *    Compose UI test. Tagged `// VM-CONTRACT` below.
 *
 * The load-bearing **rendered-UI Challenge** for Connections — open the screen, tap
 * "Discover", verify the loading indicator actually animates, the snackbar actually
 * shows the resolved IP — is owed and not yet written. The project does not
 * currently set up `src/androidTest/` with Compose UI test infrastructure;
 * tracked in `feature/CLAUDE.md` as a blocking item for any release that claims
 * feature-level Sixth-Law compliance for this surface.
 *
 * Until the UI Challenge exists, a green run of this file MUST be read as
 * "the ViewModel correctly produces the right state and contract for the screen"
 * — NOT as "the user can complete discovery and select an endpoint on a real device".
 *
 * Wiring uses REAL UseCase implementations end-to-end:
 *   ViewModel → real UseCase → real Repository (TestEndpointsRepository,
 *   TestSettingsRepository — both behaviourally equivalent to the production
 *   `:core:data` impls per the Anti-Bluff Pact's Third Law).
 * `ObserveEndpointsStatusUseCase` is the only mocked dependency because it
 * crosses an external network boundary (`ConnectionService`); per the Pact's
 * Second Law mocking is permitted ONLY at outermost boundaries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {

    @get:Rule
    val dispatcherRule = lava.testing.rule.MainDispatcherRule()

    private lateinit var endpointsRepository: TestEndpointsRepository
    private lateinit var settingsRepository: TestSettingsRepository
    private lateinit var discoveryService: TestLocalNetworkDiscoveryService
    private lateinit var observeEndpointsStatusUseCase: MockObserveEndpointsStatusUseCase

    @Before
    fun setup() {
        endpointsRepository = TestEndpointsRepository()
        settingsRepository = TestSettingsRepository()
        discoveryService = TestLocalNetworkDiscoveryService()
        observeEndpointsStatusUseCase = MockObserveEndpointsStatusUseCase()
    }

    /**
     * SP-3 fix (2026-04-29): builder is a `TestScope` extension so the
     * `DiscoverLocalEndpointsUseCaseImpl` it constructs shares the
     * surrounding `runTest` scheduler. See `TestDispatchers.kt` KDoc
     * for the forensic anchor.
     */
    private fun TestScope.createViewModel(): ConnectionsViewModel {
        val addEndpointUseCase = AddEndpointUseCaseImpl(endpointsRepository)
        val removeEndpointUseCase = RemoveEndpointUseCaseImpl(
            endpointsRepository = endpointsRepository,
            settingsRepository = settingsRepository,
        )
        val setEndpointUseCase = SetEndpointUseCaseImpl(settingsRepository)
        val discoverUseCase = DiscoverLocalEndpointsUseCaseImpl(
            discoveryService = discoveryService,
            endpointsRepository = endpointsRepository,
            settingsRepository = settingsRepository,
            dispatchers = testDispatchers(),
        )
        return ConnectionsViewModel(
            addEndpointUseCase = addEndpointUseCase,
            discoverLocalEndpointsUseCase = discoverUseCase,
            removeEndpointUseCase = removeEndpointUseCase,
            setEndpointUseCase = setEndpointUseCase,
            observeEndpointsStatusUseCase = observeEndpointsStatusUseCase,
        )
    }

    // VM-CONTRACT — initial-state contract; no user-visible state mutation
    // assertion (the empty list IS the initial-state default).
    @Test
    fun `initial state is empty`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
        }
    }

    // VM-CONTRACT — verifies the screen receives the open-dialog side-effect.
    // The rendered-screen Challenge (the dialog actually composes and is
    // dismissable) is owed (see class KDoc).
    @Test
    fun `clicking connection item emits ShowConnectionDialog`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.ConnectionItemClick)
            expectSideEffect(ConnectionsSideEffect.ShowConnectionDialog)
        }
    }

    // CHALLENGE — primary assertion on `state.edit`, the user-visible
    // toggle that controls the edit-mode action bar. State transition is
    // observable in the rendered UI.
    @Test
    fun `edit click toggles edit mode`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.EditClick)
            val stateAfterEdit = awaitState()
            assertTrue(stateAfterEdit.edit)
            viewModel.perform(ConnectionsAction.DoneClick)
            val stateAfterDone = awaitState()
            assertFalse(stateAfterDone.edit)
        }
    }

    // CHALLENGE — primary assertion on repository membership AND state.edit.
    // The user observes both: the new endpoint appears in the list, the
    // edit bar disappears.
    @Test
    fun `submit endpoint adds it and exits edit mode`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.EditClick)
            val stateAfterEdit = awaitState()
            assertTrue(stateAfterEdit.edit)
            viewModel.perform(ConnectionsAction.SubmitEndpoint("192.168.1.100"))
            val stateAfterSubmit = awaitState()
            assertFalse(stateAfterSubmit.edit)
        }
        val all = endpointsRepository.observeAll().first()
        assertTrue(
            "Submitted endpoint must exist in repository",
            all.any { it is Endpoint.Mirror && it.host == "192.168.1.100" },
        )
    }

    // CHALLENGE — primary assertion on the persisted settings row.
    // Every other screen reads this same row to decide which backend to call.
    @Test
    fun `select endpoint updates settings`() = runTest(dispatcherRule.testDispatcher) {
        val endpoint = Endpoint.Mirror("192.168.1.100")
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.SelectEndpoint(endpoint))
        }
        assertEquals(endpoint, settingsRepository.getSettings().endpoint)
    }

    // CHALLENGE — primary assertions on `state.discovering` (rendered as
    // the progress indicator) AND on the side-effect message text. Both
    // are user-visible: the spinner and the snackbar.
    //
    // Emission order: ConnectionsViewModel.onDiscoverLocalEndpoints does
    // `reduce(d=true) → useCase → postSideEffect → reduce(d=false)`,
    // so the test consumes state(d=true) → side-effect → state(d=false).
    // The older form of this test asserted state → state → side-effect,
    // which only happened to pass on non-deterministic scheduler timing
    // — a Sixth-Law-clause-2 bluff. Fixed 2026-04-29 alongside the
    // TestDispatchers scheduler-share fix that made the order deterministic.
    @Test
    fun `discover local endpoints shows loading and emits message when found`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        val discovered = lava.data.api.service.DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.DiscoverLocalEndpoints)
            val loadingState = awaitState()
            assertTrue("Should show loading", loadingState.discovering)
            expectSideEffect(
                ConnectionsSideEffect.ShowMessage("Discovered local endpoint: 192.168.1.100:8080"),
            )
            val doneState = awaitState()
            assertFalse("Should hide loading", doneState.discovering)
        }
    }

    // CHALLENGE — same emission order as the test above:
    // state(d=true) → side-effect → state(d=false).
    @Test
    fun `discover local endpoints not found shows message`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        launch { discoveryService.complete() }

        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.DiscoverLocalEndpoints)
            val loadingState = awaitState()
            assertTrue("Should show loading", loadingState.discovering)
            expectSideEffect(ConnectionsSideEffect.ShowMessage("No local endpoint found"))
            val doneState = awaitState()
            assertFalse("Should hide loading", doneState.discovering)
        }
    }

    // CHALLENGE — same emission order as above.
    @Test
    fun `discover local endpoints already configured shows active message`() = runTest(dispatcherRule.testDispatcher) {
        val viewModel = createViewModel()
        // Seed the repository and select the endpoint that will be discovered.
        val mirror = Endpoint.Mirror("192.168.1.100:8080")
        endpointsRepository.add(mirror)
        settingsRepository.setEndpoint(mirror)
        val discovered = lava.data.api.service.DiscoveredEndpoint(
            host = "192.168.1.100:8080",
            port = 8080,
            name = "lava-proxy",
        )
        launch {
            discoveryService.emit(discovered)
            discoveryService.complete()
        }

        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.DiscoverLocalEndpoints)
            val loadingState = awaitState()
            assertTrue("Should show loading", loadingState.discovering)
            expectSideEffect(
                ConnectionsSideEffect.ShowMessage("Local API active: 192.168.1.100:8080"),
            )
            val doneState = awaitState()
            assertFalse("Should hide loading", doneState.discovering)
        }
    }

    // CHALLENGE — primary assertion on repository membership. The user
    // observes the missing endpoint in any later list-rendering screen.
    //
    // The 2026-04-29 fix: the original form called `viewModel.perform(...)`
    // OUTSIDE a `viewModel.test { }` block, which only happened to work
    // because orbit's container had been started earlier by another test
    // sharing JVM state, OR because the previous non-deterministic
    // scheduler dispatched the intent eagerly enough. Container-start
    // is lazy-on-subscribe; without `viewModel.test { }` the intent
    // queued forever. Wrapping in `viewModel.test { }` makes the
    // container-start explicit and the intent-execution deterministic.
    @Test
    fun `remove endpoint deletes it from repository`() = runTest(dispatcherRule.testDispatcher) {
        val endpoint = Endpoint.Mirror("192.168.1.100")
        endpointsRepository.add(endpoint)
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.RemoveEndpoint(endpoint))
        }

        val all = endpointsRepository.observeAll().first()
        assertFalse(
            "Removed endpoint must not exist in repository",
            all.any { it is Endpoint.Mirror && it.host == "192.168.1.100" },
        )
    }

    // CHALLENGE — primary assertion on the persisted settings row's
    // post-remove fallback. User-visible because every other screen reads
    // `settings.endpoint` to decide which backend to call.
    @Test
    fun `remove selected endpoint falls back to Proxy`() = runTest(dispatcherRule.testDispatcher) {
        val endpoint = Endpoint.Mirror("192.168.1.100")
        endpointsRepository.add(endpoint)
        settingsRepository.setEndpoint(endpoint)
        val viewModel = createViewModel()
        // Same fix as above: wrap perform in viewModel.test { } so the
        // container starts and the intent actually runs before assertion.
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.RemoveEndpoint(endpoint))
        }

        assertEquals(Endpoint.Proxy, settingsRepository.getSettings().endpoint)
    }

    /**
     * Mock of [ObserveEndpointsStatusUseCase] that exposes a [MutableSharedFlow]
     * so tests can inject endpoint states. Mocking is permitted here because
     * this use case depends on [ConnectionService], an external network boundary.
     */
    private class MockObserveEndpointsStatusUseCase : ObserveEndpointsStatusUseCase {
        private val flow = MutableSharedFlow<List<EndpointState>>(replay = 1)
        override suspend fun invoke() = flow
        suspend fun emit(states: List<EndpointState>) {
            flow.emit(states)
        }
    }
}
