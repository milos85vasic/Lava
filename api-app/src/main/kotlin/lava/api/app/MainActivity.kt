package lava.api.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint
import lava.api.app.ui.ApiControlScreen
import lava.designsystem.theme.LavaTheme

/**
 * Entry-point Activity hosting the Lava API landing screen (Phase D-ui).
 *
 * The screen renders the live [lava.api.app.control.ApiControlState] from the
 * Hilt-injected [lava.api.app.control.ApiControlViewModel] (which shares the
 * singleton [lava.api.app.control.ApiEngineController] with the foreground
 * Service) — start/stop/restart controls, the reachable URL + LAN IPs, the live
 * request count, and the access key.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LavaTheme {
                ApiControlScreen()
            }
        }
    }
}
