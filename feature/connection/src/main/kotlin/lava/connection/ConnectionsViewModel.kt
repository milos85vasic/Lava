package lava.connection

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import lava.domain.model.endpoint.EndpointState
import lava.domain.usecase.AddEndpointUseCase
import lava.domain.usecase.DiscoverLocalEndpointsResult
import lava.domain.usecase.DiscoverLocalEndpointsUseCase
import lava.domain.usecase.ObserveEndpointsStatusUseCase
import lava.domain.usecase.RemoveEndpointUseCase
import lava.domain.usecase.SetEndpointUseCase
import lava.models.settings.Endpoint
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

@HiltViewModel
internal class ConnectionsViewModel @Inject constructor(
    private val addEndpointUseCase: AddEndpointUseCase,
    private val discoverLocalEndpointsUseCase: DiscoverLocalEndpointsUseCase,
    private val removeEndpointUseCase: RemoveEndpointUseCase,
    private val setEndpointUseCase: SetEndpointUseCase,
    private val observeEndpointsStatusUseCase: ObserveEndpointsStatusUseCase,
) : ViewModel(), ContainerHost<ConnectionsState, ConnectionsSideEffect> {
    override val container: Container<ConnectionsState, ConnectionsSideEffect> = container(
        initialState = ConnectionsState(),
        onCreate = { observeConnections() },
    )

    fun perform(action: ConnectionsAction) {
        when (action) {
            is ConnectionsAction.ConnectionItemClick -> onClickConnectionItem()
            is ConnectionsAction.DiscoverLocalEndpoints -> onDiscoverLocalEndpoints()
            is ConnectionsAction.DoneClick -> onDoneClick()
            is ConnectionsAction.EditClick -> onEditClick()
            is ConnectionsAction.RemoveEndpoint -> onRemoveEndpoint(action.endpoint)
            is ConnectionsAction.SelectEndpoint -> onSelectEndpoint(action.endpoint)
            is ConnectionsAction.SubmitEndpoint -> onSubmitEndpoint(action.endpoint)
        }
    }

    private fun onDoneClick() = intent { reduce { state.copy(edit = false) } }

    private fun onEditClick() = intent { reduce { state.copy(edit = true) } }

    private fun onDiscoverLocalEndpoints() = intent {
        reduce { state.copy(discovering = true) }
        when (val result = discoverLocalEndpointsUseCase()) {
            is DiscoverLocalEndpointsResult.Discovered -> {
                postSideEffect(
                    ConnectionsSideEffect.ShowMessage(
                        "Discovered local endpoint: ${result.endpoint.host}",
                    ),
                )
            }
            DiscoverLocalEndpointsResult.NotFound -> {
                postSideEffect(
                    ConnectionsSideEffect.ShowMessage(
                        "No local endpoint found",
                    ),
                )
            }
            DiscoverLocalEndpointsResult.AlreadyConfigured -> {
                postSideEffect(
                    ConnectionsSideEffect.ShowMessage(
                        "Local endpoint already added",
                    ),
                )
            }
        }
        reduce { state.copy(discovering = false) }
    }

    private fun observeConnections() = intent {
        observeEndpointsStatusUseCase().collectLatest { connections ->
            reduce {
                state.copy(
                    selected = connections.firstOrNull(EndpointState::selected),
                    connections = connections,
                )
            }
        }
    }

    private fun onClickConnectionItem() = intent {
        postSideEffect(ConnectionsSideEffect.ShowConnectionDialog)
    }

    private fun onRemoveEndpoint(endpoint: Endpoint) = intent {
        removeEndpointUseCase(endpoint)
    }

    private fun onSelectEndpoint(endpoint: Endpoint) = intent {
        setEndpointUseCase(endpoint)
    }

    private fun onSubmitEndpoint(endpoint: String) = intent {
        addEndpointUseCase(endpoint)
        reduce { state.copy(edit = false) }
    }
}
