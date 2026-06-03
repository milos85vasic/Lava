package lava.api.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import lava.api.app.control.ApiControlAction
import lava.api.app.control.ApiControlViewModel
import lava.api.app.ui.ApiControlScreen
import lava.applink.AppLinkContract
import lava.designsystem.theme.LavaTheme

/**
 * Entry-point Activity hosting the Lava API landing screen (Phase D-ui).
 *
 * **Task 2.2 — auto-start:** when this Activity is launched with
 * [AppLinkContract.EXTRA_START_API]=true (from the Lava client's onboarding
 * "On this device" flow), it dispatches [ApiControlAction.StartRequested] to
 * the [ApiControlViewModel] after requesting the POST_NOTIFICATIONS permission
 * on API 33+. The VM starts the engine; the live port becomes available via
 * [lava.api.app.handoff.ApiKeyProvider].
 *
 * **Task 2.3 — return navigation:** when [AppLinkContract.EXTRA_RETURN_TO] is
 * present in the launch intent, [ApiControlViewModel.launchedFromClient] is set
 * to true so the "Back to Lava client" button label and return extras (host/port)
 * are included in [lava.api.app.control.ApiControlSideEffect.LaunchClient].
 *
 * The screen renders the live [lava.api.app.control.ApiControlState] from the
 * Hilt-injected [ApiControlViewModel] (which shares the singleton
 * [lava.api.app.control.ApiEngineController] with the foreground Service) —
 * start/stop/restart controls, the reachable URL + LAN IPs, the live request
 * count, and the access key.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: ApiControlViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Whether the user granted or denied POST_NOTIFICATIONS, we proceed with
        // starting the engine. The foreground notification may not appear on API 33+
        // if denied, but the engine runs and the service holds the OS locks.
        viewModel.perform(ApiControlAction.StartRequested)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            LavaTheme {
                ApiControlScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return

        // Task 2.3: track whether we were launched from the client so the ViewModel
        // can include return extras (host/port) in the LaunchClient side effect.
        // The target client package is resolved by the injected SiblingAppLauncher's
        // candidate list (release first, dev second) rather than a mutable field.
        if (intent.getStringExtra(AppLinkContract.EXTRA_RETURN_TO) != null) {
            viewModel.launchedFromClient = true
        }

        // Task 2.2: auto-start the engine when the client launches us with START_API=true.
        if (intent.getBooleanExtra(AppLinkContract.EXTRA_START_API, false)) {
            requestNotificationPermissionThenStart()
        }
    }

    private fun requestNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                viewModel.perform(ApiControlAction.StartRequested)
            } else {
                // Launch the system permission dialog; the callback always proceeds
                // with StartRequested regardless of grant/deny.
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            // Below API 33 POST_NOTIFICATIONS does not exist; start directly.
            viewModel.perform(ApiControlAction.StartRequested)
        }
    }
}
