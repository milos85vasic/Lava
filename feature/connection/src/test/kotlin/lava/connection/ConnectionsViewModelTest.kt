package lava.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import lava.domain.model.endpoint.EndpointState
import lava.domain.usecase.AddEndpointUseCaseImpl
import lava.domain.usecase.DiscoverLocalEndpointsUseCaseImpl
import lava.domain.usecase.ObserveEndpointsStatusUseCase
import lava.domain.usecase.RemoveEndpointUseCaseImpl
import lava.domain.usecase.SetEndpointUseCaseImpl
import lava.models.settings.Endpoint
import lava.testing.TestDispatchers
import lava.testing.repository.TestEndpointsRepository
import lava.testing.repository.TestSettingsRepository
import lava.testing.service.TestLocalNetworkDiscoveryService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

/**
 * Integration Challenge Tests for [ConnectionsViewModel].
 *
 * These tests wire the REAL ViewModel to REAL UseCase implementations backed
 * by behaviorally equivalent fakes. Only [ObserveEndpointsStatusUseCase] is
 * mocked because it depends on [ConnectionService] (an external network
 * boundary), which is outside the scope of this feature's business logic.
 *
 * If a bug exists in any UseCase or repository layer, these tests WILL fail.
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

    private fun createViewModel(): ConnectionsViewModel {
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
            dispatchers = TestDispatchers(),
            discoveryTimeoutMs = 100L,
        )
        return ConnectionsViewModel(
            addEndpointUseCase = addEndpointUseCase,
            discoverLocalEndpointsUseCase = discoverUseCase,
            removeEndpointUseCase = removeEndpointUseCase,
            setEndpointUseCase = setEndpointUseCase,
            observeEndpointsStatusUseCase = observeEndpointsStatusUseCase,
        )
    }

    @Test
    fun `initial state is empty`() = runTest {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
        }
    }

    @Test
    fun `clicking connection item emits ShowConnectionDialog`() = runTest {
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.ConnectionItemClick)
            expectSideEffect(ConnectionsSideEffect.ShowConnectionDialog)
        }
    }

    @Test
    fun `edit click toggles edit mode`() = runTest {
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

    @Test
    fun `submit endpoint adds it and exits edit mode`() = runTest {
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

    @Test
    fun `select endpoint updates settings`() = runTest {
        val endpoint = Endpoint.Mirror("192.168.1.100")
        val viewModel = createViewModel()
        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.SelectEndpoint(endpoint))
        }
        assertEquals(endpoint, settingsRepository.getSettings().endpoint)
    }

    @Test
    fun `discover local endpoints shows loading and emits message when found`() = runTest {
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
            val doneState = awaitState()
            assertFalse("Should hide loading", doneState.discovering)
            expectSideEffect(
                ConnectionsSideEffect.ShowMessage("Discovered local endpoint: 192.168.1.100:8080"),
            )
        }
    }

    @Test
    fun `discover local endpoints not found shows message`() = runTest {
        val viewModel = createViewModel()
        launch { discoveryService.complete() }

        viewModel.test(this) {
            expectInitialState()
            viewModel.perform(ConnectionsAction.DiscoverLocalEndpoints)
            val loadingState = awaitState()
            assertTrue("Should show loading", loadingState.discovering)
            val doneState = awaitState()
            assertFalse("Should hide loading", doneState.discovering)
            expectSideEffect(ConnectionsSideEffect.ShowMessage("No local endpoint found"))
        }
    }

    @Test
    fun `discover local endpoints already configured shows message`() = runTest {
        val viewModel = createViewModel()
        // Seed the repository with the endpoint that will be discovered.
        endpointsRepository.add(Endpoint.Mirror("192.168.1.100:8080"))
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
            val doneState = awaitState()
            assertFalse("Should hide loading", doneState.discovering)
            expectSideEffect(
                ConnectionsSideEffect.ShowMessage("Local endpoint already added"),
            )
        }
    }

    @Test
    fun `remove endpoint deletes it from repository`() = runTest {
        val endpoint = Endpoint.Mirror("192.168.1.100")
        endpointsRepository.add(endpoint)
        val viewModel = createViewModel()
        viewModel.perform(ConnectionsAction.RemoveEndpoint(endpoint))

        val all = endpointsRepository.observeAll().first()
        assertFalse(
            "Removed endpoint must not exist in repository",
            all.any { it is Endpoint.Mirror && it.host == "192.168.1.100" },
        )
    }

    @Test
    fun `remove selected endpoint falls back to Proxy`() = runTest {
        val endpoint = Endpoint.Mirror("192.168.1.100")
        endpointsRepository.add(endpoint)
        settingsRepository.setEndpoint(endpoint)
        val viewModel = createViewModel()
        viewModel.perform(ConnectionsAction.RemoveEndpoint(endpoint))

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
