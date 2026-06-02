package lava.api.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dagger.hilt.android.AndroidEntryPoint

/**
 * Placeholder entry-point Activity.
 *
 * Phase D-infra delivers only the headless infrastructure (Service, controller,
 * advertiser, key store). The real landing screen — start/stop control, the
 * reachable URL, the auth key, the live request count — is Phase D-ui. This
 * empty Compose host keeps the manifest valid and the APK assemblable; D-ui
 * replaces the body with the actual screen + ViewModel.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { }
    }
}
