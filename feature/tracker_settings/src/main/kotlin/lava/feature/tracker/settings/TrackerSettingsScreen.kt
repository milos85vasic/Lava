package lava.feature.tracker.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import lava.feature.tracker.settings.components.AddCustomMirrorDialog
import lava.navigation.viewModel
import lava.feature.tracker.settings.components.MirrorListSection
import lava.feature.tracker.settings.components.TrackerSelectorList

/**
 * Top-level Tracker Settings screen. Composes the four pieces:
 *  - TopAppBar with a back button
 *  - TrackerSelectorList (Task 4.12)
 *  - MirrorListSection per tracker (Task 4.13 + 4.14)
 *  - AddCustomMirrorDialog when state.showAddMirrorDialog is true
 *  - Snackbar host driven by [TrackerSettingsSideEffect.ShowToast]
 *
 * Added in SP-3a Phase 4 (Task 4.16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerSettingsScreen(
    onBack: () -> Unit,
    viewModel: TrackerSettingsViewModel = viewModel(),
) {
    val state by viewModel.container.stateFlow.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.container.sideEffectFlow.collect { effect ->
            when (effect) {
                is TrackerSettingsSideEffect.ShowToast ->
                    snackbarHostState.showSnackbar(effect.message)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Trackers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        TrackerSettingsContent(
            state = state,
            padding = padding,
            onSelect = { viewModel.onAction(TrackerSettingsAction.SwitchActive(it)) },
            onAddCustomMirror = { trackerId ->
                viewModel.onAction(TrackerSettingsAction.OpenAddMirrorDialog(trackerId))
            },
            onDismissDialog = { viewModel.onAction(TrackerSettingsAction.DismissAddMirrorDialog) },
            onConfirmAddMirror = { trackerId, url, priority, protocol ->
                viewModel.onAction(
                    TrackerSettingsAction.AddCustomMirror(trackerId, url, priority, protocol),
                )
            },
            onRemoveMirror = { trackerId, url ->
                viewModel.onAction(TrackerSettingsAction.RemoveCustomMirror(trackerId, url))
            },
            onProbeNow = { viewModel.onAction(TrackerSettingsAction.ProbeNow(it)) },
        )
    }
}

@Composable
private fun TrackerSettingsContent(
    state: TrackerSettingsState,
    padding: PaddingValues,
    onSelect: (String) -> Unit,
    onAddCustomMirror: (String) -> Unit,
    onDismissDialog: () -> Unit,
    onConfirmAddMirror: (
        trackerId: String,
        url: String,
        priority: Int,
        protocol: lava.sdk.api.Protocol,
    ) -> Unit,
    onRemoveMirror: (String, String) -> Unit,
    onProbeNow: (String) -> Unit,
) {
    if (state.loading) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState()),
    ) {
        TrackerSelectorList(
            trackers = state.availableTrackers,
            activeTrackerId = state.activeTrackerId,
            onSelect = onSelect,
        )
        for (tracker in state.availableTrackers) {
            val states = state.mirrorHealthByTracker[tracker.trackerId].orEmpty()
            val customUrls = state.customMirrors[tracker.trackerId].orEmpty().map { it.url }.toSet()
            MirrorListSection(
                trackerId = tracker.trackerId,
                states = states,
                customMirrorUrls = customUrls,
                onAddCustomMirror = { onAddCustomMirror(tracker.trackerId) },
                onRemoveMirror = { url -> onRemoveMirror(tracker.trackerId, url) },
                onProbeNow = { onProbeNow(tracker.trackerId) },
            )
        }
    }

    if (state.showAddMirrorDialog && state.addMirrorTargetTracker != null) {
        AddCustomMirrorDialog(
            targetTrackerId = state.addMirrorTargetTracker,
            onDismiss = onDismissDialog,
            onConfirm = { url, priority, protocol ->
                onConfirmAddMirror(state.addMirrorTargetTracker, url, priority, protocol)
            },
        )
    }
}
