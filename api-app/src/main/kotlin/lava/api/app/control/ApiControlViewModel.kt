package lava.api.app.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import lava.api.app.service.ApiServiceStarter
import org.orbitmvi.orbit.Container
import org.orbitmvi.orbit.ContainerHost
import org.orbitmvi.orbit.syntax.simple.intent
import org.orbitmvi.orbit.syntax.simple.postSideEffect
import org.orbitmvi.orbit.syntax.simple.reduce
import org.orbitmvi.orbit.viewmodel.container
import javax.inject.Inject

/**
 * MVI ViewModel for the Lava API landing screen.
 *
 * The VM's container state IS [ApiControlState] — the same single-source-of-
 * truth the [ApiEngineController] exposes. On creation the VM mirrors every
 * [ApiEngineController.state] emission straight into its container so the screen
 * always renders the live engine state (the user-visible surface).
 *
 * User intents ([ApiControlAction]) map to controller lifecycle calls. Because
 * holding the OS Wi-Fi/wake locks while serving requires a foreground Service,
 * Start/Restart also ask the injected [ApiServiceStarter] to promote the Service
 * to the foreground (the Service shares the SAME singleton controller via Hilt,
 * so there is no double-drive — the Service only manages locks + notification
 * off the controller's state, the VM is the lifecycle driver). The starter is an
 * abstraction so unit tests substitute a recording fake and assert on emitted
 * [ApiControlState] WITHOUT an Android Context.
 *
 * Anti-Bluff (§6.J): the PRIMARY assertion of [ApiControlViewModelTest] is on
 * the emitted state (Running carries the real url/ips/count/authKey), never on
 * "the starter was called".
 */
@HiltViewModel
class ApiControlViewModel @Inject constructor(
    private val controller: ApiEngineController,
    private val serviceStarter: ApiServiceStarter,
) : ViewModel(), ContainerHost<ApiControlState, ApiControlSideEffect> {

    override val container: Container<ApiControlState, ApiControlSideEffect> = container(
        initialState = controller.state.value,
        onCreate = { observeController() },
    )

    /**
     * Mirror every controller state emission into the container so the screen
     * renders the live engine state. Also surface [ApiControlState.Error] as a
     * one-time [ApiControlSideEffect.ShowError] for a snackbar — while keeping
     * the Error state itself rendered (so Start stays enabled).
     */
    private fun observeController() = intent {
        controller.state
            .onEach { engineState ->
                intent {
                    reduce { engineState }
                    if (engineState is ApiControlState.Error) {
                        postSideEffect(ApiControlSideEffect.ShowError(engineState.message))
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun perform(action: ApiControlAction) {
        when (action) {
            ApiControlAction.StartClicked -> onStart()
            ApiControlAction.StopClicked -> onStop()
            ApiControlAction.RestartClicked -> onRestart()
            is ApiControlAction.CopyKeyClicked -> onCopyKey()
        }
    }

    private fun onStart() = intent {
        // Promote the Service to foreground so it holds the OS locks the embed
        // needs while serving; then drive the shared controller up.
        serviceStarter.ensureRunning()
        controller.start()
    }

    private fun onStop() = intent {
        controller.stop()
        serviceStarter.stop()
    }

    private fun onRestart() = intent {
        serviceStarter.ensureRunning()
        controller.restart()
    }

    private fun onCopyKey() = intent {
        // The clipboard write happens on the UI side (needs ClipboardManager);
        // the VM only confirms the user-visible outcome via a one-time effect.
        postSideEffect(ApiControlSideEffect.KeyCopied)
    }
}
