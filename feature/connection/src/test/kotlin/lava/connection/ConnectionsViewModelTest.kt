package lava.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import lava.domain.model.endpoint.EndpointState
import lava.domain.usecase.AddEndpointUseCase
import lava.domain.usecase.ObserveEndpointsStatusUseCase
import lava.domain.usecase.RemoveEndpointUseCase
import lava.domain.usecase.SetEndpointUseCase
import lava.models.settings.Endpoint
import lava.testing.repository.TestEndpointsRepository
import lava.testing.repository.TestSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.orbitmvi.orbit.test.test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModelTest {

    @get:Rule
    val dispatcherRule = lava.testing.rule.MainDispatcherRule()

    private val endpointsRepository = TestEndpointsRepository()
    private val settingsRepository = TestSettingsRepository()

    private val addEndpointUseCase = object : AddEndpointUseCase {
        override suspend fun invoke(endpoint: String) {
            endpointsRepository.add(Endpoint.Mirror(endpoint))
        }
    }

    private val removeEndpointUseCase = object : RemoveEndpointUseCase {
        override suspend fun invoke(endpoint: Endpoint) {
            endpointsRepository.remove(endpoint)
            if (settingsRepository.getSettings().endpoint == endpoint) {
                settingsRepository.setEndpoint(Endpoint.Proxy)
            }
        }
    }

    private val setEndpointUseCase = object : SetEndpointUseCase {
        override suspend fun invoke(endpoint: Endpoint) {
            settingsRepository.setEndpoint(endpoint)
        }
    }

    private val observeEndpointsStatusUseCase = object : ObserveEndpointsStatusUseCase {
        private val flow = MutableSharedFlow<List<EndpointState>>(replay = 1)
        override suspend fun invoke() = flow
        suspend fun emit(states: List<EndpointState>) {
            flow.emit(states)
        }
    }

    private fun createViewModel(): ConnectionsViewModel = ConnectionsViewModel(
        addEndpointUseCase = addEndpointUseCase,
        removeEndpointUseCase = removeEndpointUseCase,
        setEndpointUseCase = setEndpointUseCase,
        observeEndpointsStatusUseCase = observeEndpointsStatusUseCase,
    )

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
        assertTrue(endpointsRepository.observeAll().first().any { it is Endpoint.Mirror && it.host == "192.168.1.100" })
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
}
