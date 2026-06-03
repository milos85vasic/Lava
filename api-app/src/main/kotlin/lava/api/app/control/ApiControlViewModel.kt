package lava.api.app.control

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import lava.api.app.service.ApiServiceStarter
import lava.applink.AppLinkContract
import lava.applink.SiblingAppLauncher
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
 * **Task 2.2 — auto-start:** [ApiControlAction.StartRequested] is dispatched by
 * [lava.api.app.MainActivity] when the launch intent carries
 * [AppLinkContract.EXTRA_START_API]=true. It starts the engine identically to
 * [ApiControlAction.StartClicked] but via a dedicated path for test clarity.
 *
 * **Task 2.3 — client navigation:** [ApiControlAction.OpenClient] uses the
 * injected [SiblingAppLauncher] to decide whether to launch the installed client
 * or open the Firebase download page, then posts [ApiControlSideEffect.LaunchClient].
 * When [launchedFromClient] is true (set by [MainActivity] when detecting
 * [AppLinkContract.EXTRA_RETURN_TO]), the return extras include
 * [AppLinkContract.EXTRA_API_HOST]/[AppLinkContract.EXTRA_API_PORT].
 *
 * Anti-Bluff (§6.J): PRIMARY assertions in [ApiControlViewModelTest] are on
 * the emitted state (Running carries the real url/ips/count/authKey/port),
 * never on "the starter was called".
 */
@HiltViewModel
class ApiControlViewModel @Inject constructor(
    private val controller: ApiEngineController,
    private val serviceStarter: ApiServiceStarter,
    private val clientLauncher: SiblingAppLauncher,
) : ViewModel(), ContainerHost<ApiControlState, ApiControlSideEffect> {

    /**
     * Set by [lava.api.app.MainActivity] when it detects
     * [AppLinkContract.EXTRA_RETURN_TO] in the launch intent, indicating the
     * session was started from the Lava client. Controls button label
     * ("Back to Lava client" vs "Open Lava client") and whether return extras
     * include host/port.
     */
    var launchedFromClient: Boolean = false

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
            ApiControlAction.StartRequested -> onStartRequested()
            ApiControlAction.OpenClient -> onOpenClient()
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

    /**
     * Programmatic start triggered when the launch intent carries
     * [AppLinkContract.EXTRA_START_API]=true. Promotes the foreground Service
     * then drives the shared controller up — delegates to [onStart] so any
     * future error-handling or telemetry added there applies here too.
     */
    private fun onStartRequested() = onStart()

    /**
     * "Back to Lava client" / "Open Lava client" tapped.
     *
     * Uses [clientLauncher] ([SiblingAppLauncher]) to produce an [android.content.Intent]:
     * - installed → [SiblingAppLauncher.intentToOpen] with return extras (host/port
     *   when [launchedFromClient]).
     * - not installed → [SiblingAppLauncher.intentToDownload] (Firebase download URL;
     *   never market://).
     *
     * Both cases post [ApiControlSideEffect.LaunchClient] carrying the ready Intent.
     */
    private fun onOpenClient() = intent {
        val openIntent = clientLauncher.intentToOpen()
        val launchIntent = if (openIntent != null) {
            openIntent.apply {
                buildReturnExtras().forEach { (k, v) -> putExtra(k, v) }
            }
        } else {
            clientLauncher.intentToDownload()
        }
        postSideEffect(ApiControlSideEffect.LaunchClient(launchIntent))
    }

    private fun buildReturnExtras(): Map<String, String> =
        if (launchedFromClient) {
            val port = (controller.state.value as? ApiControlState.Running)?.port
            buildMap {
                put(AppLinkContract.EXTRA_API_HOST, AppLinkContract.LOOPBACK_HOST)
                if (port != null) put(AppLinkContract.EXTRA_API_PORT, port.toString())
            }
        } else {
            emptyMap()
        }
}
