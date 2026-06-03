package lava.onboarding.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lava.data.api.service.discoveredApiLabel
import lava.designsystem.component.Button
import lava.designsystem.component.Surface
import lava.designsystem.component.Text
import lava.designsystem.theme.AppTheme
import lava.models.settings.Endpoint
import lava.onboarding.ApiConnectivityState

/**
 * The API discovery + selection step inserted as the FIRST screen the
 * user interacts with after Welcome (60th §6.L invocation, 2026-05-18).
 *
 * UX contract:
 * - On entry, [discoveryRunning] is true; show a spinner + "Searching..."
 * - As [discovered] populates (via mDNS NSD), render each entry tappable.
 * - Tapping an entry triggers a connectivity probe (`ConnectionService
 *   .isReachable`). [connectivity] becomes `Testing`; on success the
 *   ViewModel advances the step; on failure [connectivity] is `Failure`
 *   and the user can pick another or retry.
 * - If no APIs are discovered, surface a "Search again" affordance.
 *
 * §6.Q compliance: this screen uses `Column(verticalScroll)` with the
 * discovered-list as plain composables (NOT a LazyColumn) — onboarding
 * lists are bounded to N≈3 candidates so LazyColumn is unnecessary and
 * would violate §6.Q if nested in a parent scroller.
 *
 * §6.R compliance: this screen renders only values the Endpoint already
 * carries; no host/port literals embedded here. The "search again"
 * button re-triggers the ViewModel's discovery, which uses the existing
 * `LocalNetworkDiscoveryService` mDNS NSD source of truth.
 *
 * §6.AB rendering: every visible Text rendered here is a candidate for
 * Challenge-test assertion. The "Searching for APIs…" / "Found N APIs"
 * / "No APIs discovered" status copy is the rendered-state contract.
 */
