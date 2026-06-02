package lava.api.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import lava.api.app.R
import lava.api.app.control.ApiControlAction
import lava.api.app.control.ApiControlSideEffect
import lava.api.app.control.ApiControlState
import lava.api.app.control.ApiControlViewModel

/**
 * The Lava API landing screen — the single user surface of the standalone API
 * app. Renders the live [ApiControlState] (Stopped / Starting… / Running /
 * Stopping… / Error), the Start/Stop/Restart controls (enabled per state), and,
 * while Running, the reachable URL, every LAN IP, the live request count, and
 * the access key with a copy button + a plain-language explanation of what
 * "running" means and that other devices on the network can discover + use it.
 *
 * §6.AB: status words come from title-cased string resources, never
 * `enum.name` — NO underscores appear in any user-facing label
 * ([ApiStatusLabelTest] guards this).
 */
@Composable
fun ApiControlScreen(
    modifier: Modifier = Modifier,
    viewModel: ApiControlViewModel = hiltViewModel(),
) {
    val state by viewModel.container.stateFlow.collectAsState()
    ApiControlScreen(
        state = state,
        sideEffects = viewModel.container.sideEffectFlow,
        onAction = viewModel::perform,
        modifier = modifier,
    )
}

/**
 * Stateless screen body — driven entirely by [state] + [onAction], so the
 * Challenge Test can render it with a real VM and assert on the rendered text.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ApiControlScreen(
    state: ApiControlState,
    sideEffects: Flow<ApiControlSideEffect>,
    onAction: (ApiControlAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard: ClipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val keyCopiedMsg = stringResource(R.string.api_screen_key_copied)

    LaunchedEffect(sideEffects) {
        sideEffects.collect { effect ->
            when (effect) {
                is ApiControlSideEffect.KeyCopied ->
                    scope.launch { snackbarHostState.showSnackbar(keyCopiedMsg) }
                is ApiControlSideEffect.ShowError ->
                    scope.launch { snackbarHostState.showSnackbar(effect.message) }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(stringResource(R.string.api_app_name)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(16.dp))
            StatusIndicator(state)
            Spacer(Modifier.height(20.dp))
            ControlButtons(state, onAction)
            Spacer(Modifier.height(20.dp))

            when (state) {
                is ApiControlState.Running -> RunningDetails(
                    state = state,
                    onCopyKey = { key ->
                        clipboard.setText(AnnotatedString(key))
                        onAction(ApiControlAction.CopyKeyClicked(key))
                    },
                )
                is ApiControlState.Error -> ErrorDetails(state)
                else -> StoppedExplainer()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusIndicator(state: ApiControlState) {
    val statusRes = when (state) {
        ApiControlState.Stopped -> R.string.api_screen_status_stopped
        ApiControlState.Starting -> R.string.api_screen_status_starting
        is ApiControlState.Running -> R.string.api_screen_status_running
        ApiControlState.Stopping -> R.string.api_screen_status_stopping
        is ApiControlState.Error -> R.string.api_screen_status_error
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state is ApiControlState.Starting || state is ApiControlState.Stopping) {
            CircularProgressIndicator(modifier = Modifier.height(24.dp))
        }
        Text(
            modifier = Modifier.testTag(TAG_STATUS),
            text = stringResource(statusRes),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
private fun ControlButtons(
    state: ApiControlState,
    onAction: (ApiControlAction) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            modifier = Modifier.testTag(TAG_START),
            onClick = { onAction(ApiControlAction.StartClicked) },
            enabled = ApiStatusLabels.startEnabled(state),
        ) { Text(stringResource(R.string.api_screen_button_start)) }

        OutlinedButton(
            modifier = Modifier.testTag(TAG_STOP),
            onClick = { onAction(ApiControlAction.StopClicked) },
            enabled = ApiStatusLabels.stopEnabled(state),
        ) { Text(stringResource(R.string.api_screen_button_stop)) }

        OutlinedButton(
            modifier = Modifier.testTag(TAG_RESTART),
            onClick = { onAction(ApiControlAction.RestartClicked) },
            enabled = ApiStatusLabels.restartEnabled(state),
        ) { Text(stringResource(R.string.api_screen_button_restart)) }
    }
}

@Composable
private fun RunningDetails(
    state: ApiControlState.Running,
    onCopyKey: (String) -> Unit,
) {
    LabelledValue(
        label = stringResource(R.string.api_screen_reachable_at),
        value = state.url,
        valueTag = TAG_URL,
    )
    Spacer(Modifier.height(16.dp))

    if (state.lanIps.isNotEmpty()) {
        Text(
            text = stringResource(R.string.api_screen_other_addresses),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(4.dp))
        state.lanIps.forEach { ip ->
            Text(
                modifier = Modifier.testTag(TAG_LAN_IP),
                text = "$ip:${state.port}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(16.dp))
    }

    LabelledValue(
        label = stringResource(R.string.api_screen_requests_served),
        value = state.requestCount.toString(),
        valueTag = TAG_REQUEST_COUNT,
    )
    Spacer(Modifier.height(16.dp))

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.api_screen_access_key),
                style = MaterialTheme.typography.labelLarge,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    modifier = Modifier
                        .weight(1f)
                        .testTag(TAG_ACCESS_KEY),
                    text = state.authKey,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(
                    modifier = Modifier.testTag(TAG_COPY_KEY),
                    onClick = { onCopyKey(state.authKey) },
                ) { Text(stringResource(R.string.api_screen_copy_key)) }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.api_screen_access_key_hint),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(Modifier.height(16.dp))

    Text(
        text = stringResource(R.string.api_screen_running_explainer),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun ErrorDetails(state: ApiControlState.Error) {
    Text(
        modifier = Modifier.testTag(TAG_ERROR_MESSAGE),
        text = state.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(16.dp))
    StoppedExplainer()
}

@Composable
private fun StoppedExplainer() {
    Text(
        text = stringResource(R.string.api_screen_stopped_explainer),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun LabelledValue(label: String, value: String, valueTag: String) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            modifier = Modifier.testTag(valueTag),
            text = value,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

// Stable test tags for the Compose UI Challenge Test (D-ui acceptance gate).
const val TAG_STATUS = "api_status"
const val TAG_START = "api_btn_start"
const val TAG_STOP = "api_btn_stop"
const val TAG_RESTART = "api_btn_restart"
const val TAG_URL = "api_url"
const val TAG_LAN_IP = "api_lan_ip"
const val TAG_REQUEST_COUNT = "api_request_count"
const val TAG_ACCESS_KEY = "api_access_key"
const val TAG_COPY_KEY = "api_copy_key"
const val TAG_ERROR_MESSAGE = "api_error"