@Composable
fun ApiSelectionStep(
    discoveryRunning: Boolean,
    discovered: List<Endpoint>,
    selected: Endpoint?,
    connectivity: ApiConnectivityState,
    onSelect: (Endpoint) -> Unit,
    onRetryDiscovery: () -> Unit,
    onRetryProbe: () -> Unit,
    // Cloud / remote-server section (2026-05-31 operator request).
    // Defaults keep existing call sites (e.g. Challenge26) compiling; the
    // production OnboardingScreen + Challenge30 pass all five explicitly.
    cloudInput: String = "",
    cloudDefaults: List<Endpoint> = emptyList(),
    cloudError: String? = null,
    onCloudInputChange: (String) -> Unit = {},
    onAddCloud: () -> Unit = {},
    // Task 3.4 (2026-06-03): "On this device" section.
    // Defaults keep existing call sites (Challenge26, Challenge30, etc.)
    // compiling without changes — matches the existing default-param
    // convention in this file (cloudInput, cloudDefaults, etc.).
    onLaunchOnDeviceApi: () -> Unit = {},
    onDeviceApiInstalled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = AppTheme.colors.background,
    ) {
        Column(
            modifier = Modifier
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Choose your API",
                style = AppTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Lava talks to a Lava-API service. Pick one running on your local " +
                    "network, or connect to a cloud / remote server. You only need to choose one.",
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            // ── Section 1: On your network ──────────────────────────────
            Text(
                text = "On your network",
                style = AppTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = "api-section-local" },
            )
            Spacer(Modifier.height(12.dp))

            // Status line — tells the user what's happening
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (discoveryRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.primary,
                    )
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = "Searching for APIs on your network…",
                        style = AppTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "api-discovery-searching" },
                    )
                } else if (discovered.isEmpty()) {
                    Text(
                        text = "No APIs discovered on your network.",
                        style = AppTheme.typography.bodyMedium,
                        color = AppTheme.colors.onSurfaceVariant,
                        modifier = Modifier.semantics { contentDescription = "api-discovery-empty" },
                    )
                } else {
                    Text(
                        text = "Found ${discovered.size} API${if (discovered.size == 1) "" else "s"}:",
                        style = AppTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "api-discovery-found" },
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Discovered API list
            discovered.forEach { endpoint ->
                ApiRow(
                    endpoint = endpoint,
                    isSelected = endpoint == selected,
                    connectivity = if (endpoint == selected) connectivity else ApiConnectivityState.Idle,
                    onClick = { onSelect(endpoint) },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Failure detail + retry — visible only when probe failed
            val failure = connectivity as? ApiConnectivityState.Failure
            if (failure != null) {
                Text(
                    text = "Could not reach this API: ${failure.reason}",
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.error,
                    modifier = Modifier.semantics { contentDescription = "api-probe-failure" },
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    text = "Try again",
                    onClick = onRetryProbe,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            // Search again — always visible so the user can rescan
            Button(
                text = if (discoveryRunning) "Searching…" else "Search again",
                onClick = onRetryDiscovery,
                enabled = !discoveryRunning,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(28.dp))

            // ── Section 2: On this device ────────────────────────────────
            // §6.Q: plain Column children — NOT a LazyColumn inside a
            // verticalScroll parent. The section has exactly one Button.
            Text(
                text = "On this device",
                style = AppTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = "api-section-ondevice" },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Run the Lava API app directly on this Android device for a zero-config, " +
                    "mDNS-free local connection.",
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                text = if (onDeviceApiInstalled) "Open Lava API app" else "Install Lava API app",
                onClick = onLaunchOnDeviceApi,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "api-ondevice-launch" },
            )

            Spacer(Modifier.height(28.dp))

            // ── Section 3: Cloud / remote server ────────────────────────
            Text(
                text = "Cloud / remote server",
                style = AppTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { contentDescription = "api-section-cloud" },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Connect to a Lava-API hosted elsewhere. Pick a preset, or enter the " +
                    "address and port of your own server.",
                style = AppTheme.typography.bodyMedium,
                color = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            // Pre-installed default options (e.g. https://lava.app:7777). Each
            // taps into the SAME SelectApi → probe → persist → advance flow.
            cloudDefaults.forEach { endpoint ->
                ApiRow(
                    endpoint = endpoint,
                    isSelected = endpoint == selected,
                    connectivity = if (endpoint == selected) connectivity else ApiConnectivityState.Idle,
                    onClick = { onSelect(endpoint) },
                    contentTag = "api-cloud-default",
                )
                Spacer(Modifier.height(8.dp))
            }

            // Manual entry: address + port.
            OutlinedTextField(
                value = cloudInput,
                onValueChange = onCloudInputChange,
                singleLine = true,
                isError = cloudError != null,
                label = { Text("Server address (https://host:port)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "api-cloud-input" },
            )
            if (cloudError != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = cloudError,
                    style = AppTheme.typography.bodySmall,
                    color = AppTheme.colors.error,
                    modifier = Modifier.semantics { contentDescription = "api-cloud-error" },
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                text = "Add server",
                onClick = onAddCloud,
                enabled = cloudInput.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "api-cloud-add" },
            )
        }
    }
}

@Composable
private fun ApiRow(
    endpoint: Endpoint,
    isSelected: Boolean,
    connectivity: ApiConnectivityState,
    onClick: () -> Unit,
    contentTag: String = "api-row",
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = contentTag },
        shape = AppTheme.shapes.medium,
        color = if (isSelected) {
            AppTheme.colors.primary.copy(alpha = 0.12f)
        } else {
            AppTheme.colors.surfaceVariant.copy(alpha = 0.5f)
        },
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(
                    text = endpoint.displayHostPort(),
                    style = AppTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    text = endpoint.displaySubtitle(),
                    style = AppTheme.typography.labelSmall,
                    color = AppTheme.colors.onSurfaceVariant,
                )
            }
            Box(modifier = Modifier.size(24.dp)) {
                if (connectivity is ApiConnectivityState.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = AppTheme.colors.primary,
                    )
                }
            }
        }
    }
}

/**
 * Format an Endpoint as host:port for display. No literal embedded —
 * the values come from the Endpoint instance the discovery service
 * produced.
 */
internal fun Endpoint.displayHostPort(): String = when (this) {
    is Endpoint.GoApi -> "$host:$port"
    is Endpoint.Mirror -> host
    is Endpoint.Rutracker -> host
}

internal fun Endpoint.displaySubtitle(): String = when (this) {
    // Sub-project 2: distinguish an on-device API instance (platform=android)
    // from a host/server one in the discovered list. A GoApi with no platform
    // attribute (host/server, or a cloud preset) renders exactly as before
    // ("Lava API · On this network"); one advertising platform=android adds the
    // "Android device" tail via discoveredApiLabel.
    is Endpoint.GoApi -> "Lava API · ${discoveredApiLabel(platform)}"
    is Endpoint.Mirror -> "Mirror"
    is Endpoint.Rutracker -> "Direct"
}
